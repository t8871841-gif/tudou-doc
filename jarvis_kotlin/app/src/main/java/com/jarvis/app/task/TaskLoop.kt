package com.jarvis.app.task

import android.content.Context
import android.graphics.BitmapFactory
import android.util.Base64
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.command.LocalCommands
import com.jarvis.app.net.AgnesClient
import com.jarvis.app.net.DeepSeekClient
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
 * 批九：多步跨应用任务循环。借鉴 Andee 的 BodyCoreService 循环结构（看屏 →
 * 模型出一步 → 执行 → 再看），但不搬它的服务/人格/配置体系：模型走本工程的
 * [AgnesClient]（用户自填的 Agnes Key；批十二起设置里可改选 [DeepSeekClient]
 * 纯文本跑循环），动作走批九移植的 [ScreenController]，
 * 急停复用 JarvisAgent 的代次序号（genSeq 变化即中止），记录走 [AuditLog]。
 *
 * 一次 run 的生命周期：构造一个专属 ScreenController 注册到无障碍服务（事件
 * 转发喂它的界面静稳判定）→ 接管边框亮起 → 最多 [MAX_STEPS] 步 →
 * done/fail/急停收尾并注销。整个循环跑在单线程执行器上，串行执行。
 *
 * 模型每步只能输出一个 JSON 动作；解析不了时带原因重试一次，再不行以 fail
 * 收尾并如实回报卡在哪一步——不猜、不假装完成。
 */
object TaskLoop {

    private const val MAX_STEPS = 12
    private const val MODEL_TIMEOUT_SEC = 45L
    private const val ELEMENTS_CHARS = 5000

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
                // 类名、文字、0-1000 相对中心坐标，带 * 的可点（ScreenController 格式）。
                val elements = dump.optString("elements")
                var image: String? = pendingImage
                pendingImage = null
                if (image == null && pkg.isNotEmpty() && !JarvisAccessibilityService.isShotBlocked(pkg)) {
                    image = try {
                        val shot = controller.screenshot()
                        val b64 = shot.optString("png_base64")
                        if (b64.isNotEmpty()) {
                            val bytes = Base64.decode(b64, Base64.DEFAULT)
                            val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                            val jpeg = bmp?.let { GridOverlay.toJpegBase64(it) }
                            bmp?.recycle()
                            jpeg
                        } else {
                            null
                        }
                    } catch (_: Exception) {
                        null
                    }
                }
                val userText = buildString {
                    append("任务：").append(taskText).append('\n')
                    append("当前前台应用包名：").append(pkg.ifEmpty { "未知" }).append('\n')
                    if (hint.isNotEmpty()) append("界面提示：").append(hint).append('\n')
                    append("界面元素（每行：编号|控件|文字|[中心x,中心y]，坐标是 0-1000 相对值，带 * 号的可点）：\n")
                    append(elements.take(ELEMENTS_CHARS).ifEmpty { "（无元素：这个应用可能不向无障碍暴露内容，只能看截图操作）" }).append('\n')
                    if (history.isNotEmpty()) {
                        append("已执行步骤：\n")
                        history.forEach { append(it).append('\n') }
                    }
                    if (lastError.isNotEmpty()) append("上一步的问题：").append(lastError).append('\n')
                    append("现在只输出下一步的一个 JSON 动作。")
                }
                // 批十二：任务大脑按设置选——DeepSeek 是纯文本模型，不带图（循环靠
                // 界面树文字推进，Andee 同款路线）；界面树空白且最终不带图时，明确
                // 告知模型它看不到元素，别让它对着截图说明文案空转。
                val taskBrain = JarvisPrefs.getTaskBrain(ctx)
                val sendImage = if (taskBrain == "deepseek") null else image
                val askBase = if (elements.isBlank() && sendImage == null) {
                    userText + "\n（注意：本屏界面树不可见，你看不到界面元素，只能根据历史步骤判断；若无法推进请输出 done 并说明卡点）"
                } else {
                    userText
                }
                // ---------- 问模型（解析失败带原因重试一次） ----------
                var action: JSONObject? = null
                var attempt = 0
                var askText = askBase
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
                        lastError = "模型输出不是合法的 JSON 动作"
                        askText = askBase + "\n你上一次的输出无法解析：${reply?.take(200)}。请严格只输出一个 JSON 对象，不要任何其他文字。"
                    }
                }
                if (action == null) {
                    finalReply = "任务卡在第 $step 步：模型连续两次没给出可执行的动作。" +
                        "当前停在${pkg.ifEmpty { "未知界面" }}，没有继续乱点。"
                    AuditLog.append(ctx, "任务循环", "第 $step 步解析失败两次", "fail 收尾")
                    return
                }
                // ---------- 执行 ----------
                val kind = action.optString("action")
                when (kind) {
                    "done" -> {
                        val msg = action.optString("message").ifEmpty { "任务完成了" }
                        history += "第 $step 步：done — $msg"
                        AuditLog.append(ctx, "任务循环", "done", msg)
                        finalReply = msg
                        return
                    }
                    "fail" -> {
                        val reason = action.optString("reason").ifEmpty { "模型判断做不下去" }
                        history += "第 $step 步：fail — $reason"
                        AuditLog.append(ctx, "任务循环", "fail", reason)
                        finalReply = "任务没完成，卡在第 $step 步：$reason"
                        return
                    }
                    else -> {
                        val (summary, ok, zoomImage) = execute(ctx, controller, action)
                        if (zoomImage != null) pendingImage = zoomImage
                        history += "第 $step 步：$kind — $summary"
                        AuditLog.append(ctx, "任务循环", "${kind}（第 ${step} 步）", summary)
                        lastError = if (ok) "" else summary
                        try {
                            Thread.sleep(600)
                        } catch (_: InterruptedException) {
                            return
                        }
                    }
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

    /** 同步等一轮模型回复（askOnce 是回调式，这里用闩锁包成同步，跑在循环线程上）。 */
    private fun askModel(ctx: Context, userText: String, image: String?): Pair<String?, String?> {
        val latch = CountDownLatch(1)
        var reply: String? = null
        var error: String? = null
        // 批十二：按设置路由任务大脑。DeepSeek 走纯文本（image 强制传 null，
        // 调用方也已把 sendImage 置空，这里双保险）；Agnes 维持现状带图。
        if (JarvisPrefs.getTaskBrain(ctx) == "deepseek") {
            DeepSeekClient.askOnce(
                ctx, SYSTEM_PROMPT, userText, null,
                onResult = { reply = it; latch.countDown() },
                onError = { error = it; latch.countDown() }
            )
        } else {
            AgnesClient.askOnce(
                ctx, SYSTEM_PROMPT, userText, image,
                onResult = { reply = it; latch.countDown() },
                onError = { error = it; latch.countDown() }
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

    /**
     * 执行一个动作，返回（结果摘要, 是否成功, zoom 放大图的 JPEG base64 或 null）。
     * 动作到 ScreenController 的映射（全部用它自己的瞄准体系，不另造坐标换算）：
     * 有 eid 走 tapByEid/longPressByEid（按上一步元素大纲的编号，最稳）；
     * 有 cell 或 x/y 走 tapAim/longPressAim（x/y 是所看图片的 0–1000 相对坐标，
     * on 指明读自全屏图 "screen" 还是放大图 "zoom"）；swipe 走 swipeNorm
     * （0–1000 相对坐标直通）；zoom 走 zoomAim（只吃 cell，x/y 先换算成 cell）。
     */
    private fun execute(
        ctx: Context, controller: ScreenController, action: JSONObject
    ): Triple<String, Boolean, String?> {
        val kind = action.optString("action")
        return try {
            when (kind) {
                "tap", "longPress" -> {
                    val eid = action.optString("eid")
                    val cell = action.optString("cell").ifEmpty { null }
                    val part = action.optString("part").ifEmpty { null }
                    val on = action.optString("on").ifEmpty { "screen" }
                    val ms = action.optLong("ms", 700)
                    val result = when {
                        eid.isNotEmpty() ->
                            if (kind == "tap") controller.tapByEid(eid)
                            else controller.longPressByEid(eid, ms)
                        cell != null ->
                            if (kind == "tap") controller.tapAim(on, null, null, cell, part)
                            else controller.longPressAim(on, null, null, cell, part, ms)
                        action.has("x") && action.has("y") -> {
                            val x = action.optInt("x", -1)
                            val y = action.optInt("y", -1)
                            if (kind == "tap") controller.tapAim(on, x, y, null, part)
                            else controller.longPressAim(on, x, y, null, null, ms)
                        }
                        else -> return Triple("$kind 缺少 eid、cell 或坐标", false, null)
                    }
                    summarize(result)
                }
                "swipe" -> {
                    val result = controller.swipeNorm(
                        action.optInt("x1", 500), action.optInt("y1", 700),
                        action.optInt("x2", 500), action.optInt("y2", 300),
                        durationMs = 350, fling = false
                    )
                    summarize(result)
                }
                "type" -> {
                    val text = action.optString("text")
                    if (text.isEmpty()) return Triple("type 动作没有文字", false, null)
                    summarize(controller.typeText(text, null))
                }
                "submit" -> summarize(controller.submitInput(null))
                "zoom" -> {
                    val cell = action.optString("cell").ifEmpty {
                        cellFromNorm(action.optInt("x", 500), action.optInt("y", 500))
                    }
                    val result = controller.zoomAim(cell, action.optString("part").ifEmpty { null })
                    val (summary, ok) = summarize(result)
                    val full = "已放大 ${result.optString("aimed_at")}。${result.optString("aim_check")}".trim()
                    // zoom 图是 PNG，转成 JPEG 与全屏截图同一格式再喂回模型
                    val png = result.optString("png_base64")
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
                    Triple(if (full.length > 12) full else summary, ok || jpeg != null, jpeg)
                }
                "back" -> summarize(controller.globalAction("back"))
                "home" -> summarize(controller.globalAction("home"))
                "openApp" -> {
                    val app = action.optString("app")
                    if (app.isEmpty()) return Triple("openApp 没有应用名", false, null)
                    val r = LocalCommands.launchApp(ctx, app)
                    Triple(r, r.startsWith("正在打开"), null)
                }
                "wait" -> {
                    val ms = action.optLong("ms", 1200).coerceIn(300, 3000)
                    Thread.sleep(ms)
                    Triple("已等待 ${ms}ms", true, null)
                }
                else -> Triple("未知动作：$kind", false, null)
            }
        } catch (e: InterruptedException) {
            throw e
        } catch (e: Exception) {
            Triple("执行 $kind 出错：${e.message}", false, null)
        }
    }

    /** 0–1000 相对坐标 → 全屏瞄准网格的 cell 标签（8 列 A–H × 12 行），zoom 用。 */
    private fun cellFromNorm(x: Int, y: Int): String {
        val col = (x.coerceIn(0, 999) * 8 / 1000).coerceIn(0, 7)
        val row = (y.coerceIn(0, 999) * 12 / 1000).coerceIn(0, 11) + 1
        return "${'A' + col}$row"
    }

    /** 把 ScreenController 的结果 JSON 收成一句给模型看的摘要。 */
    private fun summarize(result: JSONObject): Triple<String, Boolean, String?> {
        val performed = result.optBoolean("performed", false)
        val error = result.optString("error")
        val summary = when {
            error.isNotEmpty() -> error
            result.has("now_pkg") -> "已执行，现在在 ${result.optString("now_pkg")}，界面${if (result.optBoolean("screen_changed")) "有变化" else "没变化"}"
            result.has("text") -> "已输入，框内文字：${result.optString("text").take(40)}"
            performed -> "已执行"
            else -> "未执行（无说明）"
        }
        return Triple(summary, performed, null)
    }

    private const val SYSTEM_PROMPT =
        "你是 Jarvis 的跨应用任务执行器，在用户的安卓手机上一步一步完成任务。" +
            "每一轮你会看到任务原文、当前前台应用、界面元素大纲和一张带网格标定的截图。" +
            "元素大纲每行格式：编号|控件|文字|[中心x,中心y]，坐标是 0-1000 相对值，带 * 号的可点。" +
            "你只能输出一个 JSON 对象表示下一步动作，不要输出任何其他文字、不要用代码块包裹。" +
            "动作格式：{\"action\":\"tap\",\"eid\":\"e3\"} 点元素大纲里的编号（最优先、最稳）；" +
            "或 {\"action\":\"tap\",\"x\":540,\"y\":1200} 按 0-1000 相对坐标点你刚看到的全屏截图；" +
            "或 {\"action\":\"tap\",\"cell\":\"D7\",\"part\":\"tr\"} 按截图上的网格格号点（part 是格内九宫格部位，可省略）。" +
            "{\"action\":\"longPress\",\"eid\":\"e3\"} 长按，可加 \"ms\":800。" +
            "{\"action\":\"swipe\",\"x1\":500,\"y1\":800,\"x2\":500,\"y2\":300} 滑动，坐标都是 0-1000 相对值。" +
            "{\"action\":\"type\",\"text\":\"要输入的文字\"} 往当前已聚焦的输入框输入，输入前必须先 tap 输入框。" +
            "{\"action\":\"submit\"} 提交当前输入框（如搜索）。" +
            "{\"action\":\"back\"} 返回；{\"action\":\"home\"} 回桌面；{\"action\":\"wait\",\"ms\":1500} 等待；{\"action\":\"openApp\",\"app\":\"微信\"} 打开应用。" +
            "{\"action\":\"zoom\",\"cell\":\"D7\"} 放大某个网格格看清小字小图标（也可以给 x/y）；下一轮你会看到放大图，" +
            "若要按放大图点，动作里加 \"on\":\"zoom\"，坐标和格号都按放大图自身的 0-1000 与小写格号读。" +
            "{\"action\":\"done\",\"message\":\"完成情况一句话\"} 任务真正完成时用。" +
            "{\"action\":\"fail\",\"reason\":\"卡在哪一步、什么原因\"} 做不下去时用，禁止假装完成。" +
            "规则：一步只做一个动作；点之前先确认目标在元素大纲或截图里，找不到先 swipe/wait/zoom，不要盲点；" +
            "有些应用不给元素大纲（只有截图），这类界面先 zoom 再按 on=\\\"zoom\\\" 点，系统会拒绝没放大过的盲点；" +
            "发消息这类任务，要依次完成：打开应用、找到联系人、点开会话、输入消息、点发送，发送完成才能 done；" +
            "绝不输入密码、验证码；绝不点付款、转账、删除类按钮，除非任务原文明确要求。"
}
