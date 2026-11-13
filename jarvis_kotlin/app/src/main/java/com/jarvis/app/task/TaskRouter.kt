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
    // 多步骤规则不在表里（见下面的 isMultiStepTask：裸“然后/接着”不算任务）。
    private val DEEPSEEK_RULES = listOf(
        Regex("发消息|发微信|留言|发短信|发给") to "含发消息",
        Regex("转账|转钱|转.{0,6}给|付款|付钱|查账单|还款|账单") to "涉及钱/账单",
        Regex("导航|路线|怎么走|怎么去") to "含导航",
        Regex("找人(?!聊天|说话|倾诉|解闷)|找联系人|通讯录里|在通讯录") to "需找人",
        Regex("(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户).{0,4}(聊天|会话)") to "找人聊天",
        Regex("搜.{0,8}(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户)") to "搜找人",
        Regex("给.{0,16}发(个|条|张)?(消息|微信|信息|短信|语音|图片|位置|红包|文件)|给.{0,16}留言|给.{0,16}问") to "给人发消息",
        Regex("(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户).{0,4}(发|找|联系|留言)") to "找人发消息",
        Regex("(问|告诉|跟)(老婆|老公|女儿|儿子|妈妈|爸爸|老妈|老爸|朋友|同事|客户)") to "问人/找人",
        Regex("打开.{0,10}?(搜|查|找|买|订)") to "打开搜/查/买/订",
    )

    // 多步骤（Claude 审出）：“先…再/然后/接着”裸用是日常口语（“我先洗个澡
    // 再睡觉”“然后呢”），单独出现不算任务；必须同时提到应用名或动作动词
    // （打开/发/打/查/搜/导航/转账…）才算数。
    private val MULTI_STEP = Regex("先.+再|然后|接着|再打开|再去")
    private val STEP_ACTION = Regex("打开|开启|启动|进入|发|打电话|拨打|查|搜|导航|转账|转钱|付款|付钱")
    private fun isMultiStepTask(t: String): Boolean {
        if (!MULTI_STEP.containsMatchIn(t)) return false
        return mentionedApps(t).isNotEmpty() || STEP_ACTION.containsMatchIn(t)
    }

    /** 明确命中的 DeepSeek 规则原因；没命中返回 null（route() 末尾的“无法判断，
     * 默认 DeepSeek”不走这里）。route() 与 matchesDeepSeekRule() 共用这一处，
     * 两道门不再各管各的。 */
    private fun deepSeekReason(t: String): String? {
        if (CALL_PATTERN.containsMatchIn(t)) return null
        for ((pattern, reason) in DEEPSEEK_RULES) {
            if (pattern.containsMatchIn(t)) return reason
        }
        if (isMultiStepTask(t)) return "多步骤"
        val apps = mentionedApps(t)
        if (apps.size >= 2) return "跨应用（${apps.joinToString("、")}）"
        return null
    }

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
        deepSeekReason(t)?.let { return Route("deepseek", it) }
        for ((pattern, reason) in GLM_RULES) {
            if (pattern.containsMatchIn(t)) return Route("glm", reason)
        }
        return Route("deepseek", "无法判断，默认 DeepSeek")
    }

    /** 只认明确命中的 DeepSeek 规则（发消息/钱/账单/导航/多步骤/找人/跨应用），
     * 不认 route() 末尾的“无法判断，默认 DeepSeek”——普通聊天/问句不能被当成任务。
     * 供 TaskLoop.looksLikeTask 与 LocalCommands 用：进任务循环的门与循环里的
     * 自动选模型用同一套规则，两道门不再互相不认（2026-11-13 语音高德被聊天
     * 模型回绝的根子）。打电话先判 GLM（与 route() 一致），不在这里算 DeepSeek。 */
    fun matchesDeepSeekRule(text: String): Boolean = deepSeekReason(text.trim()) != null

    // ---- 意图层收敛（框架第 2 步，2026-11-13）----
    // “这是不是任务”只在这里判。原来散在四处的分类正则——TaskLoop 的四种
    // 发消息句式、LocalCommands 的 TASK_SHAPED、JarvisAgent 的 BARE_SEND——
    // 全部搬到这里，调用方只调函数，不再各自持有正则。
    // 分工（状态机不并进来）：PendingClarification / SkillResumeState /
    // TaskResumeState 管“有没有待续的事”（状态），TaskRouter 只管“这句话是
    // 什么意图”（分类）；TripLoop 管行程分类（领域内）。分发顺序见 JarvisAgent
    // .handleUtterance：澄清 → 断点续跑 → 本地指令 → 行程 → 任务 → 聊天。
    private val SEND_SHAPES = listOf(
        // 「在<应用>里/上给<人>发信息…」「给<人>发微信…」「打开/用<应用>…发/给…」。
        // M25：「发信息给老婆」——"发"在前、"给/向/跟+人名"在后的词序，SEND_TO 只认"给"在"发"前。
        // 保守优先：必须同时出现「应用或发送动作」与「收件/发送对象」两类信号才算任务；
        // 拿不准放行原流程（聊天/技能/单步指令）。
        Regex("在.{1,12}?(上|里|中).{0,10}?(给|向|跟).{1,16}?(发信息|发消息|发微信|发短信|发送|留言)"),
        Regex("(给|向|跟).{1,16}?(发微信|发信息|发消息|发短信|发送消息|发条消息)"),
        Regex("发(个|条|送)?(信息|消息|微信|短信).{0,8}?(给|向|跟).{1,16}?"),
        Regex("^(打开|开启|启动|进入|用).{1,14}?(给|向|发|帮我).{2,}"),
    )

    // 批九守卫（原 LocalCommands.TASK_SHAPED）：句中带「给<人>发信息/发消息/
    // 发微信」的多步任务句式不是单纯开应用，不能在本地指令里按应用名吞掉
    // （"打开微信给桑田发信息" 会被误当成开「微信给桑田发信息」），放行给
    // 任务路由。注意：这里只认这个窄句式，不认整套 isTaskText——提醒/日历/
    // 场景等本地指令还在后面，整套判进来会把它们抢走。
    private val SEND_SHAPE = Regex("给.{1,16}?(发信息|发消息|发微信|发短信|发送)")
    fun isSendShape(text: String): Boolean = SEND_SHAPE.containsMatchIn(text.trim())

    // 发信息缺收件人（原 JarvisAgent.BARE_SEND）：裸的“发消息/发个信息”且
    // 不带疑问词、收件人也不在动词后——先反问，不当闲聊回掉。问句不算（带吗/
    // ？/怎么/为什么等），避免“发信息要钱吗”这类求知句被误拦。
    private val BARE_SEND = Regex("^(帮我)?发(个|条)?(信息|消息|微信|短信)")
    private val BARE_SEND_QUESTION = Regex("(吗|？|\\?|怎么|为什么|为何|多少|是不是|能不能|会不会)")
    fun isBareSend(text: String): Boolean = BARE_SEND.containsMatchIn(text)
    fun isBareSendQuestion(text: String): Boolean = BARE_SEND_QUESTION.containsMatchIn(text)

    /** “这是不是任务”——唯一入口。纯聊天、单步打开应用、提问句都不算。
     * 原 TaskLoop.looksLikeTask 的整套逻辑，原样搬过来（长度门槛 3 字 +
     * 问号排除 + 四种发消息句式 + DeepSeek 规则）；TaskLoop 那边只留一个
     * 薄别名，调用方不用改。 */
    fun isTaskText(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        if (t.contains('？') || t.contains('?')) return false
        return SEND_SHAPES.any { it.containsMatchIn(t) } || matchesDeepSeekRule(t)
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
