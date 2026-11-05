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

    /**
     * L5：本轮 speak 的独立收尾闭包。连续 speak() / stop() 时旧轮的回调持有在这里，
     * 新 speak() 或 stop() 靠它把旧轮以 onDone(false) 收尾；doneFired 在闭包里，
     * 正常完成与取消只走一条路，不会调两次。
     */
    @Volatile
    private var currentFireDone: ((Boolean) -> Unit)? = null

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
            // L5：连续 speak() 时先把上一轮的回调以失败收尾——上一轮的 utterance 会被
            // 新 speak 首句的 QUEUE_FLUSH 冲掉，onDone 永远等不到，不收尾调用方就收不到回调。
            // 放在 ensureInit 回调里（而不是 speak() 入口）：引擎还在初始化、上一轮连监听
            // 都没装上时，两次 speak 的回调会按初始化完成顺序先后到达，这里才能正确收尾旧轮。
            val prevFire = currentFireDone
            currentFireDone = null
            currentDone = null
            currentStart = null
            if (prevFire != null) main.post { prevFire(false) }
            val base = "jarvis_sys_" + System.currentTimeMillis()
            val firstId = base + "_0"
            val lastId = base + "_" + (chunks.size - 1)
            // L5：每轮 speak 用自己独立的回调持有（闭包捕获本轮的 onDone/onStart），
            // 不再读共享的 currentDone/currentStart，避免连续 speak 时新旧回调串位丢回调。
            val myDone = onDone
            val myStart = onStart
            currentDone = onDone
            currentStart = onStart
            var failed = false
            var doneFired = false
            var startFired = false
            var fireDone: ((Boolean) -> Unit)? = null
            fireDone = { ok2 ->
                if (!doneFired) {
                    doneFired = true
                    // 只清还属于本轮的共享持有：新一轮已经开始时不动它的。
                    if (currentFireDone === fireDone) {
                        currentFireDone = null
                        currentDone = null
                        currentStart = null
                    }
                    myDone(ok2)
                }
            }
            currentFireDone = fireDone
            // L5：中间 chunk 出错即收尾（出错后引擎未必再回调 onDone 等 lastId）。
            val onUtteranceError: (String?) -> Unit = {
                main.post {
                    failed = true
                    try { engine.stop() } catch (_: Exception) {}
                    fireDone?.invoke(false)
                }
            }
            try {
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        if (utteranceId != firstId) return
                        main.post {
                            // 只认本轮：旧轮残留的 onStart 不再碰新轮的回调。
                            if (currentFireDone === fireDone && !startFired) {
                                startFired = true
                                myStart()
                            }
                        }
                    }
                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == lastId) main.post { fireDone?.invoke(!failed) }
                    }

                    @Deprecated("旧版回调")
                    override fun onError(utteranceId: String?) {
                        onUtteranceError(utteranceId)
                    }

                    // L5：新签名，废弃的 onError(String) 在 Android 16 上可能不触发。
                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onUtteranceError(utteranceId)
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
                    main.post { fireDone?.invoke(false) }
                }
            } catch (_: Exception) {
                main.post { fireDone?.invoke(false) }
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
        // L4：被取消的播报也回调一次 onDone(false)，调用方状态机总能收敛；
        // 走本轮独立的 fireDone 闭包，doneFired 保证与正常完成只走一条路。
        val fire = currentFireDone
        currentFireDone = null
        currentDone = null
        currentStart = null
        if (fire != null) main.post { fire(false) }
    }
}
