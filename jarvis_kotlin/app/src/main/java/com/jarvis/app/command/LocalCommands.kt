package com.jarvis.app.command

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import androidx.core.content.ContextCompat
import com.jarvis.app.AuditLog

/**
 * 本地指令层（批八，jk-v12 真机反馈）：「打电话给 X」「打开 X」这类执行类指令在本地
 * 直接办，不再掉进大模型聊天被学舌反问（"打电话给老婆吗？"）、也不能跳转。
 *
 * 命中（含失败但已给明确回报）返回 true；没命中返回 false，上层走原流程
 * （技能 → 视觉意图 → 本地模型/Agnes 聊天）。
 *
 * 防误伤：句子带提问词（吗/？/是什么/为什么/怎么/如何）一律不拦截——那是提问不是指令。
 * 查询都是本地快速查询（通讯录 LIKE、已装应用列表），在调用方线程同步执行，一次
 * 指令一查，真机量级毫秒级，不单开线程。
 */
object LocalCommands {

    private val QUESTION_HINTS = listOf("吗", "？", "?", "是什么", "为什么", "怎么", "如何")

    private val CALL_WITH_NAME = Regex("^(?:打电话给|打个电话给|拨电话给|拨打|呼叫)\\s*(.+)$")
    private val CALL_GIVE = Regex("^给\\s*(.+?)\\s*(?:打电话|打个电话|拨电话)$")
    private val CALL_BARE = Regex("^(?:打电话|拨电话|拨号)$")
    private val OPEN_APP = Regex("^(?:打开|开启|启动|进入)\\s*(.+)$")

    // 批九守卫：句中带「给<人>发信息/发消息/发微信」的多步任务句式不是单纯开应用，
    // 不能在这里按应用名吞掉（"打开微信给桑田发信息" 会被误当成开「微信给桑田发信息」），
    // 放行给 JarvisAgent 的任务路由（task/TaskLoop）。
    private val TASK_SHAPED = Regex("给.{1,16}?(发信息|发消息|发微信|发短信|发送)")

    /** speak 是结果回报通道（语音路径走 Agent.speakReply，打字路径走加气泡）。 */
    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (QUESTION_HINTS.any { t.contains(it) }) return false
        if (TASK_SHAPED.containsMatchIn(t)) return false

        if (CALL_BARE.matches(t)) {
            openDialer(ctx, null)
            AuditLog.append(ctx, "本地指令", "打电话（无名）", "已跳拨号盘")
            speak("拨号盘打开了")
            return true
        }
        val callName = CALL_WITH_NAME.matchEntire(t)?.groupValues?.get(1)
            ?: CALL_GIVE.matchEntire(t)?.groupValues?.get(1)
        if (callName != null) {
            callContact(ctx, cleanName(callName), speak)
            return true
        }
        val appName = OPEN_APP.matchEntire(t)?.groupValues?.get(1)
        if (appName != null) {
            openApp(ctx, cleanName(appName), speak)
            return true
        }
        return false
    }

    private fun cleanName(raw: String): String =
        raw.trim()
            .removePrefix("一下")
            .trim()
            .trimEnd('。', '！', '!', ' ', '吧', '啊', '啦', '呀', '呢', '嘛')

    private fun callContact(ctx: Context, name: String, speak: (String) -> Unit) {
        if (name.isBlank()) {
            speak("给谁打？说个名字，我查通讯录")
            return
        }
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            speak("打电话要先给通讯录权限：打开设置，在功能自检页点「申请通讯录权限」")
            return
        }
        val matches = queryContacts(ctx, name)
        if (matches.isEmpty()) {
            speak("通讯录里没找到$name")
            return
        }
        val (display, number) = matches.first()
        openDialer(ctx, number)
        AuditLog.append(ctx, "本地指令", "打电话给$display", "已跳拨号盘")
        speak(
            if (display == name) "给${name}打，号码已经填好了，点一下就拨"
            else "找到$display，号码已经填好了，点一下就拨"
        )
    }

    /** 返回 (显示名, 号码)：精确同名优先，其次包含匹配，号码做过空白清理。 */
    private fun queryContacts(ctx: Context, name: String): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        try {
            ctx.contentResolver.query(
                ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                arrayOf(
                    ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                    ContactsContract.CommonDataKinds.Phone.NUMBER
                ),
                "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?",
                arrayOf("%$name%"),
                null
            )?.use { c ->
                val ni = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
                val pi = c.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                while (c.moveToNext()) {
                    val d = c.getString(ni)?.trim().orEmpty()
                    val n = c.getString(pi)?.replace(" ", "")?.replace("-", "")?.trim().orEmpty()
                    if (d.isNotEmpty() && n.isNotEmpty()) out.add(d to n)
                }
            }
        } catch (_: Exception) {
            // 查询异常当没找到处理，上层会给明确回报
        }
        return out.sortedBy { if (it.first == name) 0 else 1 }
    }

    /** 批九：任务循环的 openApp 动作复用同一套按名找应用+拉起逻辑，返回结果描述（不播报）。 */
    fun launchApp(ctx: Context, name: String): String = launchAppInternal(ctx, name.trim())

    private fun openApp(ctx: Context, rawName: String, speak: (String) -> Unit) {
        val name = rawName.removeSuffix("应用").removeSuffix("软件").trim()
        if (name.isBlank()) {
            speak("打开什么？说个应用名")
            return
        }
        speak(launchAppInternal(ctx, name))
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
