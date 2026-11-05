/*
 * Copyright 2026 KUAFUAI
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Calendar logic ported from Andee:
 * net.kuafuai.andee.device.DeviceCommsController.calendarEvents /
 * addCalendarEvent / primaryCalendarId
 * (~/workspace/tudou-git/andee/app/src/main/java/net/kuafuai/andee/device/DeviceCommsController.kt).
 * Alarm entry points wrap Jarvis's own [com.jarvis.app.config.Scheduler],
 * which itself wraps [com.jarvis.app.ReminderScheduler] — one timer
 * mechanism, not a second one.
 *
 * Wiring note: this file only exposes [tryHandle] with the same signature as
 * [ReminderCommands.tryHandle]; the one-line call from LocalCommands is
 * intentionally NOT added here (that file belongs to another lane).
 */

package com.jarvis.app.command

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import com.jarvis.app.AuditLog
import com.jarvis.app.config.Scheduler
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 日历 + 闹钟的本地命令入口。
 *
 * 只认三类句式（没覆盖的如实不命中，交还给聊天流程，不强行瞎解析）：
 * ①读日程：「今天有什么安排」「明天有什么安排」「接下来三天有什么安排」「查日程」
 * ②加日历：「明天下午3点开会记到日历」「把开会记到日历，明天下午3点」
 * ③设闹钟：「设闹钟，明天早上7点」「明天早上7点半闹钟」「7点叫我起床」
 *
 * 日历读写走系统 CalendarContract；运行时权限没给就如实报"没给日历权限"，
 * 不编数据、不静默失败。
 */
object CalendarCommands {

    // ---- 读日程 ------------------------------------------------------
    private val READ = Regex(
        "^(?:查|看|看看|帮我查一下|帮我看一下)?\\s*" +
            "(今天|明天|后天|大后天|接下来[一二三四五六七\\d]+天|未来[一二三四五六七\\d]+天|这周|本周)?" +
            "\\s*(?:的)?(?:有?什么)?(?:安排|日程|行程)(?:吗|么)?$"
    )

    // ---- 加日历 ------------------------------------------------------
    // 时间在前：「明天下午3点开会记到日历」「明天上午10点到11点半开会记到日历」
    private val ADD_TIME_FIRST = Regex(
        "^(今天|明天|后天|大后天)?\\s*(早上|上午|中午|下午|晚上|傍晚)?\\s*" +
            "(\\d{1,2})[点:：](\\d{1,2}|半)?分?" +
            "(?:\\s*到\\s*(\\d{1,2})[点:：](\\d{1,2}|半)?分?)?" +
            "\\s*(.+?)(?:记到|加到|写到|放到|存到)(?:日历|日程)$"
    )
    // 标题在前：「把开会记到日历，明天下午3点」
    private val ADD_TITLE_FIRST = Regex(
        "^(?:把)?(.+?)(?:记到|加到|写到|放到|存到)(?:日历|日程)[，、]?(.*)$"
    )

    // ---- 设闹钟 ------------------------------------------------------
    private val ALARM_SET = Regex("^(?:设|定|帮我设|帮我定)?(?:一个|个)?闹钟[，、]?(.*)$")
    private val ALARM_TIME_FIRST = Regex("^(.*\\d{1,2}[点:：].*?)闹钟$")
    private val ALARM_WAKE = Regex("^(.*?)(?:叫我|喊我)(.+)$")

    // 在一段话里找钟点：「明天下午3点」「早上7点半」「18点」
    private val TIME_IN_TEXT = Regex(
        "(今天|明天|后天|大后天|今晚|明早)?\\s*(早上|上午|中午|下午|晚上|傍晚)?\\s*" +
            "(\\d{1,2})[点:：](\\d{1,2}|半)?分?"
    )
    private val END_IN_TEXT = Regex("到\\s*(\\d{1,2})[点:：](\\d{1,2}|半)?分?")

    private val CN_NUM = mapOf(
        '一' to 1, '二' to 2, '三' to 3, '四' to 4, '五' to 5, '六' to 6, '七' to 7,
    )

    private data class SpokenTime(
        val atMs: Long,
        val endMs: Long?,
        /** 原句去掉时间片段后剩下的文字（当标题/标签用）。 */
        val rest: String,
        /** 没写日期但时间已过、顺延到明天时为 true（播报里要说"明天"）。 */
        val rolledTomorrow: Boolean,
    )

    private data class CalEvent(
        val title: String,
        val beginMs: Long,
        val endMs: Long,
        val location: String,
    )

    fun tryHandle(ctx: Context, text: String, speak: (String) -> Unit): Boolean {
        val t = text.trim()

        READ.find(t)?.let { m ->
            readSchedule(ctx, m.groupValues[1], speak)
            return true
        }

        ADD_TIME_FIRST.find(t)?.let { m ->
            val dayWord = m.groupValues[1]
            val periodWord = m.groupValues[2]
            val hourRaw = m.groupValues[3].toIntOrNull() ?: return@let
            val minute = parseMinute(m.groupValues[4])
            val title = m.groupValues[7].trim()
            if (title.isBlank()) return@let
            val endHourRaw = m.groupValues[5].toIntOrNull()
            val endMinute = if (m.groupValues[5].isBlank()) null else parseMinute(m.groupValues[6])
            addEvent(ctx, title, dayWord, periodWord, hourRaw, minute, endHourRaw, endMinute, speak)
            return true
        }

        ADD_TITLE_FIRST.find(t)?.let { m ->
            val title = m.groupValues[1].trim()
            val tail = m.groupValues[2].trim()
            if (title.isBlank()) return@let
            val st = parseSpokenTime(tail) ?: parseSpokenTime(t)
            if (st == null) {
                speak("没听清几点，换个说法，比如「明天下午3点${title}记到日历」")
                return true
            }
            // 时间在句尾时，标题就是第 1 组；时间在句中时，parseSpokenTime 抠掉
            // 时间剩下的文字还带着"把…记到日历"的壳，不能当标题，用第 1 组。
            val atTitle = if (tail.isNotBlank()) st.rest.ifBlank { title } else title
            addEventAt(ctx, atTitle, st, speak)
            return true
        }

        ALARM_SET.find(t)?.let { m ->
            val rest = m.groupValues[1].trim()
            val st = parseSpokenTime(rest)
            if (st == null) {
                speak("几点叫你？比如「明天早上7点闹钟」")
                return true
            }
            setAlarm(ctx, st, speak)
            return true
        }

        ALARM_TIME_FIRST.find(t)?.let { m ->
            val st = parseSpokenTime(m.groupValues[1])
            if (st != null) {
                setAlarm(ctx, st, speak)
                return true
            }
        }

        ALARM_WAKE.find(t)?.let { m ->
            val st = parseSpokenTime(m.groupValues[1])
            if (st != null) {
                val label = m.groupValues[2].trim().ifBlank { "起床" }
                setAlarm(ctx, st.copy(rest = label), speak)
                return true
            }
        }

        return false
    }

    // ------------------------------------------------------------------
    // 读日程
    // ------------------------------------------------------------------

    private fun readSchedule(ctx: Context, dayWord: String, speak: (String) -> Unit) {
        if (!hasPermission(ctx, Manifest.permission.READ_CALENDAR)) {
            AuditLog.append(ctx, "本地指令", "查日程", "没给日历权限")
            speak("没给日历权限，到设置里打开 Jarvis 的日历权限再试")
            return
        }
        val now = System.currentTimeMillis()
        val (from, to, label): Triple<Long, Long, String> = when {
            dayWord == "今天" -> dayWindow(0).let { Triple(it.first, it.second, "今天") }
            dayWord == "明天" -> dayWindow(1).let { Triple(it.first, it.second, "明天") }
            dayWord == "后天" -> dayWindow(2).let { Triple(it.first, it.second, "后天") }
            dayWord == "大后天" -> dayWindow(3).let { Triple(it.first, it.second, "大后天") }
            dayWord.startsWith("接下来") || dayWord.startsWith("未来") -> {
                val n = parseDayCount(dayWord)
                Triple(now, now + n * 86_400_000L, "接下来${n}天")
            }
            dayWord == "这周" || dayWord == "本周" -> Triple(now, now + 7 * 86_400_000L, "这周")
            else -> Triple(now, now + 3 * 86_400_000L, "接下来3天")
        }
        val events = queryEvents(ctx, from, to)
        if (events.isEmpty()) {
            AuditLog.append(ctx, "本地指令", "查日程（$label）", "没有安排")
            speak("$label 没有安排")
            return
        }
        val shown = events.take(5)
        val lines = shown.joinToString("；") { formatEvent(it) }
        val more = if (events.size > 5) "，还有 ${events.size - 5} 条" else ""
        AuditLog.append(ctx, "本地指令", "查日程（$label）", "${events.size} 条")
        speak("$label 有 ${events.size} 件事：$lines$more")
    }

    /** [offset] 天后的 00:00 到次日 00:00。 */
    private fun dayWindow(offset: Int): Pair<Long, Long> {
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            add(Calendar.DAY_OF_YEAR, offset)
        }.timeInMillis
        return start to start + 86_400_000L
    }

    private fun parseDayCount(word: String): Int {
        val raw = word.removePrefix("接下来").removePrefix("未来").removeSuffix("天")
        raw.toIntOrNull()?.let { return it.coerceIn(1, 30) }
        return CN_NUM[raw.singleOrNull()] ?: 3
    }

    /** Ported from Andee DeviceCommsController.calendarEvents. */
    private fun queryEvents(ctx: Context, fromMs: Long, toMs: Long): List<CalEvent> {
        val out = ArrayList<CalEvent>()
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(fromMs.toString())
            .appendPath(toMs.toString())
            .build()
        runCatching {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.EVENT_LOCATION,
                ),
                null, null,
                CalendarContract.Instances.BEGIN + " ASC",
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CalEvent(
                            title = c.getString(0) ?: "",
                            beginMs = c.getLong(1),
                            endMs = c.getLong(2),
                            location = c.getString(3) ?: "",
                        )
                    )
                }
            }
        }
        return out
    }

    private fun formatEvent(e: CalEvent): String {
        val day = SimpleDateFormat("M月d日", Locale.US).format(Date(e.beginMs))
        val start = SimpleDateFormat("HH:mm", Locale.US).format(Date(e.beginMs))
        val end = SimpleDateFormat("HH:mm", Locale.US).format(Date(e.endMs))
        val loc = if (e.location.isNotBlank()) "（${e.location}）" else ""
        return "$day $start-$end ${e.title}$loc"
    }

    // ------------------------------------------------------------------
    // 加日历
    // ------------------------------------------------------------------

    private fun addEvent(
        ctx: Context,
        title: String,
        dayWord: String,
        periodWord: String,
        hourRaw: Int,
        minute: Int,
        endHourRaw: Int?,
        endMinute: Int?,
        speak: (String) -> Unit,
    ) {
        if (hourRaw !in 0..23) {
            speak("这个钟点不对，$hourRaw 点不存在，你再说一遍")
            return
        }
        val cal = buildCal(dayWord, periodWord, hourRaw, minute) ?: run {
            speak("这个时间已经过了，换个时间再说")
            return
        }
        val atMs = cal.timeInMillis
        var endMs: Long? = null
        if (endHourRaw != null && endMinute != null && endHourRaw in 0..23) {
            val endCal = buildCal(dayWord, periodWord, endHourRaw, endMinute)
            if (endCal != null) {
                // 开始顺延到明天时，结束时间也要跟着顺延，否则会比开始还早。
                if (endCal.timeInMillis <= atMs) endCal.add(Calendar.DAY_OF_YEAR, 1)
                if (endCal.timeInMillis > atMs) endMs = endCal.timeInMillis
            }
        }
        // 没写日期但时间已过、顺延到明天时，播报里要说"明天"。
        val rolledTomorrow = dayWord.isBlank() &&
            cal.get(Calendar.DAY_OF_YEAR) != Calendar.getInstance().get(Calendar.DAY_OF_YEAR)
        insertEvent(ctx, title, atMs, endMs, speak, rolledTomorrow = rolledTomorrow)
    }

    private fun addEventAt(ctx: Context, title: String, st: SpokenTime, speak: (String) -> Unit) {
        insertEvent(ctx, title, st.atMs, st.endMs, speak, rolledTomorrow = st.rolledTomorrow)
    }

    /** Ported from Andee DeviceCommsController.addCalendarEvent. */
    private fun insertEvent(
        ctx: Context,
        title: String,
        beginMs: Long,
        endMs: Long?,
        speak: (String) -> Unit,
        rolledTomorrow: Boolean = false,
    ) {
        if (!hasPermission(ctx, Manifest.permission.WRITE_CALENDAR)) {
            AuditLog.append(ctx, "本地指令", "加日历：$title", "没给日历写权限")
            speak("没给日历写权限，到设置里打开 Jarvis 的日历权限再试")
            return
        }
        val cv = ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, primaryCalendarId(ctx))
            put(CalendarContract.Events.TITLE, title)
            put(CalendarContract.Events.DTSTART, beginMs)
            put(CalendarContract.Events.DTEND, endMs ?: (beginMs + 3_600_000L))
            put(CalendarContract.Events.EVENT_TIMEZONE, java.util.TimeZone.getDefault().id)
        }
        val uri = runCatching {
            ctx.contentResolver.insert(CalendarContract.Events.CONTENT_URI, cv)
        }.getOrNull()
        if (uri == null) {
            AuditLog.append(ctx, "本地指令", "加日历：$title", "写不进日历")
            speak("这条没写进日历，可能是日历应用的问题，再试一次")
            return
        }
        val dayHint = if (rolledTomorrow) "明天" else ""
        val timeText = SimpleDateFormat("M月d日 HH:mm", Locale.US).format(Date(beginMs))
        AuditLog.append(ctx, "本地指令", "加日历：$title", "已记到 $dayHint$timeText")
        speak("好，已记到日历，$dayHint$timeText $title")
    }

    /** Ported from Andee DeviceCommsController.primaryCalendarId. */
    private fun primaryCalendarId(ctx: Context): Long {
        runCatching {
            ctx.contentResolver.query(
                CalendarContract.Calendars.CONTENT_URI,
                arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY),
                null, null, null,
            )?.use { c ->
                var first = -1L
                while (c.moveToNext()) {
                    if (c.getInt(1) == 1) return c.getLong(0)
                    if (first < 0) first = c.getLong(0)
                }
                if (first >= 0) return first
            }
        }
        return 1L
    }

    // ------------------------------------------------------------------
    // 设闹钟
    // ------------------------------------------------------------------

    private fun setAlarm(ctx: Context, st: SpokenTime, speak: (String) -> Unit) {
        val label = st.rest.ifBlank { "起床" }
        val id = Scheduler.armAlarm(ctx, st.atMs, label)
        if (id == null) {
            AuditLog.append(ctx, "本地指令", "设闹钟：$label", "排不上")
            speak("这个闹钟没排上，可能是闹钟权限的问题")
            return
        }
        val dayHint = if (st.rolledTomorrow) "明天" else ""
        val timeText = SimpleDateFormat("M月d日 HH:mm", Locale.US).format(Date(st.atMs))
        AuditLog.append(ctx, "本地指令", "设闹钟：$label", "已排在 $dayHint$timeText（编号 $id）")
        speak("好，$dayHint$timeText 叫你$label，编号 $id，要取消就说「取消提醒 $id」")
    }

    // ------------------------------------------------------------------
    // 中文钟点解析（与 ReminderCommands 的消歧规则保持一致）
    // ------------------------------------------------------------------

    private fun parseMinute(raw: String): Int = when {
        raw.isBlank() -> 0
        raw == "半" -> 30
        else -> raw.toIntOrNull() ?: 0
    }

    /** 中文 12 小时制口语转 24 小时制；与 ReminderCommands 的规则一致。 */
    private fun toHour24(hourRaw: Int, periodWord: String): Int {
        if (hourRaw !in 1..11) return hourRaw
        return when (periodWord) {
            "下午", "晚上", "傍晚" -> hourRaw + 12
            "中午" -> if (hourRaw == 11 || hourRaw == 12) hourRaw else if (hourRaw == 1) 13 else hourRaw
            else -> hourRaw
        }
    }

    /**
     * 按"日词 + 时段词 + H点M分"拼出 Calendar；已过的时间：
     * 没写日期时顺延到明天，写了"今天/今晚"时返回 null（已经过了，没法排）。
     */
    private fun buildCal(dayWord: String, periodWord: String, hourRaw: Int, minute: Int): Calendar? {
        val hour24 = toHour24(hourRaw, periodWord)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        when (dayWord) {
            "明天", "明早" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "后天" -> cal.add(Calendar.DAY_OF_YEAR, 2)
            "大后天" -> cal.add(Calendar.DAY_OF_YEAR, 3)
            "今天", "今晚" -> if (cal.timeInMillis <= System.currentTimeMillis()) return null
            else -> if (cal.timeInMillis <= System.currentTimeMillis()) cal.add(Calendar.DAY_OF_YEAR, 1)
        }
        return cal
    }

    /** 从一段话里抠出钟点，返回时间 + 去掉时间片段后剩下的文字。 */
    private fun parseSpokenTime(text: String): SpokenTime? {
        val m = TIME_IN_TEXT.find(text) ?: return null
        val dayWord = m.groupValues[1]
        val periodWord = m.groupValues[2]
        val hourRaw = m.groupValues[3].toIntOrNull() ?: return null
        if (hourRaw !in 0..23) return null
        val minute = parseMinute(m.groupValues[4])
        val hour24 = toHour24(hourRaw, periodWord)
        val cal = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour24); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        var rolledTomorrow = false
        when (dayWord) {
            "明天", "明早" -> cal.add(Calendar.DAY_OF_YEAR, 1)
            "后天" -> cal.add(Calendar.DAY_OF_YEAR, 2)
            "大后天" -> cal.add(Calendar.DAY_OF_YEAR, 3)
            "今天", "今晚" -> if (cal.timeInMillis <= System.currentTimeMillis()) return null
            else -> if (cal.timeInMillis <= System.currentTimeMillis()) {
                cal.add(Calendar.DAY_OF_YEAR, 1)
                rolledTomorrow = true
            }
        }
        var endMs: Long? = null
        val endMatch = END_IN_TEXT.find(text, m.range.last + 1)
        if (endMatch != null) {
            val endHourRaw = endMatch.groupValues[1].toIntOrNull()
            if (endHourRaw != null && endHourRaw in 0..23) {
                val endCal = (cal.clone() as Calendar).apply {
                    set(Calendar.HOUR_OF_DAY, toHour24(endHourRaw, periodWord))
                    set(Calendar.MINUTE, parseMinute(endMatch.groupValues[2]))
                }
                if (endCal.timeInMillis > cal.timeInMillis) endMs = endCal.timeInMillis
            }
        }
        var rest = text.removeRange(m.range)
        if (endMatch != null) {
            // endMatch 的下标是原句里的，rest 已短了一截：按文本再找一次去掉。
            val again = END_IN_TEXT.find(rest)
            if (again != null) rest = rest.removeRange(again.range)
        }
        rest = rest.trim().trim('，', '、', '。', ' ', '的')
        return SpokenTime(cal.timeInMillis, endMs, rest, rolledTomorrow)
    }

    private fun hasPermission(ctx: Context, perm: String): Boolean =
        ctx.checkSelfPermission(perm) == PackageManager.PERMISSION_GRANTED
}
