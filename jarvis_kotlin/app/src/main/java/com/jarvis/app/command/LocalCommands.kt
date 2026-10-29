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
import java.util.concurrent.Executors

/**
 * 本地指令层（批八起步，这次按复核意见补了三处：正则误伤、主线程卡顿、同名联系人消歧）：
 * 「打电话给 X」「打开 X」这类执行类指令在本地直接办，不再掉进大模型聊天被学舌反问。
 *
 * 命中（含失败但已给明确回报）返回 true；没命中返回 false，上层走原流程
 * （技能 → 视觉意图 → 本地模型/Agnes 聊天）。
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

    private val QUESTION_HINTS = listOf("吗", "？", "?", "是什么", "为什么", "怎么", "如何")

    /** 抓出来的"名字"里出现这些虚词/助词，基本可以断定不是一个人名/应用名，是误套壳的聊天句。 */
    private val NON_NAME_MARKERS = listOf(
        "的", "了", "吧", "呢", "啊", "吗", "在", "要", "想", "觉得", "看看", "听听", "说说",
        "聊", "还是", "是不是", "会不会", "能不能", "应该", "可以", "模式", "状态", "心情",
        "怎么", "为什么", "多少", "干嘛", "干什么"
    )
    private const val MAX_NAME_LEN = 6

    /** 捕获的内容是不是"像"一个真实的人名/应用名——不像就不拦截，交还给原来的聊天流程。 */
    private fun looksLikeLiteralName(s: String): Boolean =
        s.isNotBlank() && s.length <= MAX_NAME_LEN && NON_NAME_MARKERS.none { s.contains(it) }

    private val CALL_WITH_NAME = Regex("^(?:打电话给|打个电话给|拨电话给|拨打|呼叫)\\s*(.+)$")
    private val CALL_GIVE = Regex("^给\\s*(.+?)\\s*(?:打电话|打个电话|拨电话)$")
    private val CALL_BARE = Regex("^(?:打电话|拨电话|拨号)$")
    private val OPEN_APP = Regex("^(?:打开|开启|启动|进入)\\s*(.+)$")
    private val CLOSE_APP = Regex("^(?:关闭|关掉|退出)\\s*(.+)$")
    private val CLOSE_APP_BA = Regex("^把\\s*(.+?)\\s*(?:关了|关掉|关闭)$")

    // 批九守卫：句中带「给<人>发信息/发消息/发微信」的多步任务句式不是单纯开应用，
    // 不能在这里按应用名吞掉（"打开微信给桑田发信息" 会被误当成开「微信给桑田发信息」），
    // 放行给 JarvisAgent 的任务路由（task/TaskLoop）。
    private val TASK_SHAPED = Regex("给.{1,16}?(发信息|发消息|发微信|发短信|发送)")

    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "LocalCommands").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())

    /** speak 是结果回报通道（语音路径走 Agent.speakReply，打字路径走加气泡），在主线程回调。 */
    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false

        // 第一梯队批二：先看有没有待澄清的选择（比如上一句问了"给哪个？"），这句话
        // 优先当作回答来解析；命中就在这里直接执行完，不再往下走别的指令匹配。
        // 没命中（文字既不是取消也配不上任何一项）会被 PendingClarification 自己
        // 清掉状态并返回 false，交还给下面的正常流程继续处理这句话。
        if (PendingClarification.hasPending() && PendingClarification.tryResolve(text)) {
            return true
        }

        if (QUESTION_HINTS.any { t.contains(it) }) return false
        if (TASK_SHAPED.containsMatchIn(t)) return false

        // 批十三：系统功能（手电筒/截屏/锁屏等）不是应用，先于「打开应用」拦截
        if (DeviceCommands.tryHandle(ctx, t, speak)) return true

        // 第二梯队批五：定时提醒句式（"10分钟后提醒我…""晚上10点提醒我…"）优先于
        // 「打开应用」「打电话」判定——这类句子里常常带"我"这种不像应用名/人名的词，
        // 放在后面容易被其他正则抢先误吞。
        if (ReminderCommands.tryHandle(ctx, t, speak)) return true

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
                openDialer(ctx, null)
                AuditLog.append(ctx, "本地指令", "打电话（无名）", "已跳拨号盘")
                main.post { speak("拨号盘打开了") }
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
        return false
    }

    private fun cleanName(raw: String): String =
        raw.trim()
            .removePrefix("一下")
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
                openDialer(ctx, pick.number)
                AuditLog.append(ctx, "本地指令", "打电话给${pick.display}", "已跳拨号盘（${pick.numberType}）")
                speak(if (pick.display == name) "给${name}打，号码已经填好了，点一下就拨"
                      else "找到${pick.display}，号码已经填好了，点一下就拨")
            }
            return
        }
        val top = matches.take(5)
        val options = top.map { hit ->
            PendingClarification.Option(
                label = "${hit.display} 尾号${hit.number.takeLast(4)}（${hit.numberType}）",
                payload = hit.number,
                matchNumberTail = hit.number.takeLast(4)
            )
        }
        main.post {
            PendingClarification.ask(options) { chosen ->
                openDialer(ctx, chosen.payload)
                AuditLog.append(ctx, "本地指令", "打电话（澄清后选定）", chosen.label)
                speak("好，${chosen.label}，号码已经填好了，点一下就拨")
            }
            val listText = top.mapIndexed { i, h -> "第${i + 1}个，${h.display} 尾号${h.number.takeLast(4)}" }.joinToString("；")
            AuditLog.append(ctx, "本地指令", "打电话给$name·需要澄清", "${matches.size}个匹配，已反问")
            speak("通讯录里有${matches.size}个叫$name 的：$listText。你说第几个，或者报号码后几位")
        }
    }

    private data class ContactHit(val display: String, val number: String, val contactId: String, val numberType: String)

    /**
     * 返回每个联系人一条（同一个人有多个号码时，优先选"手机"号），精确同名的排前面。
     * 修复（复核发现）：原来按"行"去重，同一个人有住宅/工作/手机好几个号码会出现好几条，
     * 永远拿 ContactsProvider 吐出来的第一条（不一定是手机号）；现在按 CONTACT_ID 分组、
     * 组内优先手机号，真正按"人"去重，而不是按"号码行"去重。
     */
    private fun queryContacts(ctx: Context, name: String): List<ContactHit> {
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
    fun launchApp(ctx: Context, name: String): String = launchAppInternal(ctx, name.trim())

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
        "支付宝" to "com.eg.android.AlipayClient",
        "alipay" to "com.eg.android.AlipayClient",
        "淘宝" to "com.taobao.taobao",
        "京东" to "com.jingdong.app.mall",
        "抖音" to "com.ss.android.ugc.aweme",
        "快手" to "com.smile.gifmaker",
        "哔哩哔哩" to "tv.danmaku.bili",
        "b站" to "tv.danmaku.bili",
        "高德地图" to "com.autonavi.minimap",
        "高德" to "com.autonavi.minimap",
        "百度地图" to "com.baidu.BaiduMap",
        "设置" to "com.android.settings",
        "电话" to "com.android.dialer",
        "短信" to "com.android.messaging",
        "相机" to "com.android.camera",
        "相册" to "com.android.gallery3d"
    )

    // ---------- 打开应用：tryHandle 在 io 线程调进来，枚举/拉起在本线程，回报回主线程 ----------

    private fun openAppBg(ctx: Context, name: String, speak: (String) -> Unit) {
        if (name.isBlank()) {
            main.post { speak("打开什么？说个应用名") }
            return
        }
        val result = launchAppInternal(ctx, name)
        main.post { speak(result) }
    }

    /** 按应用名找已装应用并拉起，返回一句结果描述；成功/失败都写审计。 */
    private fun launchAppInternal(ctx: Context, name: String): String {
        val pm = ctx.packageManager
        // 2026-10-24 跨应用修复第 1 件：按名字枚举应用时好时坏（21:56 任务循环里
        // “微信/WeChat”两次没找到，21:58 同一函数直接说“打开微信”却成功了）——
        // 常用应用先按包名直查，绕过枚举整条路；直查失败（没装/没启动界面）
        // 再回落到枚举。包名都是公开的固定值，不是用户隐私。
        val direct = WELL_KNOWN_APPS[name.trim().lowercase()]
        if (direct != null) {
            val intent = pm.getLaunchIntentForPackage(direct)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    ctx.startActivity(intent)
                    AuditLog.append(ctx, "本地指令", "打开${name.trim()}", "已启动（包名直查）")
                    return "正在打开${name.trim()}"
                } catch (_: Exception) {
                    // 拉起失败时不直接判死，继续走枚举（可能装了分身/改名版）
                }
            }
        }
        var best: Pair<String, String>? = null // (label, package)
        var enumError: String? = null
        try {
            for (app in pm.getInstalledApplications(0)) {
                val label = pm.getApplicationLabel(app).toString()
                if (label == name) {
                    best = label to app.packageName
                    break
                }
                if (best == null && label.length >= 2 &&
                    (label.contains(name) || (name.length >= 3 && name.contains(label)))
                ) {
                    best = label to app.packageName
                }
            }
        } catch (e: Exception) {
            // 旧代码把枚举异常静默吞成“没找到”，下次取证分不清是真没装还是
            // 枚举本身炸了；现在把异常原因带进失败文案。
            enumError = e.message ?: e.javaClass.simpleName
        }
        val found = best
        if (found == null) {
            return if (enumError != null) "找应用时出错：$enumError" else "没找到叫${name}的应用"
        }
        val intent = pm.getLaunchIntentForPackage(found.second)
        if (intent == null) {
            return "${found.first}没有能打开的界面"
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            return "打开${found.first}失败了"
        }
        AuditLog.append(ctx, "本地指令", "打开${found.first}", "已启动")
        return "正在打开${found.first}"

    }

    // ---------- 关闭应用：批二十四新增——去最近任务划掉目标卡片 ----------

    private fun closeAppBg(ctx: Context, name: String, speak: (String) -> Unit) {
        if (name.isBlank()) {
            main.post { speak("关闭什么？说个应用名") }
            return
        }
        val svc = com.jarvis.app.JarvisAccessibilityService.instance
        if (svc == null) {
            main.post { speak("关闭应用要用无障碍服务：去设置里把 Jarvis 的无障碍打开") }
            AuditLog.append(ctx, "本地指令", "关闭$name", "无障碍服务未连接")
            return
        }
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

    private fun openDialer(ctx: Context, number: String?) {
        val intent = Intent(Intent.ACTION_DIAL).apply {
            if (!number.isNullOrBlank()) data = Uri.parse("tel:$number")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        try {
            ctx.startActivity(intent)
        } catch (_: Exception) {
            // 连拨号盘都拉不起来时静默（上层回报语已说明意图），真机基本不会走到
        }
    }
}
