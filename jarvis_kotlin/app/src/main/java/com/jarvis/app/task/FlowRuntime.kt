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
 * - 第五批·失败即停（Minitap“顺序执行失败即停”）：某步失败就中止整个流，
 *   不跑剩余步骤，结果里写明停在哪一步、为什么、跳过了几步，让模型下一轮
 *   据此纠正，而不是以为流程跑完了。失败分三类：
 *   (a) 已知失败（执行回 ok=false 且结果明确）：缺省停；只有该步显式写了
 *       onError=continue 才继续——继续的前提是“知道这一步没成、界面状态可信”；
 *   (b) 结果未知（变异动作超时、不知道成没成）：不管 onError 写没写 continue，
 *       一律停——这一步可能动了也可能没动，后面步骤的前提（界面状态）已不可信，
 *       对着未知状态继续跑就是“失败静默向后传”；
 *   (c) 守卫跳过（ifPresent 未命中 / ifAbsent 已命中 / 读界面失败跳过）：不是
 *       失败，流继续往下跑，trace 里记 skipped，整体 ok 不受影响。
 *   失败的定义复用现有结果语义，不另起一套；流中止在 TaskLoop 侧只记一步
 *   失败（复用 consecutiveFailures），不按流内步数重复计。
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
     * durationMs、outcomeUnknown；observe 步另带 excerpt）/
     * aborted / failedStep / skippedSteps / abortReason（第五批·失败即停：
     * aborted=没跑完所有步就停下；failedStep=第一个失败步的下标，没有失败步
     * 时 -1；skippedSteps=一次都没跑的步数；abortReason=停下的原因一句话，
     * 只在 aborted 时有。TaskLoop.summarizeFlow 按这四个字段写摘要）。
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
        // L8：timeoutMs<=0（含显式传 0）用默认值，不钳成 1ms 立即超时。
        val rawTimeoutMs = flow.optLong("timeoutMs", DEFAULT_FLOW_TIMEOUT_MS)
        val timeoutMs = if (rawTimeoutMs <= 0) DEFAULT_FLOW_TIMEOUT_MS
        else rawTimeoutMs.coerceIn(1, MAX_FLOW_TIMEOUT_MS)
        val deadline = started + timeoutMs
        val traces = JSONArray()
        var ok = true
        var timedOut = false
        var outcomeUnknown = false
        // 第五批·失败即停：aborted=没跑完所有步就停下；failedStep=第一个失败步的
        // 下标（没有失败步时 -1）；abortReason=停下的原因（一句话，给模型看）。
        var aborted = false
        var failedStep = -1
        var abortReason = ""
        for (i in 0 until stepsJson.length()) {
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                ok = false
                aborted = true
                abortReason = "超时：做到第 ${i + 1} 步前时限已到"
                break
            }
            val step = stepsJson.optJSONObject(i)
            val trace = if (step == null) {
                stepTrace(i, "", "error", "这一步不是 JSON 对象", 0, false)
            } else {
                runStep(ctx, controller, step, i, deadline)
            }
            traces.put(trace)
            val stepUnknown = trace.optBoolean("outcomeUnknown", false)
            if (stepUnknown) outcomeUnknown = true
            if (trace.optString("status") == "error") {
                ok = false
                if (failedStep < 0) failedStep = i
                // 第五批·失败即停（定义见文件头注释）：
                // (a) 已知失败：缺省停；只有显式 onError=continue 才继续——
                //     继续的前提是“知道这一步没成、界面状态可信”；
                // (b) 结果未知：不管 onError 写没写 continue，一律停——这一步
                //     可能动了也可能没动，后面步骤的前提（界面状态）已不可信，
                //     对着未知状态继续跑就是“失败静默向后传”。
                // 守卫跳过（status=skipped）不是失败，不进这个分支、继续跑。
                val knownFailure = !stepUnknown
                if (!knownFailure || step?.optString("onError") != "continue") {
                    aborted = true
                    // abortReason 只写原因本身，不重复步号——步号由调用方
                    // （summarizeFlow 的“第 X 步失败”/审计的“失败步 X”）另行标出。
                    abortReason = if (!knownFailure) {
                        "结果未知：${trace.optString("error").ifEmpty { "不知道成没成" }}"
                    } else {
                        trace.optString("error").ifEmpty { "失败原因未知" }
                    }
                    break
                }
            } else if (stepUnknown) {
                // 第五批·结果未知也停：这一步报 ok 但效果没来得及验证（目前只在
                // 跑完才发现已过时限时出现，见 runStep），流整体不能算 ok——
                // “不知道成没成”绝不当成功，后面步骤也不对着未验证的状态跑。
                // 失败计数由 TaskLoop 侧按 outcomeUnknown 免计（见 TaskLoop），
                // 这里只负责停下并如实上报。
                ok = false
                aborted = true
                abortReason = "第 ${i + 1} 步结果未知：不知道成没成"
                break
            }
            if (System.currentTimeMillis() >= deadline) {
                timedOut = true
                ok = false
                // 只剩最后一步已跑完时不算中途停下（aborted 保持 false），只是收尾超时；
                // 还有步没跑时才是中途停下。
                if (i + 1 < stepsJson.length()) {
                    aborted = true
                    abortReason = "超时：第 ${i + 1} 步跑完时限已到，剩余步骤没跑"
                }
                break
            }
        }
        val elapsed = System.currentTimeMillis() - started
        // 纯超时中止时没有失败步（failedStep=-1），审计里不写“失败步 0”。
        val abortNote = if (aborted) {
            val failedNote = if (failedStep >= 0) "失败步 ${failedStep + 1}，" else ""
            "，中途停下（${failedNote}跳过 ${stepsJson.length() - traces.length()} 步）：$abortReason"
        } else ""
        AuditLog.append(
            ctx, "任务循环", "本地流",
            "完成 ${traces.length()}/${stepsJson.length()} 步，耗时 ${elapsed}ms，" +
                "ok=$ok，timedOut=$timedOut，outcomeUnknown=$outcomeUnknown$abortNote"
        )
        return flowResult(
            ok = ok, timedOut = timedOut, outcomeUnknown = outcomeUnknown,
            started = started, totalSteps = stepsJson.length(), traces = traces,
            aborted = aborted, failedStep = failedStep,
            skippedSteps = stepsJson.length() - traces.length(),
            abortReason = abortReason
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
                    // L8：不只报第一个，列出前 3 个匹配，让模型能从中挑。
                    val matches = r.optJSONArray("matches")
                    val shown = (0 until minOf(3, matches?.length() ?: 0))
                        .mapNotNull { matches?.optJSONObject(it) }
                    val note = if (shown.isNotEmpty()) {
                        val list = shown.mapIndexed { idx, m ->
                            "${idx + 1}) ${m.optString("sid")}|${m.optString("type")}|${m.optString("text")}"
                        }.joinToString("；")
                        "找到 $total 个，前 ${shown.size} 个是：$list"
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
        // H6：流内步的 "handover":true（如点开分享面板/外部链接后，在另一应用
        // 上的那步）不用这里另处理——step 原样交给 TaskLoop.execute，那里读
        // 字段并透传给 requireForeground；这里只负责把步 JSON 原样递过去。
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
        started: Long, totalSteps: Int, traces: JSONArray, error: String = "",
        aborted: Boolean = false, failedStep: Int = -1, skippedSteps: Int = 0,
        abortReason: String = ""
    ): JSONObject {
        return JSONObject().apply {
            put("ok", ok)
            put("timedOut", timedOut)
            put("outcomeUnknown", outcomeUnknown)
            put("elapsedMs", System.currentTimeMillis() - started)
            put("totalSteps", totalSteps)
            put("completedSteps", traces.length())
            put("steps", traces)
            // 第五批·失败即停：aborted=没跑完所有步就停下；failedStep=第一个失败步
            // 的下标（没有失败步时 -1）；skippedSteps=一次都没跑的步数；
            // abortReason=停下的原因（一句话，只在 aborted 时有）。
            // 结构不合规的早退路（steps 不是 1–15 项数组）不走中止语义：
            // 流根本没启动，aborted=false、skippedSteps=0，原因在 error 里。
            put("aborted", aborted)
            put("failedStep", failedStep)
            put("skippedSteps", skippedSteps)
            if (abortReason.isNotEmpty()) put("abortReason", abortReason)
            if (error.isNotEmpty()) put("error", error)
        }
    }
}
