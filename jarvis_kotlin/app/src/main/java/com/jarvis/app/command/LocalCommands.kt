package com.jarvis.app.command

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.app.AuditLog
import com.jarvis.app.device.CallState
import com.jarvis.app.task.ShizukuHelper
import java.util.concurrent.Executors

/**
 * 本地指令层（批八起步，这次按复核意见补了三处：正则误伤、主线程卡顿、同名联系人消歧）：
 * 「打电话给 X」「打开 X」这类执行类指令在本地直接办，不再掉进大模型聊天被学舌反问。
 *
 * 命中（含失败但已给明确回报）返回 true；没命中返回 false，上层走原流程
 * （技能 → 视觉意图 → 本地模型/GLM 聊天）。
 *
 * 防误伤（这次改的重点）：光有"吗/？/为什么"这类提问词黑名单还不够——"呼叫中心电话是多少"
 * 不带这几个词但也不是在叫人。真正管用的是「抓出来的名字像不像一个真实的人名/应用名」：
 * 太长、带"的/了/吧/呢/在/要/想/看看/模式/怎么"这类虚词助词的，大概率是普通聊天句被正则
 * 误套了壳，直接当没匹配处理，交还给原来的聊天流程，不再往下走"没找到"的死胡同。
 *
 * 性能（这次改的重点）：通讯录查询、已装应用枚举改到后台线程跑，`speak` 回调跑在主线程——
 * `tryHandle` 本身仍然同步返回 true/false（上层只看这个布尔值决定要不要继续走别的意图），
 * 但命中之后"查、点、回"这几步不再堵住调用方线程（语音识别回调所在的主线程/打字发送的
 * 点击线程），`getInstalledApplications` 在应用多的机型上可能跑到几百毫秒，不能同步占线程。
 */
object LocalCommands {

    // H11：问句词表只在 isQuestion() 里用，不再单独当黑名单前置拦截
    // （前置拦截会误杀"帮我打开微信好吗"这类带礼貌尾词的真指令）。
    private val QUESTION_HINTS = listOf("吗", "？", "?", "是什么", "为什么", "怎么", "如何")

    /** 抓出来的"名字"里出现这些虚词/助词，基本可以断定不是一个人名/应用名，是误套壳的聊天句。 */
    private val NON_NAME_MARKERS = listOf(
        "的", "了", "吧", "呢", "啊", "吗", "在", "要", "想", "觉得", "看看", "听听", "说说",
        "聊", "还是", "是不是", "会不会", "能不能", "应该", "可以", "模式", "状态", "心情",
        "怎么", "为什么", "多少", "干嘛", "干什么"
    )
    private const val MAX_NAME_LEN = 10

    /** 判名前先剥掉的口语前缀（L7）：否则"我的微信"会因"我的"里含"的"被当成聊天句。 */
    private val NAME_PREFIXES = listOf("请帮我", "麻烦帮我", "我的", "帮我", "麻烦", "请")

    /** H10：口语前缀可多层、复合（"麻烦请帮我"、"请麻烦帮我"），循环剥直到剥不动；
     *  最长优先，避免"请帮我"被拆成"请"+"帮我"两轮才剥完。只剥句首。 */
    private val POLITE_PREFIXES = listOf("请帮我", "麻烦帮我", "麻烦请帮我", "请麻烦帮我", "麻烦", "请", "帮我")

    private fun stripPolitePrefixes(s: String): String {
        var cur = s.trim()
        while (true) {
            val hit = POLITE_PREFIXES.firstOrNull { cur.startsWith(it) } ?: break
            cur = cur.removePrefix(hit).trim()
        }
        return cur
    }

    /** 捕获的内容是不是"像"一个真实的人名/应用名——不像就不拦截，交还给原来的聊天流程。 */
    private fun looksLikeLiteralName(s: String): Boolean {
        var name = s.trim()
        // L7：先剥前缀再判长度与虚词——"我的微信支付"剥掉"我的"后才是 4 字的真名；
        // H10：前缀可复合多层，循环剥（原来只剥一层就 break，"麻烦请帮我微信"会漏）。
        // 剥完仍含"的/了/吧"等虚词或超长，才当成聊天句。
        var guard = 0
        while (guard++ < 4) {
            val hit = NAME_PREFIXES.firstOrNull { name.startsWith(it) } ?: break
            name = name.removePrefix(hit).trim()
        }
        return name.isNotBlank() && name.length <= MAX_NAME_LEN && NON_NAME_MARKERS.none { name.contains(it) }
    }

    // M12 + H10："帮我/麻烦/请"前缀在打电话/打开/关闭意图里也认，可多层、复合
    // （"麻烦帮我"、"请帮我"、"麻烦请帮我"），正则里用 `*` 而不是单选一次。
    // tryHandle 入口还会先整句剥一层 stripPolitePrefixes，这里是第二道保险。
    private const val POLITE_PREFIX_GROUP = "(?:请帮我|麻烦帮我|麻烦请帮我|请麻烦帮我|麻烦|请|帮我)*"
    private val CALL_WITH_NAME = Regex("^$POLITE_PREFIX_GROUP(?:打电话给|打个电话给|拨电话给|拨打|呼叫)\\s*(.+)$")
    private val CALL_GIVE = Regex("^${POLITE_PREFIX_GROUP}给\\s*(.+?)\\s*(?:打电话|打个电话|拨电话)$")
    private val CALL_BARE = Regex("^(?:打电话|拨电话|拨号)$")
    private val OPEN_APP = Regex("^$POLITE_PREFIX_GROUP(?:打开|开启|启动|进入)\\s*(.+)$")
    private val CLOSE_APP = Regex("^$POLITE_PREFIX_GROUP(?:关闭|关掉|退出)\\s*(.+)$")
    private val CLOSE_APP_BA = Regex("^${POLITE_PREFIX_GROUP}把\\s*(.+?)\\s*(?:关了|关掉|关闭)$")

    // 批九守卫：句中带「给<人>发信息/发消息/发微信」的多步任务句式不是单纯开应用，
    // 不能在这里按应用名吞掉（"打开微信给桑田发信息" 会被误当成开「微信给桑田发信息」），
    // 放行给 JarvisAgent 的任务路由（task/TaskLoop）。
    private val TASK_SHAPED = Regex("给.{1,16}?(发信息|发消息|发微信|发短信|发送)")

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "LocalCommands").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** speak 是结果回报通道（语音路径走 Agent.speakReply，打字路径走加气泡），在主线程回调。
     *  runJs 是可选的 HUD 桥（MainActivity 注入）：本地指令要动 HUD 时把 JS 串传进去执行；
     *  为空表示主界面没开着，动 HUD 的指令只能回绝，不能硬调。 */
    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit, runJs: ((String) -> Unit)? = null): Boolean {
        // H10：先整句剥掉口语前缀（可多层、复合），剥完再走打电话/打开应用/系统功能的匹配；
        // "请帮我打开手电筒"剥完是"打开手电筒"，不再落到"找不到应用"。
        val t = stripPolitePrefixes(text.trim())
        if (t.isEmpty()) return false

        // 第一梯队批二：先看有没有待澄清的选择（比如上一句问了"给哪个？"），这句话
        // 优先当作回答来解析；命中（true）就在这里直接执行完，不再往下走别的指令匹配。
        // 没命中分两种（M29）：明确取消（false）时状态已消费，这句话继续走正常流程；
        // 与选项无关（null）时状态不消费，同样继续走正常流程，下一句还能回答澄清。
        if (PendingClarification.hasPending() && PendingClarification.tryResolve(text) == true) {
            return true
        }

        // 批十三：系统功能（手电筒/截屏/锁屏等）不是应用，先于「打开应用」拦截。
        // 放到 QUESTION_HINTS 之前：DeviceCommands 全是整句精确匹配，命中了就一定是
        // 指令不是聊天问句（比如「网络怎么样」「联网了吗」带问词但就是查网络的指令，
        // 放后面会被 QUESTION_HINTS 的「怎么/吗」先拦掉、永远到不了这里）。
        if (DeviceCommands.tryHandle(ctx, t, speak)) return true

        // H11：问句检查不再放在打电话/打开应用匹配之前——"帮我打开微信好吗"
        // "打电话给妈妈可以吗"先走指令匹配，匹配不上、走到最后才判是不是疑问句。
        // 见文末 isQuestion：含祈使动词（打开/打电话…）的不是纯疑问句。
        if (TASK_SHAPED.containsMatchIn(t)) return false

        // 第二梯队批五：定时提醒句式（"10分钟后提醒我…""晚上10点提醒我…"）优先于
        // 「打开应用」「打电话」判定——这类句子里常常带"我"这种不像应用名/人名的词，
        // 放在后面容易被其他正则抢先误吞。
        if (ReminderCommands.tryHandle(ctx, t, speak)) return true

        // 复制自 Andee config/：日历与闹钟（CalendarCommands）。
        // 放在 ReminderCommands 之后，提醒句式优先；与场景句式无重叠。
        if (CalendarCommands.tryHandle(ctx, t, speak)) return true
        // 复制自 Andee config/ScenePresets：场景进入/退出/新建/采用预设（SceneCommands）。
        if (SceneCommands.tryHandle(ctx, t, speak)) return true

        // 全息拆解指令（7 台内置拆解机）：「拆解一台汽车发动机」「拆解变速箱」「收起拆解」。
        // 走 HUD 的 machineCommand，不进大模型；图已随包内置（holo/ + figure-raptor.html）。
        // 放在 CLOSE_APP 前面，别让「关闭拆解」被当成关一个叫「拆解」的应用。
        if (isTeardownHide(t)) {
            if (runJs != null) {
                runJs("if(typeof machineHide==='function')machineHide()")
                AuditLog.append(ctx, "本地指令", "收起拆解", "已收起全息拆解")
                main.post { speak("拆解收起来了") }
            } else {
                main.post { speak("主界面没开着，没什么可收的") }
            }
            return true
        }
        if (t.contains("拆解")) {
            val machine = findTeardownMachine(t)
            if (machine != null) {
                if (runJs != null) {
                    val title = machine.title.replace("\\", "\\\\").replace("'", "\\'")
                    runJs("if(typeof machineCommand==='function')machineCommand({action:'show',src:'${machine.src}',title:'$title'})")
                    AuditLog.append(ctx, "本地指令", "拆解${machine.shortName}", "已投影全息拆解")
                    main.post { speak("正在拆解${machine.shortName}") }
                } else {
                    main.post { speak("主界面没开着，投影不了，先打开 Jarvis 主界面") }
                }
                return true
            }
            // 有「拆解」但没指明哪台机器，交给聊天（模型会追问是哪台）。
        }

        // 批二十四：关闭应用（与「打开应用」对称）。DeviceCommands 已先行，
        // 所以「关手电筒/关闭手电筒」这类系统功能不会落到这里。
        val closeName = CLOSE_APP.matchEntire(t)?.groupValues?.get(1)
            ?: CLOSE_APP_BA.matchEntire(t)?.groupValues?.get(1)
        if (closeName != null) {
            val name = cleanName(closeName).removeSuffix("应用").removeSuffix("软件").trim()
            if (!looksLikeLiteralName(name)) return false // 不像应用名，交还给聊天流程
            io.execute { closeAppBg(ctx, name, speak) }
            return true
        }

        if (CALL_BARE.matches(t)) {
            io.execute {
                // C11：拨号结果按实际回报——拨出去了就说拨打了，只开了拨号盘就说
                // "拨号盘打开了，点一下拨出"，失败说原因，不再先开盘紧接着谎报"填好了"。
                val outcome = dial(ctx, null)
                AuditLog.append(ctx, "本地指令", "打电话（无名）", outcome.describe())
                main.post { speak(outcome.speakText("")) }
            }
            return true
        }

        val callName = CALL_WITH_NAME.matchEntire(t)?.groupValues?.get(1)
            ?: CALL_GIVE.matchEntire(t)?.groupValues?.get(1)
        if (callName != null) {
            val name = cleanName(callName)
            if (!looksLikeLiteralName(name)) return false // 不像人名，交还给聊天流程，不当指令处理
            io.execute { callContactBg(ctx, name, speak) }
            return true
        }

        val appName = OPEN_APP.matchEntire(t)?.groupValues?.get(1)
        if (appName != null) {
            val name = cleanName(appName).removeSuffix("应用").removeSuffix("软件").trim()
            if (!looksLikeLiteralName(name)) return false // 不像应用名，交还给聊天流程
            io.execute { openAppBg(ctx, name, speak) }
            return true
        }
        // H11：走到这里还没匹配上任何指令，才判是不是疑问句——是就交还聊天流程。
        if (isQuestion(t)) return false
        return false
    }

    /** H11：只有确实是疑问句才当聊天——以疑问词开头或纯疑问语气，且不含祈使动词。
     *  含"打开/打电话/关闭…"的句子是祈使句（哪怕尾巴带"好吗/可以吗"），先走指令匹配。 */
    private val IMPERATIVE_MARKERS = listOf(
        "打开", "开启", "启动", "进入", "关闭", "关掉", "退出",
        "打电话", "打个电话", "拨电话", "拨打", "呼叫",
        "发信息", "发消息", "发微信", "发短信", "提醒我", "设闹钟", "定闹钟"
    )
    private val QUESTION_OPENERS = listOf("为什么", "怎么", "如何", "是什么", "多少", "是不是", "能不能", "会不会", "哪里", "哪儿", "什么")

    private fun isQuestion(t: String): Boolean {
        if (IMPERATIVE_MARKERS.any { t.contains(it) }) return false
        if (QUESTION_OPENERS.any { t.startsWith(it) }) return true
        if (QUESTION_HINTS.any { t.contains(it) }) return true
        return t.endsWith("呢")
    }

    // ---------- 全息拆解：7 台内置机器（名 -> 图 + 标题），列表顺序即匹配优先级 ----------
    private data class TeardownMachine(val keys: List<String>, val src: String, val title: String, val shortName: String)

    private val TEARDOWN_MACHINES = listOf(
        TeardownMachine(listOf("航空发动机", "涡扇"), "holo/aircraft-turbofan.html", "航空发动机 · 大涵道比涡扇", "航空发动机"),
        TeardownMachine(listOf("汽车发动机"), "holo/car-engine.html", "汽车发动机 · 直列四缸涡轮增压", "汽车发动机"),
        TeardownMachine(listOf("燃气轮机"), "holo/gas-turbine.html", "燃气轮机 · 重型发电", "燃气轮机"),
        TeardownMachine(listOf("变速箱"), "holo/gearbox.html", "变速箱 · 六速手动", "变速箱"),
        TeardownMachine(listOf("电动机", "电机"), "holo/electric-motor.html", "电动机 · 永磁同步", "电动机"),
        TeardownMachine(listOf("离心泵", "水泵"), "holo/centrifugal-pump.html", "离心泵", "离心泵"),
        TeardownMachine(listOf("猛禽", "raptor"), "figure-raptor.html", "RAPTOR 2 · 全流量分级燃烧发动机", "猛禽发动机"),
    )

    /** 按优先级找机器：先认具体名（航空/汽车发动机），裸「发动机」默认汽车发动机（最常用）。 */
    private fun findTeardownMachine(text: String): TeardownMachine? {
        val lower = text.lowercase()
        for (m in TEARDOWN_MACHINES) {
            if (m.keys.any { lower.contains(it.lowercase()) }) return m
        }
        if (lower.contains("发动机")) return TEARDOWN_MACHINES[1] // 汽车发动机
        return null
    }

    private fun isTeardownHide(text: String): Boolean {
        val close = text.contains("收起") || text.contains("关闭") || text.contains("关掉")
        val target = text.contains("拆解") || text.contains("全息")
        return close && target
    }

    private fun cleanName(raw: String): String =
        raw.trim()
            .removePrefix("一下")
            .trim()
            // L7：名字里的"我的"前缀也剥掉，判名与查通讯录/查应用都用剥后的真名
            // （"打开我的微信"否则会按"我的微信"去枚举，精确匹配永远命中不了）。
            .removePrefix("我的")
            .trim()
            .trimEnd('。', '！', '!', ' ', '吧', '啊', '啦', '呀', '呢', '嘛')

    // ---------- 打电话：后台线程执行，查完回主线程回报 ----------

    private fun callContactBg(ctx: Context, name: String, speak: (String) -> Unit) {
        if (name.isBlank()) {
            main.post { speak("给谁打？说个名字，我查通讯录") }
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            main.post { speak("打电话要先给通讯录权限：打开设置，在功能自检页点「申请通讯录权限」") }
            return
        }
        val matches = queryContacts(ctx, name)
        if (matches.isEmpty()) {
            main.post { speak("通讯录里没找到$name") }
            return
        }
        // 第一梯队批二：原来是"不管几个，直接用第一个、顺带告知有几个同名的"——
        // 现在改成真的问一句，等用户下一句话选。只有唯一命中才直接拨。
        if (matches.size == 1) {
            val pick = matches.first()
            main.post {
                // C11：按实际结果播报——直接拨出去了就说拨打了，只开了拨号盘就说
                // "拨号盘已打开，点一下拨出"，失败说原因；不再没核对就说"号码已经填好了"。
                val outcome = dial(ctx, pick.number)
                AuditLog.append(ctx, "本地指令", "打电话给${pick.display}", outcome.describe())
                speak(outcome.speakText(if (pick.display == name) "给${name}打" else "给${pick.display}打"))
            }
            return
        }
        // M28：澄清覆盖全部同名联系人，不再只 take(5)——第 6 个以后的同名联系人
        // 以前永远选不到。选项全量进 PendingClarification；语音播报列前 10 个，
        // 超过 10 个时告诉用户总数、让用户说名字或报尾号选（匹配时每一项都能命中）。
        val options = matches.map { hit ->
            PendingClarification.Option(
                label = "${hit.display} 尾号${hit.number.takeLast(4)}（${hit.numberType}）",
                payload = hit.number,
                matchNumberTail = hit.number.takeLast(4)
            )
        }
        main.post {
            PendingClarification.ask(options) { chosen ->
                val outcome = dial(ctx, chosen.payload)
                AuditLog.append(ctx, "本地指令", "打电话（澄清后选定）", "${chosen.label}：${outcome.describe()}")
                speak(outcome.speakText("给${chosen.label}打"))
            }
            val shown = matches.take(10)
            val listText = shown.mapIndexed { i, h -> "第${i + 1}个，${h.display} 尾号${h.number.takeLast(4)}" }.joinToString("；")
            val more = if (matches.size > shown.size) "，还有${matches.size - shown.size}个" else ""
            AuditLog.append(ctx, "本地指令", "打电话给$name·需要澄清", "${matches.size}个匹配，已反问")
            speak("通讯录里有${matches.size}个叫$name 的：$listText$more。你说第几个，或者报号码后几位，也可以直接说名字")
        }
    }

    internal data class ContactHit(val display: String, val number: String, val contactId: String, val numberType: String)

    /**
     * 返回每个联系人一条（同一个人有多个号码时，优先选"手机"号），精确同名的排前面。
     * 修复（复核发现）：原来按"行"去重，同一个人有住宅/工作/手机好几个号码会出现好几条，
     * 永远拿 ContactsProvider 吐出来的第一条（不一定是手机号）；现在按 CONTACT_ID 分组、
     * 组内优先手机号，真正按"人"去重，而不是按"号码行"去重。
     */
    internal fun queryContacts(ctx: Context, name: String): List<ContactHit> {
        val byContact = LinkedHashMap<String, MutableList<Triple<String, String, Int>>>() // id -> [(display, number, type)]
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER,
                    ContactsContract.CommonDataKinds.Phone.TYPE
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { c ->
                val ii = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.CONTACT_ID)
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                val ti = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.TYPE)
                while (c.moveToNext()) {
                    val id = c.getString(ii)?.trim().orEmpty()
                    val d = c.getString(ni)?.trim().orEmpty()
                    val n = c.getString(pi)?.replace(" ", "")?.replace("-", "")?.trim().orEmpty()
                    val ty = if (ti >= 0) c.getInt(ti) else -1
                    if (id.isEmpty() || d.isEmpty() || n.isEmpty()) continue
                    byContact.getOrPut(id) { mutableListOf() }.add(Triple(d, n, ty))
                }
            }
        } catch (_: Exception) {
            // 查询异常当没找到处理，上层会给明确回报
        }
        val out = byContact.map { (id, rows) ->
            val mobile = rows.firstOrNull { it.third == ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE }
            val chosen = mobile ?: rows.first()
            val typeLabel = if (chosen === mobile) "手机" else "默认号码"
            ContactHit(chosen.first, chosen.second, id, typeLabel)
        }
        return out.sortedBy { if (it.display == name) 0 else 1 }
    }

    /** 批九：任务循环的 openApp 动作复用同一套按名找应用+拉起逻辑，返回结果描述（不播报）。 */
    /** 打开应用。fullscreen=true 时走前台拉起全屏（跨应用任务循环用：截图干净、看图模型干扰少）；
     * 日常“打开应用”也已统一走前台拉起全屏（jk-v66 小窗修复，用户 2026-11-05 原话“不要小窗，我要应用全屏”），
     * 不再走 fullscreen=false 的后台直接启动（Flyme 默认给小窗）。 */
    fun launchApp(ctx: Context, name: String, fullscreen: Boolean = false): String =
        launchAppInternal(ctx, name.trim(), fullscreen)

    /** 全屏启动：跨应用打开应用时显式要全屏窗口模式，Flyme 就不会把它开成小窗。
     * 原因：TaskLoop 在后台线程拿应用上下文启动，系统当成后台启动、Flyme 给小窗；
     * 全屏后截图里只有目标应用、没有背后的 Jarvis 界面，看图模型干扰少、任务量小。
     * setLaunchWindowingMode 是 @hide 接口（公开 SDK 里没有，直接调编译不过，
     * jk-v61 首版就因此构建失败），只能反射调；WINDOWING_MODE_FULLSCREEN 的值是 1。
     * 反射在部分系统上也被拦，失败就回落到普通启动，不让一次启动失败整个任务。
     * 成败都写审计日志：反射失败时审计里能看到“回落普通启动”，
     * 便于区分“没装 jk-v61”和“装了但系统拦了反射”两种情况。 */
    fun startActivityFullscreen(ctx: Context, intent: Intent) {
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val opts = android.app.ActivityOptions.makeBasic()
                val m = android.app.ActivityOptions::class.java.getMethod(
                    "setLaunchWindowingMode", Int::class.javaPrimitiveType
                )
                m.invoke(opts, 1) // WindowConfiguration.WINDOWING_MODE_FULLSCREEN
                ctx.startActivity(intent, opts.toBundle())
                AuditLog.append(ctx, "本地指令", "全屏启动", "已请求全屏窗口")
                return
            }
        } catch (e: Exception) {
            // 反射失败，回落到普通启动；写审计，免得小窗出现时查不到原因
            AuditLog.append(ctx, "本地指令", "全屏启动", "反射失败，已回落普通启动：${e.javaClass.simpleName}")
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    /** 前台拉起（jk-v66）：不要小窗、要应用直接全屏。
     * 反射 setLaunchWindowingMode 在这台 Flyme 上根本不存在（NoSuchMethodException），
     * 拖小窗展开也靠不住（jk-v65 审计：微信拖动后仍是小窗）。
     * 改法：把要开的包名交给 MainActivity，由它在 onResume（已在前台）时去 startActivity，
     * 系统当成前台启动，Flyme 就给全屏。第 4 批：等目标包的窗口出现才算到
     * （前台包名到了、窗口还没画出来时不算到），上限 10 秒，超时也返回
     * （任务循环下一步会再确认前台包名）。 */
    fun launchAppFullscreenViaActivity(ctx: Context, pkg: String, appName: String): String {
        val intent = android.content.Intent(ctx, com.jarvis.app.MainActivity::class.java).apply {
            putExtra(com.jarvis.app.MainActivity.EXTRA_ACTION, "launch_app")
            putExtra(com.jarvis.app.MainActivity.EXTRA_LAUNCH_PKG, pkg)
            addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP)
        }
        val svc = com.jarvis.app.JarvisAccessibilityService.instance
        // 第 3 批·第一跳改走无障碍服务自身的 context + PendingIntent：服务上下文
        // 拉起不受后台启动限制的普通约束；Flyme 上若仍被拦，逐级回落，每级都写审计。
        var relayOk = false
        if (svc != null) {
            try {
                svc.startActivity(intent)
                relayOk = true
                AuditLog.append(ctx, "本地指令", "前台拉起", "MainActivity 已通过无障碍服务上下文拉起")
            } catch (e: Exception) {
                AuditLog.append(ctx, "本地指令", "前台拉起", "服务上下文拉起被拦：${e.javaClass.simpleName}，改走 PendingIntent")
            }
            if (!relayOk) {
                try {
                    val pi = android.app.PendingIntent.getActivity(
                        svc, 0, intent,
                        android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                    )
                    pi.send()
                    relayOk = true
                    AuditLog.append(ctx, "本地指令", "前台拉起", "MainActivity 已通过 PendingIntent 拉起")
                } catch (e: Exception) {
                    AuditLog.append(ctx, "本地指令", "前台拉起", "PendingIntent 拉起被拦：${e.javaClass.simpleName}，回落应用上下文")
                }
            }
        }
        if (!relayOk) {
            try {
                ctx.startActivity(intent)
                AuditLog.append(ctx, "本地指令", "前台拉起", "走了应用上下文回落拉起")
            } catch (e: Exception) {
                AuditLog.append(ctx, "本地指令", "前台拉起", "MainActivity 拉起失败：${e.javaClass.simpleName}，回落普通启动")
                try {
                    val fallback = ctx.packageManager.getLaunchIntentForPackage(pkg)
                    if (fallback != null) {
                        fallback.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        ctx.startActivity(fallback)
                    }
                } catch (_: Exception) {
                }
                // 第 3 批：回落直启若真把应用打开了（上面没抛），不能再报“前台拉起不可用”——
                // 那是旧的自相矛盾文案。是否真到了前台由下面的轮询裁决，这里只报拉起路径。
                AuditLog.append(ctx, "本地指令", "前台拉起", "中转拉起失败，已回落普通启动，等轮询裁决是否到前台")
            }
        }
        // 第 4 批·等目标窗口出现：只凭前台包名不够——包名到了、窗口还没画出来
        // 时也算“到了”，冷启动应用首轮常在这里吃超时。改成前台包名是目标包、
        // 且目标包的窗口已出现（appWindowBounds 非空）才算到；上限 10 秒
        // （33 次 × 300ms），超时返回失败文案（不带“正在打开”前缀，任务
        // 循环的 openApp 步会判失败）；控制器拿不到时退回只看前台包名，
        // 不把已开的应用也判失败。第 3 批的 verifyTargetWindow 不动。
        // svc 已在前面取出（第 3 批第一跳用），这里复用。
        if (svc != null) {
            val controller = com.jarvis.app.JarvisAccessibilityService.screenController
            for (i in 0 until 33) {
                try {
                    Thread.sleep(300)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    break
                }
                val fg = try {
                    svc.foregroundPackage()
                } catch (_: Exception) {
                    ""
                }
                val hasWindow = if (controller == null) {
                    true
                } else {
                    try {
                        controller.appWindowBounds(pkg) != null
                    } catch (_: Exception) {
                        false
                    }
                }
                if (fg == pkg && hasWindow) {
                    AuditLog.append(ctx, "本地指令", "前台拉起", "$appName 已到前台")
                    // 第 3 批·拉起后验窗口：量目标窗口 bounds，不足整屏 80% 判小窗，接展开。
                    // “已全屏到前台”不能只凭包名就报，旧文案正是断点 #5 的谎报。
                    return "正在打开$appName" + verifyTargetWindow(ctx, svc, pkg, appName)
                }
            }
            val cur = try {
                svc.foregroundPackage()
            } catch (_: Exception) {
                "?"
            }
            AuditLog.append(ctx, "本地指令", "前台拉起", "$appName 10 秒内没出现目标窗口（当前前台：$cur）")
            // S1：10 秒轮询超时，没确认到窗口，不报"正在打开"。
            return "打开${appName}后10秒还没出现窗口"
        } else {
            try {
                Thread.sleep(1200)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
            // S1：无障碍服务为空，无法确认前台包名，不报"正在打开"。
            AuditLog.append(ctx, "本地指令", "前台拉起", "无障碍服务不可用，无法确认$appName 是否到前台")
            return "打开${appName}失败：无障碍服务不可用"
        }
    }

    /** 第 3 批·拉起后验窗口：用无障碍服务的 getWindows() 量目标窗口 bounds，
     * 面积不足整屏 80% 判小窗，接上现成的 expandFloatingApp 做展开（jk-v64 起
     * 就是死代码，零调用）。返回要追加到“正在打开...”后面的后缀（开头不带
     * “正在打开”，调用方保证前缀完整，TaskLoop 认这个前缀判成功）：
     * 全屏返回空串；展开成功报一句；展开失败如实报明原因并提醒模型优先用
     * 编号 eid（节点 bounds 是绝对坐标，不受小窗换算影响），绝不静默。 */
    private fun verifyTargetWindow(ctx: Context, svc: com.jarvis.app.JarvisAccessibilityService?, pkg: String, appName: String): String {
        val controller = svc?.let { com.jarvis.app.JarvisAccessibilityService.screenController }
        if (controller == null) {
            AuditLog.append(ctx, "本地指令", "前台拉起", "无法验窗口：无障碍控制器为空")
            return ""
        }
        val bounds = try {
            controller.appWindowBounds(pkg)
        } catch (_: Exception) {
            null
        }
        if (bounds == null) {
            AuditLog.append(ctx, "本地指令", "前台拉起", "无法验窗口：没找到 $appName 的窗口")
            return ""
        }
        val screen = com.jarvis.app.screen.ScreenController.displaySize(ctx)
        val screenArea = screen.x.toLong() * screen.y
        val winArea = bounds.width().toLong() * bounds.height()
        if (screenArea <= 0 || winArea * 100 >= screenArea * 80) return ""
        AuditLog.append(ctx, "本地指令", "前台拉起", "$appName 还在小窗里（窗口 ${bounds.width()}x${bounds.height()}），尝试展开")
        val expanded = try {
            controller.expandFloatingApp(pkg)
        } catch (e: Exception) {
            null
        }
        val ok = expanded?.optBoolean("expanded", false) == true
        val detail = expanded?.optString("detail").orEmpty()
        return if (ok) {
            AuditLog.append(ctx, "本地指令", "前台拉起", "$appName 小窗已展开成全屏")
            "，小窗已展开成全屏"
        } else {
            AuditLog.append(ctx, "本地指令", "前台拉起", "$appName 小窗展开失败：$detail")
            "，小窗展开失败（仍是小窗：${detail}，坐标按全屏算可能偏移，建议优先用编号 eid 点）"
        }
    }

    /**
     * 常用应用包名直查表（键已小写；查前把名字 trim + lowercase）。
     * 直查失败（包不存在、无启动界面、拉起抛异常）时一律回落到枚举，
     * 所以表里即使有个别 ROM 上包名不同的条目也不会开错应用——
     * 最坏只是直查没命中，走回老路。
     */
    private val WELL_KNOWN_APPS = mapOf(
        "微信" to "com.tencent.mm",
        "wechat" to "com.tencent.mm",
        "qq" to "com.tencent.mobileqq",
        "支付宝" to "com.eg.android.AlipayGphone",
        "alipay" to "com.eg.android.AlipayGphone",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        // 第 6 批·断点 #9：补小红书、今日头条、微博、知乎、闲鱼（包名与
        // AppKnowledge 一致）；小红书包名待真机 pm list packages 核实。
        "小红书" to "com.xingin.xhs",
        "红书" to "com.xingin.xhs",
        "xiaohongshu" to "com.xingin.xhs",
        "今日头条" to "com.ss.android.article.news",
        "微博" to "com.sina.weibo",
        "知乎" to "com.zhihu.android",
        "闲鱼" to "com.taobao.idlefish",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "设置" to "com.android.settings",
        // 第 6 批·断点 #9：电话/短信改走魅族包名（白名单里已有
        // com.meizu.flyme.mms/dialer 作参照）；相机按魅族常见包名填，
        // 待真机 pm list packages 核实。直查失败仍回落到标签枚举，
        // 所以别家 ROM 上这三条不命中也不会开错应用。
        "电话" to "com.meizu.flyme.dialer",
        "拨号" to "com.meizu.flyme.dialer",
        "短信" to "com.meizu.flyme.mms",
        "相机" to "com.meizu.media.camera",
        "相册" to "com.android.gallery3d"
    )

    /** 按应用名找包名（小窗展开用）：先查常用表（含别名），再枚举已装应用——
     *  先按 label 精确匹配（忽略大小写），无精确命中时子串兜底（label 含名字、
     *  或名字含 label，名字至少 2 字，label 为空时不参与反向包含；第 6 批，
     *  与 findAppCandidates 同款顺序）；找不到返回 null。
     *  同名多包时精确匹配返回第一个（任务循环里 launchApp 已先报过多候选错误，
     *  不会走到这里）；子串命中多个时不猜，返回 null。 */
    fun packageForApp(ctx: Context, name: String): String? {
        val key = name.trim().lowercase()
        WELL_KNOWN_APPS[key]?.let { return it }
        val pm = ctx.packageManager
        try {
            for (app in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.equals(name.trim(), ignoreCase = true)) return app.packageName
            }
            val query = name.trim()
            if (query.length >= 2) {
                var hit: String? = null
                for (app in pm.getInstalledApplications(0)) {
                    val label = pm.getApplicationLabel(app).toString()
                    if (label.contains(query, ignoreCase = true) ||
                        (label.isNotEmpty() && query.contains(label, ignoreCase = true))
                    ) {
                        if (hit != null) return null // 多个子串命中，不猜
                        hit = app.packageName
                    }
                }
                if (hit != null) return hit
            }
        } catch (_: Exception) {
        }
        return null
    }

    /** C10 + M28：第 6 批改成精确→子串两级（别名在调用方的常用表里先认，
     *  如“红书”→小红书、“拨号”→电话）：先按 label 精确匹配（忽略大小写），
     *  只有零精确命中时才走子串兜底（label 含名字、或名字含 label，名字至少
     *  2 字，label 为空时不参与反向包含）——先精确后子串，免得“微信读书”把
     *  “微信”也带进来造成假多候选；同优先级多个候选都返回，调用方走澄清
     *  问用户选哪个，绝不猜一个开。枚举异常不上吞，由调用方接住并如实回报。 */
    private fun findAppCandidates(ctx: Context, name: String): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val pm = ctx.packageManager
        val query = name.trim()
        for (app in pm.getInstalledApplications(0)) {
            val label = pm.getApplicationLabel(app).toString()
            if (label.equals(query, ignoreCase = true)) {
                out.add(label to app.packageName)
            }
        }
        if (out.isNotEmpty()) return out
        if (query.length >= 2) {
            for (app in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(app).toString()
                if (label.contains(query, ignoreCase = true) ||
                    (label.isNotEmpty() && query.contains(label, ignoreCase = true))
                ) {
                    out.add(label to app.packageName)
                }
            }
        }
        return out
    }

    /** 按包名拉起一个应用，返回结果描述（不播报）；fullscreen 时走前台拉起。 */
    private fun launchPackage(ctx: Context, pkg: String, label: String, fullscreen: Boolean = false): String {
        val pm = ctx.packageManager
        val intent = pm.getLaunchIntentForPackage(pkg)
            ?: return "$label 没有能打开的界面"
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            if (fullscreen) {
                val r = launchAppFullscreenViaActivity(ctx, pkg, label)
                AuditLog.append(ctx, "本地指令", "打开$label", "前台拉起结果：$r")
                return r
            }
            ctx.startActivity(intent)
        } catch (_: Exception) {
            return "打开$label 失败了"
        }
        AuditLog.append(ctx, "本地指令", "打开$label", "已启动")
        return "正在打开$label"
    }

    // ---------- 打开应用：tryHandle 在 io 线程调进来，枚举/拉起在本线程，回报回主线程 ----------

    private fun openAppBg(ctx: Context, name: String, speak: (String) -> Unit) {
        if (name.isBlank()) {
            main.post { speak("打开什么？说个应用名") }
            return
        }
        // M28：同名应用（分身/双开）走澄清问用户选哪个，不静默开一个。
        // 常用表直查的名字（微信/支付宝…）是唯一包名，不进澄清。
        if (WELL_KNOWN_APPS[name.trim().lowercase()] == null) {
            val candidates = try {
                findAppCandidates(ctx, name)
            } catch (e: Exception) {
                main.post { speak("找应用时出错：${e.message ?: e.javaClass.simpleName}") }
                return
            }
            if (candidates.size > 1) {
                val options = candidates.map { (label, pkg) ->
                    PendingClarification.Option(
                        label = "$label（$pkg）",
                        payload = pkg,
                        matchNumberTail = ""
                    )
                }
                main.post {
                    PendingClarification.ask(options) { chosen ->
                        // jk-v66 小窗修复：日常打开统一走前台拉起全屏（与任务循环同一条路），
                        // 不再走后台直接启动（Flyme 默认给小窗）。
                        val result = launchPackage(ctx, chosen.payload, name.trim(), fullscreen = true)
                        AuditLog.append(ctx, "本地指令", "打开应用（澄清后选定）", "${chosen.label}：$result")
                        // 小窗当默认接受（2026-11-12 用户定）：只念“正在打开X”，不念小窗展开失败的后缀；
                        // 后缀仍在返回值里（任务循环/模型照常看到，审计照常记），只不进语音。
                        speak(if (result.startsWith("正在打开")) "正在打开${name.trim()}" else result)
                    }
                    val listText = candidates.mapIndexed { i, (_, pkg) -> "第${i + 1}个，$pkg" }.joinToString("；")
                    AuditLog.append(ctx, "本地指令", "打开$name·需要澄清", "${candidates.size}个同名应用，已反问")
                    speak("找到${candidates.size}个叫$name 的应用：$listText。你说第几个")
                }
                return
            }
        }
        // jk-v66 小窗修复：日常“打开应用”统一走前台拉起全屏（与任务循环同一条路，
        // 用户 2026-11-05 原话“不要小窗，我要应用全屏”）；launchAppInternal /
        // launchPackage 里 fullscreen=false 的后台直接启动分支，日常打开这条路不再走。
        val result = launchAppInternal(ctx, name, fullscreen = true)
        // 小窗当默认接受（2026-11-12 用户定）：只念“正在打开X”，不念小窗展开失败的后缀；
        // 后缀仍在返回值里（任务循环/模型照常看到，审计照常记），只不进语音。
        val spoken = if (result.startsWith("正在打开")) "正在打开${name.trim()}" else result
        main.post { speak(spoken) }
    }

    /** 按应用名找已装应用并拉起，返回一句结果描述；成功/失败都写审计。
     *  第 6 批·匹配顺序：常用表直查（含别名）→ label 精确匹配（忽略大小写，
     *  Andee DeviceActionsController.resolveLabel 同款）→ 子串兜底（无精确
     *  命中时才走）；匹配不上就返回明确错误（让模型看到错误再反问），绝不
     *  猜一个打开；多个同优先级候选时不取最短/第一个，返回错误让调用方澄清。 */
    private fun launchAppInternal(ctx: Context, name: String, fullscreen: Boolean = false): String {
        val pm = ctx.packageManager
        // 2026-10-24 跨应用修复第 1 件：按名字枚举应用时好时坏（21:56 任务循环里
        // “微信/WeChat”两次没找到，21:58 同一函数直接说“打开微信”却成功了）——
        // 常用应用先按包名直查，绕过枚举整条路；直查失败（没装/没启动界面）
        // 再回落到枚举。包名都是公开的固定值，不是用户隐私。
        // 直查表是按名字精确键控的（不是模糊匹配），保留。
        val direct = WELL_KNOWN_APPS[name.trim().lowercase()]
        if (direct != null) {
            val intent = pm.getLaunchIntentForPackage(direct)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    if (fullscreen) {
                        val r = launchAppFullscreenViaActivity(ctx, direct, name.trim())
                        AuditLog.append(ctx, "本地指令", "打开${name.trim()}", "前台拉起结果：$r")
                        return r
                    }
                    ctx.startActivity(intent)
                    AuditLog.append(ctx, "本地指令", "打开${name.trim()}", "已启动（包名直查）")
                    return "正在打开${name.trim()}"
                } catch (_: Exception) {
                    // 拉起失败时不直接判死，继续走枚举（可能装了分身/改名版）
                }
            }
        }
        var enumError: String? = null
        val exact = try {
            findAppCandidates(ctx, name)
        } catch (e: Exception) {
            // 旧代码把枚举异常静默吞成“没找到”，下次取证分不清是真没装还是
            // 枚举本身炸了；现在把异常原因带进失败文案。
            enumError = e.message ?: e.javaClass.simpleName
            emptyList()
        }
        if (exact.isEmpty()) {
            return if (enumError != null) "找应用时出错：$enumError" else "没找到叫${name}的应用"
        }
        if (exact.size > 1) {
            // C10：多个精确候选不猜。任务循环/模型侧看到这句错误会再反问；
            // 语音侧 openAppBg 已先拦截走澄清，不会走到这里。
            return "找到${exact.size}个叫${name}的应用，没法确定开哪一个，请说清开哪一个"
        }
        val found = exact.first()
        return launchPackage(ctx, found.second, found.first, fullscreen)
    }



    // ---------- 关闭应用：批二十四新增——去最近任务划掉目标卡片 ----------

    private fun closeAppBg(ctx: Context, name: String, speak: (String) -> Unit) {
        if (name.isBlank()) {
            main.post { speak("关闭什么？说个应用名") }
            return
        }
        // 无障碍服务实例先取出来，但不急着判空：Shizuku 强停不需要无障碍，
        // 判空只守最近任务兜底那条路（见下面）。以前判空放在这里会把 Shizuku 也挡住。
        val svc = com.jarvis.app.JarvisAccessibilityService.instance
        // 找目标：常用应用先包名直查（与打开同一张表），否则按标签精确匹配；
        // 关闭错应用比关不成严重，所以这里不用打开那边的模糊匹配。
        val pm = ctx.packageManager
        var label: String? = null
        var pkg: String? = null
        val direct = WELL_KNOWN_APPS[name.trim().lowercase()]
        if (direct != null && pm.getLaunchIntentForPackage(direct) != null) {
            label = name.trim(); pkg = direct
        } else {
            try {
                for (app in pm.getInstalledApplications(0)) {
                    val l = pm.getApplicationLabel(app).toString()
                    if (l == name) { label = l; pkg = app.packageName; break }
                }
            } catch (e: Exception) {
                main.post { speak("找应用时出错：${e.message ?: e.javaClass.simpleName}") }
                return
            }
        }
        if (label == null || pkg == null) {
            main.post { speak("没找到叫${name}的应用") }
            return
        }
        // 自己不能这样关：划掉自己的卡片会把前台界面杀掉。
        if (pkg == ctx.packageName) {
            main.post { speak("Jarvis 自己不能这样关") }
            return
        }
        val targetLabel = label
        val targetPkg = pkg
        // Shizuku 强停优先：不用无障碍、不走最近任务划卡片，更稳。
        val shizukuAvailable = ShizukuHelper.isShizukuAvailable(ctx)
        if (shizukuAvailable && ShizukuHelper.hasPermission()) {
            if (ShizukuHelper.forceStopPackage(ctx, targetPkg)) {
                AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "Shizuku 强停成功")
                main.post { speak("${targetLabel}关了") }
                return
            }
            AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "Shizuku 强停失败，走最近任务兜底")
        } else if (shizukuAvailable) {
            // 有 Shizuku 但没授权：弹一次授权框，本次仍走最近任务兜底，不等授权结果；
            // 下次关应用时若已授权就走强停。
            ShizukuHelper.requestPermission(ctx)
            AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "Shizuku 未授权，已请求授权，本次走最近任务兜底")
        }
        // 最近任务兜底：要无障碍服务，没连上就到此为止。
        if (svc == null) {
            main.post { speak("关闭应用要用无障碍服务：去设置里把 Jarvis 的无障碍打开") }
            AuditLog.append(ctx, "本地指令", "关闭$name", "无障碍服务未连接")
            return
        }
        try {
            svc.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_RECENTS)
        } catch (_: Exception) {
            main.post { speak("最近任务没打开，系统拒绝了这个动作") }
            return
        }
        // 等最近任务界面真正成为前台（SystemUI 或桌面启动器），最多等 2 秒；
        // 没等到就不乱划——在原应用里上滑会变成翻页，不是关应用。
        var inRecents = false
        for (i in 0 until 10) {
            Thread.sleep(200)
            val fg = svc.foregroundPackage()
            if (fg.contains("systemui") || fg.endsWith(".launcher")) { inRecents = true; break }
        }
        if (!inRecents) {
            main.post { speak("最近任务界面没等到，再试一次") }
            AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "最近任务界面未出现")
            return
        }
        // 最近任务刚出现时卡片可能还没铺好（入场动画），先等一拍再找卡片；
        // 一次没找到不立刻认输，晚 800ms 再找一次。两次都用精确匹配，关错风险不变。
        Thread.sleep(600)
        var dispatched = false
        var dispatchReason = ""
        for (attempt in 0 until 2) {
            val latch = java.util.concurrent.CountDownLatch(1)
            svc.dismissRecentCard(targetLabel) { ok, reason ->
                dispatched = ok; dispatchReason = reason; latch.countDown()
            }
            latch.await(3, java.util.concurrent.TimeUnit.SECONDS)
            if (dispatched || dispatchReason != "not_found") break
            if (attempt == 0) Thread.sleep(800)
        }
        if (!dispatched) {
            // 卡片没找到：先看目标是不是正以前台小窗开着——小窗应用在 Flyme 最近任务里
            // 没有卡片，划卡片那条路走不通，但应用本身在运行，“可能没在后台运行”是错话。
            // 是小窗就直接点小窗的关闭钮；不是前台应用才如实说没找到。
            if (dispatchReason == "not_found") {
                val fg = try { svc.foregroundPackage() } catch (_: Exception) { "" }
                if (fg == targetPkg) {
                    AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "最近任务无卡片，目标正以前台小窗开着，改点小窗关闭钮")
                    val latch2 = java.util.concurrent.CountDownLatch(1)
                    var closed = false
                    var closeReason = ""
                    svc.tryCloseSmallWindow { ok, reason ->
                        closed = ok; closeReason = reason; latch2.countDown()
                    }
                    latch2.await(3, java.util.concurrent.TimeUnit.SECONDS)
                    Thread.sleep(600)
                    val goneNow = try { svc.foregroundPackage() } catch (_: Exception) { "" } != targetPkg
                    if (closed && goneNow) {
                        AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "小窗关闭钮已点，应用已不在前台")
                        main.post { speak("${targetLabel}关了") }
                        return
                    }
                    AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "小窗关闭失败：$closeReason")
                    main.post { speak("${targetLabel}正以前台小窗开着，最近任务里没有它的卡片，关不掉；Shizuku 授权后强停能关") }
                    return
                }
            }
            val why = when (dispatchReason) {
                "not_found" -> "最近任务里没找到${targetLabel}，可能它没在后台运行"
                "no_window" -> "最近任务界面没拿到，系统正忙"
                "cancelled" -> "手势被系统取消了，没关成"
                else -> "手势没发出去，系统正忙，没关成"
            }
            AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "失败：$dispatchReason")
            if (dispatchReason == "not_found") {
                val seen = svc.recentCardLabels()
                AuditLog.append(ctx, "本地指令", "关闭$targetLabel", "最近任务卡片标题：${seen.joinToString("、").ifEmpty { "(一个都没读到)" }}")
            }
            main.post { speak(why) }
            return
        }
        // 划后复查：卡片还在就是没划掉，如实说，不装成功。
        Thread.sleep(600)
        val gone = !svc.hasTextNode(targetLabel)
        AuditLog.append(ctx, "本地指令", "关闭$targetLabel", if (gone) "卡片已划掉" else "划后卡片仍在")
        main.post {
            speak(
                if (gone) "${targetLabel}关了"
                else "划了一下但${targetLabel}的卡片还在，系统可能没放行，可以手动划掉"
            )
        }
    }

    // C11：拨号结果（Andee DeviceCommsController.dial 同款三分法：直接拨打 /
    // 只开拨号盘 / 失败）。调用方必须按实际结果播报，不许没核对就说"填好了"。
    private enum class DialKind { DIALED, DIALER_ONLY, FAILED }
    private data class DialOutcome(val kind: DialKind, val number: String?, val reason: String?) {
        fun describe(): String = when (kind) {
            DialKind.DIALED -> "已直接拨打${number ?: ""}"
            DialKind.DIALER_ONLY -> "只打开了拨号盘${number?.let { "（$it）" } ?: ""}"
            DialKind.FAILED -> "失败：${reason ?: "未知原因"}"
        }
        /** who：主语，如"给妈妈打"；留空时按"拨号盘"这件事本身说。 */
        fun speakText(who: String): String = when (kind) {
            DialKind.DIALED -> if (who.isBlank()) "正在拨打" else "$who，正在拨打"
            DialKind.DIALER_ONLY -> if (who.isBlank()) "拨号盘已打开，点一下拨出" else "$who，拨号盘已打开，点一下拨出"
            DialKind.FAILED -> if (who.isBlank()) "没打成：${reason ?: "未知原因"}" else "$who，没打成：${reason ?: "未知原因"}"
        }
    }

    /**
     * C11：打电话。优先 ACTION_CALL 直接拨打（Andee dial(direct=true)）；
     * 没号码、或没给 CALL_PHONE 权限时才回落 ACTION_DIAL 只开拨号盘；
     * L17：通话中不新起拨号。返回实际结果，由调用方按结果播报。
     */
    private fun dial(ctx: Context, number: String?): DialOutcome {
        if (CallState.isInCall()) {
            return DialOutcome(DialKind.FAILED, number, "正在通话中，先挂断再打")
        }
        val hasCallPermission = ContextCompat.checkSelfPermission(ctx, Manifest.permission.CALL_PHONE) ==
            PackageManager.PERMISSION_GRANTED
        if (!number.isNullOrBlank() && hasCallPermission) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(intent)
                return DialOutcome(DialKind.DIALED, number, null)
            } catch (e: SecurityException) {
                // 权限在检查后被收回：回落到只开拨号盘，不谎报已拨打。
            } catch (e: Exception) {
                return DialOutcome(DialKind.FAILED, number, e.message ?: e.javaClass.simpleName)
            }
        }
        // 没号码（只说"打电话"）、或没给 CALL_PHONE 权限：只开拨号盘，如实说只开了盘。
        return try {
            val intent = Intent(Intent.ACTION_DIAL).apply {
                if (!number.isNullOrBlank()) data = Uri.parse("tel:$number")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            ctx.startActivity(intent)
            DialOutcome(DialKind.DIALER_ONLY, number, null)
        } catch (e: Exception) {
            DialOutcome(DialKind.FAILED, number, "拨号盘没打开：${e.message ?: e.javaClass.simpleName}")
        }
    }
}
