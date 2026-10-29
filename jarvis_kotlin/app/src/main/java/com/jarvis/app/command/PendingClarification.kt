package com.jarvis.app.command

/**
 * 第一梯队批二新增：多轮澄清对话的最小状态机。
 *
 * 用途：遇到"通讯录里有好几个叫 X 的"这类歧义时，不再是"如实告知用了第一个"，
 * 而是先反问用户选哪个，把选项存在这里，等用户下一句话回来再真正执行。
 *
 * 设计上刻意做成一个很薄的单例（只认一件待澄清的事，不是通用对话状态机）：
 * - [ask] 登记一次待澄清的选择，带上过期时间（默认 60 秒——避免用户十分钟后随口说了
 *   个"算了"把一个早就该忘掉的待澄清事项重新触发）；
 * - [tryResolve] 是下一句话进来时第一个被问的——命中（选中某项/取消）才消费掉这次
 *   待澄清状态并返回 true；没命中就清掉状态（不强行刨根问底），返回 false 交还给
 *   正常的指令/聊天流程继续处理这句话。
 *
 * 没有做的：连续多轮追问（"没听清，再说一遍选第几个"）、多个待澄清事项排队——
 * 目前全应用只有"打电话给谁"这一种场景会用到它，先按最简单的够用的做，
 * 真出现第二种需要澄清的场景（比如同名应用）再扩展成列表/栈。
 */
object PendingClarification {

    data class Option(
        val label: String,   // 播报用的描述，比如"尾号1234的手机号"
        val payload: String, // 命中后真正要用的值（这里是号码）
        val matchNumberTail: String // 用于"说号码后几位"匹配
    )

    private data class Pending(
        val options: List<Option>,
        val expiresAt: Long,
        val onResolved: (Option) -> Unit
    )

    private const val TTL_MS = 60_000L
    private val ORDINAL_WORDS = listOf("一", "二", "三", "四", "五", "六", "七", "八", "九", "十")
    private val CANCEL_WORDS = listOf("算了", "不用了", "取消", "不打了", "不选了", "不要了")

    @Volatile
    private var pending: Pending? = null

    /** 登记一次待澄清选择。[options] 最多建议 5 条（受语音播报长度限制，调用方自己截断）。 */
    fun ask(options: List<Option>, onResolved: (Option) -> Unit) {
        pending = Pending(options, System.currentTimeMillis() + TTL_MS, onResolved)
    }

    fun hasPending(): Boolean {
        val p = pending ?: return false
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return false
        }
        return true
    }

    /**
     * 用这句话尝试消费掉当前待澄清状态。命中（选中某项）会回调 [Pending.onResolved] 并
     * 返回 true；没有待澄清事项、已过期、或者这句话既不是取消也匹配不上任何一项，
     * 一律清掉状态并返回 false（不强行追问，交还正常流程）。
     */
    fun tryResolve(text: String): Boolean {
        val p = pending ?: return false
        pending = null // 不管命中与否，先消费掉——不支持重试追问，见类注释
        if (System.currentTimeMillis() > p.expiresAt) return false

        val t = text.trim()
        if (CANCEL_WORDS.any { t.contains(it) }) return false // 取消：当没选，交还正常流程

        // 1）数字/中文序数词：「第一个」「第2个」「2」「二」
        val ordinalMatch = Regex("第?([0-9一二三四五六七八九十]+)个?").find(t)
        if (ordinalMatch != null) {
            val raw = ordinalMatch.groupValues[1]
            val idx = raw.toIntOrNull()?.minus(1)
                ?: ORDINAL_WORDS.indexOf(raw).let { if (it >= 0) it else -1 }
            if (idx in p.options.indices) {
                p.onResolved(p.options[idx])
                return true
            }
        }
        // 2）号码尾号：用户直接念出几位数字
        val digits = Regex("\\d{3,}").find(t)?.value
        if (digits != null) {
            val hit = p.options.firstOrNull { it.matchNumberTail.endsWith(digits) || digits.endsWith(it.matchNumberTail.takeLast(minOf(digits.length, it.matchNumberTail.length))) }
            if (hit != null) {
                p.onResolved(hit)
                return true
            }
        }
        return false
    }
}
