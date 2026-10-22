package com.jarvis.native

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.jarvis.native.asr.OfflineAsrSession
import com.jarvis.native.net.AgnesClient
import com.jarvis.native.tts.EdgeTts
import com.jarvis.native.vision.ScreenVisionAgent

/**
 * 二期新增：对话 + 语音的总调度。接管 OrbController 的 eventSink/dartAlive
 * （原来是 Flutter 侧接的，纯 Kotlin 重写后由这里直接接）。
 *
 * 状态机：idle → listening（听） → thinking（问 Agnes） → speaking（EdgeTts 播报） → idle
 * 任一状态都能被 estop 打断回到 idle；speaking 状态下如果检测到用户开口，按第 6 条做打断。
 */
object JarvisAgent {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var state = "idle" // idle/listening/thinking/speaking

    @Volatile
    private var wakeLoopRunning = false

    @Volatile
    private var genSeq = 0 // 每次状态切换/急停自增，老的异步回调靠这个号失效

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        OrbController.dartAlive = true
        OrbController.eventSink = { event, ack -> onOrbEvent(app, event, ack) }
        maybeStartWakeLoop()
    }

    /** 悬浮球短按兜底 launchApp("assist") / 磁贴 / 数字助理入口都会走到这里：等价于"再按一次球"。 */
    fun triggerListenFromAssist(ctx: Context) {
        start(ctx)
        if (state == "idle") {
            beginListening(ctx.applicationContext, fromWake = false, prefilledText = null)
        } else {
            hardResetToIdle(ctx.applicationContext)
            beginListening(ctx.applicationContext, fromWake = false, prefilledText = null)
        }
    }

    private fun onOrbEvent(ctx: Context, event: String, ack: (Boolean) -> Unit) {
        when (event) {
            "tap" -> {
                if (state == "idle") {
                    ack(true)
                    beginListening(ctx, fromWake = false, prefilledText = null)
                } else {
                    // 非 idle 时短按：当成"打断当前环节，重新开始听"，而不是静默吞掉。
                    ack(true)
                    hardResetToIdle(ctx)
                    beginListening(ctx, fromWake = false, prefilledText = null)
                }
            }
            "estop" -> {
                ack(true)
                hardResetToIdle(ctx)
            }
            else -> ack(false)
        }
    }

    /** 急停 / 打断：取消一切在飞的网络请求、停止 TTS、停止识别，回到 idle，球与气泡已由 OrbController 自己处理。 */
    private fun hardResetToIdle(ctx: Context) {
        genSeq++
        AgnesClient.cancelInFlight()
        EdgeTts.stop()
        OfflineAsrSession.stop()
        ScreenVisionAgent.cancelInFlight()
        TakeoverBorder.hide()
        setState(ctx, "idle")
        maybeStartWakeLoop()
    }

    private fun setState(ctx: Context, s: String) {
        state = s
        OrbController.setState(s)
    }

    // ---------- 主流程：听 → 问 → 答 → 播 ----------

    private fun beginListening(ctx: Context, fromWake: Boolean, prefilledText: String?) {
        wakeLoopRunning = false // 正式进入对话流程，暂停唤醒词轮询
        val mySeq = genSeq
        setState(ctx, "listening")
        if (!prefilledText.isNullOrBlank()) {
            onUserUtterance(ctx, mySeq, prefilledText)
            return
        }
        OfflineAsrSession.listenOnce(
            ctx,
            onPartial = { /* 本阶段不需要处理中途文字 */ },
            onFinal = { text -> main.post { if (mySeq == genSeq) onUserUtterance(ctx, mySeq, text) } },
            onError = { msg ->
                main.post {
                    if (mySeq != genSeq) return@post
                    OrbController.showBubble(if (fromWake) "没听清，继续待命" else "没听清：$msg")
                    setState(ctx, "idle")
                    maybeStartWakeLoop()
                }
            }
        )
    }

    // 三期新增：两类问法在进 Agnes 聊天之前先分流到视觉看屏通道。
    private val TAP_INTENT = Regex("^(帮我)?点(一下|击)?\\s*(.+)$")
    private val SCREEN_QUESTION = Regex("(屏幕上|这上面|这是什么|这是啥|这个(按钮|图标|东西)|这上边)")

    private fun onUserUtterance(ctx: Context, mySeq: Int, text: String) {
        if (mySeq != genSeq) return
        val tapMatch = TAP_INTENT.find(text)
        if (tapMatch != null && tapMatch.groupValues[3].isNotBlank()) {
            val target = tapMatch.groupValues[3].trim()
            setState(ctx, "thinking")
            ScreenVisionAgent.locateAndTap(ctx, target, "语音指令：点击$target") { ok, detail ->
                if (mySeq != genSeq) return@locateAndTap
                speakReply(ctx, mySeq, if (ok) "好的，点了" else "没点成：$detail")
            }
            return
        }
        if (SCREEN_QUESTION.containsMatchIn(text)) {
            setState(ctx, "thinking")
            ScreenVisionAgent.describeScreen(ctx, text,
                onResult = { answer -> if (mySeq == genSeq) speakReply(ctx, mySeq, answer) },
                onError = { msg -> if (mySeq == genSeq) speakReply(ctx, mySeq, "看屏失败：$msg") }
            )
            return
        }
        setState(ctx, "thinking")
        AgnesClient.ask(
            ctx, text,
            onResult = { reply -> main.post { if (mySeq == genSeq) speakReply(ctx, mySeq, reply) } },
            onError = { msg ->
                main.post {
                    if (mySeq != genSeq) return@post
                    OrbController.showBubble("出错了：$msg")
                    setState(ctx, "idle")
                    maybeStartWakeLoop()
                }
            }
        )
    }

    private fun speakReply(ctx: Context, mySeq: Int, reply: String) {
        if (mySeq != genSeq) return
        OrbController.showBubble(reply.take(80))
        EdgeTts.speak(
            ctx, reply,
            onStart = {
                main.post {
                    if (mySeq != genSeq) return@post
                    setState(ctx, "speaking")
                    if (JarvisPrefs.isBargeInEnabled(ctx)) startBargeInWatch(ctx, mySeq)
                }
            },
            onDone = { _ ->
                main.post {
                    if (mySeq != genSeq) return@post
                    setState(ctx, "idle")
                    maybeStartWakeLoop()
                }
            }
        )
    }

    /**
     * 第 6 条「打断说话」降级版：没有做完整回声消除(AEC)，用离线识别在播报期间监听，
     * 一旦收到非空的中途识别文字就认为"用户在开口"，立即停播并转入听。
     * 已知局限：没有 AEC，扬声器播报的声音本身可能被麦克风拾取触发误判，
     * 真机联调如果经常被自己的播报打断自己，先把"打断说话"开关关掉，或改用听筒/耳机测试。
     */
    private fun startBargeInWatch(ctx: Context, mySeq: Int) {
        // 留 500ms 起播缓冲，避免刚开口的瞬间杂音立刻触发。
        main.postDelayed({
            if (mySeq != genSeq || state != "speaking") return@postDelayed
            OfflineAsrSession.listenOnce(
                ctx,
                onPartial = { txt ->
                    if (mySeq == genSeq && state == "speaking" && txt.trim().length >= 2) {
                        main.post {
                            if (mySeq != genSeq || state != "speaking") return@post
                            EdgeTts.stop()
                            OrbController.showBubble("打断了，在听…")
                            beginListening(ctx, fromWake = false, prefilledText = null)
                        }
                    }
                },
                onFinal = { /* 播报期间的识别只用来判断"有没有在说话"，最终结果走 beginListening 重新识别一次 */ },
                onError = { /* 播报期间识别拿不到结果很正常（本来就没人说话），忽略 */ }
            )
        }, 500)
    }

    // ---------- 第 5 条：唤醒词（仍是降级版——真离线 ASR 已接上，但不是专门的低功耗 KWS） ----------

    /**
     * 三期-B 换上 sherpa-onnx 真离线识别后，这里的唤醒词本质上还是"反复跑一次完整的流式识别、
     * 看文字里有没有命中关键词"，不是专门为常驻监听设计的轻量级关键词唤醒模型(KWS)——
     * 识别器本身常驻在内存里（首次用时才加载,之后复用），比之前砸系统在线识别省电一些，
     * 但持续跑 zipformer 编解码仍然比真正的 KWS 耗电，具体耗电数据同样没有真机实测。
     * 如果连续待命发热/掉电明显，先把灵敏度调低或直接关掉这个开关。
     */
    fun maybeStartWakeLoop() {
        val ctx = appCtx ?: return
        if (!JarvisForegroundService.running) return
        if (!JarvisPrefs.isWakeEnabled(ctx)) return
        if (state != "idle") return
        if (wakeLoopRunning) return
        wakeLoopRunning = true
        wakeTick(ctx, genSeq)
    }

    fun stopWakeLoop() {
        wakeLoopRunning = false
    }

    private fun wakeTick(ctx: Context, mySeq: Int) {
        if (!wakeLoopRunning || mySeq != genSeq || state != "idle" || !JarvisPrefs.isWakeEnabled(ctx)) {
            wakeLoopRunning = false
            return
        }
        OfflineAsrSession.listenOnce(
            ctx,
            onPartial = { },
            onFinal = { text -> main.post { onWakeResult(ctx, mySeq, text) } },
            onError = {
                main.post {
                    // 静音超时很正常，短暂间隔后继续下一轮轮询
                    main.postDelayed({ wakeTick(ctx, mySeq) }, 400)
                }
            }
        )
    }

    private fun onWakeResult(ctx: Context, mySeq: Int, text: String) {
        if (mySeq != genSeq || state != "idle") return
        val hit = WAKE_WORDS.any { text.contains(it) }
        if (!hit) {
            main.postDelayed({ wakeTick(ctx, mySeq) }, 400)
            return
        }
        // 命中唤醒词：如果同一句话里唤醒词后面还带了指令（比如"贾维斯，几点了"），直接把剩余部分当问题；
        // 否则进入正常的听一句模式。
        wakeLoopRunning = false
        val remainder = WAKE_WORDS.fold(text) { acc, w -> acc.replace(w, "") }
            .trim(' ', ',', '，', '。', '！', '!')
        OrbController.showBubble("我在")
        if (remainder.length >= 2) {
            setState(ctx, "listening")
            onUserUtterance(ctx, genSeq, remainder)
        } else {
            beginListening(ctx, fromWake = true, prefilledText = null)
        }
    }

    private val WAKE_WORDS = listOf("贾维斯", "Jarvis", "jarvis", "贾维思", "加维斯")
}
