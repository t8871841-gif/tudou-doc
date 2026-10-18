package com.example.jarvis

import android.accessibilityservice.AccessibilityService
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import org.json.JSONArray
import org.json.JSONObject

/**
 * Jarvis 跨应用自动化引擎。
 *
 * 两种工作模式：
 *  A. 旧模式 [arm]：武装后窗口内只点“发送”（保留，兼容旧调用）；
 *  B. 任务模式 [startTask]：由 Dart 侧“每 App 一个适配器”下发一串声明式步骤
 *     （点击 / 填字 / 等待 / 点发送 / 核实），本引擎逐步执行，每步都等界面就绪，
 *     超时即失败退出，并把“卡在哪一步”原样回报。
 *
 * 安全设计（硬性，Dart 侧传什么都突破不了）：
 *  1. 平时不做任何事：只有任务/武装窗口内才扫描界面，结束或超时立即解除；
 *  2. 白名单：只在 [HARD_WHITELIST]（+ 系统默认短信 App，由 MainActivity 补充）里工作，
 *     Dart 传来的包名只能取交集，不能扩大；绝不包含支付、银行、系统设置；
 *  3. 只做消息发送类操作：点击前对控件文字做黑名单检查（转账/红包/付款/密码/验证码/
 *     删除/朋友圈/关注/点赞/评论…），命中即拒绝并终止任务；不碰密码输入框；不按坐标乱点；
 *  4. “发送”只点一次：点过之后只做只读核实，不再点任何东西；
 *  5. 核实只比对“Jarvis 自己刚发的那句话”是否出现，不读取、不保存、不回传
 *     用户在其他 App 里的任何其他聊天内容。
 */
class JarvisAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "JarvisA11y"
        const val DEFAULT_WINDOW_MS = 20_000L
        private const val SCAN_INTERVAL_MS = 250L // 窗口内容事件很密集，节流
        private const val MAX_NODES = 800 // 单次遍历的节点上限，防止卡顿
        private const val TASK_TICK_MS = 300L

        /** 任务模式的硬白名单（不含支付/银行/系统设置）。默认短信 App 由 MainActivity 动态补充。 */
        val HARD_WHITELIST: Set<String> = setOf(
            "com.tencent.mm", // 微信
            "com.tencent.mobileqq", // QQ
            "com.whatsapp",
            "com.whatsapp.w4b",
            // 用户常用 App（2026-10-18 要求扩白名单）：
            // 支付宝只许看屏点输；转账/支付/红包/密码等点击黑名单（FORBIDDEN_CLICK）原样拦截。
            "com.eg.android.AlipayGphone", // 支付宝
            "com.autonavi.minimap", // 高德地图
            "com.ss.android.ugc.aweme.lite", // 抖音极速版
            "com.meizu.weather", // 魅族自带天气
            "com.meizu.flyme.weather", // 魅族天气（候选包名，未装则不命中）
            "com.salt.music", // Salt Player（音乐）
            "com.meizu.flyme.dialer", // 电话（魅族）
            "com.android.dialer", // 电话（AOSP）
            "com.meizu.flyme.mms", // 短信（魅族；另有动态默认短信 App 补充）
            "com.android.mms" // 短信（AOSP）
        )

        /** 点击黑名单：这些字眼出现在被点控件上，一律拒绝。 */
        private val FORBIDDEN_CLICK = Regex(
            "转账|汇款|红包|付款|支付|收款|充值|提现|密码|验证码|删除|朋友圈|动态|关注|点赞|评论|退出登录|注销|解除绑定"
        )
        private val FORBIDDEN_INPUT = Regex("密码|验证码|password|passcode", RegexOption.IGNORE_CASE)

        @Volatile private var instance: JarvisAccessibilityService? = null
        @Volatile private var armedUntil = 0L
        @Volatile private var allowedPackages: Set<String> = emptySet()
        @Volatile private var state = "idle" // idle | armed | clicked | timeout

        // ---- 任务结果（跨 Activity 重建也不丢）----
        @Volatile private var lastResult: Map<String, Any?>? = null
        @Volatile private var awaiter: ((Map<String, Any?>) -> Unit)? = null

        // 按钮文字：整词匹配，避免把“发送人”“我发送了文件”之类的文字当成按钮
        private val SEND_TEXT = Regex("^(发送|发 送|send)$", RegexOption.IGNORE_CASE)
        // contentDescription：图标按钮常见写法，如“发送”“发送短信”“Send message”
        private val SEND_DESC = Regex("发送|send", RegexOption.IGNORE_CASE)
        // viewId：如 com.whatsapp:id/send、send_message_button_icon、btn_send
        private val SEND_ID =
            Regex("(/send$|send\\w*(btn|button)|(btn|button)\\w*send)", RegexOption.IGNORE_CASE)

        /** 武装（旧模式）：[windowMs] 毫秒内，只在 [packages] 里自动点“发送”。 */
        fun arm(packages: Set<String>, windowMs: Long = DEFAULT_WINDOW_MS) {
            allowedPackages = packages
            armedUntil = SystemClock.elapsedRealtime() + windowMs
            state = "armed"
            instance?.requestScan() // 目标界面可能已经在前台
        }

        fun disarm() {
            armedUntil = 0L
            if (state == "armed") state = "idle"
        }

        fun isArmed(): Boolean = SystemClock.elapsedRealtime() < armedUntil

        /** idle / armed / clicked / timeout，供 Flutter 查询上一次结果。 */
        fun status(): String {
            if (state == "armed" && !isArmed()) state = "timeout"
            return state
        }

        // ---------------- 任务模式入口 ----------------

        /**
         * 开始一个任务。必须在拉起目标 App 之前调用。
         * 返回 "ok" / "busy" / "no_service" / "bad_steps" / "bad_package"。
         */
        fun startTask(
            packages: Set<String>,
            steps: List<Map<String, Any?>>,
            windowMs: Long
        ): String {
            val svc = instance ?: return "no_service"
            val pkgs = packages.intersect(HARD_WHITELIST + svc.extraWhitelist)
            if (pkgs.isEmpty()) return "bad_package"
            val parsed = parseSteps(steps) ?: return "bad_steps"
            if (parsed.isEmpty()) return "bad_steps"
            return svc.beginTask(pkgs, parsed, windowMs)
        }

        /** MainActivity 在启动任务前补充“系统默认短信 App”包名（只增加短信类，其余仍受硬白名单约束）。 */
        fun setExtraWhitelist(pkgs: Set<String>) {
            instance?.extraWhitelist = pkgs
        }

        fun cancelTask() {
            instance?.finishTask("cancelled", "已取消")
        }

        /** 等待当前任务结束；任务已结束则立即回调上一次结果。回调在主线程。 */
        fun awaitTask(cb: (Map<String, Any?>) -> Unit) {
            val done = lastResult
            if (instance?.task == null && done != null) {
                cb(done)
                return
            }
            if (instance?.task == null) {
                cb(mapOf("status" to "idle", "step" to -1, "stepName" to "", "detail" to "没有任务"))
                return
            }
            awaiter = cb
        }

        // ---------------- Agent 循环：看屏导出 + 通用动作（新增，任务引擎原样保留） ----------------
        // 注意：这两条不经过任务模式的武装窗口（Agent 循环按步拉取，无法预先武装），
        // 安全边界与任务引擎一致：白名单（HARD_WHITELIST + 默认短信 App）+
        // 点击黑名单（FORBIDDEN_CLICK）+ 不碰密码/验证码输入（FORBIDDEN_INPUT）+ 不按坐标点。
        // 看屏导出仅供当次决策，不保存、不回传聊天内容。

        /** 导出当前屏幕节点树 JSON。结构化错误（Dart 侧分开报错，不混成一句）：
         *  服务未开启 → {"error":"service_off"}；
         *  前台不在白名单 → {"error":"not_whitelisted","pkg":"..."}；
         *  无根节点 → "{}"。 */
        fun perceiveScreenTree(): String {
            val svc = instance ?: return """{"error":"service_off"}"""
            return svc.dumpScreenTree()
        }

        /** 通用动作：click（点文字）/ input（向可编辑框填字）/ back（返回）。 */
        fun performAgentAction(
            actionType: String,
            targetText: String,
            inputText: String?
        ): Boolean {
            val svc = instance ?: return false
            return svc.runAgentAction(actionType, targetText, inputText)
        }

        // ---------------- 步骤解析 ----------------

        private val ACTIONS = setOf("click", "setText", "wait", "send", "verifyText")

        @Suppress("UNCHECKED_CAST")
        private fun parseSteps(raw: List<Map<String, Any?>>): List<Step>? {
            return try {
                raw.map { m ->
                    val action = m["action"] as? String ?: return null
                    if (action !in ACTIONS) return null
                    val matchers = ((m["matches"] as? List<Map<String, Any?>>) ?: emptyList()).map { mm ->
                        Matcher(
                            textEquals = mm["textEquals"] as? String,
                            textRegex = (mm["textRegex"] as? String)?.let { Regex(it) },
                            descRegex = (mm["descRegex"] as? String)?.let { Regex(it) },
                            idRegex = (mm["idRegex"] as? String)?.let { Regex(it) },
                            classContains = mm["classContains"] as? String,
                            editable = mm["editable"] as? Boolean,
                            topOnly = (mm["topOnly"] as? Boolean) ?: false
                        )
                    }
                    // click / setText / wait 必须带选择器；send 用内置“发送”识别；verifyText 用 text
                    if ((action == "click" || action == "setText" || action == "wait") && matchers.isEmpty()) {
                        return null
                    }
                    val text = m["text"] as? String
                    if ((action == "setText" || action == "verifyText") && text.isNullOrEmpty()) return null
                    Step(
                        name = (m["name"] as? String) ?: action,
                        action = action,
                        matchers = matchers,
                        text = text,
                        timeoutMs = ((m["timeoutMs"] as? Number)?.toLong() ?: 6_000L).coerceIn(1_000L, 30_000L),
                        optional = (m["optional"] as? Boolean) ?: false,
                        soft = (m["soft"] as? Boolean) ?: false,
                        settleMs = ((m["settleMs"] as? Number)?.toLong() ?: 500L).coerceIn(0L, 3_000L),
                        backAfterMs = ((m["backAfterMs"] as? Number)?.toLong() ?: 2_500L).coerceIn(1_000L, 10_000L),
                        maxBack = ((m["maxBack"] as? Number)?.toInt() ?: 0).coerceIn(0, 3)
                    )
                }
            } catch (e: Exception) {
                Log.w(TAG, "bad steps", e)
                null
            }
        }
    }

    // ---------------- 数据结构 ----------------

    internal class Matcher(
        val textEquals: String?,
        val textRegex: Regex?,
        val descRegex: Regex?,
        val idRegex: Regex?,
        val classContains: String?,
        val editable: Boolean?,
        val topOnly: Boolean
    )

    internal class Step(
        val name: String,
        val action: String,
        val matchers: List<Matcher>,
        val text: String?,
        val timeoutMs: Long,
        val optional: Boolean,
        val soft: Boolean, // verifyText 超时时报 unverified 而不是 failed
        val settleMs: Long,
        val backAfterMs: Long,
        val maxBack: Int
    )

    internal class Task(val pkgs: Set<String>, val steps: List<Step>, val deadline: Long) {
        var idx = 0
        var stepStart = SystemClock.elapsedRealtime()
        var settleUntil = 0L
        var backs = 0
        var lastBack = 0L
        var textAttempts = 0
        var sendClicked = false
        var baseCount = 0 // 点发送前，界面上已有几条同文本的气泡（防止把旧消息当成新发的）
    }

    private val handler = Handler(Looper.getMainLooper())
    private var lastScan = 0L
    private var rescanPending = false
    private var task: Task? = null
    private var extraWhitelist: Set<String> = emptySet()
    private val tickRunnable = Runnable { tickTask() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        finishTask("cancelled", "无障碍服务已断开")
        instance = null
        disarm()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    override fun onInterrupt() {}

    // ---------------- 旧模式：事件驱动，只点“发送” ----------------

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null || task != null || !isArmed()) return
        val type = event.eventType
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            type != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        ) return
        val pkg = event.packageName?.toString() ?: return
        if (pkg !in allowedPackages) return
        requestScan()
    }

    /** 节流：窗口内容事件很多，过密的请求合并成一次稍后的重扫，避免漏掉最后一次变化。 */
    private fun requestScan() {
        val now = SystemClock.elapsedRealtime()
        if (now - lastScan >= SCAN_INTERVAL_MS) {
            scanOnce()
        } else if (!rescanPending) {
            rescanPending = true
            handler.postDelayed({
                rescanPending = false
                if (isArmed()) scanOnce()
            }, SCAN_INTERVAL_MS)
        }
    }

    private fun scanOnce() {
        lastScan = SystemClock.elapsedRealtime()
        if (!isArmed() || task != null) return
        val root = rootInActiveWindow ?: return
        try {
            val pkg = root.packageName?.toString() ?: return
            if (pkg !in allowedPackages) return
            if (findAndClick(root)) {
                state = "clicked"
                armedUntil = 0L
                Log.d(TAG, "clicked send in $pkg")
            }
        } catch (e: Exception) {
            Log.w(TAG, "scan failed", e)
        } finally {
            recycleCompat(root)
        }
    }

    /** 广度优先找“发送”按钮并点击；所有中途取得的节点都在 finally 里统一回收。 */
    private fun findAndClick(root: AccessibilityNodeInfo): Boolean {
        val obtained = ArrayList<AccessibilityNodeInfo>()
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (isSendCandidate(node) && clickNodeOrParent(node)) return true
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    obtained.add(child)
                    queue.add(child)
                }
            }
            return false
        } finally {
            obtained.forEach { recycleCompat(it) }
        }
    }

    // ---------------- 任务模式 ----------------

    private fun beginTask(pkgs: Set<String>, steps: List<Step>, windowMs: Long): String {
        if (task != null) return "busy"
        val ms = windowMs.coerceIn(5_000L, 90_000L)
        lastResult = null
        awaiter = null
        task = Task(pkgs, steps, SystemClock.elapsedRealtime() + ms)
        // 任务窗口 = 武装窗口：到点自动解除
        armedUntil = SystemClock.elapsedRealtime() + ms
        state = "armed"
        scheduleTick(TASK_TICK_MS)
        return "ok"
    }

    private fun scheduleTick(delay: Long) {
        handler.removeCallbacks(tickRunnable)
        if (task != null) handler.postDelayed(tickRunnable, delay)
    }

    private fun finishTask(status: String, detail: String) {
        val t = task ?: return
        val stepName = t.steps.getOrNull(t.idx)?.name ?: ""
        val result = mapOf<String, Any?>(
            "status" to status, // sent | unverified | failed | blocked | timeout | cancelled
            "step" to t.idx,
            "stepName" to stepName,
            "detail" to detail
        )
        task = null
        handler.removeCallbacks(tickRunnable)
        armedUntil = 0L // 任务结束立即解除武装
        state = if (status == "sent" || status == "unverified") "clicked" else "idle"
        lastResult = result
        val cb = awaiter
        awaiter = null
        cb?.invoke(result)
        Log.d(TAG, "task finished: $status @ $stepName ($detail)")
    }

    private fun advance(t: Task) {
        t.idx++
        t.stepStart = SystemClock.elapsedRealtime()
        t.backs = 0
        t.textAttempts = 0
        if (t.idx >= t.steps.size) {
            val last = t.steps.last()
            // 最后一步是“核实”且通过才算 sent；否则只是流程走完
            finishTask(if (last.action == "verifyText") "sent" else "done", "全部步骤完成")
        }
    }

    private fun tickTask() {
        val t = task ?: return
        val now = SystemClock.elapsedRealtime()
        if (now >= t.deadline) {
            finishTask("timeout", "整体超时")
            return
        }
        var root: AccessibilityNodeInfo? = null
        try {
            root = rootInActiveWindow
            val pkg = root?.packageName?.toString()
            // 前台不是白名单 App：什么都不做，只等（超时计时照常走）
            if (root != null && pkg != null && pkg in t.pkgs && now >= t.settleUntil) {
                val step = t.steps[t.idx]
                if (now - t.stepStart > step.timeoutMs) {
                    when {
                        step.optional -> advance(t)
                        step.soft -> finishTask("unverified", "步骤「${step.name}」没能确认")
                        else -> finishTask("failed", "步骤「${step.name}」超时")
                    }
                } else {
                    val done = runStep(root, t, step)
                    if (done && task === t) advance(t)
                }
            } else if (now - t.stepStart > t.steps[t.idx].timeoutMs + 8_000L) {
                // 长时间不在白名单 App 里：别空等到整体超时
                finishTask("failed", "目标 App 没有出现在前台")
            }
        } catch (e: Exception) {
            Log.w(TAG, "tick failed", e)
        } finally {
            recycleCompat(root)
            if (task != null) scheduleTick(TASK_TICK_MS)
        }
    }

    /** 执行一步；返回 true 表示这一步完成，可以进入下一步。 */
    private fun runStep(root: AccessibilityNodeInfo, t: Task, step: Step): Boolean {
        val rect = Rect().also { root.getBoundsInScreen(it) }
        val now = SystemClock.elapsedRealtime()
        when (step.action) {
            "wait" -> {
                return withNode(root, rect, step.matchers) { it != null }
            }

            "click" -> {
                val clicked = withNode(root, rect, step.matchers) { node ->
                    if (node == null) false
                    else when (clickGuarded(node)) {
                        ClickResult.OK -> true
                        ClickResult.BLOCKED -> {
                            finishTask("blocked", "安全限制：目标控件涉及敏感操作，已拒绝")
                            false
                        }
                        ClickResult.FAILED -> false
                    }
                }
                if (task !== t) return false
                if (clicked) {
                    t.settleUntil = now + step.settleMs
                    return true
                }
                // 找不到入口：按步骤声明有限次后退（只在白名单 App 内、最多 maxBack 次）
                if (step.maxBack > 0 && t.backs < step.maxBack &&
                    now - maxOf(t.stepStart, t.lastBack) >= step.backAfterMs
                ) {
                    t.backs++
                    t.lastBack = now
                    performGlobalAction(GLOBAL_ACTION_BACK)
                }
                return false
            }

            "setText" -> {
                val target = step.text ?: return false
                // 先看是不是已经填好了
                val filled = withNode(root, rect, step.matchers) { node ->
                    node != null && node.text?.toString() == target
                }
                if (filled) {
                    t.settleUntil = now + step.settleMs
                    return true
                }
                if (t.textAttempts >= 4) return false // 交给步骤超时去判失败
                withNode(root, rect, step.matchers) { node ->
                    if (node != null) {
                        val hint = node.contentDescription?.toString().orEmpty() +
                            node.viewIdResourceName.orEmpty()
                        if (node.isPassword || FORBIDDEN_INPUT.containsMatchIn(hint)) {
                            finishTask("blocked", "安全限制：不向密码/验证码输入框填内容")
                        } else {
                            t.textAttempts++
                            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                            val args = Bundle().apply {
                                putCharSequence(
                                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                    target
                                )
                            }
                            node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                        }
                    }
                    Unit
                }
                return false // 下一个 tick 再确认文字是否真的进去了
            }

            "send" -> {
                if (t.sendClicked) return true
                // 点之前先数一下“同文本气泡”已有几条（核实时必须比它多）
                val next = t.steps.getOrNull(t.idx + 1)
                if (next != null && next.action == "verifyText" && next.text != null) {
                    t.baseCount = countPlainText(root, next.text)
                }
                val ok = findAndClick(root)
                if (ok) {
                    t.sendClicked = true // 只点一次，此后只读
                    t.settleUntil = now + step.settleMs
                }
                return ok
            }

            "verifyText" -> {
                if (!t.sendClicked && t.steps.any { it.action == "send" }) return false
                val target = step.text ?: return false
                val n = countPlainText(root, target)
                return n > t.baseCount || (n >= 1 && t.baseCount == 0)
            }
        }
        return false
    }

    // ---------------- 节点查找 ----------------

    private fun <T> withNode(
        root: AccessibilityNodeInfo,
        rect: Rect,
        matchers: List<Matcher>,
        block: (AccessibilityNodeInfo?) -> T
    ): T {
        val obtained = ArrayList<AccessibilityNodeInfo>()
        try {
            for (m in matchers) {
                val node = bfs(root, obtained) { matches(it, m, rect) }
                if (node != null) return block(node)
            }
            return block(null)
        } finally {
            obtained.forEach { recycleCompat(it) }
        }
    }

    private fun bfs(
        root: AccessibilityNodeInfo,
        obtained: MutableList<AccessibilityNodeInfo>,
        pred: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var visited = 0
        while (queue.isNotEmpty() && visited < MAX_NODES) {
            val node = queue.removeFirst()
            visited++
            if (pred(node)) return node
            for (i in 0 until node.childCount) {
                val child = node.getChild(i) ?: continue
                obtained.add(child)
                queue.add(child)
            }
        }
        return null
    }

    private fun isEditNode(node: AccessibilityNodeInfo): Boolean =
        node.isEditable || (node.className?.toString().orEmpty().contains("EditText"))

    private fun matches(node: AccessibilityNodeInfo, m: Matcher, rootRect: Rect): Boolean {
        if (!node.isVisibleToUser) return false
        m.editable?.let { if (isEditNode(node) != it) return false }
        m.classContains?.let { if (!(node.className?.toString().orEmpty().contains(it))) return false }
        val text = node.text?.toString()?.trim().orEmpty()
        m.textEquals?.let { if (text != it.trim()) return false }
        m.textRegex?.let { if (text.length > 80 || !it.matches(text)) return false }
        m.descRegex?.let {
            val d = node.contentDescription?.toString()?.trim().orEmpty()
            if (d.isEmpty() || d.length > 40 || !it.matches(d)) return false
        }
        m.idRegex?.let {
            val id = node.viewIdResourceName.orEmpty()
            if (id.isEmpty() || !it.containsMatchIn(id)) return false
        }
        if (m.topOnly) {
            val r = Rect().also { node.getBoundsInScreen(it) }
            if (r.bottom > rootRect.top + rootRect.height() * 0.2f) return false
        }
        // 一个条件都没写的选择器不允许匹配任何东西
        return m.textEquals != null || m.textRegex != null || m.descRegex != null ||
            m.idRegex != null || m.classContains != null || m.editable != null
    }

    /** 数“非输入框、文字恰好等于 [target]”的节点个数；只比对，不保存、不回传任何文字。 */
    private fun countPlainText(root: AccessibilityNodeInfo, target: String): Int {
        val obtained = ArrayList<AccessibilityNodeInfo>()
        try {
            var count = 0
            val want = target.trim()
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (!isEditNode(node) && node.text?.toString()?.trim() == want) count++
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    obtained.add(child)
                    queue.add(child)
                }
            }
            return count
        } finally {
            obtained.forEach { recycleCompat(it) }
        }
    }

    // ---------------- 点击 ----------------

    private enum class ClickResult { OK, BLOCKED, FAILED }

    private fun isForbiddenNode(node: AccessibilityNodeInfo): Boolean {
        val s = (node.text?.toString().orEmpty() + " " + node.contentDescription?.toString().orEmpty())
        return FORBIDDEN_CLICK.containsMatchIn(s)
    }

    /** 带黑名单的点击：被点的节点（含向上找到的可点击父节点）命中黑名单就拒绝。 */
    private fun clickGuarded(node: AccessibilityNodeInfo): ClickResult {
        if (isForbiddenNode(node)) return ClickResult.BLOCKED
        if (node.isClickable) {
            return if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ClickResult.OK
            else ClickResult.FAILED
        }
        val climbed = ArrayList<AccessibilityNodeInfo>()
        try {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) { // 最多往上 4 层，别点到整行/整页
                climbed.add(parent)
                if (parent.isClickable) {
                    if (isForbiddenNode(parent)) return ClickResult.BLOCKED
                    return if (parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ClickResult.OK
                    else ClickResult.FAILED
                }
                parent = parent.parent
                depth++
            }
            return ClickResult.FAILED
        } finally {
            climbed.forEach { recycleCompat(it) }
        }
    }

    private fun isSendCandidate(node: AccessibilityNodeInfo): Boolean {
        if (!node.isEnabled || !node.isVisibleToUser) return false // 没填内容时按钮是灰的，等下次事件
        if (node.isEditable) return false
        val cls = node.className?.toString().orEmpty()
        if (cls.contains("EditText")) return false

        val text = node.text?.toString()?.trim().orEmpty()
        if (text.length <= 6 && SEND_TEXT.matches(text)) return true

        val desc = node.contentDescription?.toString()?.trim().orEmpty()
        if (desc.length in 1..12 && SEND_DESC.containsMatchIn(desc)) return true

        // 需要 accessibility_service_config 里的 flagReportViewIds
        val id = node.viewIdResourceName.orEmpty()
        return id.isNotEmpty() && SEND_ID.containsMatchIn(id)
    }

    /** 点击节点本身，否则向上找可点击的父节点（旧模式用，只会点“发送”候选）。 */
    private fun clickNodeOrParent(node: AccessibilityNodeInfo): Boolean {
        if (node.isClickable) {
            return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
        val climbed = ArrayList<AccessibilityNodeInfo>()
        try {
            var parent = node.parent
            var depth = 0
            while (parent != null && depth < 4) { // 最多往上 4 层，别点到整行/整页
                climbed.add(parent)
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
                depth++
            }
            return false
        } finally {
            climbed.forEach { recycleCompat(it) }
        }
    }

    /** API 33 起 recycle() 是空操作并已废弃；旧系统仍需要回收。 */
    @Suppress("DEPRECATION")
    private fun recycleCompat(node: AccessibilityNodeInfo?) {
        if (node == null || Build.VERSION.SDK_INT >= 33) return
        try {
            node.recycle()
        } catch (_: Exception) {
        }
    }

    // ---------------- Agent 循环：看屏导出 + 通用动作 ----------------

    /** 导出当前屏幕节点树 JSON。白名单外 App → 结构化错误（带当前包名）；无根节点 → "{}"。 */
    private fun dumpScreenTree(): String {
        val root = rootInActiveWindow ?: return "{}"
        try {
            val pkg = root.packageName?.toString() ?: return "{}"
            if (pkg !in HARD_WHITELIST + extraWhitelist) {
                return """{"error":"not_whitelisted","pkg":"$pkg"}"""
            }
            return dumpNode(root, 0, intArrayOf(0)).toString()
        } catch (e: Exception) {
            Log.w(TAG, "dump tree failed", e)
            return "{}"
        } finally {
            recycleCompat(root)
        }
    }

    /**
     * 递归导出节点。边界：深度 ≤20、总节点 ≤400、只导可见节点、
     * 密码框文字掩码、文字截断 80 字符（与任务引擎 matches() 的 80 字符上限一致）。
     * 本函数只读自己取到的子节点并在循环内回收；传入的 node 由调用方回收。
     */
    private fun dumpNode(
        node: AccessibilityNodeInfo,
        depth: Int,
        count: IntArray
    ): JSONObject {
        val obj = JSONObject()
        if (depth > 20 || count[0] >= 400) return obj
        count[0]++
        obj.put("class", node.className?.toString()?.substringAfterLast('.') ?: "")
        val text = node.text?.toString() ?: ""
        obj.put("text", if (node.isPassword) "[PROTECTED]" else text.take(80))
        obj.put("desc", (node.contentDescription?.toString() ?: "").take(40))
        val id = node.viewIdResourceName ?: ""
        obj.put("viewId", id.substringAfterLast('/').take(60))
        obj.put("clickable", node.isClickable)
        obj.put("editable", node.isEditable)
        val rect = Rect().also { node.getBoundsInScreen(it) }
        obj.put("bounds", JSONArray().apply {
            put(rect.left)
            put(rect.top)
            put(rect.right)
            put(rect.bottom)
        })
        val children = JSONArray()
        for (i in 0 until node.childCount) {
            if (count[0] >= 400) break
            val child = node.getChild(i) ?: continue
            try {
                if (child.isVisibleToUser) children.put(dumpNode(child, depth + 1, count))
            } finally {
                recycleCompat(child)
            }
        }
        obj.put("children", children)
        return obj
    }

    /** 通用动作（Agent 慢路径用）。白名单外 App 一律拒绝。 */
    private fun runAgentAction(
        actionType: String,
        targetText: String,
        inputText: String?
    ): Boolean {
        val root = rootInActiveWindow ?: return false
        try {
            val pkg = root.packageName?.toString() ?: return false
            if (pkg !in HARD_WHITELIST + extraWhitelist) return false
            return when (actionType) {
                "click" -> {
                    if (targetText.isEmpty()) return false
                    val node = findAgentNode(root, targetText, editableOnly = false)
                        ?: return false
                    try {
                        // 复用任务引擎的带黑名单点击（黑名单 + 向上最多爬 4 层）。
                        clickGuarded(node) == ClickResult.OK
                    } finally {
                        recycleCompat(node)
                    }
                }
                "input" -> {
                    if (targetText.isEmpty() || inputText == null) return false
                    val node = findAgentNode(root, targetText, editableOnly = true)
                        ?: return false
                    try {
                        val hint = node.contentDescription?.toString().orEmpty() +
                            node.viewIdResourceName.orEmpty()
                        // 不向密码 / 验证码输入框填内容。
                        if (node.isPassword || FORBIDDEN_INPUT.containsMatchIn(hint)) {
                            return false
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        val args = Bundle().apply {
                            putCharSequence(
                                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                                inputText
                            )
                        }
                        node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    } finally {
                        recycleCompat(node)
                    }
                }
                "back" -> performGlobalAction(GLOBAL_ACTION_BACK)
                else -> false
            }
        } catch (e: Exception) {
            Log.w(TAG, "agent action failed", e)
            return false
        } finally {
            recycleCompat(root)
        }
    }

    /**
     * BFS 找可见节点：先精确相等（text / desc），再子串。
     * 返回的节点由调用方回收；其余取到的节点在这里统一回收。
     */
    private fun findAgentNode(
        root: AccessibilityNodeInfo,
        target: String,
        editableOnly: Boolean
    ): AccessibilityNodeInfo? {
        val obtained = ArrayList<AccessibilityNodeInfo>()
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var visited = 0
            var containsHit: AccessibilityNodeInfo? = null
            while (queue.isNotEmpty() && visited < MAX_NODES) {
                val node = queue.removeFirst()
                visited++
                if (node.isVisibleToUser && (!editableOnly || isEditNode(node))) {
                    val text = node.text?.toString()?.trim().orEmpty()
                    val desc = node.contentDescription?.toString()?.trim().orEmpty()
                    if (text == target || desc == target) {
                        obtained.remove(node) // 调用方回收，不在这里重复回收
                        return node
                    }
                    if (containsHit == null &&
                        (text.contains(target) || desc.contains(target))
                    ) {
                        containsHit = node
                    }
                }
                for (i in 0 until node.childCount) {
                    val child = node.getChild(i) ?: continue
                    obtained.add(child)
                    queue.add(child)
                }
            }
            if (containsHit != null) {
                obtained.remove(containsHit)
                return containsHit
            }
            return null
        } finally {
            obtained.forEach { recycleCompat(it) }
        }
    }
}
