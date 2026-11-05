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
    private val CONTINUE_WORDS = listOf("从这里继续", "从这继续", "再试一次", "继续", "接着做", "接着来", "接着")
    private val NEGATE_WORDS = listOf("不要再继续", "别再继续", "不用再继续", "不要继续", "别继续", "不用继续", "不继续了")
    /** 只允许跟在“继续”类说法后面的语气词/客套词，多一个实义词都不算“继续”。 */
    private val CONTINUE_FILLERS = listOf("帮我", "麻烦", "谢谢", "一下", "吧", "啊", "呀", "呢", "哦", "嗯", "哈", "请", "了", "就", "好")

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
     * 用这句话判断要不要继续。M29：只在输入是真正的“继续”类说法（且不是否定）
     * 时才消费断点并返回 [ResumeInfo]（调用方据此重新发起 [SkillRunner.run]
     * 并传入 fromIndex）；输入不匹配（包括直接是别的新指令、否定句）一律返回 null
     * 且不消费断点——断点留着，等用户之后说“继续”、或新任务开始、或 TTL 过期。
     * 不要开头就 pending = null，那会和澄清状态机互相吃掉对方的状态。
     * （JarvisAgent 路会先查澄清、再查技能续跑；这里只改 tryResolve 本身的消费语义。）
     */
    fun tryResolve(text: String): ResumeInfo? {
        val p = pending ?: return null
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return null
        }
        val t = text.trim()
        // 否定先判：含“继续”子串的否定句（“不要继续了，帮我查天气”）不是“继续”，不断点、不消费。
        if (NEGATE_WORDS.any { t.contains(it) }) return null
        if (!isContinueUtterance(t)) return null // 不是“继续”，不断点、不消费
        pending = null // 确认是真正的“继续”意图才消费断点
        return p.info
    }

    /**
     * 整句是否以继续类说法为主：去掉继续类词、语气词/客套词与标点空白后不剩
     * 任何实义词才算。“继续”“接着做吧”“嗯，继续”算；“继续查天气”
     * “从这里继续做完转账”不算——后者带了额外任务内容，留待用户说一句干净的
     * “继续”再续跑。注意顺序：去“继续”类词时按从长到短删，先删“从这里继续”
     * 再删“继续”，否则短词先删会把长词拆散、残留“从这里”被误判成新指令。
     */
    private fun isContinueUtterance(t: String): Boolean {
        if (t.isEmpty()) return false
        var rest = t
        for (w in CONTINUE_WORDS) rest = rest.replace(w, "")
        for (f in CONTINUE_FILLERS) rest = rest.replace(f, "")
        rest = rest.filterNot { it.isWhitespace() || it in "，。！？、；：,.!?;:" }
        return rest.isEmpty()
    }
}
