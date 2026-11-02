package com.jarvis.app.task

import com.jarvis.app.screen.ScreenController
import org.json.JSONObject

/**
 * 界面等待原语（TaskLoop 与 FlowRuntime 共用）：等界面静止、等界面变化、滑到元素。
 *
 * 动机（NeuralBridge / mcp-android 研究结论）：跨应用任务里“点早了、界面还没加载完”
 * 是一类失败；固定等几秒是盲等，要么等不够、要么白白浪费。改成按界面哈希判断：
 * 哈希连续两次一样 = 界面静止了；哈希跟基线不一样 = 界面变了；滑两次哈希都不动 = 列表到底了。
 *
 * 思路只借了“等界面静止 / 等界面变化 / 滑到元素”这个模式，每一行都是对着本工程
 * 自己的 ScreenController 现写的 Kotlin，没有抄任何外部代码（mcp-android 无许可证，
 * 不能抄；NeuralBridge 是 Apache 2.0，但这里的等待原语是按思路重写）。
 *
 * 纪律：永远不谎报没观察到的变化——超时没等到就直说没等到；读不到树就直说读不到。
 * InterruptedException 不吞，直接往上抛（调用方按任务取消处理）。
 */
object ScreenWait {

    /** 轮询间隔。 */
    private const val POLL_MS = 300L

    /** TaskLoop 每步执行完，等界面静止的缺省上限。 */
    const val SETTLE_DEFAULT_MS = 3_000L

    /** wait_idle / wait_change 缺省等多久。 */
    const val WAIT_DEFAULT_MS = 5_000L

    /** 任何等待的上限。 */
    const val WAIT_MAX_MS = 10_000L

    /** scroll_to 缺省最多滑几次、上限。 */
    const val SCROLL_DEFAULT_MAX = 8
    const val SCROLL_MAX = 12

    /** 滑一次后等界面动的最久时间；等满还没动算这次没滑出变化。 */
    private const val SCROLL_SETTLE_MS = 1_500L

    data class SettleResult(
        val settled: Boolean,
        val changed: Boolean,
        val elapsedMs: Long,
        val finalHash: Int?,
        val readFailed: Boolean
    )

    data class ScrollResult(
        val found: Boolean,
        val endReached: Boolean,
        val swipes: Int
    )

    /** 读当前界面树的 elements 大纲；读失败返回 null（调用方按“看不见”处理）。 */
    internal fun currentElements(controller: ScreenController): String? {
        return try {
            controller.dumpUiTree().optString("elements")
        } catch (_: Exception) {
            null
        }
    }

    /** 读当前界面树的 compact 紧凑表；读失败返回 null。 */
    internal fun currentCompact(controller: ScreenController): String? {
        return try {
            controller.dumpUiTree().optString("compact")
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 选择器匹配：{"eid":"e3"} 按编号（大纲里编号后可能带 * 号可点标记，两边都去掉再比），
     * {"text":"发送"} 按该行文字字段包含。两个键都给了要同时命中。
     * （从 FlowRuntime 抽出来共用，语义不变；紧凑表也是同一行格式，稳定编号一样能比。）
     */
    internal fun matchesSelector(elements: String, selector: JSONObject): Boolean {
        val wantEid = selector.optString("eid").trim().removeSuffix("*")
        val wantText = selector.optString("text")
        if (wantEid.isEmpty() && wantText.isEmpty()) return false
        for (rawLine in elements.lines()) {
            val line = rawLine.trim()
            if (line.isEmpty()) continue
            val fields = line.split("|")
            val lineEid = fields.getOrNull(0).orEmpty().trim().removeSuffix("*")
            val lineText = fields.getOrNull(2).orEmpty()
            val eidOk = wantEid.isEmpty() || lineEid == wantEid
            val textOk = wantText.isEmpty() || lineText.contains(wantText)
            if (eidOk && textOk) return true
        }
        return false
    }

    /**
     * 当前界面的哈希：pkg + elements 大纲。elements 是整棵树的文本大纲，
     * 对变化最敏感（紧凑表只列前 40 个可操作元素，变化可能漏掉）；大纲空白时
     * 退到 compact。读失败返回 null。
     */
    fun screenHash(controller: ScreenController): Int? {
        return try {
            val dump = controller.dumpUiTree()
            val body = dump.optString("elements").ifEmpty { dump.optString("compact") }
            if (body.isEmpty() && dump.optString("pkg").isEmpty()) null
            else (dump.optString("pkg") + "\n" + body).hashCode()
        } catch (_: Exception) {
            null
        }
    }

    /**
     * 等界面静止（mode="idle"：连续两次读到的哈希相同）或等界面变化（mode="change"：
     * 哈希跟调用时的基线不同）。每 300ms 读一次。
     * - idle：返回时 settled 表示连续两次哈希相同（界面静止）；超时没稳住 settled=false。
     * - change：返回时 changed 表示哈希跟基线不同（界面变了）；超时没变 changed=false。
     * 超时没等到就直说没等到，绝不谎报。整程一次都没读到树，readFailed=true。
     */
    fun waitForSettle(controller: ScreenController, timeoutMs: Long, mode: String): SettleResult {
        val budget = timeoutMs.coerceIn(300L, WAIT_MAX_MS)
        val started = System.currentTimeMillis()
        fun elapsed() = System.currentTimeMillis() - started
        var baseline = screenHash(controller)
        var everRead = baseline != null
        if (baseline == null) {
            // 基线读不到：按固定时长等，期间每轮再试读；读到了就按正常流程走。
            while (elapsed() < budget) {
                Thread.sleep(POLL_MS)
                val h = screenHash(controller)
                if (h != null) {
                    baseline = h
                    everRead = true
                    break
                }
            }
            if (baseline == null) {
                return SettleResult(
                    settled = false, changed = false,
                    elapsedMs = elapsed(), finalHash = null, readFailed = true
                )
            }
        }
        var prev = baseline!!
        var last: Int? = prev
        while (elapsed() < budget) {
            Thread.sleep(POLL_MS)
            val now = screenHash(controller)
            if (now == null) continue // 这一轮读失败，不算变化也不算稳定，继续等
            everRead = true
            last = now
            if (mode == "change") {
                if (now != baseline) {
                    return SettleResult(
                        settled = false, changed = true,
                        elapsedMs = elapsed(), finalHash = now, readFailed = false
                    )
                }
            } else {
                // idle：连续两次哈希相同 = 界面静止。
                if (now == prev) {
                    return SettleResult(
                        settled = true, changed = now != baseline,
                        elapsedMs = elapsed(), finalHash = now, readFailed = false
                    )
                }
            }
            prev = now
        }
        return SettleResult(
            settled = false, changed = false,
            elapsedMs = elapsed(), finalHash = last, readFailed = !everRead
        )
    }

    /**
     * 在当前列表里滑动找目标元素（text 按包含、eid 精确，两个都给要同时命中；
     * eid 可以是紧凑表的稳定编号，也可以是当轮的 e 数字编号——两张表都查）。
     * 找到就停；连续两次滑动哈希都不动，列表到底了，停下来报 endReached；
     * 滑满 maxSwipes 还没找到，报 notFound。绝不盲滑过头。
     * direction "down"（缺省）从 500,700 滑到 500,300，"up" 反向。
     */
    fun scrollTo(
        controller: ScreenController,
        text: String?, eid: String?,
        direction: String, maxSwipes: Int
    ): ScrollResult {
        val max = maxSwipes.coerceIn(1, SCROLL_MAX)
        val up = direction == "up"
        val selector = JSONObject().apply {
            if (!eid.isNullOrEmpty()) put("eid", eid)
            if (!text.isNullOrEmpty()) put("text", text)
        }
        fun present(): Boolean {
            val elements = currentElements(controller).orEmpty()
            if (matchesSelector(elements, selector)) return true
            val compact = currentCompact(controller).orEmpty()
            return compact.isNotEmpty() && matchesSelector(compact, selector)
        }
        var swipes = 0
        var unchangedStreak = 0
        var prevHash = screenHash(controller)
        while (true) {
            if (present()) {
                return ScrollResult(found = true, endReached = false, swipes = swipes)
            }
            if (swipes >= max) break
            if (up) {
                controller.swipeNorm(500, 300, 500, 700, durationMs = 350, fling = false)
            } else {
                controller.swipeNorm(500, 700, 500, 300, durationMs = 350, fling = false)
            }
            swipes++
            // 滑完等一拍，看哈希动没动；等满 1.5 秒还没动，算这次没滑出变化。
            val before = prevHash
            var changed = false
            val waitEnd = System.currentTimeMillis() + SCROLL_SETTLE_MS
            while (System.currentTimeMillis() < waitEnd) {
                Thread.sleep(POLL_MS)
                val h = screenHash(controller)
                if (h != null && h != before) {
                    changed = true
                    break
                }
            }
            prevHash = screenHash(controller) ?: prevHash
            if (!changed) {
                unchangedStreak++
                if (unchangedStreak >= 2) {
                    return ScrollResult(found = false, endReached = true, swipes = swipes)
                }
            } else {
                unchangedStreak = 0
            }
        }
        return ScrollResult(found = false, endReached = false, swipes = swipes)
    }
}
