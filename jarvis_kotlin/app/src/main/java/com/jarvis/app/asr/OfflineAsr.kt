package com.jarvis.app.asr

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.EndpointConfig
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OnlineModelConfig
import com.k2fsa.sherpa.onnx.OnlineRecognizer
import com.k2fsa.sherpa.onnx.OnlineRecognizerConfig
import com.k2fsa.sherpa.onnx.OnlineStream
import com.k2fsa.sherpa.onnx.OnlineTransducerModelConfig
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * 移植自参考实现（原 net.kuafuai.andee.asr.OfflineAsr）：sherpa-onnx 流式 zipformer
 * 中英双语离线识别，和之前 Flutter 版 Jarvis 用的同一套模型。逻辑基本没改，只是：
 *   - 去掉了未用到的 VoiceConfig 构造参数；
 *   - 包名从 net.kuafuai.andee.asr 改成 com.jarvis.app.asr。
 *
 * 这个类本身只管"喂 PCM 进去、吐文字出来"，不摸麦克风——采集音频、判断"用户说完了没有"
 * 是 [OfflineAsrSession] 的活，两层分开是为了跟 SystemAsr 的降级版保持同一种调用方式
 * （上层 JarvisAgent 只认 listenOnce/stop/isAvailable，这层细节切换不影响它）。
 *
 * **编译前提**：app/libs/ 下要有 sherpa-onnx 的 aar，app/src/main/assets/asr/ 下要有四个模型文件
 * （文件名见下面 ENCODER/DECODER/JOINER/TOKENS），这两样都是二进制，不进 git，需要你们自己放。
 * 缺了任何一样，要么编译不过（缺 aar），要么运行时报"模型加载失败"（缺模型文件）——
 * 不会假装能用，具体症状看 OfflineAsrSession.isAvailable() 和自检页。
 */
class OfflineAsr(
    private val listener: AsrEngine.Listener,
    private val context: Context,
) : AsrEngine {

    /** 本地解码很快，这个等待主要是留给最后几帧处理，不是在等网络。 */
    override val finalWaitMs: Long = 2_000L

    /** 调用方（OfflineAsrSession）自己的安静判断窗口，这里只是声明给别人看，本类不依赖它。 */
    override val silenceMs: Long = 3_000L

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "OfflineAsr").apply { isDaemon = true }
    }

    private val finishedLatch = CountDownLatch(1)

    @Volatile
    private var closed = false

    // ---- 以下字段只在 io 这一条工作线程上读写 ----

    private var recognizer: OnlineRecognizer? = null
    private var stream: OnlineStream? = null
    private var failed = false
    private var finalSent = false

    /** 端点检测器已经切出来的片段，跨多次 decode 累积。 */
    private var committed = ""
    private var lastPartial = ""

    override fun start(sampleRate: Int, channels: Int) {
        io.execute {
            try {
                val rec = sharedRecognizer(context)
                recognizer = rec
                stream = rec.createStream()
                listener.onReady()
            } catch (t: Throwable) {
                Log.e(TAG, "recogniser unavailable", t)
                failed = true
                listener.onError("离线识别不可用：${t.message ?: "模型加载失败"}")
                finishedLatch.countDown()
            }
        }
    }

    override fun sendAudio(pcm: ByteArray, isLast: Boolean) {
        if (closed) return
        val block = if (isLast) null else pcm.copyOf()
        io.execute {
            if (closed) return@execute
            if (failed) {
                finishedLatch.countDown()
                return@execute
            }
            val rec = recognizer ?: return@execute
            val s = stream ?: return@execute
            if (block != null && block.isNotEmpty()) {
                s.acceptWaveform(toFloats(block), SAMPLE_RATE)
                decodeAvailable(rec, s)
            }
            if (isLast) finishTurn(rec, s)
        }
    }

    override fun endOfTurn(bySilence: Boolean) = Unit

    override fun waitFinished(timeoutMs: Long): Boolean =
        finishedLatch.await(timeoutMs, TimeUnit.MILLISECONDS)

    override fun close() {
        closed = true
        runCatching {
            io.execute {
                runCatching { stream?.release() }
                stream = null
            }
            io.shutdown()
        }
    }

    // ---- 以下只在 io 工作线程上调用 ----

    private fun decodeAvailable(rec: OnlineRecognizer, s: OnlineStream) {
        while (rec.isReady(s)) rec.decode(s)
        if (rec.isEndpoint(s)) {
            val seg = rec.getResult(s).text
            if (seg.isNotBlank()) committed += seg
            rec.reset(s)
        }
        reportPartial(rec, s)
    }

    private fun reportPartial(rec: OnlineRecognizer, s: OnlineStream) {
        val text = committed + rec.getResult(s).text
        if (text != lastPartial) {
            lastPartial = text
            if (text.isNotEmpty()) listener.onPartial(text)
        }
    }

    private fun finishTurn(rec: OnlineRecognizer, s: OnlineStream) {
        if (finalSent) return
        finalSent = true
        try {
            s.inputFinished()
            while (rec.isReady(s)) rec.decode(s)
            val full = committed + rec.getResult(s).text
            listener.onFinal(full)
        } catch (t: Throwable) {
            Log.e(TAG, "final decode failed", t)
            listener.onError("离线识别出错：${t.message ?: "解码失败"}")
        } finally {
            finishedLatch.countDown()
        }
    }

    /** 16bit 小端 PCM → 识别器要的浮点波形。 */
    private fun toFloats(pcm: ByteArray): FloatArray {
        val n = pcm.size / 2
        val out = FloatArray(n)
        for (i in 0 until n) {
            val lo = pcm[2 * i].toInt() and 0xFF
            val hi = pcm[2 * i + 1].toInt()
            out[i] = ((hi shl 8) or lo) / 32768.0f
        }
        return out
    }

    companion object {
        private const val TAG = "OfflineAsr"
        const val SAMPLE_RATE = 16_000

        // sherpa-onnx-streaming-zipformer-bilingual-zh-en-2023-02-20 的四个文件，
        // 放在 app/src/main/assets/asr/ 下（不进 git，二进制，需要你们自己放）。
        private const val ENCODER = "encoder-epoch-99-avg-1.int8.onnx"
        private const val DECODER = "decoder-epoch-99-avg-1.onnx"
        private const val JOINER = "joiner-epoch-99-avg-1.int8.onnx"
        private const val TOKENS = "tokens.txt"
        val MODEL_FILES = listOf(ENCODER, DECODER, JOINER, TOKENS)

        /** 模型换版本时改这个，旧的已复制文件会被判定过期重新复制。 */
        private const val MODEL_MARKER = ".ready_v1"

        @Volatile
        private var shared: OnlineRecognizer? = null
        private val sharedLock = Any()

        init {
            // aar 自己的类里也会加载一次，这里重复加载是空操作；放在这里是为了让失败能被
            // 日志捕捉到，而不是悄悄崩在 native 层。
            runCatching { System.loadLibrary("sherpa-onnx-jni") }
        }

        private fun sharedRecognizer(context: Context): OnlineRecognizer {
            shared?.let { return it }
            synchronized(sharedLock) {
                shared?.let { return it }
                val dir = prepareModelDir(context.applicationContext)
                val config = OnlineRecognizerConfig(
                    featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                    modelConfig = OnlineModelConfig(
                        transducer = OnlineTransducerModelConfig(
                            encoder = File(dir, ENCODER).absolutePath,
                            decoder = File(dir, DECODER).absolutePath,
                            joiner = File(dir, JOINER).absolutePath,
                        ),
                        tokens = File(dir, TOKENS).absolutePath,
                        numThreads = 2,
                        modelType = "zipformer",
                    ),
                    endpointConfig = EndpointConfig(),
                    enableEndpoint = true,
                    decodingMethod = "greedy_search",
                )
                val rec = OnlineRecognizer(null, config)
                shared = rec
                return rec
            }
        }

        /** 模型文件以 assets 形式随 APK，但 native 代码要的是真实路径，首次运行复制到 filesDir。 */
        private fun prepareModelDir(context: Context): File {
            val dir = File(context.filesDir, "asr")
            val marker = File(dir, MODEL_MARKER)
            if (marker.exists()) return dir
            dir.mkdirs()
            for (name in MODEL_FILES) {
                context.assets.open("asr/$name").use { input ->
                    FileOutputStream(File(dir, name)).use { output -> input.copyTo(output) }
                }
            }
            marker.writeText("ok")
            return dir
        }

        /** 自检页用：assets 里是不是已经放齐了四个模型文件（不保证内容完整/版本对，只查存在性）。 */
        fun assetsPresent(context: Context): Boolean {
            return try {
                val names = context.assets.list("asr")?.toSet() ?: emptySet()
                MODEL_FILES.all { it in names }
            } catch (_: Exception) {
                false
            }
        }
    }
}
