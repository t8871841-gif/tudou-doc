package com.jarvis.app.command

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.ReminderScheduler
import java.util.Calendar

/**
 * 第二梯队批五新增：自然语言定时提醒——"10分钟后提醒我喝水""晚上10点提醒我吃药"这类，
 * 本地正则直接解析排进 [ReminderScheduler]，不用掉进大模型再反过来调用工具。
 *
 * 只认两种句式，覆盖"相对时间"和"今天内的钟点时间"这两类最常见的说法：
 * ①相对时间：「N分钟/小时后提醒我XXX」「过N分钟提醒我XXX」
 * ②钟点时间：「[今天/明天][早上/上午/中午/下午/晚上]H点[M分|半]提醒我XXX」
 * 没覆盖的说法（"三天后""下周一"这类更复杂的相对表达）如实不命中，交还给聊天流程
 * ——不强行瞎解析出一个错误时间，那比"没听懂"更糟。
 */
object ReminderCommands {

    private val RELATIVE = Regex("^(?:过)?(\\d{1,3})\\s*(分钟|小时)(?:后|之后)?提醒我(.+)$")
    private val CLOCK = Regex(
        "^(明天|今天|今晚|明早)?\\s*(早上|上午|中午|下午|晚上|傍晚)?\\s*(\\d{1,2})[点:：](\\d{1,2}|半)?分?\\s*提醒我(.+)$"
    )
    private val CANCEL = Regex("^(?:取消|删掉|删除)(?:所有|全部)?提醒$")

    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit): Boolean {
        val t = text.trim()

        if (CANCEL.matches(t)) {
            ReminderScheduler.cancelAll(ctx)
            AuditLog.append(ctx, "本地指令", "取消全部提醒", "已清空")
            speak("提醒都取消了")
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
            var hour24 = hourRaw.coerceIn(0, 23)
            // 中文钟点口语里"H点"默认指 12 小时制，要靠上下午/晚上词消歧；
            // 没说上下午时，沿用用户常见默认：7 点以下当下午/晚上说的概率更高的不在此猜测范围内，
            // 这里保守处理——没有时段词时，按 24 小时制原样理解（用户说"18点"已经是 24 小时制）。
            if (hourRaw in 1..11) {
                when (periodWord) {
                    "下午", "晚上", "傍晚" -> hour24 = hourRaw + 12
                    "中午" -> hour24 = if (hourRaw == 12) 12 else hourRaw + 12
                    "早上", "上午" -> hour24 = hourRaw
                    else -> {} // 没说时段，按原数字理解（用户自己说清楚了就用哪个）
                }
            }
            val cal = Calendar.getInstance().apply {
                set(Calendar.HOUR_OF_DAY, hour24); set(Calendar.MINUTE, minute)
                set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            }
            when (dayWord) {
                "明天", "明早" -> cal.add(Calendar.DAY_OF_YEAR, 1)
                else -> if (cal.timeInMillis <= System.currentTimeMillis()) {
                    // "今天"或没说日期，但算出来的时间已经过了——按明天理解，而不是排一个
                    // 已经过去的提醒（ReminderScheduler.schedule 对过去时间的行为未定义，
                    // 不要指望它自己处理）。
                    cal.add(Calendar.DAY_OF_YEAR, 1)
                }
            }
            scheduleAndReport(ctx, cal.timeInMillis, content, speak, confirmDay = dayWord.isBlank())
            return true
        }

        return false
    }

    private fun scheduleAndReport(ctx: Context, atMs: Long, content: String, speak: (String) -> Unit, confirmDay: Boolean = false) {
        val id = ReminderScheduler.nextAdHocId(ctx)
        val ok = ReminderScheduler.schedule(ctx, id, atMs, "Jarvis 提醒", content, "reminder")
        if (ok) {
            val cal = Calendar.getInstance().apply { timeInMillis = atMs }
            val timeText = "%02d:%02d".format(cal.get(Calendar.HOUR_OF_DAY), cal.get(Calendar.MINUTE))
            val dayHint = if (confirmDay && cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)) "明天" else ""
            AuditLog.append(ctx, "本地指令", "定时提醒：$content", "已排在 $dayHint$timeText")
            speak("好，$dayHint$timeText 提醒你$content")
        } else {
            AuditLog.append(ctx, "本地指令", "定时提醒：$content", "排不上（闹钟权限？）")
            speak("这条提醒没排上，可能是闹钟权限的问题")
        }
    }
}
