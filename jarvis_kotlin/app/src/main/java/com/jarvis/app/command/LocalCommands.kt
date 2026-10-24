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
        if (QUESTION_HINTS.any { t.contains(it) }) return false
        if (TASK_SHAPED.containsMatchIn(t)) return false

        // 批十三：系统功能（手电筒/截屏/锁屏等）不是应用，先于「打开应用」拦截
        if (DeviceCommands.tryHandle(ctx, t, speak)) return true

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
        val exactCount = matches.count { it.display == name }
        val pick = matches.first()
        main.post {
            openDialer(ctx, pick.number)
            AuditLog.append(ctx, "本地指令", "打电话给${pick.display}", "已跳拨号盘（${pick.numberType}）")
            val extra = if (exactCount > 1) "，通讯录里有$exactCount 个叫$name 的，先用第一个" else ""
            speak(
                (if (pick.display == name) "给${name}打，号码已经填好了，点一下就拨"
                else "找到${pick.display}，号码已经填好了，点一下就拨") + extra
            )
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
        var best: Pair<String, String>? = null // (label, package)
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
        } catch (_: Exception) {
        }
        val found = best
        if (found == null) {
            return "没找到叫${name}的应用"
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
