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
import com.jarvis.app.net.DeepSeekClient
import com.jarvis.app.screen.ScreenCapture
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.screen.TapMarkerUi
import com.jarvis.app.TakeoverBorder
import com.jarvis.app.vision.GridOverlay
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
 *   DeepSeek 纯文本永不带图（批十二路由不变）。无障碍截图拿不到图而录屏通道
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
     * 无进展熔断（真机故障追查后新增）：原来的重复守卫只认"连续 N 次一字不差的同一签名"，
     * 模型只要在 wait/home/back/openApp 这几种"不算交互"的动作里换着来（尤其 wait 还被
     * 提示词教着要一次比一次久，签名天然每次都不同），就能在实际原地打转的情况下一路
     * 绕开那层守卫，25 步跑满都不会报警——这正是 2026-10-24 那次微信任务故障的直接原因。
     * 这里单独数"连续多少步没有出现 tap/longPress/type/zoom 这类真正在跟界面交互的动作"，
     * 不管中间怎么换着花样 wait/home/back/openApp、动作签名是不是每次都不同，到了这个数
     * 直接强制 fail，不再指望模型自觉。
     *
     * 没有把 swipe 算进"真操作"——滑动本身更像是在找东西，不一定代表任务在往前推进；
     * 如果真机验收发现"找联系人要滑好几屏"这类场景被误伤，告诉我，加进去就是一行事。
     */
    private const val MAX_NO_PROGRESS_STEPS = 7
    private val REAL_ACTION_KINDS = setOf("tap", "longPress", "type", "zoom")

    private const val MODEL_TIMEOUT_SEC = 45L
    private const val ELEMENTS_CHARS = 5000

    /** 历史段字符预算（单条提示词内，含任务与大纲之外的余量）。 */
    private const val HISTORY_CHARS = 6000

    /** 每步结果摘要进历史时的截断（对应 LocalBrain 对单条工具结果的 MAX_TOOL_CHARS 思路，本工程量级更小）。 */
    private const val STEP_RESULT_CHARS = 300

    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "jarvis-taskloop").apply { isDaemon = true }
    }
    private val runSeq = AtomicInteger(0)

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
        val controller = ScreenController(svc, TapMarkerUi())
        JarvisAccessibilityService.screenController = controller
        AuditLog.append(ctx, "任务循环", taskText, "开始（上限 $MAX_STEPS 步）")
        TakeoverBorder.show()
        val history = mutableListOf<String>()
        var lastError = ""
        var pendingImage: String? = null // zoom 放大图：作为下一轮的观察图喂回模型
        var finalReply: String? = null
        // 重复守卫（LocalBrain 同款：数的是动作签名，不是点的位置——那层在
        // ScreenController 里另有计数）：同一签名连着来，repeats 每次 +1，
        // 达到 MAX_REPEATS 时在执行前拦下中止。
        var lastSignature = ""
        var repeats = 0
        // 无进展熔断计数：连续多少步没有出现 tap/longPress/type/zoom 这类真交互，
        // 见 MAX_NO_PROGRESS_STEPS 处的说明。任何一步出现这几种动作就清零。
        var noProgressSteps = 0
        // 上一步的成败与界面变化：决定下一步要不要带图核对（截图策略）。
        var prevStepFailed = false
        var prevScreenChanged: Boolean? = null
        // 界面指纹（pkg + 大纲）：和上一步一字不差时在提示词里点明，防模型对着
        // 同一张屏反复下同样的动作。
        var prevScreenSig = ""
        try {
            var step = 0
            while (step < MAX_STEPS) {
                if (!alive()) {
                    finalReply = null // 急停：调用方（新指令/停止）会接管播报，这里不抢话
                    AuditLog.append(ctx, "任务循环", "第 ${step + 1} 步前被急停", "已中止")
                    return
                }
                step++
                // ---------- 观察 ----------
                val dump = try {
                    controller.dumpUiTree()
                } catch (e: Exception) {
                    lastError = "读取界面失败：${e.message}"
                    history += "第 $step 步：$lastError"
                    continue
                }
                val pkg = dump.optString("pkg")
                val hint = dump.optString("hint")
                // elements 是文本大纲：每行「e3*|Button|发送|[540,2100]」——编号、
                // 类名、文字、0-1000 相对中心坐标，带 * 的可点，缩进表示从属层级
                // （ScreenController 格式）。
                val elements = dump.optString("elements")
                val nodesTotal = dump.optInt("nodes_total", -1)
                val nodesUseful = dump.optInt("nodes_useful", -1)
                val withheld = dump.optBoolean("withheld", false)
                val screenSig = pkg + "\n" + elements
                val screenSameAsPrev = prevScreenSig.isNotEmpty() && screenSig == prevScreenSig
                prevScreenSig = screenSig

                // ---------- 截图策略（ToolSchemas 纪律 + 批十二路由） ----------
                // 默认只看树；带图的情形：树为空（自绘/被扣留）、上一步失败或
                // 界面没变化需要核对、zoom 留了放大图。录屏通道只作无障碍截图
                // 拿不到图时的兜底。隐私黑名单包两条通道都不拍。
                val taskBrain = JarvisPrefs.getTaskBrain(ctx)
                val brainWantsImage = taskBrain != "deepseek"
                var image: String? = pendingImage
                var imageFromProjection = false
                pendingImage = null
                val needImage = brainWantsImage &&
                    !JarvisAccessibilityService.isShotBlocked(pkg) &&
                    (image != null || elements.isBlank() || prevStepFailed || prevScreenChanged == false)
                if (needImage && image == null && pkg.isNotEmpty()) {
                    image = accessibilityShot(controller)
                    if (image == null) {
                        image = projectionShot(ctx)
                        imageFromProjection = image != null
                    }
                }
                if (!brainWantsImage) image = null

                val userText = buildString {
                    append("任务：").append(taskText).append('\n')
                    append("当前前台应用包名：").append(pkg.ifEmpty { "未知" }).append('\n')
                    if (hint.isNotEmpty()) append("界面提示：").append(hint).append('\n')
                    if (nodesTotal >= 0) {
                        append("界面节点：共 $nodesTotal 个，其中带名可用的 $nodesUseful 个")
                        if (withheld) append("（界面树被这个应用扣留，元素可能不全）")
                        append('\n')
                    }
                    append("界面元素（每行：编号|控件|文字|[中心x,中心y]，坐标是 0-1000 相对值，带 * 号的可点，缩进表示从属层级）：\n")
                    append(
                        elements.take(ELEMENTS_CHARS)
                            .ifEmpty { "（无元素：这个应用可能不向无障碍暴露内容，只能看截图操作）" }
                    ).append('\n')
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
                // 批十二：DeepSeek 是纯文本模型，不带图（循环靠界面树文字推进）；
                // 界面树空白且最终不带图时，明确告知模型它看不到元素，别让它空转。
                val sendImage = if (taskBrain == "deepseek") null else image
                val askBase = if (elements.isBlank() && sendImage == null) {
                    userText + "\n（注意：本屏界面树不可见，你看不到界面元素，只能根据历史步骤判断；若无法推进请输出 done 并说明卡点）"
                } else {
                    userText
                }
                // ---------- 问模型（解析失败带原因重试一次，再不行记一步失败继续） ----------
                var action: JSONObject? = null
                var attempt = 0
                var askText = askBase
                var badReply: String? = null
                while (action == null && attempt < 2) {
                    attempt++
                    val (reply, err) = askModel(ctx, askText, sendImage)
                    if (!alive()) return
                    if (err != null) {
                        finalReply = "任务没能继续：$err"
                        AuditLog.append(ctx, "任务循环", "第 $step 步问模型失败", err)
                        return
                    }
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
                    // 解析不出动作，自然也谈不上是真交互，一并计入无进展熔断。
                    noProgressSteps++
                    if (noProgressSteps >= MAX_NO_PROGRESS_STEPS) {
                        finalReply = "任务卡在第 $step 步：已经连续 $noProgressSteps 步没有真正点/输入/放大过任何东西" +
                            "（含输出解析失败），像是在原地打转，已停下。当前停在${pkg.ifEmpty { "未知界面" }}。"
                        AuditLog.append(ctx, "任务循环", "无进展熔断触发", "解析失败累计 $noProgressSteps 步无交互")
                        return
                    }
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
                // ---------- 无进展熔断：换着花样 wait/home/back/openApp 也躲不过 ----------
                // 签名守卫拦的是"一字不差的重复"；这里拦的是"不管签名变不变，反正没有一步
                // 是真交互"。两层互补，顺序在签名守卫之后——真重复优先报那个更精确的原因。
                noProgressSteps = if (kind in REAL_ACTION_KINDS) 0 else noProgressSteps + 1
                if (noProgressSteps >= MAX_NO_PROGRESS_STEPS) {
                    finalReply = "任务卡在第 $step 步：已经连续 $noProgressSteps 步没有真正点/输入/放大过任何东西" +
                        "（期间只有 wait/back/home/openApp 这类动作），像是在原地打转，已停下。" +
                        "当前停在${pkg.ifEmpty { "未知界面" }}。"
                    AuditLog.append(ctx, "任务循环", "无进展熔断触发", "连续 $noProgressSteps 步无真交互，最后一步是 $kind")
                    return
                }
                // ---------- 执行 ----------
                val r = execute(ctx, controller, action)
                if (r.zoomImage != null) pendingImage = r.zoomImage
                history += "第 $step 步：${describeAction(action)} → ${r.summary.take(STEP_RESULT_CHARS)}"
                AuditLog.append(ctx, "任务循环", "$kind（第 $step 步）", r.summary)
                lastError = if (r.ok) "" else r.summary
                prevStepFailed = !r.ok
                prevScreenChanged = r.screenChanged
                try {
                    Thread.sleep(600)
                } catch (_: InterruptedException) {
                    return
                }
            }
            finalReply = "任务跑满了 $MAX_STEPS 步还没完成，已停下。最后进展：${history.lastOrNull() ?: "没有进展"}"
            AuditLog.append(ctx, "任务循环", "步数用完", history.lastOrNull() ?: "")
        } catch (e: Exception) {
            finalReply = "任务执行中出错：${e.message}"
            AuditLog.append(ctx, "任务循环", "异常", e.message ?: e.javaClass.simpleName)
        } finally {
            TakeoverBorder.hide()
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
        for (k in listOf("eid", "cell", "part", "on", "text", "app")) {
            val v = action.optString(k)
            if (v.isNotEmpty()) sb.append('|').append(k).append('=').append(v)
        }
        for (k in listOf("x", "y", "x1", "y1", "x2", "y2", "ms", "screens")) {
            if (action.has(k)) sb.append('|').append(k).append('=').append(action.optInt(k))
        }
        return sb.toString()
    }

    /** 一步的简短描述（进历史用）：种类 + 关键参数，比光秃秃的种类更能让模型对上号。 */
    private fun describeAction(action: JSONObject): String {
        val kind = action.optString("action")
        val detail = when (kind) {
            "tap", "longPress" -> action.optString("eid").ifEmpty {
                action.optString("cell").ifEmpty {
                    if (action.has("x")) "${action.optInt("x")},${action.optInt("y")}" else ""
                }
            }
            "type" -> "「${action.optString("text").take(20)}」"
            "openApp" -> action.optString("app")
            "swipe" -> "(${action.optInt("x1")},${action.optInt("y1")})→(${action.optInt("x2")},${action.optInt("y2")})"
            "zoom" -> action.optString("cell")
            "wait" -> "${action.optLong("ms", 1200)}ms"
            else -> ""
        }
        return if (detail.isEmpty()) kind else "$kind $detail"
    }

    /** 无障碍截图 → JPEG base64（批九同路径：ScreenController 出带网格的 PNG，GridOverlay 只做编码）。 */
    private fun accessibilityShot(controller: ScreenController): String? {
        return try {
            val shot = controller.screenshot()
            val b64 = shot.optString("png_base64")
            if (b64.isEmpty()) {
                null
            } else {
                val bytes = Base64.decode(b64, Base64.DEFAULT)
                val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                val jpeg = bmp?.let { GridOverlay.toJpegBase64(it) }
                bmp?.recycle()
                jpeg
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 录屏兜底（批十四，BodyCoreService 的 captureFallback 同位）：无障碍截图
     * 拿不到图时才走。录屏图是原始像素：缩到长边 [ScreenCapture.SHOT_MAX_SIDE]
     * 后直接编码，不画网格——它没有 shot_id 与点击绑定，只能看不能按图瞄准，
     * 提示词里会标明。录屏通道未授权/不可用时返回 null，静默退回纯树。
     */
    private fun projectionShot(ctx: Context): String? {
        return try {
            val cap = ScreenCapture.shared(ctx)
            if (!cap.isAvailable()) return null
            val bmp = cap.grab() ?: return null
            val scaled = downscale(bmp, ScreenCapture.SHOT_MAX_SIDE)
            val jpeg = GridOverlay.toJpegBase64(scaled)
            if (scaled !== bmp) scaled.recycle()
            bmp.recycle()
            jpeg
        } catch (_: Exception) {
            null
        }
    }

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
// 批十二：按设置路由任务大脑。DeepSeek 走纯文本（image 强制传 null，
// 调用方也已把 sendImage 置空，这里双保险）；Agnes 维持带图。
if (JarvisPrefs.getTaskBrain(ctx) == "deepseek") {
DeepSeekClient.askOnce(
ctx, SYSTEM_PROMPT, userText, null,
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
if (obj.optString("action").isEmpty()) null else obj
} catch (_: Exception) {
null
}
}

/** 一步执行的结果（摘要、是否成功、界面是否变了、zoom 放大图）。 */
private data class StepResult(
val summary: String,
val ok: Boolean,
val screenChanged: Boolean?,
val zoomImage: String?
)

/**
* 执行一个动作。动作到 ScreenController 的映射（全部用它自己的瞄准体系，
* 不另造坐标换算）：有 eid 走 tapByEid/longPressByEid（按上一步元素大纲的
* 编号，最稳）；有 cell 或 x/y 走 tapAim/longPressAim（x/y 是所看图片的
* 0–1000 相对坐标，on 指明读自全屏图 "screen" 还是放大图 "zoom"）；swipe
* 走 swipeNorm（0–1000 相对坐标直通，可带 screens 连滑多屏）；zoom 走
* zoomAim（只吃 cell，x/y 先换算成 cell）。
*/
private fun execute(
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
val result = when {
eid.isNotEmpty() ->
if (kind == "tap") controller.tapByEid(eid)
else controller.longPressByEid(eid, ms)
cell!= null ->
if (kind == "tap") controller.tapAim(on, null, null, cell, part)
else controller.longPressAim(on, null, null, cell, part, ms)
action.has("x") && action.has("y") -> {
val x = action.optInt("x", -1)
val y = action.optInt("y", -1)
if (kind == "tap") controller.tapAim(on, x, y, null, part)
else controller.longPressAim(on, x, y, null, null, ms)
}
else -> return StepResult("$kind 缺少 eid、cell 或坐标", false, null, null)
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
else -> StepResult("未知动作：$kind", false, null, null)
}
} catch (e: InterruptedException) {
throw e
} catch (e: Exception) {
StepResult("执行 $kind 出错：${e.message}", false, null, null)
}
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
"每一轮你会看到：任务原文、当前前台应用、界面元素大纲（带编号的文本列表）、已执行步骤和上一步的结果。" +
"带图时还会有一张截图（全屏图带 A1–H12 网格标定；若提示词说明来自录屏通道，则那张图没有网格、只能看不能按图瞄准）。\n" +
"元素大纲是默认且最准的观察：每行格式「编号|控件|文字|[中心x,中心y]」，坐标是系统算好的 0-1000 相对值，带 * 号的可点，行首缩进表示从属层级——" +
"列表型界面（聊天列表、动态流、搜索结果）里同一形状会重复出现，要认准目标文字所在的那一行，只用它自己缩进组内的控件（头像、名字、正文、按钮属于同一组），绝不把一行的名字配上另一行的按钮。" +
"文字为 # 开头的是控件的内部 id，不是屏幕上印的字，只能用来区分控件，不许当可见文字。" +
"有编号就用编号点（tap 的 eid），绝不拿 [x,y] 或截图位置重算坐标去点一个有编号的元素。" +
"没文字没说明的图标按钮靠它所在缩进组的位置认领（比如某条动态下方的评论图标就是那条动态的）。" +
"编号只对当前这一屏有效，每轮给你的都是最新一屏的编号，只用最新大纲里的编号。\n" +
"你只能输出一个 JSON 对象表示下一步动作，不要输出任何其他文字、不要用代码块包裹。" +
"{\"action\":\"tap\",\"eid\":\"e3\"} 点元素大纲里的编号（最优先、最稳）；" +
"或 {\"action\":\"tap\",\"x\":540,\"y\":1200} 按 0-1000 相对坐标点你刚看到的全屏截图；" +
"或 {\"action\":\"tap\",\"cell\":\"D7\",\"part\":\"tr\"} 按截图上的网格格号点（part 是格内九宫格部位，可省略）。" +
"{\"action\":\"longPress\",\"eid\":\"e3\"} 长按，可加 \"ms\":800。" +
"{\"action\":\"swipe\",\"x1\":500,\"y1\":800,\"x2\":500,\"y2\":300} 滑动，坐标都是 0-1000 相对值，手指移动多远列表就走多远；要连滑多屏加 \"screens\":3（系统会回报实际滑了几屏），不要用一串相同的小滑动去凑。" +
"{\"action\":\"type\",\"text\":\"要输入的文字\"} 往当前已聚焦的输入框输入。" +
"{\"action\":\"submit\"} 提交当前输入框（如搜索、发送）。" +
"{\"action\":\"back\"} 返回；{\"action\":\"home\"} 回桌面；{\"action\":\"wait\",\"ms\":1500} 等待；{\"action\":\"openApp\",\"app\":\"微信\"} 打开应用。" +
"{\"action\":\"zoom\",\"cell\":\"D7\"} 放大某个网格格看清小字小图标（也可以给 x/y）；下一轮你会看到放大图，" +
"若要按放大图点，动作里加 \"on\":\"zoom\"，坐标和格号都按放大图自身的 0-1000 与小写格号读。" +
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
