package com.jarvis.app.task

import android.util.Log
import com.jarvis.app.JarvisAccessibilityService

/**
 * 跨应用任务的“断点续跑”：任务被 STOP 停下（或跑满步数）后，把“做到哪一步”
 * 记下来，用户下一句说“继续”就从断点接着做，不是从头来。
 *
 * 跟 skill/SkillResumeState 是同一种“很薄的单轮状态机”设计：全应用同一时间
 * 只会有一个任务在等“继续不继续”；新任务开始（TaskLoop.run 开头）或用户明
 * 确说不要继续，待处理状态就清掉；其他句子不断点（见 S20）。状态只放内存，
 *
 * 2026-11-05 审计修（S20/M20/M21/M22，只改本文件）：
 * - S20：“继续”不再是子串匹配。否定（不要/别/不用继续）先判，直接清掉断点
 *   走新指令；只有整句以继续类说法为主（继续类词 + 少量语气词）才算“继续”；
 *   其他句子不断点、不消费，等新任务开始（TaskLoop.run）或 10 分钟过期再清。
 * - M20：已有未过期断点时 offer() 不再静默覆盖，保留旧断点并返回 false。
 * - M21：restore() 不再重置完整 10 分钟，只把 take() 到 restore() 之间核实
 *   消耗的时间补回来（最多 2 分钟），且绝不超过从现在起的完整 TTL。
 * - M22：断点记下登记时的前台包名；tryResolve() 发现前台已是别家应用时，
 *   在返回的 ResumeInfo 上置 foregroundPkgMismatch，调用方据此先提醒用户。
 * 第四批·断点升级（2026-11-07）：ResumeInfo 再记 plan/completed/screenSig，
 * 续跑时计划与界面指纹一起恢复；三个字段都有默认值，旧断点按空回落。
 * 第十二批·Scratchpad 持久笔记（2026-11-07）：ResumeInfo 再记 notes，
 * 续跑时笔记一起恢复；字段有默认值，旧断点按空回落。
 * tryResolve() 的 copy(foregroundPkgMismatch = true) 会原样带上这几个字段，
 * TripLoop 的 take()/restore() 只负责原样取走还回，都不用改。
 */
object TaskResumeState {

    data class ResumeInfo(
        val taskText: String,
        val history: List<String>,
        val stepsDone: Int,
        /** 断点登记时前台应用的包名（M22 现场校验用）；登记时取不到为 null。 */
        val foregroundPkg: String? = null,
        /** tryResolve() 置位：续跑时前台已不是断点那家应用，调用方应先提醒用户再续跑。 */
        val foregroundPkgMismatch: Boolean = false,
        /** 第四批·断点升级：断点时的待做子目标（第一批的 plan，最多 10 条）。 */
        val plan: List<String> = emptyList(),
        /** 第四批·断点升级：断点时已完成的子目标（第一批的 completed，最多 20 条）。 */
        val completed: List<String> = emptyList(),
        /** 第四批·断点升级：断点时最后一次观察到的界面指纹（pkg + "\n" + elements），
         * 续跑时用来恢复 prevScreenSig，让首步就能判断界面自断点起变没变；
         * 还没观察过（首步前就停下）为 null。只放内存，不落盘。 */
        val screenSig: String? = null,
        /** 第十二批·Scratchpad 持久笔记：断点时的持久笔记列表（TaskLoop 的 notes，
         * 最多 10 条）；续跑时恢复，旧断点没有该字段时默认空列表。只放内存，不落盘。 */
        val notes: List<String> = emptyList()
    )

    private data class Pending(val info: ResumeInfo, val expiresAt: Long)

    private const val TAG = "TaskResumeState"
    private const val TTL_MS = 600_000L // 停下后等用户说“继续”的时间：10 分钟
    /** restore() 最多补回核实消耗的这么多时间（M21 上限，另有 now + TTL 的硬上限）。 */
    private const val RESTORE_GRACE_MS = 120_000L // 2 分钟
    // 注意顺序：去“继续”类词时按从长到短删，先删“从这里继续”再删“继续”，
    // 否则短词先删会把长词拆散、残留“从这里”被误判成新指令。
    private val CONTINUE_WORDS = listOf("从这里继续", "从这继续", "再试一次", "继续", "接着做", "接着来", "接着")
    private val NEGATE_WORDS = listOf("不要再继续", "别再继续", "不用再继续", "不要继续", "别继续", "不用继续", "不继续了")
    /** 只允许跟在“继续”类说法后面的语气词/客套词，多一个实义词都不算“继续”。 */
    private val CONTINUE_FILLERS = listOf("帮我", "麻烦", "谢谢", "一下", "吧", "啊", "呀", "呢", "哦", "嗯", "哈", "请", "了", "就", "好")

    @Volatile
    private var pending: Pending? = null

    /** take() 拿走断点时记下的原有效期与拿走时间，供 restore() 补回核实消耗的时间。 */
    @Volatile
    private var takenExpiresAt: Long? = null
    @Volatile
    private var takenAt: Long? = null

    /**
     * 任务停下（STOP 或跑满步数）后调用，登记“可以从这一步继续”。
     * M20：已有未过期断点时不覆盖，保留旧断点并返回 false（调用方按需处理，
     * 例如先播报旧断点再决定）；没有待处理断点（或旧的已过期）才登记并返回 true。
     * [foregroundPkg] 不传时现取前台包名（取不到为 null，不阻塞登记）。
     * 第四批·断点升级：[plan] / [completed] / [screenSig] 一并记下来，续跑时
     * 恢复；调用方（TaskLoop）保证 plan/completed 已在第一批的上限内，
     * 这里原样存、不另起一套上限常量。三个参数都有默认值，旧的 offer 调用
     * （只传前三个参数）照旧能编，缺字段的旧断点按空列表/空指纹回落。
     * 第十二批·Scratchpad 持久笔记：[notes] 一并记下来，续跑时恢复；调用方
     * （TaskLoop）保证 notes 已在第十二批的上限内，这里原样存。参数有默认值，
     * 旧的 offer 调用照旧能编，缺字段的旧断点按空列表回落。
     */
    fun offer(taskText: String, history: List<String>, stepsDone: Int, foregroundPkg: String? = null,
              plan: List<String> = emptyList(), completed: List<String> = emptyList(),
              screenSig: String? = null, notes: List<String> = emptyList()): Boolean {
        val now = System.currentTimeMillis()
        val existing = pending
        if (existing != null && now <= existing.expiresAt) {
            Log.w(TAG, "offer 拒绝覆盖：已有未过期断点（${existing.info.taskText}，已做 ${existing.info.stepsDone} 步），新任务“$taskText”未登记")
            return false
        }
        val pkg = foregroundPkg?.takeIf { it.isNotEmpty() } ?: currentForegroundPkg()
        pending = Pending(
            ResumeInfo(taskText, history.toList(), stepsDone, foregroundPkg = pkg,
                plan = plan.toList(), completed = completed.toList(),
                screenSig = screenSig?.takeIf { it.isNotEmpty() },
                notes = notes.toList()),
            now + TTL_MS
        )
        return true
    }

    fun hasPending(): Boolean {
        val p = pending ?: return false
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return false
        }
        return true
    }

    fun clear() { pending = null }

    /**
     * 暂时取走待处理的断点（调用方自己保管，用完再还回来）；没有返回 null。
     * 给内部子任务用的：TripLoop 的高德核实是内部调 TaskLoop，TaskLoop.run
     * 开头会清断点——先取走，核实完再还回来，免得用户一句“继续”被内部任务顶掉。
     * 取走时记下原有效期，restore() 按它补回核实消耗的时间（见 M21）。
     */
    fun take(): ResumeInfo? {
        val p = pending ?: run { takenExpiresAt = null; takenAt = null; return null }
        pending = null
        if (System.currentTimeMillis() > p.expiresAt) {
            takenExpiresAt = null
            takenAt = null
            return null
        }
        takenExpiresAt = p.expiresAt
        takenAt = System.currentTimeMillis()
        return p.info
    }

    /**
     * 把取走的断点还回来。M21：不再重置完整 10 分钟——保留原 expiresAt，只把
     * take() 到 restore() 之间核实消耗的时间补回来（最多补 [RESTORE_GRACE_MS]，
     * 且结果绝不超过从现在起的完整 TTL）；原有效期已过、或没经过 take() 就还
     * 回来的，断点作废，返回 false。
     */
    fun restore(info: ResumeInfo): Boolean {
        val now = System.currentTimeMillis()
        val origExpires = takenExpiresAt
        val takenFrom = takenAt
        takenExpiresAt = null
        takenAt = null
        if (origExpires == null || takenFrom == null || origExpires <= now) return false
        val elapsed = (now - takenFrom).coerceAtLeast(0L)
        val restoredExpires = minOf(origExpires + elapsed.coerceAtMost(RESTORE_GRACE_MS), now + TTL_MS)
        pending = Pending(info, restoredExpires)
        return true
    }

    /** 停在哪一步，仅用于停下时的播报；没有待处理项返回 null。 */
    fun pendingStepsDone(): Int? {
        val p = pending ?: return null
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return null
        }
        return p.info.stepsDone
    }

    /**
     * 用这句话判断要不要继续。S20：只有确认是真正的“继续”意图才消费断点并
     * 返回 [ResumeInfo]（调用方据此重新发起 TaskLoop.run 并传入 resumeFrom）；
     * 其余情况不断点、不消费，返回 null，断点留待用户之后说“继续”、或新任务
     * 开始（TaskLoop.run 开头清掉）、或 10 分钟过期。两种例外：
     * - 否定句（不要/别/不用继续）：用户明确不要续跑，直接清掉断点走新指令；
     * - 已过期：断点已死，清掉返回 null。
     * M22：命中“继续”时比对前台包名，与断点登记时不是同一家应用（且当前前台
     * 能确认是别家应用）就在返回的 ResumeInfo 上置 [ResumeInfo.foregroundPkgMismatch]，
     * 调用方应先提醒用户（例如“你现在不在 XX，界面可能已经变了，还继续吗”）
     * 再续跑；本函数只做标记、不拦续跑，标记同时写 Log。
     */
    fun tryResolve(text: String): ResumeInfo? {
        val p = pending ?: return null
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return null
        }
        val t = text.trim()
        // 否定先判：含“继续”子串的否定句（“不要继续了，帮我查天气”）绝不能续跑。
        if (NEGATE_WORDS.any { t.contains(it) }) {
            pending = null
            Log.d(TAG, "tryResolve 否定句，清掉断点走新指令：$t")
            return null
        }
        if (!isContinueUtterance(t)) return null // 不是“继续”，不断点、不消费
        pending = null // 确认是真正的“继续”意图才消费断点
        val storedPkg = p.info.foregroundPkg
        val currentPkg = currentForegroundPkg()
        val ownPkg = currentOwnPkg()
        // 当前前台是 Jarvis 自己（用户正在跟 Jarvis 说话）或取不到包名时，无法判断
        // 现场变没变，不算不符；只有确认在别家应用才置标记。
        val mismatch = !storedPkg.isNullOrEmpty() && currentPkg.isNotEmpty() &&
            currentPkg != storedPkg && currentPkg != ownPkg
        if (mismatch) {
            Log.w(TAG, "tryResolve 续跑但前台包名不符：断点时 $storedPkg，现在 $currentPkg（任务：${p.info.taskText}）")
        }
        return if (mismatch) p.info.copy(foregroundPkgMismatch = true) else p.info
    }

    /**
     * 整句是否以继续类说法为主：去掉继续类词、语气词/客套词与标点空白后不剩
     * 任何实义词才算。“继续”“接着做吧”“嗯，继续”算；“继续查天气”
     * “不要继续了，帮我查天气”（否定已先判）“从这里继续做完转账”不算——
     * 后者带了额外任务内容，留待用户说一句干净的“继续”再续跑。
     */
    private fun isContinueUtterance(t: String): Boolean {
        if (t.isEmpty()) return false
        var rest = t
        for (w in CONTINUE_WORDS) rest = rest.replace(w, "")
        for (f in CONTINUE_FILLERS) rest = rest.replace(f, "")
        rest = rest.filterNot { it.isWhitespace() || it in "，。！？、；：,.!?;:" }
        return rest.isEmpty()
    }

    /** 现取前台包名；无障碍服务未就绪或取不到时返回 ""（不抛异常）。 */
    private fun currentForegroundPkg(): String {
        return try {
            JarvisAccessibilityService.instance?.foregroundPackage() ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    /** Jarvis 自身包名（用户正在跟 Jarvis 说话时前台就是它，M22 比对时要排除）。 */
    private fun currentOwnPkg(): String {
        return try {
            JarvisAccessibilityService.instance?.packageName ?: ""
        } catch (_: Exception) {
            ""
        }
    }
}
