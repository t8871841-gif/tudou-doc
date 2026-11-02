package com.jarvis.app.task

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.command.LocalCommands
import com.jarvis.app.net.AgnesClient
import com.jarvis.app.net.CustomClient
import com.jarvis.app.screen.ScreenCapture
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.screen.TapMarkerUi
import com.jarvis.app.TakeoverBorder
import com.jarvis.app.vision.GridOverlay
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 跨应用任务循环。批九初版是自写的简化循环；批十四按用户要求把 Andee 的循环
 * 内核（BodyCoreService + brain/LocalBrain + net/ToolSchemas 的做法）移植进来，
 * 与现状不同处以它为准：
 *
 * - 步数上限 25（Andee VoiceConfig.DEFAULT_MAX_STEPS），不再是 12。
 * - 相同动作签名连续重复守卫（LocalBrain 的 MAX_REPEATS=3）：同一个动作连着
 *   发第 4 次之前就中止并如实回报在打转，不让它原地耗满步数。无法解析的输出
 *   也算一种签名，连着来一样触发。
 * - 模型输出不合规：带原因立即纠偏重试一次（原有做法，保留）；再不行记为一
 *   步失败继续循环（Andee 是把错误当工具结果回喂让模型自纠），不再整场中止。
 * - 历史回喂：每步把「动作 + 结果摘要」记进下一步提示词，结果摘要尽量透传
 *   ScreenController 自己的判定（screen_changed / now_pkg / elements_hint /
 *   snapped）；历史段有字符预算，超了从最旧的整步丢弃并注明（对应 LocalBrain
 *   的 MAX_HISTORY_CHARS 截断思路；本工程是一次性提示词，预算按单条提示词定）。
 * - 截图策略（ToolSchemas 的纪律）：界面树大纲是默认观察，截图只在树为空
 *   （自绘界面/被扣留）、上一步失败或界面没变化需要核对、或 zoom 要求时才带；
 *   任务大脑（Agnes / 自定义）都带图问。无障碍截图拿不到图而录屏通道
 *   （[ScreenCapture]，批十四移植）可用时用它兜底，图上无网格、只许看不许按
 *   图瞄准，提示词里会标明来源；隐私黑名单包（isShotBlocked）两条通道都不拍。
 *
 * 模型每步仍只能输出一个 JSON 动作（本工程两个 Client 都是单轮文本接口，
 * 没有原生 tool-calling，协议不变）。一次 run 的生命周期：构造专属
 * ScreenController 注册到无障碍服务 → 接管边框亮起 → 循环 → done/fail/急停
 * 收尾并注销。急停复用 JarvisAgent 的代次序号 + 本类 runSeq 双保险。
 */
object TaskLoop {

    /** 步数上限：Andee 的 DEFAULT_MAX_STEPS。 */
    private const val MAX_STEPS = 25

    /** 相同动作签名连续重复的容忍次数（LocalBrain MAX_REPEATS）：达到即判打转中止。 */
    private const val MAX_REPEATS = 3

    /**
     * 无进展计数（Andee 对齐后不再熔断 abort）：原来这里有一层“连续 7 步无真交互
     * 就强制 fail”的熔断，是 2026-10-24 微信任务故障追查后加的；但 Andee 没有这层
     * （只有重复守卫 + 25 步上限兜底），且熔断会误伤需要多轮等待的慢任务。
     * 2026-10-25 起按 Andee 照搬：计数保留，只用于“连续 2 轮无真交互时强制带图”
     * （截图策略），让模型有图可破局；真正的兜底是 MAX_STEPS 与重复守卫。
     * 没有把 swipe 算进“真操作”——滑动本身更像是在找东西，不一定代表任务在往前推进。
     */
    private val REAL_ACTION_KINDS = setOf("tap", "longPress", "type", "zoom", "flow")

    private const val MODEL_TIMEOUT_SEC = 200L // Andee 对齐：客户端读超时 180 秒，闩锁须大于它才不先成瓶颈
    private const val COMPACT_CHARS = 2500

    /** 历史段字符预算（单条提示词内，含任务与大纲之外的余量）。 */
    private const val HISTORY_CHARS = 6000

    /** 每步结果摘要进历史时的截断（对应 LocalBrain 对单条工具结果的 MAX_TOOL_CHARS 思路，本工程量级更小）。 */
    private const val STEP_RESULT_CHARS = 300

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-taskloop").apply { isDaemon = true }
    }
    private val runSeq = AtomicInteger(0)

    /** 任务循环是否正在执行（彻底融合第 3 步：HUD Agent 调度面板要读的真状态）。
     * 在 runBlocking 的 try 之前置位、finally 里复位；无障碍未开那条早退路在置位之前，
     * 不会把标志留在 true。 */
    @Volatile
    private var running = false
    fun isRunning(): Boolean = running

    // 「在<应用>里/上给<人>发信息…」「给<人>发微信…」「打开/用<应用>…发/给…」。
    // 保守优先：必须同时出现「应用或发送动作」与「收件/发送对象」两类信号才算任务；
    // 拿不准放行原流程（聊天/技能/单步指令）。
    private val IN_APP_SEND = Regex("在.{1,12}?(上|里|中).{0,10}?(给|向|跟).{1,16}?(发信息|发消息|发微信|发短信|发送|留言)")
    private val SEND_TO = Regex("(给|向|跟).{1,16}?(发微信|发信息|发消息|发短信|发送消息|发条消息)")
    private val OPEN_THEN = Regex("^(打开|开启|启动|进入|用).{1,14}?(给|向|发|帮我).{2,}")

    /** 任务句式判定（JarvisAgent 路由用）。纯聊天、单步打开应用、提问句都不算。 */
    fun looksLikeTask(text: String): Boolean {
        val t = text.trim()
        if (t.length < 6) return false
        if (t.contains('？') || t.contains('?')) return false
        return IN_APP_SEND.containsMatchIn(t) ||
            SEND_TO.containsMatchIn(t) ||
            OPEN_THEN.containsMatchIn(t)
    }

    /** 急停：自增序号，正在跑的循环下一步检查时自行中止（与 Agent 的 genSeq 急停并行）。 */
    fun cancelInFlight() {
        runSeq.incrementAndGet()
    }

    /**
     * 执行一个多步任务。[isAlive] 由调用方给（Agent 的代次检查）；[onReply] 只会被
     * 调用一次，内容是给用户的一句结果（完成说明或卡在哪一步的如实说明）。
     */
    fun run(ctx: Context, taskText: String, isAlive: () -> Boolean, onReply: (String) -> Unit) {
        val mySeq = runSeq.incrementAndGet()
        val appCtx = ctx.applicationContext
        executor.execute {
            runBlocking(appCtx, taskText, mySeq, isAlive, onReply)
        }
    }

    private fun runBlocking(
        ctx: Context, taskText: String, mySeq: Int,
        isAlive: () -> Boolean, onReply: (String) -> Unit
    ) {
        fun alive() = mySeq == runSeq.get() && isAlive()
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            onReply("无障碍服务没有开启，跨应用任务做不了。请到设置里打开 Jarvis 无障碍服务。")
            return
        }
        // 构造签名 (service, marker, ownArea)：ownArea 用默认（不遮罩自家悬浮球区域）。
        val controller = ScreenController(svc, TapMarkerUi(svc))
        JarvisAccessibilityService.screenController = controller
        AuditLog.append(ctx, "任务循环", taskText, "开始（上限 $MAX_STEPS 步）")
        TakeoverBorder.show()
        // 用户定：跨应用任务期球彻底隐藏，减少屏幕干扰、有利于定位；任务结束
        // 在 finally 里恢复。截图空白是另一层标志，互不顶掉（见 OrbController）。
        com.jarvis.app.OrbController.setTaskHidden(true)
        val history = mutableListOf<String>()
        var lastError = ""
        var pendingImage: String? = null // zoom 放大图：作为下一轮的观察图喂回模型
        var finalReply: String? = null
        // 重复守卫（LocalBrain 同款：数的是动作签名，不是点的位置——那层在
        // ScreenController 里另有计数）：同一签名连着来，repeats 每次 +1，
        // 达到 MAX_REPEATS 时在执行前拦下中止。
        var lastSignature = ""
        var repeats = 0
        // 无进展计数：连续多少步没有出现 tap/longPress/type/zoom 这类真交互，
        // 见上方的说明。任何一步出现这几种动作就清零；计数只用于强制带图。
        var noProgressSteps = 0
        // 2026-10-24 跨应用修复第 2 件：问模型连续失败计数（网络 timeout 之类），
        // 旧代码一次失败就直接 return 判死整轮任务；现在允许连着失败 2 次才判死，
        // 中间那次记一步失败继续（下一轮重新观察、重新问）。
        var askFails = 0
        // 上一步的成败与界面变化：决定下一步要不要带图核对（截图策略）。
        var prevStepFailed = false
        var prevScreenChanged: Boolean? = null
        // 界面指纹（pkg + 大纲）：和上一步一字不差时在提示词里点明，防模型对着
        // 同一张屏反复下同样的动作。
        var prevScreenSig = ""
        running = true
        try {
            var step = 0
            stepLoop@ while (step < MAX_STEPS) {
                if (!alive()) {
                    finalReply = null // 急停：调用方（新指令/停止）会接管播报，这里不抢话
                    AuditLog.append(ctx, "任务循环", "第 ${step + 1} 步前被急停", "已中止")
                    return
                }
                step++
                // ---------- 观察 ----------
                var dump = try {
                    controller.dumpUiTree()
                } catch (e: Exception) {
                    lastError = "读取界面失败：${e.message}"
                    history += "第 $step 步：$lastError"
                    continue
                }
                // Andee 对齐（SETTLE_BUDGET_MS=1500）：树空时先等它长出来再往下走，
                // 而不是立刻按“看不见”处理；300ms 轮询一次，上限 1500ms。
                if (dump.optInt("nodes_useful", -1) == 0) {
                    val settleStart = System.currentTimeMillis()
                    while (System.currentTimeMillis() - settleStart < 1500) {
                        try { Thread.sleep(300) } catch (_: InterruptedException) { break }
                        val again = try { controller.dumpUiTree() } catch (_: Exception) { null }
                        if (again == null) break
                        dump = again
                        if (dump.optInt("nodes_useful", -1) != 0) break
                    }
                }
                val pkg = dump.optString("pkg")
                val hint = dump.optString("hint")
                // elements 是文本大纲：每行「e3*|Button|发送|[540,2100]」——编号、
                // 类名、文字、0-1000 相对中心坐标，带 * 的可点，缩进表示从属层级
                // （ScreenController 格式）。
                val elements = dump.optString("elements")
                // 紧凑表（Phase 2）：只列可操作元素，稳定编号 + 语义类型 + 文字 +
                // 中心坐标，是给模型看的主列表；elements 原样保留（屏变检测、
                // FlowRuntime 的 find/守卫仍按它匹配），不放进提示词。
                val compact = dump.optString("compact")
                val compactDropped = dump.optInt("compact_dropped", 0)
                val nodesTotal = dump.optInt("nodes_total", -1)
                val nodesUseful = dump.optInt("nodes_useful", -1)
                val withheld = dump.optBoolean("withheld", false)
                val screenSig = pkg + "\n" + elements
                val screenSameAsPrev = prevScreenSig.isNotEmpty() && screenSig == prevScreenSig
                prevScreenSig = screenSig

                // ---------- 截图策略（Phase 2：每轮默认带图） ----------
                // 紧凑表 + 截图一起给模型（get_screen_context 模式），让它点真实
                // 存在的元素，不再只靠文字猜。needImage 仍是“图是必需的”信号
                // （树空、上一步失败、界面没变、树太薄、无进展≥2），只用于下面
                // “需要图但没拿到”的提示；截图本身每轮都尽量拍（黑名单包除外），
                // 拍不到就只给树、不卡住这一步。录屏通道仍是无障碍截图的兜底。
                var image: String? = pendingImage
                var imageFromProjection = false
                pendingImage = null
                val treeTooThin = nodesUseful >= 0 && nodesUseful < 3
                val needImage =
                    !JarvisAccessibilityService.isShotBlocked(pkg) &&
                    (image != null || elements.isBlank() || prevStepFailed || prevScreenChanged == false ||
                        treeTooThin || noProgressSteps >= 2)
                if (image == null && pkg.isNotEmpty() && !JarvisAccessibilityService.isShotBlocked(pkg)) {
                    image = accessibilityShot(controller, ctx)
                    if (image == null) {
                        image = projectionShot(ctx)
                        imageFromProjection = image != null
                    }
                }

                val userText = buildString {
                    append("任务：").append(taskText).append('\n')
                    append("当前前台应用包名：").append(pkg.ifEmpty { "未知" }).append('\n')
                    if (hint.isNotEmpty()) append("界面提示：").append(hint).append('\n')
                    if (nodesTotal >= 0) {
                        append("界面节点：共 $nodesTotal 个，其中带名可用的 $nodesUseful 个")
                        if (withheld) append("（界面树被这个应用扣留，元素可能不全）")
                        append('\n')
                    }
                    append("界面元素（紧凑表，每行：稳定编号|类型|文字|[中心x,中心y]，坐标是 0-1000 相对值；稳定编号跨轮不变，同一元素每轮同一个编号，点它就用这个编号；类型 Button/Input/Text/Image/List/Scroll/Checkbox）：\n")
                    append(
                        compact.take(COMPACT_CHARS)
                            .ifEmpty { "（无可操作元素：这个应用可能不向无障碍暴露可点内容，只能看截图操作）" }
                    ).append('\n')
                    if (compactDropped > 0) {
                        append("（紧凑表只列了前 40 个可操作元素，另有 $compactDropped 个没列出；要找没列出的，滑一滑或缩小范围再看）\n")
                    }
                    if (needImage && image == null) {
                        append("（本轮需要截图但没能拿到，只能按界面树操作）\n")
                    }
                    if (imageFromProjection) {
                        append("本轮附图来自录屏通道：原始屏幕像素，没有网格标定，不能按图报格号或坐标瞄准；只能看内容，操作仍按元素编号和界面树进行。\n")
                    }
                    if (screenSameAsPrev) {
                        append("注意：界面与上一步完全相同、纹丝没动——上一个动作没有生效或页面卡住了。不要重复同一个动作，换个做法（先 zoom 看清、滑动、返回或 wait 更久）。\n")
                    }
                    val hist = historySection(history)
                    if (hist.isNotEmpty()) {
                        append("已执行步骤（旧→新）：\n").append(hist)
                    }
                    if (lastError.isNotEmpty()) append("上一步的问题：").append(lastError).append('\n')
                    append("现在只输出下一步的一个 JSON 动作。")
                }
                // 任务大脑（Agnes / 自定义）都带图问；紧凑表空白且最终不带图时，
                // 明确告知模型它看不到可操作元素，别让它空转。
                val sendImage = image
                val askBase = if (compact.isBlank() && sendImage == null) {
                    userText + "\n（注意：本屏没有可操作元素、也看不到截图，只能根据历史步骤判断；若无法推进请输出 done 并说明卡点）"
                } else {
                    userText
                }
                // 第一梯队批二·失败后换策略：同一个动作签名已经连续出现到快要触发重复守卫
                // （repeats 从上一步执行后带到这里），提前一步给出强提示，逼模型主动换一种
                // 真正不同的做法，而不是等拦停了才回报失败——拦停是整场任务直接结束，没有
                // 重试机会，能提前破局就不要走到那一步。
                val strategyNudge = if (repeats >= MAX_REPEATS - 1) {
                    "\n【重要】你前面已经连续${repeats + 1}次做了几乎一样的动作、界面却没有真正推进。" +
                        "再重复一次相似的动作这个任务就会被强制中止，没有下次机会了。现在必须换一种" +
                        "完全不同的思路：比如滚动页面找找看有没有别的入口、点搜索图标直接搜目标文字、" +
                        "先返回上一页换条路线、或者先 zoom 放大看清楚这个点有没有点偏了。" +
                        "不要再输出和前面几步相似的动作。"
                } else ""
                val askTextBase = askBase + strategyNudge
                // ---------- 问模型（解析失败带原因重试一次，再不行记一步失败继续） ----------
                var action: JSONObject? = null
                var attempt = 0
                var askText = askTextBase
                var badReply: String? = null
                while (action == null && attempt < 2) {
                    attempt++
                    // 每步问模型前固定停 1 秒，降速、别一分钟内连着发多次请求撞限流。
                    try { Thread.sleep(1000) } catch (_: InterruptedException) { /* 继续 */ }
                    val (reply, err) = askModel(ctx, askText, sendImage)
                    if (!alive()) return
                    if (err != null) {
                        // 2026-10-24 跨应用修复第 2 件：问模型失败不再直接判死。
                        // 连着失败 2 次才结束；第 1 次记一步失败、下一轮重新观察再问
                        // （不计入无进展计数——这是网络问题，不是模型原地打转）。
                        askFails++
                        if (askFails >= 2) {
                            finalReply = "任务没能继续：$err"
                            AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", "$err（连着失败 $askFails 次，已停下）")
                            return
                        }
                        AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", "$err（第 $askFails 次，将重试）")
                        history += "第 $step 步：问模型失败 — $err（已重试）"
                        lastError = "问模型失败：$err，已重试"
                        prevStepFailed = true
                        prevScreenChanged = null
                        // 429 是每分钟限流：等 30 秒再进下一轮，别立刻重问又撞上限。
                        // 连着失败计数照旧（2 次照样停）。
                        if (err.contains("429")) {
                            AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", "HTTP 429 限流，等 30 秒再重试")
                            try { Thread.sleep(30_000) } catch (_: InterruptedException) { /* 被打断就直接进下一轮 */ }
                        }
                        // 直接进下一轮（重新观察、重新问），不能掉进下面“输出无法解析”
                        // 的分支——那是解析失败的账，网络失败不该计入无进展计数。
                        continue@stepLoop
                    }
                    askFails = 0
                    action = parseAction(reply.orEmpty())
                    if (action == null) {
                        badReply = reply
                        lastError = "模型输出不是合法的 JSON 动作"
                        askText = askBase +
                            "\n你上一次的输出无法解析：${reply?.take(200)}。请严格只输出一个 JSON 对象，不要任何其他文字。"
                    }
                }
                if (action == null) {
                    // 按 Andee 的做法把错误当一步结果回喂（下一步提示词里带着），
                    // 模型有机会自纠；无法解析也算一种签名，连着来照样被守卫拦下。
                    history += "第 $step 步：输出无法解析 — ${badReply?.take(80) ?: "（空回复）"}"
                    prevStepFailed = true
                    prevScreenChanged = null
                    if ("∅unparseable" == lastSignature) {
                        repeats++
                    } else {
                        lastSignature = "∅unparseable"
                        repeats = 0
                    }
                    if (repeats >= MAX_REPEATS) {
                        finalReply = "任务卡在第 $step 步：模型连续多次输出无法解析的内容，已停下，没有继续乱点。"
                        AuditLog.append(ctx, "任务循环", "重复守卫触发", "连续无法解析的输出")
                        return
                    }
                    // 解析不出动作，自然也谈不上是真交互；Andee 对齐：计数保留
                    // （供强制带图），熔断 abort 删除，继续下一轮。
                    noProgressSteps++
                    continue
                }
                val kind = action.optString("action")
                if (kind == "done" || kind == "fail") {
                    val msg = if (kind == "done") {
                        action.optString("message").ifEmpty { "任务完成了" }
                    } else {
                        action.optString("reason").ifEmpty { "模型判断做不下去" }
                    }
                    history += "第 $step 步：$kind — $msg"
                    AuditLog.append(ctx, "任务循环", kind, msg)
                    finalReply = if (kind == "done") msg else "任务没完成，卡在第 $step 步：$msg"
                    return
                }
                // ---------- 重复守卫：执行前判定（LocalBrain 在执行前拦） ----------
                val sig = signatureOf(action)
                if (sig == lastSignature) {
                    repeats++
                } else {
                    lastSignature = sig
                    repeats = 0
                }
                if (repeats >= MAX_REPEATS) {
                    finalReply = "任务卡在第 $step 步：同一个动作（$kind）连着重复多次、界面没有进展，已停下。" +
                        "当前停在${pkg.ifEmpty { "未知界面" }}。"
                    AuditLog.append(ctx, "任务循环", "重复守卫触发", "$kind 连续重复")
                    return
                }
                // Andee 对齐：无进展熔断 abort 删除（Andee 只有重复守卫 + 25 步上限兜底）。
                // 计数保留：连续 2 轮无真交互时强制带图（截图策略），让模型有图可破局。
                noProgressSteps = if (kind in REAL_ACTION_KINDS) 0 else noProgressSteps + 1
                // ---------- 执行 ----------
                val r = execute(ctx, controller, action)
                if (r.zoomImage != null) pendingImage = r.zoomImage
                // 固定等 600ms 改成等界面静止：连续两次读到的界面哈希相同才算稳，
                // 下一步的大纲在界面真稳了之后再读，不盲等。超时没稳住也继续，
                // 只是如实记一笔（历史里的“界面已稳定 / 仍在变”就是这句的来处）。
                val settle = try {
                    ScreenWait.waitForSettle(controller, ScreenWait.SETTLE_DEFAULT_MS, "idle")
                } catch (e: InterruptedException) {
                    return
                } catch (_: Exception) {
                    null
                }
                val settleNote = when {
                    settle == null -> "（等界面稳定时出错，未确认）"
                    settle.readFailed -> "（读界面失败，未确认是否稳定）"
                    settle.settled -> "（界面已稳定）"
                    else -> "（界面仍在变，超时未稳）"
                }
                history += "第 $step 步：${describeAction(action)} → ${r.summary.take(STEP_RESULT_CHARS)}$settleNote"
                AuditLog.append(ctx, "任务循环", "$kind（第 $step 步）", r.summary)
                lastError = if (r.ok) "" else r.summary
                prevStepFailed = !r.ok
                prevScreenChanged = r.screenChanged
            }
            finalReply = "任务跑满了 $MAX_STEPS 步还没完成，已停下。最后进展：${history.lastOrNull() ?: "没有进展"}"
            AuditLog.append(ctx, "任务循环", "步数用完", history.lastOrNull() ?: "")
        } catch (e: Exception) {
            finalReply = "任务执行中出错：${e.message}"
            AuditLog.append(ctx, "任务循环", "异常", e.message ?: e.javaClass.simpleName)
        } finally {
            running = false
            TakeoverBorder.hide()
            com.jarvis.app.OrbController.setTaskHidden(false)
            if (JarvisAccessibilityService.screenController === controller) {
                JarvisAccessibilityService.screenController = null
            }
            val reply = finalReply
            if (reply != null && alive()) onReply(reply)
        }
    }

    /** 历史段：总字符超预算时从最旧的整步丢弃，保留最近的，并在段首注明丢了几步。 */
    private fun historySection(history: List<String>): String {
        if (history.isEmpty()) return ""
        var total = history.sumOf { it.length + 1 }
        var from = 0
        while (total > HISTORY_CHARS && from < history.size - 1) {
            total -= history[from].length + 1
            from++
        }
        val sb = StringBuilder()
        if (from > 0) sb.append("（更早的 $from 步已省略）\n")
        for (i in from until history.size) sb.append(history[i]).append('\n')
        return sb.toString()
    }

    /**
     * 动作签名（重复守卫用）：动作种类 + 决定它「是同一个动作」的关键参数。
     * wait 把 ms 折进签名——Andee 的纪律就是连着等同一次数会被守卫拦下，
     * 要等更久必须把毫秒数往上加（提示词里写明了）。
     */
    private fun signatureOf(action: JSONObject): String {
        val kind = action.optString("action")
        val sb = StringBuilder(kind)
        // 本地流：签名按流里各步的动作种类拼（tap→type→tap），同一种流连着发 3 次
        // 照样被重复守卫拦下；步的种类不同就是另一种流，不算重复。
        if (kind == "flow") {
            val steps = action.optJSONArray("steps")
            if (steps != null) {
                val types = (0 until steps.length())
                    .map { steps.optJSONObject(it)?.optString("action").orEmpty() }
                sb.append('|').append(types.joinToString(">"))
            }
            return sb.toString()
        }
        for (k in listOf("eid", "cell", "part", "on", "text", "app", "direction")) {
            val v = action.optString(k)
            if (v.isNotEmpty()) sb.append('|').append(k).append('=').append(v)
        }
        action.optJSONObject("sel")?.let { sb.append("|sel=").append(it.toString()) }
        for (k in listOf("x", "y", "x1", "y1", "x2", "y2", "ms", "screens", "maxSwipes", "index")) {
            if (action.has(k)) sb.append('|').append(k).append('=').append(action.optInt(k))
        }
        return sb.toString()
    }

    /** 一步的简短描述（进历史用）：种类 + 关键参数，比光秃秃的种类更能让模型对上号。 */
    private fun describeAction(action: JSONObject): String {
        val kind = action.optString("action")
        val detail = when (kind) {
            "tap", "longPress" -> action.optString("eid").ifEmpty {
                action.optJSONObject("sel")?.let { "选「${UiSelector.shortSummary(it)}」" } ?: action.optString("cell").ifEmpty {
                    if (action.has("x")) "${action.optInt("x")},${action.optInt("y")}" else ""
                }
            }
            "tap_sel" -> action.optJSONObject("sel")?.let { "选「${UiSelector.shortSummary(it)}」" }.orEmpty()
            "find" -> action.optJSONObject("sel")?.let { "找「${UiSelector.shortSummary(it)}」" }.orEmpty()
            "type" -> "「${action.optString("text").take(20)}」"
            "openApp" -> action.optString("app")
            "swipe" -> "(${action.optInt("x1")},${action.optInt("y1")})→(${action.optInt("x2")},${action.optInt("y2")})"
            "zoom" -> action.optString("cell")
            "wait" -> "${action.optLong("ms", 1200)}ms"
            "wait_idle" -> "等稳 ${action.optLong("ms", 5000)}ms"
            "wait_change" -> "等变 ${action.optLong("ms", 5000)}ms"
            "scroll_to" -> "滑找「${action.optString("text").ifEmpty { action.optString("eid") }}」"
            "flow" -> {
                val steps = action.optJSONArray("steps")
                if (steps == null) "" else (0 until steps.length())
                    .map { steps.optJSONObject(it)?.optString("action").orEmpty() }
                    .joinToString("→")
            }
            else -> ""
        }
        return if (detail.isEmpty()) kind else "$kind $detail"
    }

    /**
     * 无障碍截图 → JPEG base64（批九同路径：ScreenController 出带网格的 PNG，GridOverlay 只做编码）。
     *
     * 修复（截图通道追查后新增）：原来不管 `screenshot()` 是返回空 `png_base64` 还是直接抛异常
     * （超时 / `onFailure(errorCode)`，比如 FLAG_SECURE 窗口），这里一律吞成裸 `null`，连异常
     * message 里带的具体 errCode 都没留下。现在两种失败都写一条审计日志，把 `screenshot()`
     * 异常里的原始 message（含 errCode）原样记下来——下次真机再卡在这里，审计日志里就能直接
     * 看到是哪种失败，不用再靠猜。
     */
    private fun accessibilityShot(controller: ScreenController, ctx: Context): String? {
        return try {
            val shot = controller.screenshot()
            val b64 = shot.optString("png_base64")
            if (b64.isEmpty()) {
                AuditLog.append(ctx, "截图通道", "accessibility", "返回了结果但没有 png_base64 字段（未抛异常）")
                null
            } else {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val jpeg = bmp?.let { GridOverlay.toJpegBase64(it) }
                bmp?.recycle()
                // 2026-10-24 跨应用修复第 3 件：成功也要留痕。旧逻辑只记失败，
                // 审计里没记录时分不清“没拍”和“拍了没用”。
                if (jpeg != null) {
                    AuditLog.append(ctx, "截图通道", "accessibility", "已拿到图")
                } else {
                    AuditLog.append(ctx, "截图通道", "accessibility", "解码失败：b64 非空但解不出位图")
                }
                jpeg
            }
        } catch (e: Exception) {
            // screenshot() 的异常 message 本身就带着 errCode（见 ScreenController.screenshot()
            // 的 "screenshot failed with code $errCode" / "screenshot timeout"），原样记下。
            AuditLog.append(ctx, "截图通道", "accessibility", "失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /**
     * 录屏兜底（批十四，BodyCoreService 的 captureFallback 同位）：无障碍截图
     * 拿不到图时才走。录屏图是原始像素：缩到长边 [ScreenCapture.SHOT_MAX_SIDE]
     * 后直接编码，不画网格——它没有 shot_id 与点击绑定，只能看不能按图瞄准，
     * 提示词里会标明。
     *
     * 修复（截图通道追查后新增）：之前这里连同 grab() 超时、解码异常，全部静默吃成
     * null，审计日志里一条痕迹都没有，没法区分"没授权"和"这次没抓到帧"。现在三种失败
     * 分别记一条，"未授权"这条按时间限流（60 秒内最多记一条），不然失败是常态、每一步
     * 都记会把审计日志刷屏。注：授权入口在 MainActivity（设置-视觉看屏"录屏授权"按钮
     * + onActivityResult 转 start()）是接好的；isAvailable() 为 false 只说明本次进程里
     * 还没授权过（MediaProjection 授权不随进程重启保留，进程死后需重新授权）。
     */
    private fun projectionShot(ctx: Context): String? {
        val cap = ScreenCapture.shared(ctx)
        if (!cap.isAvailable()) {
            val now = System.currentTimeMillis()
            if (now - lastProjectionUnavailableLogAt > PROJECTION_LOG_THROTTLE_MS) {
                lastProjectionUnavailableLogAt = now
                AuditLog.append(
                    ctx, "截图通道", "media_projection",
                    "不可用：本次进程里尚未获得录屏授权（去设置-视觉看屏点一次录屏授权；" +
                        "授权不随进程重启保留，进程死后需重新授权）"
                )
            }
            return null
        }
        return try {
            val bmp = cap.grab()
            if (bmp == null) {
                AuditLog.append(ctx, "截图通道", "media_projection", "grab() 超时，没等到帧")
                return null
            }
            val scaled = downscale(bmp, ScreenCapture.SHOT_MAX_SIDE)
            val jpeg = GridOverlay.toJpegBase64(scaled)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            // 2026-10-24 跨应用修复第 3 件：成功留痕（同 accessibility 通道）。
            AuditLog.append(ctx, "截图通道", "media_projection", "已拿到图")
            jpeg
        } catch (e: Exception) {
            AuditLog.append(ctx, "截图通道", "media_projection", "失败：${e.message ?: e.javaClass.simpleName}")
            null
        }
    }

    /** media_projection"未授权"这条日志的节流时间戳，避免每一步都重复刷同一条结论。 */
    @Volatile
    private var lastProjectionUnavailableLogAt = 0L
    private const val PROJECTION_LOG_THROTTLE_MS = 60_000L

    private fun downscale(bmp: Bitmap, maxSide: Int): Bitmap {
        val longest = maxOf(bmp.width, bmp.height)
        if (longest <= maxSide) return bmp
        val ratio = maxSide.toFloat() / longest
        return Bitmap.createScaledBitmap(
            bmp,
            (bmp.width * ratio).toInt().coerceAtLeast(1),
            (bmp.height * ratio).toInt().coerceAtLeast(1),
            true
        )
    }

/** 同步等一轮模型回复（askOnce 是回调式，这里用闩锁包成同步，跑在循环线程上）。 */
private fun askModel(ctx: Context, userText: String, image: String?): Pair<String?, String?> {
val latch = CountDownLatch(1)
var reply: String? = null
var error: String? = null
// 按设置路由任务大脑：自定义模型与 Agnes 一样走 OpenAI 兼容接口、带图问。
if (JarvisPrefs.getTaskBrain(ctx) == "custom") {
CustomClient.askOnce(
ctx, SYSTEM_PROMPT, userText, image,
onResult = { reply = it; latch.countDown()},
onError = { error = it; latch.countDown()}
)
} else {
AgnesClient.askOnce(
ctx, SYSTEM_PROMPT, userText, image,
onResult = { reply = it; latch.countDown()},
onError = { error = it; latch.countDown()}
)
}
val finished = try {
latch.await(MODEL_TIMEOUT_SEC, TimeUnit.SECONDS)
} catch (_: InterruptedException) {
false
}
if (!finished) return null to "模型 $MODEL_TIMEOUT_SEC 秒没有回复"
return reply to error
}

/** 从模型回复里抠出第一个 JSON 对象；抠不到返回 null（调用方带原因重试）。 */
private fun parseAction(reply: String): JSONObject? {
val start = reply.indexOf('{')
val end = reply.lastIndexOf('}')
if (start < 0 || end <= start) return null
return try {
val obj = JSONObject(reply.substring(start, end + 1))
if (obj.optString("action").isEmpty()) null
else if (obj.optString("action") == "flow" && !isValidFlow(obj)) null
else obj
} catch (_: Exception) {
null
}
}

/** 本地流的结构校验：steps 必须是 1–15 项的数组，每项是带 action 的 JSON 对象。
 * 不合规按无法解析处理，模型会收到重试提示。 */
private fun isValidFlow(obj: JSONObject): Boolean {
val steps = obj.optJSONArray("steps") ?: return false
if (steps.length() == 0 || steps.length() > FlowRuntime.MAX_FLOW_STEPS) return false
for (i in 0 until steps.length()) {
val s = steps.optJSONObject(i) ?: return false
if (s.optString("action").isEmpty()) return false
}
return true
}

/** 一步执行的结果（摘要、是否成功、界面是否变了、zoom 放大图）。
 * internal：FlowRuntime 的变异步复用这里的执行映射。 */
internal data class StepResult(
val summary: String,
val ok: Boolean,
val screenChanged: Boolean?,
val zoomImage: String?
)

/**
* 执行一个动作。动作到 ScreenController 的映射（全部用它自己的瞄准体系，
* 不另造坐标换算）：有 eid 走 tapByEid/longPressByEid（按上一步元素大纲的
* 编号，最稳）；有 sel 走 tapBySelector/longPressBySelector（按多条件选择器
* 找第 N 个匹配，点之前先爬到最近的可点父节点）；有 cell 或 x/y 走
* tapAim/longPressAim（x/y 是所看图片的 0–1000 相对坐标，on 指明读自全屏图
* "screen" 还是放大图 "zoom"）；swipe 走 swipeNorm（0–1000 相对坐标直通，
* 可带 screens 连滑多屏）；zoom 走 zoomAim（只吃 cell，x/y 先换算成 cell）。
* internal：FlowRuntime 的变异步复用这里的映射，单步行为不变。
*/
internal fun execute(
ctx: Context, controller: ScreenController, action: JSONObject
): StepResult {
val kind = action.optString("action")
return try {
when (kind) {
"tap", "longPress" -> {
val eid = action.optString("eid")
val cell = action.optString("cell").ifEmpty { null}
val part = action.optString("part").ifEmpty { null}
val on = action.optString("on").ifEmpty { "screen"}
val ms = action.optLong("ms", 700)
val result = try {
when {
eid.isNotEmpty() ->
if (kind == "tap") controller.tapByEid(eid)
else controller.longPressByEid(eid, ms)
action.has("sel") -> {
val sel = action.optJSONObject("sel")
?: return StepResult("$kind 的 sel 不是对象", false, null, null)
val index = action.optInt("index", 0)
if (kind == "tap") controller.tapBySelector(sel, index)
else controller.longPressBySelector(sel, index, ms)
}
cell!= null ->
if (kind == "tap") controller.tapAim(on, null, null, cell, part)
else controller.longPressAim(on, null, null, cell, part, ms)
action.has("x") && action.has("y") -> {
val x = action.optInt("x", -1)
val y = action.optInt("y", -1)
if (kind == "tap") controller.tapAim(on, x, y, null, part)
else controller.longPressAim(on, x, y, null, null, ms)
}
else -> return StepResult("$kind 缺少 eid、sel、cell 或坐标", false, null, null)
}
} catch (e: IllegalStateException) {
// 批三十：树盲应用上 tap 因 zoom 过期被拒时，自动对同一目标重拍一张 zoom 图
// 随拒绝一并返回，模型直接从这张图上用 on=zoom 再点，不用另花一步 zoom，
// 也不会在 zoom 和 tap 之间插导航把 zoom 作废。只认守卫的 refused 文案、
// 只认全屏坐标（on=screen）；参数错误（坏 cell、未知 eid/part）和 zoom 图内
// 坐标（on=zoom）不走这里，模型须先修正参数或手动重拍。
if (kind != "tap" || on != "screen" || e.message?.startsWith("refused:") != true) throw e
val zoomCell = cell ?: if (action.has("x") && action.has("y")) cellFromNorm(action.optInt("x", 500), action.optInt("y", 500)) else null
if (zoomCell == null || zoomCell.any { it.isLowerCase() }) throw e
val zoomed = runCatching { controller.zoomAim(zoomCell, part) }.getOrNull() ?: throw e
val png = zoomed.optString("png_base64")
val jpeg = if (png.isNotEmpty()) {
try {
val bytes = Base64.decode(png, Base64.DEFAULT)
val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
val j = bmp?.let { GridOverlay.toJpegBase64(it) }
bmp?.recycle()
j
} catch (_: Exception) {
null
}
} else {
null
}
if (jpeg == null) throw e
return StepResult("点按被拒：${e.message}。已自动对同一目标重拍 zoom 图（见附图），请直接从这张图上用 on=\"zoom\" 点目标，不要再做 zoom、导航等中间动作。", false, null, jpeg)
}
summarize(result)
}
"swipe" -> {
val screens = if (action.has("screens")) {
action.optInt("screens").coerceIn(1, 10)
} else {
null
}
val result = controller.swipeNorm(
action.optInt("x1", 500), action.optInt("y1", 700),
action.optInt("x2", 500), action.optInt("y2", 300),
durationMs = 350, fling = false, screens = screens
)
summarize(result)
}
"type" -> {
val text = action.optString("text")
if (text.isEmpty()) return StepResult("type 动作没有文字", false, null, null)
summarize(controller.typeText(text, null))
}
"submit" -> summarize(controller.submitInput(null))
"zoom" -> {
val cell = action.optString("cell").ifEmpty {
cellFromNorm(action.optInt("x", 500), action.optInt("y", 500))
}
val result = controller.zoomAim(cell, action.optString("part").ifEmpty { null})
val r = summarize(result)
val full = "已放大 ${result.optString("aimed_at")}。${result.optString("aim_check")}".trim()
// zoom 图是 PNG，转成 JPEG 与全屏截图同一格式再喂回模型
val png = result.optString("png_base64")
val jpeg = if (png.isNotEmpty()) {
try {
val bytes = Base64.decode(png, Base64.DEFAULT)
val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
val j = bmp?.let { GridOverlay.toJpegBase64(it)}
bmp?.recycle()
j
} catch (_: Exception) {
null
}
} else {
null
}
StepResult(
if (full.length > 12) full else r.summary,
r.ok || jpeg!= null, r.screenChanged, jpeg
)
}
"back" -> summarize(controller.globalAction("back"))
"home" -> summarize(controller.globalAction("home"))
"openApp" -> {
val app = action.optString("app")
if (app.isEmpty()) return StepResult("openApp 没有应用名", false, null, null)
val r = LocalCommands.launchApp(ctx, app)
StepResult(r, r.startsWith("正在打开"), null, null)
}
"wait" -> {
val ms = action.optLong("ms", 1200).coerceIn(300, 20000)
Thread.sleep(ms)
StepResult("已等待 ${ms}ms", true, null, null)
}
"wait_idle" -> {
// 等界面稳定：连续两次读到的哈希相同才算稳。等到了、等满没稳、读不到树，
// 都如实回报——等待本身没失败，成败看摘要里那句。
val ms = action.optLong("ms", ScreenWait.WAIT_DEFAULT_MS).coerceIn(300, ScreenWait.WAIT_MAX_MS)
val s = ScreenWait.waitForSettle(controller, ms, "idle")
val summary = if (s.readFailed) {
"等界面稳定：读界面失败（等了 ${s.elapsedMs}ms）"
} else if (s.settled) {
"界面已稳定（等了 ${s.elapsedMs}ms）"
} else {
"等了 ${s.elapsedMs}ms，界面还没稳"
}
StepResult(summary, true, null, null)
}
"wait_change" -> {
// 等界面变化：哈希跟现在比不一样了才算变。变了、等满没变、读不到树，都如实回报。
val ms = action.optLong("ms", ScreenWait.WAIT_DEFAULT_MS).coerceIn(300, ScreenWait.WAIT_MAX_MS)
val s = ScreenWait.waitForSettle(controller, ms, "change")
val summary = if (s.readFailed) {
"等界面变化：读界面失败（等了 ${s.elapsedMs}ms）"
} else if (s.changed) {
"界面已变化（等了 ${s.elapsedMs}ms）"
} else {
"等了 ${s.elapsedMs}ms，界面没变"
}
StepResult(summary, true, null, null)
}
"scroll_to" -> {
// 在当前列表里滑动找目标：找到就停；滑到底（连着两次滑完界面都不动）
// 就停下来报没找到；滑满次数还没找到也报没找到。不盲滑过头。
val text = action.optString("text").ifEmpty { null }
val eid = action.optString("eid").ifEmpty { null }
if (text == null && eid == null) {
return StepResult("scroll_to 缺少 text 或 eid", false, null, null)
}
val direction = action.optString("direction").ifEmpty { "down" }
val maxSwipes = action.optInt("maxSwipes", ScreenWait.SCROLL_DEFAULT_MAX)
val target = text ?: eid!!
val s = ScreenWait.scrollTo(controller, text, eid, direction, maxSwipes)
val summary = when {
s.found -> "已找到「$target」"
s.endReached -> "滑到底了，没找到「$target」"
else -> "滑了 ${s.swipes} 次，没找到「$target」"
}
StepResult(summary, s.found, null, null)
}
"tap_sel" -> {
// 按选择器点第 N 个匹配（流里的 tap_sel 步；单步更常用 tap 加 sel）。
// 找不到或 index 超出，tapBySelector 直接抛错，外层 catch 收成一句。
val sel = action.optJSONObject("sel")
?: return StepResult("tap_sel 缺少 sel", false, null, null)
val index = action.optInt("index", 0)
summarize(controller.tapBySelector(sel, index))
}
"find" -> {
// 按多条件选择器找元素：回前 10 个的稳定编号、类型、文字和中心坐标，
// 外加总数，模型拿到编号就能接着点；一个都找不到直说，不假装有。
val sel = action.optJSONObject("sel")
?: return StepResult("find 缺少 sel", false, null, null)
val r = controller.findBySelector(sel)
val total = r.optInt("total", 0)
if (total == 0) return StepResult("没找到符合条件的元素", false, null, null)
val matches = r.optJSONArray("matches") ?: JSONArray()
val lines = (0 until minOf(matches.length(), 10)).mapNotNull { i ->
val m = matches.optJSONObject(i) ?: return@mapNotNull null
"${m.optString("sid")}|${m.optString("type")}|${m.optString("text")}|[${m.optInt("cx")},${m.optInt("cy")}]"
}
StepResult("找到 $total 个：\n" + lines.joinToString("\n"), true, null, null)
}
"flow" -> {
// 本地流：一段有界脚本由手机在本地一口气跑完，中间不再问模型；
// 跑完把每步 trace 压成一句摘要回喂历史，模型据此决定下一步。
// 流本身算一步（不额外占 25 步额度），成败按流的整体 ok 判定。
val result = FlowRuntime.run(ctx, controller, action)
StepResult(summarizeFlow(result), result.optBoolean("ok", false), null, null)
}
else -> StepResult("未知动作：$kind", false, null, null)
}
} catch (e: InterruptedException) {
throw e
} catch (e: Exception) {
StepResult("执行 $kind 出错：${e.message}", false, null, null)
}
}

/**
* 本地流的结果压成一句给模型看的摘要：整体成败、完成几步、耗时、
* 每步的动作与状态（✓ 成、✗ 败、跳过），失败的带一句原因。
* 摘要本身要短，历史段还会再截断一次。
*/
private fun summarizeFlow(result: JSONObject): String {
val total = result.optInt("totalSteps", 0)
val done = result.optInt("completedSteps", 0)
val elapsed = result.optLong("elapsedMs", 0)
val ok = result.optBoolean("ok", false)
val sb = StringBuilder()
sb.append("本地流").append(if (ok) "完成" else "未完成")
    .append("：完成 $done/$total 步，耗时 ${elapsed}ms")
if (result.optBoolean("timedOut", false)) sb.append("（超时）")
if (result.optBoolean("outcomeUnknown", false)) sb.append("（有一步结果未知，别当它成功了）")
val steps = result.optJSONArray("steps")
if (steps != null) {
val parts = mutableListOf<String>()
for (i in 0 until steps.length()) {
val t = steps.optJSONObject(i) ?: continue
val a = t.optString("action")
val mark = when (t.optString("status")) {
"ok" -> "✓"
"skipped" -> "跳过"
else -> "✗"
}
var s = "$a$mark"
val err = t.optString("error")
if (err.isNotEmpty()) s += "（${err.take(20)}）"
parts += s
}
if (parts.isNotEmpty()) sb.append("：").append(parts.joinToString("，"))
}
return sb.toString()
}

/** 0–1000 相对坐标 → 全屏瞄准网格的 cell 标签（8 列 A–H × 12 行），zoom 用。 */
private fun cellFromNorm(x: Int, y: Int): String {
val col = (x.coerceIn(0, 999) * 8 / 1000).coerceIn(0, 7)
val row = (y.coerceIn(0, 999) * 12 / 1000).coerceIn(0, 11) + 1
return "${'A' + col}$row"
}

/**
* 把 ScreenController 的结果 JSON 收成给模型看的摘要：尽量透传它自己的
* 判定（performed / screen_changed / now_pkg / elements_hint / snapped /
* 输入读回），让模型从结果本身就知道界面变成了什么样，而不是再猜。
*/
private fun summarize(result: JSONObject): StepResult {
val performed = result.optBoolean("performed", false)
val error = result.optString("error")
val changed: Boolean? = if (result.has("screen_changed")) {
result.optBoolean("screen_changed")
} else {
null
}
if (error.isNotEmpty()) return StepResult(error, false, changed, null)
val parts = mutableListOf<String>()
parts += if (performed) "已执行" else "未执行（无说明）"
if (result.has("now_pkg")) parts += "现在在 ${result.optString("now_pkg")}"
if (changed!= null) parts += if (changed) "界面有变化" else "界面没变化"
val snappedFrom = result.optString("snapped_from")
if (snappedFrom.isNotEmpty()) {
parts += "落点被吸附：从 $snappedFrom 吸到 ${result.optString("snapped_to")}"
}
val text = result.optString("text")
if (text.isNotEmpty()) parts += "框内文字：${text.take(40)}"
val eh = result.optString("elements_hint")
if (eh.isNotEmpty()) parts += eh.take(120)
return StepResult(parts.joinToString("，"), performed, changed, null)
}

private const val SYSTEM_PROMPT =
"你是 Jarvis 的跨应用任务执行器，在用户的安卓手机上一步一步完成任务。" +
"每一轮你会看到：任务原文、当前前台应用、界面元素紧凑表（只列可操作元素，带稳定编号）、已执行步骤和上一步的结果。每轮还会附一张截图（全屏图带 A1–H12 网格标定；若提示词说明来自录屏通道，则那张图没有网格、只能看不能按图瞄准）。\n" +
"紧凑表是默认且最准的观察：每行格式「稳定编号|类型|文字|[中心x,中心y]」，坐标是系统算好的 0-1000 相对值；稳定编号（s 开头，如 s3f9a2）跨轮不变，同一元素每轮同一个编号，点它就用这个编号；列表型界面（聊天列表、动态流、搜索结果）里同一形状会重复出现，要认准目标文字所在的那一行，只点那一行的编号，绝不把一行的名字配上另一行的按钮。" +
"文字为 # 开头的是控件的内部 id，不是屏幕上印的字，只能用来区分控件，不许当可见文字。" +
"有编号就用编号点（tap 的 eid，优先用紧凑表的稳定编号），绝不拿 [x,y] 或截图位置重算坐标去点一个有编号的元素。" +
"没文字没说明的图标按钮靠类型和中心坐标认领（比如某条动态下方的评论图标就是那条动态的），也可以先 zoom 看清再点。" +
"稳定编号只对当前这一屏态有效：元素还在同一位置时每轮同一个编号；滚动或跳页面后编号会变，每轮只用最新紧凑表里的编号。e 数字编号（e3）只对当前这一轮的大纲有效，下一轮就作废了，优先用稳定编号。\n" +
"你只能输出一个 JSON 对象表示下一步动作，不要输出任何其他文字、不要用代码块包裹。" +
"{\"action\":\"tap\",\"eid\":\"s3f9a2\"} 点紧凑表里的稳定编号（最优先、最稳；e 数字编号也认，但只对当轮有效）；" +
"或 {\"action\":\"tap\",\"x\":540,\"y\":1200} 按 0-1000 相对坐标点你刚看到的全屏截图；" +
"或 {\"action\":\"tap\",\"cell\":\"D7\",\"part\":\"tr\"} 按截图上的网格格号点（part 是格内九宫格部位，可省略）。" +
"{\"action\":\"longPress\",\"eid\":\"s3f9a2\"} 长按稳定编号，可加 \"ms\":800。" +
"{\"action\":\"find\",\"sel\":{\"textContains\":\"发送\"}} 按条件找元素（text/textContains/description/descriptionContains/viewId/className/packageName/clickable/editable/enabled/visible 可组合，都满足才算中；默认不区分大小写），回前 10 个的稳定编号、类型、文字和中心坐标，外加总数；一个都找不到会直说。" +
"{\"action\":\"tap\",\"sel\":{\"textContains\":\"发送\"},\"index\":0} 按选择器点第 N 个匹配（index 缺省 0；只找到 N 个、要第 M 个不存在会直说）；点之前会先爬到最近的可点父节点。选择器是兜底：有稳定编号的元素优先用 eid 点，编号没有或要在一堆同名元素里挑时才用 sel。" +
"{\"action\":\"swipe\",\"x1\":500,\"y1\":800,\"x2\":500,\"y2\":300} 滑动，坐标都是 0-1000 相对值，手指移动多远列表就走多远；要连滑多屏加 \"screens\":3（系统会回报实际滑了几屏），不要用一串相同的小滑动去凑。" +
"{\"action\":\"type\",\"text\":\"要输入的文字\"} 往当前已聚焦的输入框输入（系统依次试输入法、直接填字、剪贴板粘贴三档，不用你换方式；输完读回不对会如实报，别原样重发 type，先 zoom 核对或换路线）。" +
"{\"action\":\"submit\"} 提交当前输入框（如搜索、发送）。" +
"{\"action\":\"back\"} 返回；{\"action\":\"home\"} 回桌面；{\"action\":\"wait\",\"ms\":1500} 等待（每次等待要比上一次久，比如 1500→3000→5000→10000，绝不要连续发相同的等待时长，否则会被重复守卫拦停）；{\"action\":\"openApp\",\"app\":\"微信\"} 打开应用。" +
"{\"action\":\"wait_idle\",\"ms\":5000} 等界面稳定下来（连续两次读到的界面一模一样才算稳），稳了就回；等满 ms 还没稳也回，告诉你没稳。{\"action\":\"wait_change\",\"ms\":5000} 等界面发生变化（跟现在比不一样了才算变），变了就回；等满 ms 没变也回，告诉你没变。这两个是按变化等的，不用固定等几秒；连着等时 ms 换个数字，不然会被判重复。" +
"{\"action\":\"scroll_to\",\"text\":\"目标文字\"} 在当前列表里向下滑动找目标，找到就停；滑到底（连着两次滑完界面都不动）就停下来告诉你没找到；也可以加 \"direction\":\"up\" 往上滑，或 \"maxSwipes\":8 限制最多滑几次；也可以用 \"eid\":\"s3f9a2\" 按稳定编号找。" +
"{\"action\":\"zoom\",\"cell\":\"D7\"} 放大某个网格格看清小字小图标（也可以给 x/y）；下一轮你会看到放大图，" +
"若要按放大图点，动作里加 \"on\":\"zoom\"，坐标和格号都按放大图自身的 0-1000 与小写格号读。" +
"{\"action\":\"flow\",\"steps\":[{\"action\":\"tap\",\"eid\":\"e3\"},{\"action\":\"type\",\"text\":\"你好\"},{\"action\":\"tap\",\"eid\":\"e5\"}]} 本地流：一段确定性的多步操作由手机在本地一口气跑完、中间不再问你，跑完一次把每步结果交回来，算一步、不额外占 25 步的额度。适合同一屏上的固定套路（比如点输入框→输文字→点发送按钮，或点一下→等界面变→再点一下），最多 15 步、总共最多 20 秒（可加 \"timeoutMs\"，但不许超过 20000）。流里的每一步用和单步动作一样的字段（tap/longPress 用 eid/cell/x/y，eid 优先用紧凑表的稳定编号、e 数字编号也认，type 用 text，swipe 用 x1/y1/x2/y2，back/home/zoom 照旧）；另外还有七种流内专用步：{\"action\":\"wait\",\"ms\":3000} 等到界面变化或等满时长，{\"action\":\"wait_idle\",\"ms\":5000} 等到界面稳定，{\"action\":\"wait_change\",\"ms\":5000} 等到界面变化，{\"action\":\"find\",\"text\":\"发送\"} 只检查元素在不在、不动手（也可以给 \"sel\":{...} 按选择器查），{\"action\":\"observe\"} 重新读一遍界面，{\"action\":\"scroll_to\",\"text\":\"目标文字\"} 在列表里滑动找目标（可加 \"direction\":\"up\"、\"maxSwipes\":8，滑到底没找到就停），{\"action\":\"tap_sel\",\"sel\":{\"textContains\":\"发送\"}} 按选择器点第 N 个匹配（可加 \"index\":1）。每一步可加 \"ifPresent\":{\"text\":\"…\"}（元素不在就跳过这步）或 \"ifAbsent\"（元素在就跳过），以及 \"onError\":\"continue\"（这步失败也继续往下跑，缺省是失败就停）。注意：稳定编号只对当前这一屏态有效，流里如果会跳到新页面（打开新应用、进新页面），编号就作废了——这种流要尽量短，或者拆成单步做；e 数字编号只对当轮有效，流里尽量用稳定编号；流里也可以 zoom，但放大图不会中途给你看，只能按你已经知道的格号点。" +
"{\"action\":\"done\",\"message\":\"完成情况一句话\"} 任务真正完成时用。" +
"{\"action\":\"fail\",\"reason\":\"卡在哪一步、什么原因\"} 做不下去时用，禁止假装完成。\n" +
"一步只做一个动作。" +
"输入文字：先用编号点输入框，下一个动作必须紧接着就是 type，不许在点框和输入之间插别的动作，也不许重复点同一个输入框；如果上一步的点击已经把回复框/评论框带起来并聚焦（结果会说明），直接 type，不要再点一次输入条（树被扣留的应用里输入条很薄，点它容易误点到下面的内容）。" +
"开屏广告、会员弹窗、更新提示、权限请求这类挡路的弹窗，先处理掉再继续任务。" +
"上一步结果说「界面没变化」就是大概率没点中：不要把同一个动作原样再发一遍（连着重复同一个动作会被判定为原地打转、任务直接中止），换瞄准方式（先 zoom 核对、改用编号、或先滑动把目标挪到清楚的位置）。" +
"wait 只在页面真的在加载时用；需要连着等时，每次必须比上一次等得更久（如 1500→3000→6000），发相同毫秒数的 wait 同样会被判打转；等到了上限界面还是一动不动，那不是在加载，是卡住了或被弹窗/登录挡住了，换路线或 fail 说明。" +
"有些应用不给元素大纲（只有截图或干脆是空的）：节点总数为 0 说明这一屏是自绘的（小程序、网页、游戏画布），优先用 back/home 退出换路线，不要盲点坐标；节点有数但带名的为 0 时，大纲的分组和中心还在，拿截图和大纲的缩进、坐标对位，用编号点，别降级成坐标点。" +
"树被扣留的应用（如微信）先 zoom 再点是硬规矩：没经过新放大的盲点会被系统拒绝，一个界面一次放大。" +
"发消息这类任务，要依次完成：打开应用、找到联系人、点开会话、输入消息、点发送，发送完成才能 done；" +
"绝不输入密码、验证码；绝不点付款、转账、删除类按钮，除非任务原文明确要求。"
}
