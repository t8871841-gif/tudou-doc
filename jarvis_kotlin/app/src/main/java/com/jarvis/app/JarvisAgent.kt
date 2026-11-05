package com.jarvis.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.jarvis.app.asr.OfflineAsrSession
import com.jarvis.app.brain.SpeechMood
import com.jarvis.app.device.CallState
import com.jarvis.app.wake.WakeWordEngine
import com.jarvis.app.command.LocalCommands
import com.jarvis.app.command.PendingClarification
import com.jarvis.app.data.WeatherTool
import com.jarvis.app.local.LocalLlm
import com.jarvis.app.memory.MemoryService
import com.jarvis.app.net.CustomClient
import com.jarvis.app.net.ChatRouter
import com.jarvis.app.skill.SkillItem
import com.jarvis.app.skill.SkillRunner
import com.jarvis.app.skill.SkillResumeState
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
 * 状态机：idle → listening（听） → thinking（问 GLM） → speaking（EdgeTts 播报） → idle
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

    /** MainActivity 注入：把 JS 串送进 HUD WebView（语音路径没有 Activity 引用时为 null，
     *  本地指令里动 HUD 的那几条会自己回绝，不硬调）。 */
    @Volatile
    var onRunHudJs: ((String) -> Unit)? = null

    fun currentState(): String = state

    // ---------- 通话接线（移植自 Andee device/CallState） ----------
    //
    // 来电/通话中：停掉当前收音轮次与播报（hardResetToIdle：停 TTS/ASR/任务循环，
    // 回 idle），通话期间不重启唤醒轮询、不新开收音轮次；挂断后恢复待命。
    // CallState 在主线程回调，这里直接处理。挂断后不自动续播、不自动续听。

    /** 通话进行中：唤醒监听与新收音轮次都暂停（见 WakeWordEngine 门控的通话检查与 beginListening 的门）。 */
    @Volatile
    private var callActive = false

    private val callListener = CallState.Listener { inCall ->
        val ctx = appCtx ?: return@Listener
        if (inCall) onCallStarted(ctx) else onCallEnded(ctx)
    }

    /** 来电/通话中：停收音停播报，回 idle；通话期间不重启唤醒监听。 */
    private fun onCallStarted(ctx: Context) {
        if (callActive) return
        callActive = true
        Log.i("JarvisAgent", "call started, stopping recording and playback")
        AuditLog.append(ctx, "通话", "来电/通话中", "已停收音与播报")
        WakeWordEngine.pause()
        hardResetToIdle(ctx)
    }

    /** 挂断：恢复待命（唤醒监听按开关恢复）。 */
    private fun onCallEnded(ctx: Context) {
        if (!callActive) return
        callActive = false
        Log.i("JarvisAgent", "call ended, resuming standby")
        WakeWordEngine.resume()
    }

    @Volatile
    private var genSeq = 0 // 每次状态切换/急停自增，老的异步回调靠这个号失效

    /** L1：上一次已推到面板的收音中途文字（去重用：相同文字不重复推；新 partial 立即推，不节流）。 */
    @Volatile
    private var lastPartialShown = ""

    /** M20/M23：最近一句用户原话，供 speakReply 单落点写历史、触发记忆提取。 */
    @Volatile
    private var lastUserText = ""

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
        // 通话接线（移植自 Andee device/CallState）：来电/通话中停收音停播报，挂断后恢复待命。
        // CallState.start 幂等、无权限时只记日志；同一 listener 实例重复 add 会被集合去重。
        CallState.start(app)
        CallState.addListener(callListener)
        // 批六：顺带扫一遍今日日程排出发提醒（幂等，同日程同 id 覆盖；晨报到点时也会扫）
        com.jarvis.app.proactive.DeparturePlanner.scan(app)
        // 唤醒词（移植自 Andee wake/）：轻量 MFCC+DTW 关键词检测，替代旧的离线识别轮询。
        // onWake 在 WakeWord 监督线程回调，先 main.post 再碰 agent 状态。
        WakeWordEngine.init(app)
        WakeWordEngine.start {
            main.post { beginListening(app, fromWake = true, prefilledText = null) }
        }
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

    /** 桥接急停（彻底融合第 2 步）：与内部 hardResetToIdle 同逻辑，公开给 HudBridge 用，内部逻辑不动。 */
    fun stopAll(ctx: Context) {
        hardResetToIdle(ctx.applicationContext)
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
        CustomClient.cancelInFlight()
        TtsRouter.stop()
        OfflineAsrSession.stop()
        ScreenVisionAgent.cancelInFlight()
        com.jarvis.app.task.TaskLoop.cancelInFlight() // 批九：跨应用任务循环一并急停
        com.jarvis.app.task.TripLoop.cancelInFlight() // 行程规划阶段 0：规划循环一并急停
        com.jarvis.app.skill.SkillRunner.cancelInFlight() // S19：技能一并急停
        TakeoverBorder.hide()
        setState(ctx, "idle")
        WakeWordEngine.resume()
    }

    private fun setState(ctx: Context, s: String) {
        state = s
        OrbController.setState(s)
        onStateChanged?.invoke(s)
    }

    /**
     * S25：失败反馈既走悬浮球气泡，也进对话面板（MainActivity 已把 onReplyText
     * 接成 addBubble）。App 前台时悬浮球根本不存在，只调 showBubble 等于静默吞掉。
     */
    private fun showFailureBubble(msg: String) {
        OrbController.showBubble(msg)
        onReplyText?.invoke(msg)
    }

    // ---------- 主流程：听 → 问 → 答 → 播 ----------

    private fun beginListening(ctx: Context, fromWake: Boolean, prefilledText: String?) {
        WakeWordEngine.pause() // 正式进入对话流程，暂停唤醒词监听
        val mySeq = genSeq
        lastPartialShown = "" // L1：新一轮收音，中途文字从头开始累计
        // 真机反馈（jk-v11）：没录音权限时别默默失败，给一句看得见的明示（重装后权限会被清掉）。
        if (prefilledText.isNullOrBlank() &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED
        ) {
            // S25：前台时悬浮球不存在，失败也要进对话面板。
            showFailureBubble("麦克风没授权：打开 App 自检页，或去系统设置给 Jarvis 录音权限")
            setState(ctx, "idle")
            return
        }
        // 通话接线：通话中不新开收音轮次（在途的轮次由来电回调的 hardResetToIdle 停掉）。
        if (CallState.isInCall()) {
            showFailureBubble("正在通话中，等挂断再说")
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
            // L1：收音中途也给面板实时反馈（“正在听”气泡），每个新 partial 立即推一次，
            // 不节流；相同文字不重复推。定稿仍由 onFinal 经 onUserUtterance 推。
            onPartial = { txt ->
                main.post {
                    if (mySeq != genSeq) return@post
                    val t = txt.trim()
                    if (t.isNotEmpty() && t != lastPartialShown) {
                        lastPartialShown = t
                        onUserText?.invoke("🎤 $t")
                    }
                }
            },
            onFinal = { text -> main.post { if (mySeq == genSeq) onUserUtterance(ctx, mySeq, text) } },
            onError = { msg ->
                main.post {
                    if (mySeq != genSeq) return@post
                    // S25：前台时悬浮球不存在，失败也要进对话面板。
                    showFailureBubble(if (fromWake) "没听清，继续待命" else "没听清：$msg")
                    setState(ctx, "idle")
                    WakeWordEngine.resume()
                }
            }
        )
    }

    // 三期新增：两类问法在进 GLM 聊天之前先分流到视觉看屏通道。
    private val TAP_INTENT = Regex("^(帮我)?点(一下|击)?\\s*(.+)$")
    private val SCREEN_QUESTION = Regex("(屏幕上|这上面|这是什么|这是啥|这个(按钮|图标|东西)|这上边)")
    // 发信息缺收件人：以“发信息/发消息/发微信/发短信”开头、却没写给谁的句式。
    // 三个任务句式（IN_APP_SEND/SEND_TO/OPEN_THEN）都要求收件人或动作词，
    // 这种裸发句式命中不了，掉进闲聊会被模型鹦鹉学舌（真机：“发信息我晚上
    // 回家吃饭”→“你晚上回家吃饭吧”）。问句不算（带吗/？/怎么/为什么等），
    // 避免“发信息要钱吗”这类求知句被误拦。
    private val BARE_SEND = Regex("^(帮我)?发(个|条)?(信息|消息|微信|短信)")
    private val BARE_SEND_QUESTION = Regex("(吗|？|\\?|怎么|为什么|为何|多少|是不是|能不能|会不会)")

    // M7：语音退出命令（Andee VoiceExitCommand 同款，归一化后整句精确匹配才认，
    // 问怎么退出、复述命令都不算；命中且是用户主动说的就停掉助手回 idle）。
    private val EXIT_COMMANDS = setOf(
        "退出Jarvis", "关闭Jarvis", "Jarvis退出", "Jarvis关闭",
        "休息一下", "先休息一下", "你先休息一下", "退下",
        "exitJarvis", "closeJarvis", "quitJarvis",
        "Jarvisexit", "Jarvisclose", "Jarvisquit",
        "takeabreak", "Jarvistakeabreak"
    )

    private fun isExitCommand(text: String): Boolean {
        val norm = text.lowercase().filter { it.isLetterOrDigit() }
        return norm in EXIT_COMMANDS
    }

    /** M29：输入像不像澄清答案（数字/“第几个”/单个中文序数）。 */
    private fun looksLikeClarificationAnswer(text: String): Boolean {
        val t = text.trim()
        if (t.contains(Regex("[0-9]"))) return true
        if (Regex("第?[一二三四五六七八九十]+个?").containsMatchIn(t)) return true
        return t in setOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    }

    /** M26：回复结尾带 ?/？/吗/呢 的问句强制视为未结束；否则按连续对话开关决定。 */
    private fun shouldContinueListening(ctx: Context, reply: String): Boolean {
        val t = reply.trim()
        if (t.endsWith('?') || t.endsWith('？') || t.endsWith("吗") || t.endsWith("呢")) return true
        return JarvisPrefs.isContinuousChat(ctx)
    }

    private fun onUserUtterance(ctx: Context, mySeq: Int, text: String) {
        if (mySeq != genSeq) return
        lastUserText = text
        // M7：语音退出命令命中就停掉助手回 idle，不发给模型当闲聊。
        if (isExitCommand(text)) {
            onUserText?.invoke(text)
            setState(ctx, "idle")
            WakeWordEngine.resume()
            return
        }
        // 批二十四：空/近空识别结果直接丢弃，不发模型、不回复。
        // 唤醒词被环境噪声误触发时，空识别结果发给模型会换来一句"你说什么，
        // 我没听清楚"。只认纯空白/纯标点，以及单个语气词——这些都不构成指令；
        // 丢弃后直接回 idle 并恢复唤醒轮询，别卡在 listening 状态。
        val norm = text.replace(Regex("[\\s，。！？、；：,.!?;:]"), "")
        if (norm.isEmpty()) {
            setState(ctx, "idle")
            WakeWordEngine.resume()
            return
        }
        // L2：单个语气词也留一条输入气泡，面板里看得到“听到了”，而不是悄悄丢掉。
        if (norm in setOf("啊", "嗯", "呃", "哦", "额")) {
            onUserText?.invoke(text)
            setState(ctx, "idle")
            WakeWordEngine.resume()
            return
        }
        // M29：澄清优先于技能续跑——有待决的联系人澄清且输入像澄清答案时先 resolve；
        // tryResolve 命中才消费 pending 并返回 true（onResolved 回调里已执行动作），
        // 不匹配返回 false 且不消费 pending（约定），落到下面继续处理。
        if (PendingClarification.hasPending() && looksLikeClarificationAnswer(text)) {
            // tryResolve 约定：Boolean?——true 命中并消费，false 明确取消（已消费），
            // null 不匹配且不消费 pending；只有 true 才直接 return。
            if (PendingClarification.tryResolve(text) == true) {
                onUserText?.invoke(text)
                return
            }
        }
        // 第一梯队批三：技能断点续播——先看有没有技能卡在某一步等"继续"，这句话优先
        // 当作回答解析。命中就从断点接着做，不再往下走记忆/技能匹配/聊天这套正常流程；
        // 没命中（不是"继续"类说法）SkillResumeState 会自己清掉状态，交还下面继续处理。
        if (SkillResumeState.hasPending()) {
            val resume = SkillResumeState.tryResolve(text)
            if (resume != null) {
                onUserText?.invoke(text)
                setState(ctx, "thinking")
                runSkillResume(ctx, mySeq, resume)
                return
            }
        }
        // 跨应用任务断点续跑（同 SkillResumeState 的薄状态机）：任务被 STOP 停下
        // 或跑满步数后，用户说“继续”就从断点接着做，不是从头来。没命中就清掉
        // 待处理状态，交还下面继续处理。
        if (com.jarvis.app.task.TaskResumeState.hasPending()) {
            val resume = com.jarvis.app.task.TaskResumeState.tryResolve(text)
            if (resume != null) {
                onUserText?.invoke(text)
                setState(ctx, "thinking")
                AuditLog.append(ctx, "任务续跑", resume.taskText, "从第 ${resume.stepsDone + 1} 步接着做")
                if (resume.foregroundPkgMismatch) {
                    // M22：续跑时前台已不是断点时的应用，现场可能变了，先提醒用户。
                    // 只进面板气泡，不语音，避免与任务播报重叠。
                    val pkgName = resume.foregroundPkg ?: "原来那个应用"
                    onReplyText?.invoke("提醒：你现在不在$pkgName里，现场可能变了，我先按断点接着做，不对你就喊停。")
                    AuditLog.append(ctx, "任务续跑", resume.taskText, "前台包名与断点时不符（断点：$pkgName），已提醒用户")
                }
                com.jarvis.app.task.TaskLoop.run(
                    ctx, resume.taskText, isAlive = { mySeq == genSeq },
                    onReply = { reply -> speakReply(ctx, mySeq, reply) },
                    resumeFrom = resume
                )
                return
            }
        }
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
            // 第一梯队批一：注入改按这句话做相关性检索，不再是「最近 15 条全量拼」。
            val mem = MemoryService.getInjectableMemories(ctx, text)
            if (mySeq != genSeq) return@launch
            // 批四：技能命中先于视觉意图判定（命中即重播，不再走现场流程）
            // 批九：多步任务句式不参与技能匹配——它必须走任务循环现场执行，
            // 不能被一段同名技能的旧录像顶替（见 handleUtterance 里的任务路由）。
            val skill = if (JarvisPrefs.isSkillEnabled(ctx) &&
                !com.jarvis.app.task.TaskLoop.looksLikeTask(text)
            ) {
                SkillService.match(ctx, text)
            } else {
                null
            }
            if (mySeq != genSeq) return@launch
            handleUtterance(ctx, mySeq, text, mem, skill)
        }
    }

    private fun handleUtterance(ctx: Context, mySeq: Int, text: String, memoryContext: String, skill: SkillItem?) {
        // 批八：本地指令层——「打电话给X」「打开X」先于技能与聊天执行，命中即办，
        // 不再掉进大模型被学舌反问（jk-v12 真机反馈）。注意：speak 必须显式传参，
        // 不能写尾随 lambda——尾随 lambda 会被当成最后一个参数 runJs，导致 speak 缺失。
        if (LocalCommands.tryHandle(ctx, text, speak = { reply -> speakReply(ctx, mySeq, reply) }, runJs = onRunHudJs)) return
        // 行程规划阶段 0（2026-11-05）：行程规划任务进规划循环（TripLoop），不走跨应用任务循环。
        // 规划走 glm-4.5（聊天模型通道），动作是问清信息、出按天排的行程，不是点按屏幕。
        // 放在 TaskLoop 之前：行程句式不该被当成跨应用任务；
        // 阶段 2（2026-11-05）：改行程 / 看行程也进 TripLoop（只改已存行程的指定部分、
        // 或读最新存档）。改行程句式带任务排除：跨应用任务里也可能出现
        // “第二天改…”（如“在微信上给桑田发信息：第二天改去华山吃饭”），那句必须
        // 归 TaskLoop，不能被改行程抢走。
        // S13：待问问题未答时，只有像回答的下一句（非任务，见 looksLikeTripAnswer）
        // 才当成回答接着排；问天气/打招呼等无关语句不再被吞，走正常路由。
        val notTask = !com.jarvis.app.task.TaskLoop.looksLikeTask(text)
        val tripHit = com.jarvis.app.task.TripLoop.looksLikeTripTask(text) ||
            (com.jarvis.app.task.TripLoop.looksLikeTripRevision(text) &&
                com.jarvis.app.task.TripLoop.hasSavedTrip(ctx) && notTask) ||
            (com.jarvis.app.task.TripLoop.looksLikeTripView(text) &&
                com.jarvis.app.task.TripLoop.hasSavedTrip(ctx) && notTask) ||
            (com.jarvis.app.task.TripLoop.hasPendingQuestion(ctx) && notTask && com.jarvis.app.task.TripLoop.looksLikeTripAnswer(text))
        if (tripHit) {
            setState(ctx, "thinking")
            AuditLog.append(ctx, "任务路由", text, "命中行程规划句式，进规划循环")
            com.jarvis.app.task.TripLoop.run(ctx, text, isAlive = { mySeq == genSeq }) { reply ->
                speakReply(ctx, mySeq, reply)
            }
            return
        }
        // 批九：多步跨应用任务句式（「在微信上给桑田发信息：…」）进任务循环
        // （移植的 ScreenController + task/TaskLoop 步进驱动）。放在技能之后、
        // TAP_INTENT 之前：技能匹配在上层已对任务句式让行（见 submitText 处），
        // 到这里 skill 必为 null；单步「点一下X」仍归下面的 TAP_INTENT。
        // jk-v14 的失败正是这类句式一条意图都没命中、掉进聊天被模型复述。
        if (com.jarvis.app.task.TaskLoop.looksLikeTask(text)) {
            setState(ctx, "thinking")
            AuditLog.append(ctx, "任务路由", text, "命中多步任务句式，进任务循环")
            com.jarvis.app.task.TaskLoop.run(ctx, text, isAlive = { mySeq == genSeq }) { reply ->
                speakReply(ctx, mySeq, reply)
            }
            return
        }
        // 发信息缺收件人：先反问，不当闲聊回掉（见 BARE_SEND 注释）。
        // 带给/向/跟的不拦：收件人可能写在动词后面（如“发信息给老婆”），
        // 那是任务句式词序没覆盖的另一缺口，不在这里错问。
        if (BARE_SEND.containsMatchIn(text) && !BARE_SEND_QUESTION.containsMatchIn(text) &&
            !text.contains("给") && !text.contains("向") && !text.contains("跟")
        ) {
            AuditLog.append(ctx, "任务路由", text, "发信息缺收件人，反问")
            speakReply(ctx, mySeq, "发给谁？你说个名字，我就帮你发。")
            return
        }
        if (skill != null) {
            setState(ctx, "thinking")
            runSkillFromStart(ctx, mySeq, skill)
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
        // 批十：天气走真数据工具（Open-Meteo），不进任何模型——防编造的第一道闸。
        if (WeatherTool.looksLikeWeather(text)) {
            setState(ctx, "thinking")
            val city = WeatherTool.extractCity(text) ?: JarvisPrefs.getWeatherCity(ctx)
            WeatherTool.fetch(city, tomorrow = text.contains("明天")) { answer ->
                main.post {
                    if (mySeq != genSeq) return@post
                    speakReply(ctx, mySeq, answer ?: "天气没查到，可能是网络不通，你再试一次")
                }
            }
            return
        }
        // 问答路由（批五原版 + 批十调配 + 第二梯队批六再调配）：「智能调配」开时——
        // net/ChatRouter 综合内容/网络/电量/隐私信号判定本地优先还是云端优先；
        // 调配关或本地没就绪 → 云端优先、云端失败再用本地兜底。本地现在会带一份精简版
        // 记忆（见 MemoryService.getInjectableMemories 的 topN 参数），不再是完全空着问。
        setState(ctx, "thinking")
        val localReady = JarvisPrefs.isLocalLlmEnabled(ctx) && LocalLlm.isReady(ctx)
        if (localReady && JarvisPrefs.isLocalLlmPrefer(ctx) && !ChatRouter.cloudFirst(ctx, text)) {
            scope.launch {
                // 本地模型上下文预算小，只要 Top-2 条最相关的，不是云端那份 Top-8。
                val localMem = MemoryService.getInjectableMemories(ctx, text, topN = 2)
                val local = LocalLlm.generate(ctx, text, localMem)
                if (mySeq != genSeq) return@launch
                if (!local.isNullOrBlank()) {
                    // M20/M23：历史与记忆提取已由 speakReply 单落点做，不再这里重复调。
                    speakReply(ctx, mySeq, local)
                } else {
                    askCustom(ctx, mySeq, text, memoryContext, localTried = true)
                }
            }
            return
        }
        askCustom(ctx, mySeq, text, memoryContext, localTried = false)
    }

    /**
     * 第一梯队批一：问答类聊天回复之后，异步触发一次隐式记忆提取（不阻塞朗读/显示，
     * 失败也不影响主流程）。只在这几处真正的"聊天回复"落点调用，天气/看屏/技能/
     * 本地指令这些结构化回复不走这里。
     */
    private fun triggerMemoryExtraction(ctx: Context, userText: String, reply: String) {
        scope.launch { MemoryService.extractImplicit(ctx, userText, reply) }
    }

    /** 第一梯队批三：技能从第一步开始重播，失败时登记断点，成功/用户不理会则清掉。 */
    private fun runSkillFromStart(ctx: Context, mySeq: Int, skill: SkillItem) {
        SkillRunner.run(ctx, skill) { ok, detail, failedIndex ->
            if (mySeq != genSeq) return@run
            if (ok) {
                speakReply(ctx, mySeq, "技能「${skill.name}」执行完了")
            } else {
                if (failedIndex != null) SkillResumeState.offer(skill, failedIndex)
                speakReply(ctx, mySeq, "技能「${skill.name}」$detail，想接着做就说「继续」")
            }
        }
    }

    /** 第一梯队批三：从登记的断点续播，而不是整条重放。 */
    private fun runSkillResume(ctx: Context, mySeq: Int, resume: SkillResumeState.ResumeInfo) {
        SkillRunner.run(ctx, resume.skill, fromIndex = resume.fromIndex) { ok, detail, failedIndex ->
            if (mySeq != genSeq) return@run
            if (ok) {
                speakReply(ctx, mySeq, "技能「${resume.skill.name}」接着做完了")
            } else {
                if (failedIndex != null) SkillResumeState.offer(resume.skill, failedIndex)
                speakReply(ctx, mySeq, "技能「${resume.skill.name}」$detail，想接着做就说「继续」")
            }
        }
    }

    private fun askCustom(ctx: Context, mySeq: Int, text: String, memoryContext: String, localTried: Boolean) {
        // 流式对话：GLM 边吐字边念，第一句完整出来立刻开念，不用等整段回完。
        // onToken/onDone 跑在 OkHttp 线程上，进会话的方法里自己不碰主线程的东西
        // （气泡/TTS/状态都在 TtsRouter 回调里回主线程）；onFinish 在主线程收尾。
        val session = StreamSpeakSession(ctx, mySeq) { fullText ->
            main.post {
                if (mySeq != genSeq) return@post
                if (fullText.isBlank()) {
                    // S25：前台时悬浮球不存在，失败也要进对话面板。
                    showFailureBubble("没听清，你再说一遍")
                    setState(ctx, "idle")
                    WakeWordEngine.resume()
                    return@post
                }
                // jk-v66：聊天流结束时把全文推进对话面板（原来只在悬浮球上闪前 80 字，
                // 面板里没有回复气泡，用户说“语音回复没有文字”）。
                onReplyText?.invoke(fullText)
                triggerMemoryExtraction(ctx, text, fullText)
                // M26：问句结尾强制视为未结束；L6：播完等 3 秒再开麦，避开尾音残响被当成插话。
                // 延迟开麦前再验一次代次：3 秒内用户点了停/球，代次已变就不开麦。
                if (shouldContinueListening(ctx, fullText)) {
                    main.postDelayed({ if (mySeq == genSeq) beginListening(ctx, fromWake = false, prefilledText = null) }, 3000)
                } else {
                    setState(ctx, "idle")
                    WakeWordEngine.resume()
                }
            }
        }
        CustomClient.askStream(
            ctx, text, memoryContext = memoryContext,
            onToken = { token -> session.onToken(token) },
            onDone = { session.onStreamDone() },
            onError = { msg ->
                main.post {
                    // C5：流中途出错先停掉播报会话（清队列、不再念残句），失败走单一终点。
                    session.stop()
                    if (mySeq != genSeq) return@post
                    val canFallback = !localTried && JarvisPrefs.isLocalLlmEnabled(ctx) && LocalLlm.isReady(ctx)
                    if (!canFallback) {
                        // S25：前台时悬浮球不存在，失败也要进对话面板。
                        showFailureBubble("出错了：$msg")
                        setState(ctx, "idle")
                        WakeWordEngine.resume()
                        return@post
                    }
                    // 云端失败 → 本地兜底：答出来就播，答不出再走原来的报错收尾
                    scope.launch {
                        val localMem = MemoryService.getInjectableMemories(ctx, text, topN = 2)
                        val local = LocalLlm.generate(ctx, text, localMem)
                        if (mySeq != genSeq) return@launch
                        if (!local.isNullOrBlank()) {
                            OrbController.showBubble("云端连不上，已用本地模型回答")
                            // M20/M23：历史与记忆提取已由 speakReply 单落点做。
                            speakReply(ctx, mySeq, local)
                        } else {
                            // S25：前台时悬浮球不存在，失败也要进对话面板。
                            showFailureBubble("出错了：$msg")
                            setState(ctx, "idle")
                            WakeWordEngine.resume()
                        }
                    }
                }
            }
        )
    }

    /**
     * 流式播报（C4：整段一次 speak + 句回调跟念）。
     * 大模型边吐字，token 只切句进队列、不逐句调 speak——逐句调 speak 会让
     * EdgeTts 每次都重新握手，句间空 1–3 秒；等流结束拿到全文后调一次
     * TtsRouter.speak(ctx, fullText, onSentence=…)，EdgeTts 内部的 split()
     * 循环在同一个会话里把各句念完，气泡跟念改由 onSentence 句回调驱动。
     * 代价：首句音频要等整段流结束才开念。
     * 用法：askCustom 里建一个，onToken 喂增量，onStreamDone 触发整段播报；
     * 播完（或全文为空）时调 onFinish（全文），由调用方做记忆提取与状态收尾。
     * 打断监听在开念时启动一次（单次 speak 覆盖整段回复），回声比对取实时全文。
     */
    // 注意：JarvisAgent 是 object（单例），里面不能用 inner class（inner 要绑外层实例，
    // object 没有外层实例可绑）。嵌套类在 object 里本来就能直接用 object 的成员
    //（main / genSeq / setState / startBargeInWatch），不用 inner。
    private class StreamSpeakSession(
        private val ctx: Context,
        private val mySeq: Int,
        private val onFinish: (fullText: String) -> Unit
    ) {
        // C4：队列只做切句缓冲，播报不再逐句从这里取句（整段一次 speak，由 EdgeTts 内部分句）。
        private val queue = ArrayDeque<String>()
        private val pending = StringBuilder()
        private val fullText = StringBuilder()
        private var streamDone = false
        private var finished = false
        private var stopped = false

        /** 流里来了一段增量：拼进全文，按句切分进队列；C4：只切句，不逐句调 speak。 */
        fun onToken(token: String) {
            if (finished || stopped) return
            fullText.append(token)
            pending.append(token)
            drainSentences()
        }

        /**
         * 流结束了：拿全文调一次 speak（C4），让 EdgeTts 在同一个会话里念完全段；
         * 气泡跟念由 onSentence 句回调驱动。全文为空时直接收尾，不开念。
         */
        fun onStreamDone() {
            if (finished || stopped) return
            streamDone = true
            pending.clear()
            val text = fullText.toString().trim()
            // 脑移植 SpeechMood：去心情标记后再念（模型没发标记时原样返回）；
            // 纯标记、无正文时直接收尾，不空念一次。
            val spoken = SpeechMood.parse(text).text
            if (spoken.isEmpty()) {
                finish()
                return
            }
            // 已被打断/急停（代次已变）：不再开念，也不走 onFinish（调用方已回 idle）。
            if (mySeq != genSeq) {
                finished = true
                return
            }
            TtsRouter.speak(
                ctx, spoken,
                onStart = {
                    main.post {
                        if (mySeq != genSeq) return@post
                        setState(ctx, "speaking")
                        if (JarvisPrefs.isBargeInEnabled(ctx)) {
                            startBargeInWatch(ctx, mySeq) { spoken }
                        }
                    }
                },
                // C4：气泡跟念由句回调驱动（TTS 路约定的 onSentence 可选参数）。
                onSentence = { sentence -> main.post { OrbController.showBubble(sentence) } },
                onDone = { ok ->
                    // M11：取消/打断（代次已变）不记“TTS 全部通道失败”审计，
                    // 只有真正播报失败才记。
                    if (!ok && mySeq == genSeq) AuditLog.append(ctx, "语音播报", "TTS 全部通道失败", spoken.take(40))
                    main.post {
                        // C6：被停（代次已变）就不再收尾——单次 speak 本来也没有下一句可念。
                        if (mySeq != genSeq) return@post
                        finish()
                    }
                }
            )
        }

        /** C5：流中途出错时停掉会话——清队列、不再念残句，也不走 finish 收尾。 */
        fun stop() {
            stopped = true
            finished = true
            queue.clear()
            pending.clear()
        }

        /**
         * 从 pending 里切出完整句子。M13：与播报侧 EdgeTts.split 对齐——
         * 认 ASCII ? ! 和 ". "（英文句末）；超长半句断句时中英文逗号都认（， 和 ,）；
         * 纯标点句过滤（须含字母或数字才进队列），不让纯标点句走到 speak 里被滤空后白白重试。
         */
        private fun drainSentences() {
            while (true) {
                val text = pending.toString()
                var endIdx = -1
                var i = 0
                while (i < text.length) {
                    val c = text[i]
                    if (c == '。' || c == '！' || c == '？' || c == '!' || c == '?' || c == '\n') {
                        endIdx = i
                        break
                    }
                    // 英文句末 ". "：句点后跟空格才算句末（"3.5" 这种小数点不算）。
                    if (c == '.' && i + 1 < text.length && text[i + 1] == ' ') {
                        endIdx = i
                        break
                    }
                    i++
                }
                if (endIdx >= 0) {
                    val sentence = text.substring(0, endIdx + 1).trim()
                    if (sentence.any { it.isLetterOrDigit() }) queue.addLast(sentence)
                    pending.delete(0, endIdx + 1)
                    continue
                }
                // 没有句末标点：半句太长（>60 字）就在最后一个中/英文逗号处断开
                if (text.length > 60) {
                    val commaIdx = maxOf(text.lastIndexOf('，'), text.lastIndexOf(','))
                    if (commaIdx > 20) {
                        val sentence = text.substring(0, commaIdx + 1).trim()
                        if (sentence.any { it.isLetterOrDigit() }) queue.addLast(sentence)
                        pending.delete(0, commaIdx + 1)
                        continue
                    }
                }
                break
            }
        }

        private fun finish() {
            if (finished) return
            finished = true
            onFinish(fullText.toString().trim())
        }
    }

    private fun speakReply(ctx: Context, mySeq: Int, reply: String) {
        if (mySeq != genSeq) return
        // 脑移植 SpeechMood：先解析心情标记，去标记后的正文用于播报/记历史/推气泡；
        // 模型没发标记时 parse 原样返回，不影响现有回复。
        val text = SpeechMood.parse(reply).text
        // M20：本地模型/任务/技能/天气/本地指令/看屏的回复也进聊天历史。
        // 聊天流式路径（askCustom）由 CustomClient.askStream 成对写历史，不走这里，不会重复记。
        CustomClient.addAssistantTurn(text)
        // M23：各回复落点都触发记忆提取（聊天流式路径在 askCustom.onFinish 里触发）。
        if (lastUserText.isNotBlank()) triggerMemoryExtraction(ctx, lastUserText, text)
        OrbController.showBubble(text.take(80))
        onReplyText?.invoke(text)
        if (JarvisPrefs.isMuted(ctx)) {
            // 批一新增：HUD 静音按钮开着——只留文字（气泡已经显示了），不过 EdgeTts，直接回 idle。
            setState(ctx, "idle")
            WakeWordEngine.resume()
            return
        }
        // 真机反馈（jk-v11）：改走 TtsRouter——Edge 失败自动落系统 TTS，不再哑火；
        // 此前这里把 EdgeTts 的 onDone(false) 直接忽略，网络不通时连失败都看不见。
        TtsRouter.speak(
            ctx, text,
            onStart = {
                main.post {
                    if (mySeq != genSeq) return@post
                    setState(ctx, "speaking")
                    if (JarvisPrefs.isBargeInEnabled(ctx)) startBargeInWatch(ctx, mySeq) { text }
                }
            },
            onDone = { ok ->
                // M11：取消/打断（代次已变）不记“TTS 全部通道失败”审计，只有真正播报失败才记。
                if (!ok && mySeq == genSeq) AuditLog.append(ctx, "语音播报", "TTS 全部通道失败", text.take(40))
                main.post {
                    if (mySeq != genSeq) return@post
                    // M26：问句结尾强制视为未结束；L6：播完等 3 秒再开麦，避开尾音残响被当成插话。
                    if (shouldContinueListening(ctx, text)) {
                        main.postDelayed({ if (mySeq == genSeq) beginListening(ctx, fromWake = false, prefilledText = null) }, 3000)
                    } else {
                        setState(ctx, "idle")
                        WakeWordEngine.resume()
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
     *
     * 批二十三回声过滤（2026-10-25 用户确认切断时气泡正是"打断了，在听…"）：
     * 批八收紧的阈值（1200ms 缓冲、连续两次 ≥4 字）拦不住长回复——扬声器自己的
     * 声音会源源不断地产生 ≥4 字的中间结果，等于必误判。现把正在播的回复文本
     * 交给监听做回声比对：识别结果去标点去空白后，只要是回复文本的子串、或以
     * 回复文本开头（回声后拖了尾巴），都当回声直接忽略，既不计数也不清零；
     * 只有回复里没有的话才计入打断。真插话仍能打断，只是要连续两次非回声结果。
     */
    /** M3：短打断指令——一次命中即打断，不用凑满 3 次长片段，也不受回声过滤拦。 */
    private val BARGE_IN_SHORT_COMMANDS = setOf("停", "停下", "别说了", "别说", "不要说了")

    private fun startBargeInWatch(ctx: Context, mySeq: Int, replyText: () -> String) {
        // 回声比对只看字本身：回复里的标点、空白都去掉，识别结果同样处理。
        // replyText 是个取数器（流式播报时全文还在长，每次比对都现取最新全文）。
        // M3：每句开念都重启一次监听（流式播报有多句，首句那次只覆盖前约 7 秒）；
        // listenOnce 开头自带 stop()，旧监听会被新监听顶掉，不会叠加。
        main.postDelayed({
            if (mySeq != genSeq || state != "speaking") return@postDelayed
            var strongHits = 0
            // 打断动作抽出来：短指令与长句达标共用同一条收尾。
            fun triggerBargeIn() {
                main.post {
                    if (mySeq != genSeq || state != "speaking") return@post
                    // C6：先自增代次（旧轮次所有回调失效），再停播、再开听——
                    // 否则 TtsRouter.stop() 触发的 onDone(false) 会把打断本身顶掉、
                    // 或打断后继续念下一句。
                    genSeq++
                    TtsRouter.stop()
                    OrbController.showBubble("打断了，在听…")
                    beginListening(ctx, fromWake = false, prefilledText = null)
                }
            }
            OfflineAsrSession.listenOnce(
                ctx,
                onPartial = { txt ->
                    if (mySeq != genSeq || state != "speaking") return@listenOnce
                    val norm = txt.replace(Regex("[\\s，。！？、；：,.!?;:]"), "")
                    val replyNorm = replyText().replace(Regex("[\\s，。！？、；：,.!?;:]"), "")
                    // 回声过滤只对 ≥5 字的长片段做子串比对：短片段没法可信地比对，
                    // 且“停/别说了”这类短打断指令必须放行，不能被回声过滤吞掉。
                    // replyNorm 为空（回复全是标点，不可能出现在正常回复里）时不过滤。
                    if (norm.length >= 5 && replyNorm.isNotEmpty() && (replyNorm.contains(norm) || norm.startsWith(replyNorm))) {
                        strongHits = 0
                        return@listenOnce
                    }
                    // M3：短打断指令单独放行，一次命中即打断，不用凑满 3 次长片段。
                    if (norm in BARGE_IN_SHORT_COMMANDS) {
                        triggerBargeIn()
                        return@listenOnce
                    }
                    // 批三十调参（2026-10-26 用户真机确认：没点球、有打断气泡、开关开着——
                    // 即扬声器自声被误判为插话）：回声过滤只认原样子串，识别错一两个字
                    // 就过不了滤；旧阈值（连续 2 次 ≥4 字）太灵敏，单句误识别的累计
                    // partial 就能凑够 2 次。改成连续 3 次 ≥5 字：真插话是持续成句的
                    // 话，轻松达标；零星误识别很难连凑 3 次长片段。功能本身不动。
                    if (txt.trim().length >= 5) strongHits++ else strongHits = 0
                    if (strongHits >= 3) triggerBargeIn()
                },
                onFinal = { /* 播报期间的识别只用来判断"有没有在说话"，最终结果走 beginListening 重新识别一次 */ },
                onError = { /* 播报期间识别拿不到结果很正常（本来就没人说话），忽略 */ }
            )
        }, 1200)
    }

    // ---------- 唤醒词（移植自 Andee wake/：轻量 MFCC+DTW 本地关键词检测） ----------
    // 监听与门控在 WakeWordEngine；start() 里 init+start，beginListening() 里 pause，
    // 各收尾处 resume。旧的离线识别轮询（maybeStartWakeLoop/wakeTick/onWakeResult）已删。
}
