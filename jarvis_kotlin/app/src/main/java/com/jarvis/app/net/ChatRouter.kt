package com.jarvis.app.net

/**
 * 本地/云端调配（批十）：替代批五的「优先本地一刀切」——
 * - 问答求知类（带疑问词、求解释）→ 云端优先：Agnes 脑子大，本地小模型只在云端失败时兜底；
 * - 闲聊寒暄类（无疑问词的短句、社交话）→ 本地优先：快、免费、隐私不出设备，答崩了自动升云端。
 * 实时信息（天气）不归这里，走 data/WeatherTool 的真数据；执行类走 LocalCommands/TaskLoop。
 */
object ChatRouter {

    private val FACTUAL = Regex(
        "(谁|什么|为什么|为何|怎么|怎样|如何|哪个|哪里|多少|几|是不是|有没有|能不能|会不会|可以吗|吗|？|\\?|讲讲|说说|介绍|解释|区别|原理|意思|历史|原因)"
    )

    /** true = 这句是求知问答，云端优先；false = 闲聊，本地优先。 */
    fun cloudFirst(text: String): Boolean = FACTUAL.containsMatchIn(text.trim())
}
