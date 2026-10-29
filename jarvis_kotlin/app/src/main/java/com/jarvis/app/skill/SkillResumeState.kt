package com.jarvis.app.skill

/**
 * 第一梯队批三新增：技能失败后的"从失败点继续"。
 *
 * 原来 SkillRunner 失败就整条结束，想重试只能从第 1 步重放——前面已经执行成功的
 * 步骤（比如已经打开了某个 App）会被重复一遍，轻则多等几秒，重则重复下单/重复
 * 发送消息。现在失败时把"卡在第几步"记下来，用户下一句说"继续"就从那一步接着做，
 * 不是从头来。
 *
 * 跟 [PendingClarification] 是同一种"很薄的单轮状态机"设计，不支持多轮追问、
 * 不支持排队——全应用同一时间只会有一个技能在等"继续不继续"。
 */
object SkillResumeState {

    data class ResumeInfo(val skill: SkillItem, val fromIndex: Int)

    private data class Pending(val info: ResumeInfo, val expiresAt: Long)

    private const val TTL_MS = 120_000L // 失败后等用户说"继续"的时间比联系人澄清更长——用户可能要先看一眼手机现场情况
    private val CONTINUE_WORDS = listOf("继续", "接着", "接着做", "接着来", "再试一次", "从这继续", "从这里继续")

    @Volatile
    private var pending: Pending? = null

    /** 技能某一步失败后调用，登记"可以从这一步继续"。 */
    fun offer(skill: SkillItem, fromIndex: Int) {
        pending = Pending(ResumeInfo(skill, fromIndex), System.currentTimeMillis() + TTL_MS)
    }

    fun hasPending(): Boolean {
        val p = pending ?: return false
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return false
        }
        return true
    }

    /** 当前待处理技能的名字，仅用于播报提示；没有待处理项返回 null。 */
    fun pendingSkillName(): String? = pending?.takeIf { System.currentTimeMillis() <= it.expiresAt }?.info?.skill?.name

    fun clear() { pending = null }

    /**
     * 用这句话判断要不要继续。命中"继续"类说法才返回 [ResumeInfo]（调用方据此重新
     * 发起 [SkillRunner.run] 并传入 fromIndex）；没命中（包括直接是别的新指令）一律
     * 清掉待处理状态并返回 null，交还正常流程处理这句话——不强行追问第二遍。
     */
    fun tryResolve(text: String): ResumeInfo? {
        val p = pending ?: return null
        pending = null // 不管命中与否先消费掉，见类注释
        if (System.currentTimeMillis() > p.expiresAt) return null
        val t = text.trim()
        return if (CONTINUE_WORDS.any { t.contains(it) }) p.info else null
    }
}
