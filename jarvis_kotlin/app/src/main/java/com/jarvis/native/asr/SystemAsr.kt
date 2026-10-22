package com.jarvis.native.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer

/**
 * 【已停用，只留着当应急备份】二期曾经用这个顶替 sherpa-onnx 离线识别（当时没有仓库权限也没有
 * 公网下载模型/aar）。现在 `JarvisAgent`/`MainActivity` 都已经改接 `OfflineAsrSession`
 * （真·sherpa-onnx 离线识别），这个类不再被任何地方引用。
 *
 * 留着不删是因为它不依赖 sherpa-onnx 的 aar/模型文件，装不上 sherpa-onnx 那套二进制依赖时，
 * 可以临时把 `JarvisAgent.kt`/`MainActivity.kt` 里的 `OfflineAsrSession` 换回 `SystemAsr`
 * （两者方法签名一致）应急联调；真正要用离线方案时还是以 `OfflineAsrSession` 为准。
 */
object SystemAsr {
    private val main = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null

    @Volatile
    private var seq = 0

    fun isAvailable(ctx: Context): Boolean = SpeechRecognizer.isRecognitionAvailable(ctx)

    /**
     * 听一句话。onPartial 随听写过程多次回调（用于打断检测：只要 onPartial 被触发就说明用户在说话）；
     * onFinal(text) 在识别出最终结果时回调一次；onError(msg) 在出错 / 超时静音时回调一次。
     * 两者只会回调其中一个，且只回调一次。
     */
    fun listenOnce(
        ctx: Context,
        onPartial: (String) -> Unit,
        onFinal: (String) -> Unit,
        onError: (String) -> Unit
    ) {
        stop()
        val mySeq = ++seq
        if (!isAvailable(ctx)) {
            onError("系统语音识别不可用")
            return
        }
        val r = SpeechRecognizer.createSpeechRecognizer(ctx)
        recognizer = r
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        r.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {}

            override fun onPartialResults(partialResults: Bundle?) {
                if (mySeq != seq) return
                val txt = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                if (!txt.isNullOrBlank()) onPartial(txt)
            }

            override fun onResults(results: Bundle?) {
                if (mySeq != seq) return
                val txt = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                cleanup(r)
                if (txt.isNullOrBlank()) onError("没听清") else onFinal(txt)
            }

            override fun onError(error: Int) {
                if (mySeq != seq) return
                cleanup(r)
                onError("识别出错(code=$error)")
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        try {
            r.startListening(intent)
        } catch (e: Exception) {
            cleanup(r)
            onError("启动识别失败：${e.message}")
        }
    }

    private fun cleanup(r: SpeechRecognizer) {
        main.post {
            try { r.destroy() } catch (_: Exception) {}
            if (recognizer === r) recognizer = null
        }
    }

    /** 中止当前识别（急停 / 被打断时调用）。调用方不会再收到这次 listenOnce 的任何回调。 */
    fun stop() {
        seq++
        val r = recognizer
        recognizer = null
        if (r != null) {
            main.post {
                try { r.cancel() } catch (_: Exception) {}
                try { r.destroy() } catch (_: Exception) {}
            }
        }
    }
}
