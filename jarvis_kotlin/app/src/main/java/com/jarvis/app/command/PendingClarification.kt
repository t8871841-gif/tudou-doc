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
 * - [tryResolve] 是下一句话进来时第一个被问的——只有这句话真正命中某个选项
 *   （数字/"第几个"/人名/尾号）或明确取消时才消费掉这次待澄清状态；
 *   没命中（与选项无关的另一句话）返回 null 且不消费，状态留给下一句，交还给
 *   正常的指令/聊天流程继续处理这句话（M29：开头就 `pending = null` 会把
 *   待澄清和技能断点续跑互相吃掉）。
 *
 * 没有做的：连续多轮追问（"没听清，再说一遍选第几个"）、多个待澄清事项排队——
 * 目前只有"打电话给谁"/"开哪个同名应用"/"取消哪条提醒"这几种场景会用到它，先按
 * 最简单的够用的做。
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

    /** 登记一次待澄清选择。选项不再只取前 5 条（M28）：调用方把全部候选都传进来，
     *  播报时调用方自己控制列多长，匹配时每一项都能被选中。 */
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
     * 用这句话尝试消费掉当前待澄清状态。返回值三态（M29）：
     * - true：命中某个选项，已回调 [Pending.onResolved] 并消费掉状态；
     * - false：用户明确取消（"算了/不用了/取消…"），状态已消费，调用方仍应让这句话
     *   走正常流程（比如"取消提醒"里的"取消"可能另有所指），只是不要再把它当澄清答案；
     * - null：没有待澄清事项、已过期、或者这句话与选项无关——状态不消费（过期除外，
     *   过期本来就失效了），调用方照常处理这句话，下一句还能继续回答澄清。
     */
    fun tryResolve(text: String): Boolean? {
        val p = pending ?: return null
        if (System.currentTimeMillis() > p.expiresAt) {
            pending = null
            return null
        }

        val t = text.trim()
        // 明确取消：消费状态，但返回 false 让调用方继续正常处理这句话。
        if (CANCEL_WORDS.any { t.contains(it) }) {
            pending = null
            return false
        }

        // 1）数字/中文序数词：「第一个」「第2个」「2」「二」
        val ordinalMatch = Regex("第?([0-9一二三四五六七八九十]+)个?").find(t)
        if (ordinalMatch != null) {
            val raw = ordinalMatch.groupValues[1]
            val idx = raw.toIntOrNull()?.minus(1)
                ?: ORDINAL_WORDS.indexOf(raw).let { if (it >= 0) it else -1 }
            if (idx in p.options.indices) {
                pending = null
                p.onResolved(p.options[idx])
                return true
            }
        }
        // 2）号码尾号：用户直接念出几位数字
        val digits = Regex("\\d{3,}").find(t)?.value
        if (digits != null) {
            val hit = p.options.firstOrNull { it.matchNumberTail.endsWith(digits) || digits.endsWith(it.matchNumberTail.takeLast(minOf(digits.length, it.matchNumberTail.length))) }
            if (hit != null) {
                pending = null
                p.onResolved(hit)
                return true
            }
        }
        // 3）人名/标题：用户直接说出选项里的名字（"张三"、"喝水"、"微信"）。
        // label 形如"张三 尾号1234（手机）"或"10:00 喝水"，取" "前的那段当名字比；
        // 整句与 label 互含（至少 2 字）也算命中，避免"第几个"之外的口语说法被漏掉。
        val nameHit = p.options.firstOrNull { opt ->
            val head = opt.label.substringBefore(" ").substringBefore("尾号").trim()
            val clean = t.trimEnd('。', '！', '!', '？', '?', ' ', '吧', '啊', '啦', '呀', '呢', '嘛')
            (head.isNotEmpty() && (clean == head || (clean.length >= 2 && opt.label.contains(clean)))) ||
                (opt.payload.isNotEmpty() && clean == opt.payload)
        }
        if (nameHit != null) {
            pending = null
            p.onResolved(nameHit)
            return true
        }
        // 没命中：不消费，留给下一句回答。
        return null
    }
}
