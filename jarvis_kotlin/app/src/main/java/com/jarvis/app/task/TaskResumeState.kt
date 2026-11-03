package com.jarvis.app.task

/**
 * 跨应用任务的“断点续跑”：任务被 STOP 停下（或跑满步数）后，把“做到哪一步”
 * 记下来，用户下一句说“继续”就从断点接着做，不是从头来。
 *
 * 跟 skill/SkillResumeState 是同一种“很薄的单轮状态机”设计：全应用同一时间
 * 只会有一个任务在等“继续不继续”；新任务开始或用户说了别的新指令，待处理
 * 状态就清掉。状态只放内存，App 被杀就丢——丢了也无妨，用户重说一遍任务即可。
 */
object TaskResumeState {

    data class ResumeInfo(val taskText: String, val history: List<String>, val stepsDone: Int)

    private data class Pending(val info: ResumeInfo, val expiresAt: Long)

    private const val TTL_MS = 600_000L // 停下后等用户说“继续”的时间：10 分钟
    private val CONTINUE_WORDS = listOf("继续", "接着", "接着做", "接着来", "再试一次", "从这继续", "从这里继续")

    @Volatile
    private var pending: Pending? = null

    /** 任务停下（STOP 或跑满步数）后调用，登记“可以从这一步继续”。 */
    fun offer(taskText: String, history: List<String>, stepsDone: Int) {
        pending = Pending(
            ResumeInfo(taskText, history.toList(), stepsDone),
            System.currentTimeMillis() + TTL_MS
        )
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
     * 用这句话判断要不要继续。命中“继续”类说法才返回 [ResumeInfo]（调用方据此
     * 重新发起 TaskLoop.run 并传入 resumeFrom）；没命中（包括直接是别的新指令）
     * 一律清掉待处理状态并返回 null，交还正常流程处理这句话——不强行追问第二遍。
     */
    fun tryResolve(text: String): ResumeInfo? {
        val p = pending ?: return null
        pending = null // 不管命中与否先消费掉
        if (System.currentTimeMillis() > p.expiresAt) return null
        val t = text.trim()
        return if (CONTINUE_WORDS.any { t.contains(it) }) p.info else null
    }
}
