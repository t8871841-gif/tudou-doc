package com.jarvis.app.command

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.ReminderScheduler
import com.jarvis.app.config.Scheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 第二梯队批五新增：自然语言定时提醒——"10分钟后提醒我喝水""晚上10点提醒我吃药"这类，
 * 本地正则直接解析排进 [ReminderScheduler]，不用掉进大模型再反过来调用工具。
 *
 * 只认两种句式，覆盖"相对时间"和"今天内的钟点时间"这两类最常见的说法：
 * ①相对时间：「N分钟/小时后提醒我XXX」「过N分钟提醒我XXX」
 * ②钟点时间：「[今天/明天][早上/上午/中午/下午/晚上]H点[M分|半]提醒我XXX」
 * 没覆盖的说法（"三天后""下周一"这类更复杂的相对表达）如实不命中，交还给聊天流程
 * ——不强行瞎解析出一个错误时间，那比"没听懂"更糟。
 *
 * 提醒按条管理（M31）：每条提醒排上时都有编号（见 [Scheduler.armAlarm] 的播报，
 * 闹钟同号段），"列出提醒"看编号，"取消提醒 <编号>"/"完成提醒 <编号>"只动那一条；
 * 没带编号的"取消提醒"不再一句话删全部——先列出、让用户指定哪一条
 * （走 [PendingClarification]，60 秒内说"第几个"/内容/编号都行），
 * 只有明确说"取消全部提醒"/"取消所有提醒"才删全部。
 */
object ReminderCommands {

    private val RELATIVE = Regex("^(?:过)?(\\d{1,3})\\s*(分钟|小时)(?:后|之后)?提醒我(.+)$")
    private val CLOCK = Regex(
        "^(明天|今天|今晚|明早)?\\s*(早上|上午|中午|下午|晚上|傍晚)?\\s*(\\d{1,2})[点:：](\\d{1,2}|半)?分?\\s*提醒我(.+)$"
    )
    private val LIST = Regex("^(?:列出|查看|看看|显示)?(?:我|我的)?(?:有?什么|还有哪些|有哪些)?提醒(?:列表)?$")
    private val CANCEL_ID = Regex("^(?:取消|删掉|删除|去掉)(?:提醒)?\\s*第?\\s*(\\d+)\\s*(?:号|条)?(?:提醒)?$")
    private val COMPLETE_ID = Regex("^(?:完成|办完|做完|已经完成|已完成)(?:提醒)?\\s*第?\\s*(\\d+)\\s*(?:号|条)?(?:提醒)?$")
    private val CANCEL_ALL = Regex("^(?:取消|删掉|删除)(?:所有|全部)提醒$")
    private val CANCEL_BARE = Regex("^(?:取消|删掉|删除)提醒$")

    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit): Boolean {
        val t = text.trim()

        if (LIST.matches(t)) {
            listAndSpeak(ctx, speak)
            return true
        }

        CANCEL_ID.find(t)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return@let
            cancelOneById(ctx, id, speak, doneWord = "取消")
            return true
        }

        COMPLETE_ID.find(t)?.let { m ->
            val id = m.groupValues[1].toIntOrNull() ?: return@let
            cancelOneById(ctx, id, speak, doneWord = "完成")
            return true
        }

        if (CANCEL_ALL.matches(t)) {
            val count = ReminderScheduler.list(ctx).size
            ReminderScheduler.cancelAll(ctx)
            AuditLog.append(ctx, "本地指令", "取消全部提醒", "已清空 $count 条")
            speak(if (count > 0) "$count 条提醒都取消了" else "现在本来就没有排着的提醒")
            return true
        }

        if (CANCEL_BARE.matches(t)) {
            askWhichToCancel(ctx, speak)
            return true
        }

        RELATIVE.find(t)?.let { m ->
            val amount = m.groupValues[1].toIntOrNull() ?: return@let
            val unit = m.groupValues[2]
            val content = m.groupValues[3].trim()
            if (content.isBlank()) return@let
            val deltaMs = if (unit == "小时") amount * 3_600_000L else amount * 60_000L
            // 单条提醒设个上限（48 小时），太离谱的数字（比如口误说成 999 分钟）不悄悄排一个
            // 两周后才响的提醒，直接说一声让用户确认说法对不对。
            if (deltaMs > 48L * 3_600_000L) {
                speak("这个时间有点太远了（超过 48 小时），要排这么久以后的提醒，换个说法分开说清楚日期")
                return true
            }
            val at = System.currentTimeMillis() + deltaMs
            scheduleAndReport(ctx, at, content, speak)
            return true
        }

        CLOCK.find(t)?.let { m ->
            val dayWord = m.groupValues[1]
            val periodWord = m.groupValues[2]
            val hourRaw = m.groupValues[3].toIntOrNull() ?: return@let
            val minuteRaw = m.groupValues[4]
            val content = m.groupValues[5].trim()
            if (content.isBlank()) return@let
            val minute = when {
                minuteRaw.isBlank() -> 0
                minuteRaw == "半" -> 30
                else -> minuteRaw.toIntOrNull() ?: 0
            }
            // C12：非法钟点不静默 clamp 到 23——直接拒掉，让用户重说。
            if (hourRaw !in 0..23) {
                speak("这个钟点不对，$hourRaw 点不存在，你再说一遍")
                return true
            }
            var hour24 = hourRaw
            // 中文钟点口语里"H点"默认指 12 小时制，要靠上下午/晚上词消歧；
            // 没说上下午时，沿用用户常见默认：7 点以下当下午/晚上说的概率更高的不在此猜测范围内，
            // 这里保守处理——没有时段词时，按 24 小时制原样理解（用户说"18点"已经是 24 小时制）。
            if (hourRaw in 1..11) {
                when (periodWord) {
                    "下午", "晚上", "傍晚" -> hour24 = hourRaw + 12
                    // C12："中午 11 点"是 11 点不是 23 点——中午只有 11/12/1 三个说法；
                    // 11→11、12→12、1→13，2..10 配"中午"是口误，不加 12。
                    "中午" -> hour24 = if (hourRaw == 11 || hourRaw == 12) hourRaw else if (hourRaw == 1) 13 else hourRaw
                    "早上", "上午" -> hour24 = hourRaw
                    else -> {} // 没说时段，按原数字理解（用户自己说清楚了就用哪个）
                }
            }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour24); set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            // C12：算出来的时间已过、顺延到明天时，播报里必须说"明天"，不能悄悄顺延。
            var rolledToTomorrow = false
            when (dayWord) {
                "明天", "明早" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                else -> if (cal.timeInMillis <= System.currentTimeMillis()) {
                    // "今天"或没说日期，但算出来的时间已经过了——按明天理解，而不是排一个
                    // 已经过去的提醒（ReminderScheduler.schedule 对过去时间的行为未定义，
                    // 不要指望它自己处理）。
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                    rolledToTomorrow = true
                }
            }
            scheduleAndReport(ctx, cal.timeInMillis, content, speak, confirmDay = dayWord.isBlank() || rolledToTomorrow)
            return true
        }

        return false
    }

    // ------------------------------------------------------------------
    // 按条管理：列出 / 按编号取消 / 按编号完成 / 没编号时先问再动
    // ------------------------------------------------------------------

    /** 一条提醒播报用的样子：「闹钟 08:00 起床」/「10:00 喝水」。 */
    private fun describeRow(row: Map<String, Any?>): String {
        val body = (row["body"] as? String).orEmpty()
        val atMs = (row["atMs"] as? Long) ?: 0L
        val prefix = if ((row["title"] as? String) == "Jarvis 闹钟") "闹钟 " else ""
        return "$prefix${fmtTime(atMs)} $body"
    }

    private fun fmtTime(atMs: Long): String {
        val cal = Calendar.getInstance().apply { timeInMillis = atMs }
        val today = Calendar.getInstance()
        val tomorrow = (Calendar.getInstance() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val day = when {
            cal.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == today.get(Calendar.YEAR) -> "今天"
            cal.get(Calendar.DAY_OF_YEAR) == tomorrow.get(Calendar.DAY_OF_YEAR) &&
                cal.get(Calendar.YEAR) == tomorrow.get(Calendar.YEAR) -> "明天"
            else -> SimpleDateFormat("M月d日", Locale.US).format(Date(atMs))
        }
        return "$day " + SimpleDateFormat("HH:mm", Locale.US).format(Date(atMs))
    }

    private fun listAndSpeak(ctx: Context, speak: (String) -> Unit) {
        val items = Scheduler.listReminders(ctx)
        if (items.isEmpty()) {
            speak("现在没有排着的提醒")
            return
        }
        val shown = items.take(5)
        val lines = shown.mapIndexed { i, row ->
            "第${i + 1}个，编号${row["id"]}，${describeRow(row)}"
        }.joinToString("；")
        val more = if (items.size > 5) "，还有 ${items.size - 5} 条" else ""
        AuditLog.append(ctx, "本地指令", "列出提醒", "${items.size} 条")
        speak("现在有 ${items.size} 条提醒：$lines$more")
    }

    /** 按编号只动一条：先查到内容再删，删完播报删的是哪条；编号不存在如实说。 */
    private fun cancelOneById(ctx: Context, id: Int, speak: (String) -> Unit, doneWord: String) {
        val row = Scheduler.listReminders(ctx).firstOrNull { it["id"] == id }
        if (row == null) {
            speak("没找到编号 $id 的提醒，可能是已经响过或者编号说错了，先说「列出提醒」看一眼")
            return
        }
        val desc = describeRow(row)
        Scheduler.cancelReminder(ctx, id)
        AuditLog.append(ctx, "本地指令", "${doneWord}提醒：$desc", "编号 $id 已删")
        speak("好，${doneWord}了：$desc")
    }

    /**
     * 没带编号的"取消提醒"：先列出、让用户指定哪一条，不直接删全部。
     * 走 [PendingClarification]：60 秒内说"第几个"/内容/编号都行；想全删就说
     * "取消全部提醒"。下一句话由 LocalCommands 先送去 PendingClarification
     * 匹配，命中了直接在这里的回调里删，不再往下走。
     */
    private fun askWhichToCancel(ctx: Context, speak: (String) -> Unit) {
        val items = Scheduler.listReminders(ctx)
        if (items.isEmpty()) {
            speak("现在没有排着的提醒")
            return
        }
        val shown = items.take(5)
        val lines = shown.mapIndexed { i, row ->
            "第${i + 1}个，${describeRow(row)}"
        }.joinToString("；")
        val more = if (items.size > 5) "，还有 ${items.size - 5} 条" else ""
        val options = items.map { row ->
            PendingClarification.Option(
                label = describeRow(row),
                payload = "${row["id"]}",
                matchNumberTail = "${row["id"]}",
            )
        }
        PendingClarification.ask(options) { opt ->
            val id = opt.payload.toIntOrNull() ?: return@ask
            cancelOneById(ctx, id, speak, doneWord = "取消")
        }
        speak("现在有 ${items.size} 条提醒：$lines$more。说「第几个」或者念出内容、编号，我就取消那一条；想全删就说「取消全部提醒」")
    }

    private fun scheduleAndReport(ctx: Context, atMs: Long, content: String, speak: (String) -> Unit, confirmDay: Boolean = false) {
        val id = ReminderScheduler.nextAdHocId(ctx)
        val ok = ReminderScheduler.schedule(ctx, id, atMs, "Jarvis 提醒", content, "reminder")
        if (ok) {
            val cal = Calendar.getInstance().apply { timeInMillis = atMs }
            val timeText = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val dayHint = if (confirmDay && cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) "明天" else ""
            AuditLog.append(ctx, "本地指令", "定时提醒：$content", "已排在 $dayHint$timeText（编号 $id）")
            speak("好，$dayHint$timeText 提醒你$content，编号 $id")
        } else {
            AuditLog.append(ctx, "本地指令", "定时提醒：$content", "排不上（闹钟权限？）")
            speak("这条提醒没排上，可能是闹钟权限的问题")
        }
    }
}
