package com.jarvis.app.task

import android.content.Context
import com.jarvis.app.JarvisPrefs

/**
 * 自动选模型·第 1 批：按任务文本定本次任务用哪个脑子。
 *
 * - 简单任务（单应用、查状态、调设置、打电话）→ GLM（autoglm-phone，专为手机任务调过）；
 * - 跨应用多步骤（找人/发消息、钱/账单、导航、多步骤、两个以上应用）→ DeepSeek
 *   （原生工具调用 + search_contacts + send_chat_message）。
 * - 打电话先判：以前 GLM 就能正常打，单应用拨号不走 DeepSeek。
 * - 拿不准默认 DeepSeek：把复杂任务误判给 GLM 的代价更大——GLM 的 do() 词汇表里
 *   没有 zoom、没有找人、没有确定性发送，接了也做不成。
 *
 * 判断是纯规则（关键字 + 应用名计数），不额外调模型、不花钱、没延迟。
 * 规则边界靠真机跑出来的例子慢慢调——自动档下每次选了谁都写进审计日志，看得见。
 * 手动档（GLM/自定义/DeepSeek）不受影响：resolve() 原样返回开关值。
 */
object TaskRouter {

    /** 一次路由的结果：用的模式 + 选它的原因（写审计用）。 */
    data class Route(val mode: String, val reason: String)

    // 打电话先判：含打电话/拨打/打给的任务一律 GLM（以前 GLM 就能正常打），
    // 优先于下面所有 DeepSeek 规则。
    private val CALL_PATTERN = Regex("打电话|打个电话|拨打|打给")

    // DeepSeek 信号：任一命中即 DeepSeek（顺序即优先级，只取第一条命中的原因）。
    private val DEEPSEEK_RULES = listOf(
        Regex("发消息|发微信|留言|发短信|发给") to "含发消息",
        Regex("转账|转钱|转.{0,6}给|付款|付钱|查账单|还款|账单") to "涉及钱/账单",
        Regex("导航|路线|怎么走|怎么去") to "含导航",
        Regex("先.+再|然后|接着|再打开|再去") to "多步骤",
        Regex("找人|联系人|通讯录") to "需找人",
        Regex("(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户).{0,4}(聊天|会话)") to "找人聊天",
        Regex("搜.{0,8}(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户)") to "搜找人",
        Regex("给.+发|给.+留言|给.+问") to "给人发消息",
        Regex("(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户).{0,4}(发|找|联系|留言)") to "找人发消息",
        Regex("(问|告诉|跟)(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户)") to "问人/找人",
    )

    // 跨应用：文本里出现两个以上已知应用名（长名优先排前面，避免“高德地图”被算成两个）。
    private val APP_CANONICAL = listOf(
        "高德地图" to "高德", "高德" to "高德",
        "百度地图" to "百度地图",
        "哔哩哔哩" to "哔哩哔哩", "B站" to "哔哩哔哩",
        "微信" to "微信", "支付宝" to "支付宝", "小红书" to "小红书",
        "抖音" to "抖音", "快手" to "快手", "淘宝" to "淘宝",
        "京东" to "京东", "微博" to "微博", "知乎" to "知乎",
        "闲鱼" to "闲鱼", "QQ" to "QQ", "拼多多" to "拼多多", "美团" to "美团",
    )

    // GLM 信号：单应用简单操作。
    private val GLM_RULES = listOf(
        Regex("电量|电池|几点了|现在.{0,2}时间") to "查状态/时间",
        Regex("天气") to "查天气",
        Regex("^打开[\\u4e00-\\u9fa5a-zA-Z0-9]{1,8}(吧|一下|呀)?$") to "只打开一个应用",
        Regex("(亮度|音量).{0,2}调|调.{0,2}(亮度|音量)|(设|订|定).{0,2}闹钟") to "调设备设置",
    )

    /** 只按文本路由，不看开关。 */
    fun route(taskText: String): Route {
        val t = taskText.trim()
        // 打电话先判（用户 2026-11-13：以前 GLM 就能正常打）：含打电话/拨打/
        // 打给的任务一律 GLM，优先于下面所有 DeepSeek 规则。
        if (CALL_PATTERN.containsMatchIn(t)) return Route("glm", "打电话")
        for ((pattern, reason) in DEEPSEEK_RULES) {
            if (pattern.containsMatchIn(t)) return Route("deepseek", reason)
        }
        val apps = mentionedApps(t)
        if (apps.size >= 2) return Route("deepseek", "跨应用（${apps.joinToString("、")}）")
        for ((pattern, reason) in GLM_RULES) {
            if (pattern.containsMatchIn(t)) return Route("glm", reason)
        }
        return Route("deepseek", "无法判断，默认 DeepSeek")
    }

    /** 按开关 + 文本 + 断点定本次任务的模式：断点里有模式先沿用（续跑不换脑，
     * 中间改了开关也不影响）；手动档原样返回；"auto" 才走路由。 */
    fun resolve(ctx: Context, taskText: String, resumeMode: String? = null): Route {
        if (resumeMode == "glm" || resumeMode == "deepseek" || resumeMode == "custom") {
            return Route(resumeMode, "断点续跑沿用")
        }
        return when (val global = JarvisPrefs.getModelMode(ctx)) {
            "deepseek" -> Route("deepseek", "手动 DeepSeek")
            "custom" -> Route("custom", "手动自定义")
            "auto" -> route(taskText)
            else -> Route("glm", "手动 GLM")
        }
    }

    private fun mentionedApps(text: String): List<String> {
        val lower = text.lowercase()
        val found = linkedSetOf<String>()
        for ((name, canonical) in APP_CANONICAL) {
            if (lower.contains(name.lowercase())) found += canonical
        }
        return found.toList()
    }
}
