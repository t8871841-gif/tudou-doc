package com.jarvis.app.vision

import android.graphics.Bitmap
import android.util.Log
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * OCR 补字层（2026-11-05，Mika 同款移植，改包名 + 改阻塞式）：
 * 树不够用时（微信这类藏界面树的 App），用本机 ML Kit 把图里的字捞回成可点坐标。
 *
 * 两套内置模型（中文 + 拉丁）均在本机离线运行，Bundled 版不依赖 Play Services。
 * 同一帧并行识别；两套都出错时抛异常（不当成“没字”，这样调用方才能写出 ocr: failed，
 * 而不是误导模型以为屏上没字）。
 *
 * 调用方是 TaskLoop 的阻塞式 executor 线程，所以这里用 CountDownLatch 同步等，
 * 不套协程。位图由 read() 负责回收（无论成功失败），调用方不要再 recycle。
 */
object OcrReader {
    private const val TAG = "OcrReader"

    /** ML Kit 给了置信度、又低于这个值的行不要；没给的照留。 */
    private const val MIN_CONFIDENCE = 0.4f

    /** 以中文为主的屏上，不含中文、字母数字不超过这么多个的零碎串才可能是认错的。 */
    private const val FRAGMENT = 4

    data class OcrLine(
        val text: String,
        /** bounds：0–999 相对坐标 [left, top, right, bottom]，与 tap 的 x/y 同一套。 */
        val bounds: IntArray,
        val centerX: Int,
        val centerY: Int,
        val confidence: Float?,
    )

    private val chinese: TextRecognizer by lazy {
        TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
    }
    private val latin: TextRecognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /**
     * 对一张位图跑 OCR，返回识别出的行（bounds 已归一化到 0–999）。
     * 两套识别器并行跑，总耗时不超过 [timeoutMs]；超时未完成的部分按失败计，
     * 至少一套成功即返回已有结果；两套都失败抛 IllegalStateException。
     * 位图无论成功失败都由这里回收。
     */
    fun read(bitmap: Bitmap, timeoutMs: Long): List<OcrLine> {
        val recognizers = try {
            listOf(chinese, latin)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        val image = try {
            InputImage.fromBitmap(bitmap, 0)
        } catch (e: Exception) {
            bitmap.recycle()
            throw e
        }
        // 位图宽高先取出来：识别是异步回调，read() 返回后位图已回收，
        // 回调里再读 bitmap.width 虽不抛错但不干净，这里直接用快照值。
        val bmpW = bitmap.width
        val bmpH = bitmap.height
        val lines = Collections.synchronizedList(mutableListOf<OcrLine>())
        val latch = CountDownLatch(recognizers.size)
        var succeeded = 0
        val succeededLock = Any()
        try {
            for (recognizer in recognizers) {
                try {
                    recognizer.process(image).addOnCompleteListener { done ->
                        if (done.isSuccessful) {
                            synchronized(succeededLock) { succeeded++ }
                            done.result?.textBlocks?.forEach { block ->
                                block.lines.forEach { line ->
                                    val box = line.boundingBox ?: return@forEach
                                    val value = line.text.trim()
                                    if (value.isNotEmpty()) {
                                        lines += OcrLine(
                                            value,
                                            intArrayOf(
                                                normalize(box.left, bmpW),
                                                normalize(box.top, bmpH),
                                                normalize(box.right, bmpW),
                                                normalize(box.bottom, bmpH),
                                            ),
                                            (normalize(box.left, bmpW) + normalize(box.right, bmpW)) / 2,
                                            (normalize(box.top, bmpH) + normalize(box.bottom, bmpH)) / 2,
                                            line.confidence.takeIf { it > 0f },
                                        )
                                    }
                                }
                            }
                        } else {
                            Log.w(TAG, "recognizer failed", done.exception)
                        }
                        latch.countDown()
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "recognizer failed", e)
                    latch.countDown()
                }
            }
            latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        } finally {
            bitmap.recycle()
        }
        if (succeeded == 0) throw IllegalStateException("text recognition failed")
        return lines.toList()
    }

    /** 去掉认错的行：置信度低的；一个字母或数字也没有的；符号比字还多的；中文屏上的碎片串。 */
    fun legible(lines: List<OcrLine>): List<OcrLine> {
        val letters = lines.flatMap { line -> line.text.filter(Char::isLetter).toList() }
        val cjkScreen = letters.isNotEmpty() && letters.count(::isCjk) * 2 >= letters.size
        return lines.filterNot { noise(it, cjkScreen) }
    }

    private fun noise(line: OcrLine, cjkScreen: Boolean): Boolean {
        if (line.confidence != null && line.confidence < MIN_CONFIDENCE) return true
        val text = line.text.filterNot(Char::isWhitespace)
        val word = text.filter(Char::isLetterOrDigit)
        if (word.isEmpty() || text.length - word.length > word.length) return true
        if (!cjkScreen || word.any(::isCjk) || word.all(Char::isDigit)) return false
        return word.length == 1 || word.length <= FRAGMENT && (word.any(Char::isDigit) || text.length > word.length)
    }

    private fun isCjk(c: Char) = when (Character.UnicodeScript.of(c.code)) {
        Character.UnicodeScript.HAN,
        Character.UnicodeScript.HIRAGANA,
        Character.UnicodeScript.KATAKANA,
        Character.UnicodeScript.HANGUL -> true
        else -> false
    }

    /**
     * 只留树里没有的字：OCR 行的文字与树上已知文字相同（或互相包含，≥3 字）即判为
     * “树里已有”，丢掉。树上文字由调用方从元素大纲里抽（归一化：小写、只留字母数字）。
     * 不比 bounds——树不够用时元素本来就没几个，文字比对简单且够用。
     */
    fun missing(knownTexts: Set<String>, lines: List<OcrLine>): List<OcrLine> {
        val known = knownTexts.map(::key).filter { it.isNotEmpty() }.toSet()
        if (known.isEmpty()) return lines
        return lines.filter { line ->
            val value = key(line.text)
            value.isNotEmpty() && known.none { same(value, it) }
        }
    }

    private fun key(text: String) = text.lowercase().filter { it.isLetterOrDigit() }

    private fun same(a: String, b: String) =
        a == b || a.length >= 3 && b.contains(a) || b.length >= 3 && a.contains(b)

    private fun normalize(value: Int, total: Int): Int =
        if (total <= 0) 0 else (value * 999 / total).coerceIn(0, 999)
}
