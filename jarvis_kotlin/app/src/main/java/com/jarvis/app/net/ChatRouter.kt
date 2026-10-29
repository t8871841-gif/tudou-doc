package com.jarvis.app.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.BatteryManager

/**
 * 本地/云端调配（批十原版：内容判定）+ 第二梯队批六再升级（网络/电量/隐私信号）：
 * - 内容判定（原批十逻辑）：问答求知类（带疑问词、求解释）→ 云端优先，本地小模型只在
 *   云端失败时兜底；闲聊寒暄类（无疑问词的短句、社交话）→ 本地优先，快、免费、隐私
 *   不出设备，答崩了自动升云端。
 * - 第二梯队批六新增两个更高优先级的信号，都比"内容是问答还是闲聊"更该优先考虑：
 *   ①隐私敏感：这句话提到密码/账号/病情这类字眼，不管是不是"问答形态"，都不要往外送，
 *     本地优先（本地答不出来才兜底云端，兜底仍然会把原话发出去——如实说明这个局限，
 *     见下方函数注释）；
 *   ②没有可用网络：云端这次请求必然超时失败，与其先等云端超时再回落本地干等几秒，
 *     不如直接本地优先，省掉这趟必死的网络往返。
 *   ③电量低且未充电：端侧 LLM 推理是吃 CPU/GPU 的重活，比一次网络请求更耗电；
 *     电量紧张时让云端扛，优先级低于前两条（省电不如隐私/能不能连上网重要）。
 * 实时信息（天气）不归这里，走 data/WeatherTool 的真数据；执行类走 LocalCommands/TaskLoop。
 */
object ChatRouter {

    private val FACTUAL = Regex(
        "(谁|什么|为什么|为何|怎么|怎样|如何|哪个|哪里|多少|几|是不是|有没有|能不能|会不会|可以吗|吗|？|\\?|讲讲|说说|介绍|解释|区别|原理|意思|历史|原因)"
    )

    // 提到这些字眼，不管问句/陈述句，都不该是第一选择就送上云端。
    // 如实说明局限：这只是关键词匹配，不是真正的隐私风险识别，覆盖不到委婉说法
    // （"我那个毛病又犯了"不会命中）；本地答不出来时仍会按原有兜底逻辑转云端，
    // 这里只是调整"先试哪条路"的优先级，不是"涉及这些字眼就绝不联网"的硬限制。
    private val PRIVACY_SENSITIVE = Regex(
        "(密码|银行卡|身份证号|账号余额|工资|收入|存款|负债|贷款|诊断|病历|心理咨询|抑郁|隐私|体检报告)"
    )

    // 电量低于这个百分比且未充电，判定"电量紧张"。
    private const val LOW_BATTERY_PCT = 15

    /** true = 这句云端优先；false = 本地优先。[ctx] 为 null 时只看内容判定（给不方便传 Context 的调用方/单测用）。 */
    fun cloudFirst(ctx: Context?, text: String): Boolean {
        val t = text.trim()
        if (ctx != null) {
            if (PRIVACY_SENSITIVE.containsMatchIn(t)) return false
            if (!hasUsableNetwork(ctx)) return false
            if (isBatteryLow(ctx)) return true
        }
        return FACTUAL.containsMatchIn(t)
    }

    /** 保留纯内容判定的重载，兼容只有文本、没有 Context 的调用场景（不接入新信号）。 */
    fun cloudFirst(text: String): Boolean = cloudFirst(null, text)

    private fun hasUsableNetwork(ctx: Context): Boolean {
        return try {
            val cm = ctx.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return true
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        } catch (_: Exception) {
            true // 查不到就不拦，交给原有的"云端请求失败再兜底本地"兜着
        }
    }

    private fun isBatteryLow(ctx: Context): Boolean {
        return try {
            val bm = ctx.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager ?: return false
            val pct = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val charging = bm.isCharging
            pct in 0 until LOW_BATTERY_PCT && !charging
        } catch (_: Exception) {
            false
        }
    }
}
