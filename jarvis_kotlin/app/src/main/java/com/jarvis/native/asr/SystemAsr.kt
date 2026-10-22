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
 * 【降级 / 临时替代】需求文档要求用 sherpa-onnx 流式 zipformer 离线识别，并直接复用
 * 仓库 tudou-doc 历史提交 ae04c76 的 `asr/OfflineAsr.kt`。本次交付环境：
 *   1) 没有该仓库的访问权限，取不到那份已验证代码；
 *   2) 没有公网访问，无法下载 sherpa-onnx 的 AAR 和 onnx 模型文件做验证。
 * 所以二期先用 Android 系统自带的 SpeechRecognizer 顶上，保证"听"这一步能跑通联调；
 * 这不是真正离线识别（多数机型走 Google 在线识别，需要联网，且不满足需求文档"离线"的初衷），
 * 如实标注为降级版。等 sherpa-onnx 的 aar/模型文件就位后，按 OfflineAsr 的方式替换本类，
 * 上层 JarvisAgent 只认 listenOnce/startContinuous 这两个接口，替换时不用动调用方代码。
 *
 * 魅族 Flyme 上部分版本可能没有可用的 RecognitionService，isAvailable() 会返回 false，
 * 此时自检页要如实显示"系统语音识别不可用"，不能假装能用。
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
