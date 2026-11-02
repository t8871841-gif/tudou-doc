package com.jarvis.app.task

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.screen.ScreenController
import org.json.JSONArray
import org.json.JSONObject

/**
 * 本地流：一段有界的多步脚本，手机在本地一口气跑完、中间不再问模型，
 * 跑完一次把每步的 trace 交回去，模型据此决定下一步。
 *
 * 动机（NeuralBridge / mcp-android 研究结论）：跨应用任务慢，主因是模型
 * 每步都要问一次（还总撞 429）；本地流把“点输入框→输文字→点发送”这类
 * 同一屏上的固定套路一次发下来，手机本地连跑，少问模型好几次。
 *
 * 思路只借了“有界脚本 + 每步 trace + 守卫 + outcomeUnknown”这个模式，
 * 每一行都是对着本工程自己的 ScreenController / TaskLoop 现写的 Kotlin，
 * 没有抄任何外部代码（mcp-android 无许可证，不能抄；NeuralBridge 是
 * Apache 2.0，但流这个模式出自前者，按思路重写）。
 *
 * 安全线（照抄 mcp-android 那套纪律的精神，用自己的实现）：
 * - 步数上限 15、总时限上限 20 秒，超时即停，不谎报成功；
 * - 每步可带 ifPresent / ifAbsent 守卫（元素在不在才做）与 onError
 *   （缺省失败即停，显式 continue 才继续）；
 * - 变异动作（点/长按/输入/滑动/放大）在时限内没跑完，标 outcomeUnknown，
 *   绝不把“不知道成没成”说成成功；
 * - 流里不许 openApp / submit 这类越界动作，流外单步做。
 */
object FlowRuntime {

    /** 一段流最多几步：够一次确定性操作、又不易失控（mcp-android 是 40 步，我们取 15）。 */
    const val MAX_FLOW_STEPS = 15

    /** 流的总时限上限（毫秒）；timeoutMs 缺省 20000，超过按 20000 算。 */
    const val MAX_FLOW_TIMEOUT_MS = 20_000L
    const val DEFAULT_FLOW_TIMEOUT_MS = 20_000L

    /** 流内可用的动作（tap/longPress/tap_sel/type/swipe/back/home/zoom 走 TaskLoop.execute 的
     * 既有映射；wait/wait_idle/wait_change/find/observe/scroll_to 是流内专用步，
     * 等待与滑动走共用的 ScreenWait，不在本文件另造一套）。 */
    private val FLOW_ACTIONS = setOf(
        "tap", "longPress", "tap_sel", "type", "swipe", "back", "home", "zoom",
        "wait", "wait_idle", "wait_change", "find", "observe", "scroll_to"
    )

    /** 动作开始后才可能“不知道成没成”的几种：超时要标 outcomeUnknown。 */
    private val MUTATING_ACTIONS = setOf("tap", "longPress", "tap_sel", "type", "swipe", "zoom", "scroll_to")

    /** observe 步附在 trace 里的元素摘要上限（只给一小段，不塞整棵树）。 */
    private const val OBSERVE_EXCERPT_CHARS = 300

    /**
     * 跑一段流。返回结果 JSON：
     * ok / timedOut / outcomeUnknown / elapsedMs / totalSteps / completedSteps /
     * steps（每步 trace：index、action、status ok|error|skipped、ok、error、
     * durationMs、outcomeUnknown；observe 步另带 excerpt）。
     * 顶层 steps 不合规时直接回一个带 error 的失败结果（TaskLoop.parseAction
     * 那层已经先拦过一次，这里是第二道防线）。
     */
    fun run(ctx: Context, controller: ScreenController, flow: JSONObject): JSONObject {
        val started = System.currentTimeMillis()
        val stepsJson = flow.optJSONArray("steps")
        if (stepsJson == null || stepsJson.length() == 0 || stepsJson.length() > MAX_FLOW_STEPS) {
            return flowResult(
                ok = false, timedOut = false, outcomeUnknown = false,
                started = started, totalSteps = stepsJson?.length() ?: 0,
                traces = JSONArray(), error = "流的 steps 必须是 1–$MAX_FLOW_STEPS 项的数组"
            )
        }
        val timeoutMs = flow.optLong("timeoutMs", DEFAULT_FLOW_TIMEOUT_MS)
            .coerceIn(1, MAX_FLOW_TIMEOUT_MS)
        val deadline = started + timeoutMs
        val traces = JSONArray()
        var ok = true
        var timedOut = false
        var outcomeUnknown = false
        for (i in 0 until stepsJson.length()) {
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                ok = false
                break
            }
            val step = stepsJson.optJSONObject(i)
            val trace = if (step == null) {
                stepTrace(i, "", "error", "这一步不是 JSON 对象", 0, false)
            } else {
                runStep(ctx, controller, step, i, deadline)
            }
            traces.put(trace)
            if (trace.optBoolean("outcomeUnknown", false)) outcomeUnknown = true
            if (trace.optString("status") == "error") {
                ok = false
                // 缺省失败即停；只有显式 onError=continue 才继续往下跑。
                if (step?.optString("onError") != "continue") break
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                ok = false
                break
            }
        }
        val elapsed = System.currentTimeMillis() - started
        AuditLog.append(
            ctx, "任务循环", "本地流",
            "完成 ${traces.length()}/${stepsJson.length()} 步，耗时 ${elapsed}ms，" +
                "ok=$ok，timedOut=$timedOut，outcomeUnknown=$outcomeUnknown"
        )
        return flowResult(
            ok = ok, timedOut = timedOut, outcomeUnknown = outcomeUnknown,
            started = started, totalSteps = stepsJson.length(), traces = traces
        )
    }

    /** 跑流内的一步，返回它的 trace。InterruptedException 不吞，直接往上抛、让循环停下。 */
    private fun runStep(
        ctx: Context, controller: ScreenController, step: JSONObject,
        index: Int, deadline: Long
    ): JSONObject {
        val stepStarted = System.currentTimeMillis()
        val action = step.optString("action")
        fun done(
            status: String, error: String = "", outcomeUnknown: Boolean = false,
            note: String = "", excerpt: String = ""
        ): JSONObject {
            val trace = stepTrace(
                index, action, status, error,
                System.currentTimeMillis() - stepStarted, outcomeUnknown
            )
            if (note.isNotEmpty()) trace.put("note", note)
            if (excerpt.isNotEmpty()) trace.put("excerpt", excerpt)
            return trace
        }
        if (action.isEmpty()) return done("error", "这一步缺少 action")
        if (action !in FLOW_ACTIONS) return done("error", "不支持的流内动作：$action")

        // ---------- 守卫：ifPresent / ifAbsent ----------
        // 有守卫才读一次树；读不到树就按“看不见”处理——宁可跳过，也不盲动。
        if (step.has("ifPresent") || step.has("ifAbsent")) {
            val elements = ScreenWait.currentElements(controller)
            if (elements == null) return done("skipped", note = "读界面失败，已跳过")
            val presentSel = step.optJSONObject("ifPresent")
            val absentSel = step.optJSONObject("ifAbsent")
            if (presentSel != null && !ScreenWait.matchesSelector(elements, presentSel)) {
                return done("skipped", note = "ifPresent 未命中，已跳过")
            }
            if (absentSel != null && ScreenWait.matchesSelector(elements, absentSel)) {
                return done("skipped", note = "ifAbsent 已命中，已跳过")
            }
        }

        // ---------- 流内专用步 ----------
        when (action) {
            "wait", "wait_idle", "wait_change" -> return runWaitStep(controller, step, index, stepStarted, deadline)
            "scroll_to" -> {
                val text = step.optString("text").ifEmpty { null }
                val eid = step.optString("eid").ifEmpty { null }
                if (text == null && eid == null) {
                    return done("error", "scroll_to 缺少 text 或 eid")
                }
                val direction = step.optString("direction").ifEmpty { "down" }
                val maxSwipes = step.optInt("maxSwipes", ScreenWait.SCROLL_DEFAULT_MAX)
                val target = text ?: eid!!
                val s = ScreenWait.scrollTo(controller, text, eid, direction, maxSwipes)
                return when {
                    s.found -> done("ok", note = "已找到「$target」")
                    s.endReached -> done("error", "滑到底了，没找到「$target」")
                    else -> done("error", "滑了 ${s.swipes} 次，没找到「$target」")
                }
            }
            "find" -> {
                if (step.has("sel")) {
                    val sel = step.optJSONObject("sel")
                        ?: return done("error", "find 的 sel 不是对象")
                    val r = controller.findBySelector(sel)
                    val total = r.optInt("total", 0)
                    if (total == 0) return done("error", "找不到符合条件的元素")
                    val first = r.optJSONArray("matches")?.optJSONObject(0)
                    val note = if (first != null) {
                        "找到 $total 个，第一个是 ${first.optString("sid")}|${first.optString("type")}|${first.optString("text")}"
                    } else "找到 $total 个"
                    return done("ok", note = note)
                }
                val elements = ScreenWait.currentElements(controller)
                    ?: return done("error", "读界面失败")
                val sel = JSONObject().apply {
                    if (step.has("eid")) put("eid", step.optString("eid"))
                    if (step.has("text")) put("text", step.optString("text"))
                }
                if (!step.has("eid") && !step.has("text")) {
                    return done("error", "find 缺少 text 或 eid")
                }
                return if (ScreenWait.matchesSelector(elements, sel)) {
                    done("ok", note = "元素在")
                } else {
                    done("error", "找不到元素：${step.optString("text").ifEmpty { step.optString("eid") }}")
                }
            }
            "observe" -> {
                val elements = ScreenWait.currentElements(controller)
                    ?: return done("error", "读界面失败")
                return done("ok", excerpt = elements.take(OBSERVE_EXCERPT_CHARS))
            }
        }

        // ---------- 变异动作：走 TaskLoop.execute 的既有映射 ----------
        val result = TaskLoop.execute(ctx, controller, step)
        // 动作跑完才发现已过时限：这一步的效果没来得及验证，标 outcomeUnknown，
        // 绝不把“不知道成没成”说成成功。
        val unknown = action in MUTATING_ACTIONS && System.currentTimeMillis() >= deadline
        return if (result.ok) {
            done("ok", outcomeUnknown = unknown, note = result.summary.take(120))
        } else {
            done("error", result.summary.take(120), outcomeUnknown = unknown)
        }
    }

    /**
     * wait / wait_idle / wait_change 步：走共用的 ScreenWait.waitForSettle，
     * 不在本文件另造轮询。wait 与 wait_change 都是等界面变化（没变也算完成，
     * 界面已稳定）；wait_idle 是等界面稳定（连续两次哈希相同）。
     * 等满没等到也算完成（ok），成败看 note 里那句，不谎报。
     * 读树失败的那一程按“读界面失败”记，不算变化也不算稳定。
     */
    private fun runWaitStep(
        controller: ScreenController, step: JSONObject,
        index: Int, stepStarted: Long, deadline: Long
    ): JSONObject {
        val action = step.optString("action")
        val mode = if (action == "wait_idle") "idle" else "change"
        val requested = step.optLong("ms", ScreenWait.WAIT_DEFAULT_MS)
            .coerceIn(300, MAX_FLOW_TIMEOUT_MS)
        val remaining = (deadline - System.currentTimeMillis()).coerceAtLeast(0)
        val budget = minOf(requested, remaining)
        val result = ScreenWait.waitForSettle(controller, budget, mode)
        val trace = stepTrace(
            index, action, "ok", "",
            System.currentTimeMillis() - stepStarted, false
        )
        val note = if (result.readFailed) {
            "读界面失败，按固定时长等了 ${result.elapsedMs}ms"
        } else if (mode == "idle") {
            if (result.settled) "等了 ${result.elapsedMs}ms，界面已稳定"
            else "等了 ${result.elapsedMs}ms，界面还没稳定"
        } else {
            if (result.changed) "等了 ${result.elapsedMs}ms，界面有变化"
            else "等了 ${result.elapsedMs}ms，界面没有变化（已稳定）"
        }
        trace.put("note", note)
        return trace
    }

    private fun stepTrace(
        index: Int, action: String, status: String, error: String,
        durationMs: Long, outcomeUnknown: Boolean
    ): JSONObject {
        return JSONObject().apply {
            put("index", index)
            put("action", action)
            put("status", status)
            put("ok", status != "error")
            if (error.isNotEmpty()) put("error", error)
            put("durationMs", durationMs)
            put("outcomeUnknown", outcomeUnknown)
        }
    }

    private fun flowResult(
        ok: Boolean, timedOut: Boolean, outcomeUnknown: Boolean,
        started: Long, totalSteps: Int, traces: JSONArray, error: String = ""
    ): JSONObject {
        return JSONObject().apply {
            put("ok", ok)
            put("timedOut", timedOut)
            put("outcomeUnknown", outcomeUnknown)
            put("elapsedMs", System.currentTimeMillis() - started)
            put("totalSteps", totalSteps)
            put("completedSteps", traces.length())
            put("steps", traces)
            if (error.isNotEmpty()) put("error", error)
        }
    }
}
