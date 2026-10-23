package com.jarvis.app.tts

import android.content.Context
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * 系统 TTS 兜底（移植旧 Flutter 版 SystemBridge 里的原生 TTS 做法）。
 * jk-v11 真机教训：Kotlin 版只搬了 Edge（要联网、走微软接口），网络不通时完全哑火；
 * 系统 TTS 不依赖网络，做 Edge 失败时的兜底。引擎懒初始化，首次调用稍慢。
 */
object SystemTts {

    private var tts: TextToSpeech? = null

    @Volatile
    private var ready = false

    @Volatile
    private var initializing = false
    private val pendingInit = ArrayList<(Boolean) -> Unit>()

    @Volatile
    private var currentDone: ((Boolean) -> Unit)? = null

    fun speak(ctx: Context, text: String, onDone: (Boolean) -> Unit) {
        val clean = text.replace(Regex("[\\r\\n]+"), " ").trim()
        if (clean.isEmpty()) {
            onDone(false)
            return
        }
        ensureInit(ctx) { ok ->
            val engine = tts
            if (!ok || engine == null) {
                onDone(false)
                return@ensureInit
            }
            val id = "jarvis_sys_" + System.currentTimeMillis()
            currentDone = onDone
            try {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {}
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == id) {
                            val cb = currentDone
                            currentDone = null
                            cb?.invoke(true)
                        }
                    }

                    @Deprecated("旧版回调")
                    override fun onError(utteranceId: String?) {
                        if (utteranceId == id) {
                            val cb = currentDone
                            currentDone = null
                            cb?.invoke(false)
                        }
                    }
                })
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
                if (engine.speak(clean, TextToSpeech.QUEUE_FLUSH, params, id) != TextToSpeech.SUCCESS) {
                    currentDone = null
                    onDone(false)
                }
            } catch (_: Exception) {
                currentDone = null
                onDone(false)
            }
        }
    }

    private fun ensureInit(ctx: Context, cb: (Boolean) -> Unit) {
        if (ready && tts != null) {
            cb(true)
            return
        }
        synchronized(pendingInit) {
            pendingInit.add(cb)
            if (initializing) return
            initializing = true
        }
        try {
            tts?.shutdown()
        } catch (_: Exception) {
        }
        tts = TextToSpeech(ctx.applicationContext) { status ->
            val cbs: List<(Boolean) -> Unit>
            synchronized(pendingInit) {
                ready = status == TextToSpeech.SUCCESS
                initializing = false
                cbs = pendingInit.toList()
                pendingInit.clear()
            }
            if (ready) {
                try {
                    tts?.language = Locale.CHINESE
                } catch (_: Exception) {
                }
            }
            cbs.forEach { it(ready) }
        }
    }

    fun stop() {
        try {
            tts?.stop()
        } catch (_: Exception) {
        }
        currentDone = null
    }
}
