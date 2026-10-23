package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.jarvis.app.asr.OfflineAsrSession
import com.jarvis.app.local.LocalLlm
import com.jarvis.app.memory.MemoryService
import com.jarvis.app.net.AgnesClient
import com.jarvis.app.skill.SkillItem
import com.jarvis.app.skill.SkillRunner
import com.jarvis.app.skill.SkillService
import com.jarvis.app.tts.TtsRouter
import com.jarvis.app.vision.ScreenVisionAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 二期新增：对话 + 语音的总调度。接管 OrbController 的 eventSink/dartAlive
 * （原来是 Flutter 侧接的，纯 Kotlin 重写后由这里直接接）。
 *
 * 状态机：idle → listening（听） → thinking（问 Agnes） → speaking（EdgeTts 播报） → idle
 * 任一状态都能被 estop 打断回到 idle；speaking 状态下如果检测到用户开口，按第 6 条做打断。
 */
object JarvisAgent {
    private val main = Handler(Looper.getMainLooper())

    // 批三：记忆读写是挂起调用，统一在这个主线程作用域里跑（单例，不取消）
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    @Volatile
    private var appCtx: Context? = null

    @Volatile
    private var state = "idle" // idle/listening/thinking/speaking

    /** 批一新增：HUD 主页订阅状态变化（舞台配色、状态文案）。在主线程回调。 */
    @Volatile
    var onStateChanged: ((String) -> Unit)? = null

    // 真机反馈（jk-v11）：语音问答也要在 App 对话里留文字——MainActivity 注册这两个回调把
    // 用户的话和 Jarvis 的回复同步成气泡（此前只有悬浮球气泡一闪而过，对话里看不到）。
    @Volatile
    var onUserText: ((String) -> Unit)? = null

    @Volatile
    var onReplyText: ((String) -> Unit)? = null

    fun currentState(): String = state

    @Volatile
    private var wakeLoopRunning = false

    @Volatile
    private var genSeq = 0 // 每次状态切换/急停自增，老的异步回调靠这个号失效

    fun start(ctx: Context) {
        val app = ctx.applicationContext
        appCtx = app
        OrbController.dartAlive = true
        OrbController.eventSink = { event, ack -> onOrbEvent(app, event, ack) }
        // 批二：看屏白名单是用户在设置里填的，无障碍服务那边不会自己去读 SharedPreferences，
        // 每次 Agent 启动时同步一次（设置页保存时也会同步一次，见 MainActivity）。
        JarvisAccessibilityService.setUserWhitelist(JarvisPrefs.visionWhitelistSet(app))
        // 批六：接上通知监听的主动播报钩子（决策在 Announcer，含勿扰/验证码/开关）
        com.jarvis.app.proactive.Announcer.attach(app)
        // 批六：顺带扫一遍今日日程排出发提醒（幂等，同日程同 id 覆盖；晨报到点时也会扫）
        com.jarvis.app.proactive.DeparturePlanner.scan(app)
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
        TtsRouter.stop()
        OfflineAsrSession.stop()
        ScreenVisionAgent.cancelInFlight()
        TakeoverBorder.hide()
        setState(ctx, "idle")
        maybeStartWakeLoop()
    }

    private fun setState(ctx: Context, s: String) {
        state = s
        OrbController.setState(s)
        onStateChanged?.invoke(s)
    }

    // ---------- 主流程：听 → 问 → 答 → 播 ----------

    private fun beginListening(ctx: Context, fromWake: Boolean, prefilledText: String?) {
        wakeLoopRunning = false // 正式进入对话流程，暂停唤醒词轮询
        val mySeq = genSeq
        // 真机反馈（jk-v11）：没录音权限时别默默失败，给一句看得见的明示（重装后权限会被清掉）。
        if (prefilledText.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            OrbController.showBubble("麦克风没授权：打开 App 自检页，或去系统设置给 Jarvis 录音权限")
            setState(ctx, "idle")
            return
        }
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
                    if (fromWake) noteFalseWake() // 唤醒了但没等到有效指令，算一次疑似误唤醒
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
        // 批三：先拦截显式记忆指令；未命中再取长期记忆，随问答一起注入。
        scope.launch {
            if (mySeq != genSeq) return@launch
            onUserText?.invoke(text)
            val ack = MemoryService.processExplicitMemory(ctx, text)
            if (mySeq != genSeq) return@launch
            if (ack != null) {
                speakReply(ctx, mySeq, ack)
                return@launch
            }
            val mem = MemoryService.getInjectableMemories(ctx)
            if (mySeq != genSeq) return@launch
            // 批四：技能命中先于视觉意图判定（命中即重播，不再走现场流程）
            val skill = if (JarvisPrefs.isSkillEnabled(ctx)) SkillService.match(ctx, text) else null
            if (mySeq != genSeq) return@launch
            handleUtterance(ctx, mySeq, text, mem, skill)
        }
    }

    private fun handleUtterance(ctx: Context, mySeq: Int, text: String, memoryContext: String, skill: SkillItem?) {
        if (skill != null) {
            setState(ctx, "thinking")
            SkillRunner.run(ctx, skill) { ok, detail ->
                if (mySeq != genSeq) return@run
                speakReply(ctx, mySeq, if (ok) "技能「${skill.name}」执行完了" else "技能「${skill.name}」$detail，已停下")
            }
            return
        }
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
        // 批五：问答路由——本地模型可用时，"优先本地"先本地答、答不出再云端；
        // 否则云端优先，云端失败（断网/报错）再用本地兜底。本地只喂原话，不带记忆
        // 注入（那颗 gemma3 上下文上限 1024，塞不下）。
        setState(ctx, "thinking")
        val localReady = JarvisPrefs.isLocalLlmEnabled(ctx) && LocalLlm.isReady(ctx)
        if (localReady && JarvisPrefs.isLocalLlmPrefer(ctx)) {
            scope.launch {
                val local = LocalLlm.generate(ctx, text)
                if (mySeq != genSeq) return@launch
                if (!local.isNullOrBlank()) {
                    speakReply(ctx, mySeq, local)
                } else {
                    askAgnes(ctx, mySeq, text, memoryContext, localTried = true)
                }
            }
            return
        }
        askAgnes(ctx, mySeq, text, memoryContext, localTried = false)
    }

    private fun askAgnes(ctx: Context, mySeq: Int, text: String, memoryContext: String, localTried: Boolean) {
        AgnesClient.ask(
            ctx, text, memoryContext = memoryContext,
            onResult = { reply -> main.post { if (mySeq == genSeq) speakReply(ctx, mySeq, reply) } },
            onError = { msg ->
                main.post {
                    if (mySeq != genSeq) return@post
                    val canFallback = !localTried && JarvisPrefs.isLocalLlmEnabled(ctx) && LocalLlm.isReady(ctx)
                    if (!canFallback) {
                        OrbController.showBubble("出错了：$msg")
                        setState(ctx, "idle")
                        maybeStartWakeLoop()
                        return@post
                    }
                    // 云端失败 → 本地兜底：答出来就播，答不出再走原来的报错收尾
                    scope.launch {
                        val local = LocalLlm.generate(ctx, text)
                        if (mySeq != genSeq) return@launch
                        if (!local.isNullOrBlank()) {
                            OrbController.showBubble("云端连不上，已用本地模型回答")
                            speakReply(ctx, mySeq, local)
                        } else {
                            OrbController.showBubble("出错了：$msg")
                            setState(ctx, "idle")
                            maybeStartWakeLoop()
                        }
                    }
                }
            }
        )
    }

    private fun speakReply(ctx: Context, mySeq: Int, reply: String) {
        if (mySeq != genSeq) return
        OrbController.showBubble(reply.take(80))
        onReplyText?.invoke(reply)
        if (JarvisPrefs.isMuted(ctx)) {
            // 批一新增：HUD 静音按钮开着——只留文字（气泡已经显示了），不过 EdgeTts，直接回 idle。
            setState(ctx, "idle")
            maybeStartWakeLoop()
            return
        }
        // 真机反馈（jk-v11）：改走 TtsRouter——Edge 失败自动落系统 TTS，不再哑火；
        // 此前这里把 EdgeTts 的 onDone(false) 直接忽略，网络不通时连失败都看不见。
        TtsRouter.speak(
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
                    // 批二「连续对话」：开着的话说完答案直接继续听，不用再喊一次唤醒词。
                    if (JarvisPrefs.isContinuousChat(ctx)) {
                        beginListening(ctx, fromWake = false, prefilledText = null)
                    } else {
                        setState(ctx, "idle")
                        maybeStartWakeLoop()
                    }
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
                            TtsRouter.stop()
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
     * 如果连续待命发热/掉电明显，先把唤醒严格度调到"严格"或直接关掉这个开关。
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

    // 批二：10 分钟内 3 次"唤醒了但没等到有效指令"，临时把判定收紧到严格档，缓解误唤醒。
    private val falseWakeTimes = ArrayList<Long>()
    private fun noteFalseWake() {
        val now = System.currentTimeMillis()
        falseWakeTimes.removeAll { now - it > 10 * 60_000L }
        falseWakeTimes.add(now)
    }
    private fun effectiveStrictness(ctx: Context): Int {
        val now = System.currentTimeMillis()
        falseWakeTimes.removeAll { now - it > 10 * 60_000L }
        if (falseWakeTimes.size >= 3) return 0
        return JarvisPrefs.getWakeStrictness(ctx)
    }

    private fun wakeHitIndex(text: String): Int {
        for (w in WAKE_WORDS) {
            val i = text.indexOf(w, ignoreCase = true)
            if (i >= 0) return i
        }
        return -1
    }

    private fun onWakeResult(ctx: Context, mySeq: Int, text: String) {
        if (mySeq != genSeq || state != "idle") return
        val idx = wakeHitIndex(text)
        val hit = when {
            idx < 0 -> false
            // 严格：「贾维斯」须在句首（容忍 1 个前导字符的识别误差）且整句短，像一句独立的唤醒指令。
            effectiveStrictness(ctx) == 0 -> idx <= 1 && text.length <= 12
            // 标准：在句首附近就行。
            effectiveStrictness(ctx) == 1 -> idx <= 6
            // 宽松：句中任意位置都算命中。
            else -> true
        }
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
