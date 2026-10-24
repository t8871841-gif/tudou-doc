package com.jarvis.app.tts

import android.content.Context
import android.os.Bundle
import android.os.Handler
import android.os.Looper
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

    @Volatile
    private var currentStart: (() -> Unit)? = null

    private val main = Handler(Looper.getMainLooper())

    /**
     * 修复（批八复核发现）：原来没有 onStart 参数，TtsRouter 却按有这个参数调用，
     * 编译都过不去；这里补上，并且真正接到 TextToSpeech 自己的 onStart 回调上——
     * 之前即使补了参数，没接线也等于白补，JarvisAgent 的"speaking"状态/打断监听
     * 在系统 TTS 兜底时永远不会触发。UtteranceProgressListener 的回调不保证在主线程，
     * 统一 post 到主线程再往外传。
     */
    fun speak(ctx: Context, text: String, onStart: () -> Unit = {}, onDone: (Boolean) -> Unit) {
        val clean = text.replace(Regex("[\\r\\n]+"), " ").trim()
        if (clean.isEmpty()) {
            onDone(false)
            return
        }
        // 2026-10-24 语音播不完整修复：系统 TTS 一次 speak() 塞长文本时，
        // 部分 ROM 引擎会在内部截断；改按句切分、首句 QUEUE_FLUSH、其余 QUEUE_ADD
        // 排队播，全部播完才算完成。回调都 post 到主线程，failed/doneFired 只在
        // 主线程读写，不用加锁。
        val chunks = splitSentences(clean)
        ensureInit(ctx) { ok ->
            val engine = tts
            if (!ok || engine == null) {
                onDone(false)
                return@ensureInit
            }
            val base = "jarvis_sys_" + System.currentTimeMillis()
            val firstId = base + "_0"
            val lastId = base + "_" + (chunks.size - 1)
            currentDone = onDone
            currentStart = onStart
            var failed = false
            var doneFired = false
            val fireDone: (Boolean) -> Unit = { ok2 ->
                if (!doneFired) {
                    doneFired = true
                    val cb = currentDone
                    currentDone = null
                    currentStart = null
                    cb?.invoke(ok2)
                }
            }
            try {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId != firstId) return
                        main.post {
                            val cb = currentStart
                            currentStart = null
                            cb?.invoke()
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == lastId) main.post { fireDone(!failed) }
                    }

                    @Deprecated("旧版回调")
                    override fun onError(utteranceId: String?) {
                        main.post {
                            failed = true
                            if (utteranceId == lastId) fireDone(false)
                        }
                    }
                })
                val params = Bundle().apply { putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, 1f) }
                var queued = true
                for ((i, chunk) in chunks.withIndex()) {
                    val mode = if (i == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD
                    if (engine.speak(chunk, mode, params, base + "_" + i) != TextToSpeech.SUCCESS) {
                        queued = false
                        break
                    }
                }
                if (!queued) {
                    try { engine.stop() } catch (_: Exception) {}
                    main.post { fireDone(false) }
                }
            } catch (_: Exception) {
                main.post { fireDone(false) }
            }
        }
    }

    private fun splitSentences(pending: String): List<String> =
        pending.split(Regex("(?<=[。！？!?\n])|(?<=\\. )"))
            .map { it.trim() }
            .filter { it.isNotEmpty() }

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
        currentStart = null
    }
}
