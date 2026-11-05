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
 * Ported from Andee: net.kuafuai.andee.config.Scheduler
 * (~/workspace/tudou-git/andee/app/src/main/java/net/kuafuai/andee/config/Scheduler.kt).
 * Structure and time helpers follow Andee; the arming itself does NOT copy
 * Andee's AlarmManager code — it wraps Jarvis's existing [ReminderScheduler],
 * which already owns the store, the setAlarmClock timer and the boot
 * re-arm. There is one timer mechanism in this app, not two.
 *
 * Differences from Andee, all deliberate:
 *  * No exact-alarm permission is ever requested: [ReminderScheduler] arms
 *    with `setAlarmClock`, which is exact under Doze without
 *    SCHEDULE_EXACT_ALARM, so [exactAllowed] has nothing to check.
 *  * No sweep timer: Jarvis has no quiet-hour conversation sweep; [AlarmReceiver]
 *    therefore only handles alarms and boot.
 *  * No Notebook: Jarvis stores reminders as [ReminderScheduler] rows, not
 *    Andee todos, so [catchUp] re-arms the future and reports how many live
 *    rows it found — rows whose moment already passed are pruned by the
 *    existing store on the next list() and are not replayed or notified.
 *    That is a known gap against Andee's missed-todo notification.
 *  * Cron: [CronExpr] is ported and [nextCronAfter]/[validateCron] are
 *    exposed, but no repeating cron todo is armed yet — Jarvis has no
 *    Notebook-style todo store to hold a cron expression. When one exists,
 *    arm it here (re-arm on every fire, Andee's shape) rather than handing
 *    AlarmManager a repeating alarm.
 */

package com.jarvis.app.config

import android.app.AlarmManager
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import com.jarvis.app.ReminderReceiver
import com.jarvis.app.ReminderScheduler
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Everything that has to happen "later": one-shot reminders and alarms.
 *
 * The clock is [ReminderScheduler]; this file contains no second timer, only
 * the Andee-shaped entry points on top of it: arm, cancel one row, list,
 * catch-up after boot, and the missed-promise notification for the case where
 * nothing is running to act on a firing alarm.
 */
object Scheduler {

    private const val TAG = "JarvisSched"

    /** Fired by an alarm PendingIntent that targets [AlarmReceiver] directly. */
    const val ACTION_ALARM = "com.jarvis.app.action.ALARM_DUE"

    const val EXTRA_ID = "alarm_id"
    const val EXTRA_LABEL = "alarm_label"

    /** Some OEMs send this instead of BOOT_COMPLETED. */
    const val QUICKBOOT = "android.intent.action.QUICKBOOT_POWERON"

    // ------------------------------------------------------------------
    // alarms (one-shot, stored as ReminderScheduler rows with kind "alarm")
    // ------------------------------------------------------------------

    /**
     * Arm a one-shot alarm at [atMs] with [label]. Returns the row id, or
     * null when it could not be armed. The id is the same id space as
     * reminders ([ReminderScheduler.nextAdHocId]), so "取消提醒 <id>" and the
     * per-item list in [com.jarvis.app.command.ReminderCommands] cover alarms
     * too — one list, not two.
     */
    fun armAlarm(ctx: Context, atMs: Long, label: String): Int? {
        val id = ReminderScheduler.nextAdHocId(ctx)
        val ok = ReminderScheduler.schedule(ctx, id, atMs, "Jarvis 闹钟", label, "alarm")
        if (ok) {
            Log.i(TAG, "alarm armed id=$id at ${formatLocal(atMs)} label=$label")
            return id
        }
        Log.e(TAG, "alarm arming failed id=$id at ${formatLocal(atMs)}")
        return null
    }

    /**
     * Cancel one reminder/alarm row by id: drop the AlarmManager alarm **and**
     * the stored row. Returns true when a stored row existed.
     *
     * [ReminderScheduler.remove] only drops the row, so the PendingIntent is
     * rebuilt here and cancelled first — the match ignores extras (only the
     * component and the request code matter), which is exactly how
     * [ReminderScheduler] builds it.
     */
    fun cancelReminder(ctx: Context, id: Int): Boolean {
        val existed = ReminderScheduler.list(ctx).any { it["id"] == id }
        runCatching {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val pi = PendingIntent.getBroadcast(
                ctx, id, Intent(ctx, ReminderReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE,
            )
            if (pi != null) am.cancel(pi)
        }.onFailure { Log.w(TAG, "cancel alarm failed id=$id", it) }
        ReminderScheduler.remove(ctx, id)
        return existed
    }

    /** Live reminders and alarms, soonest first (expired rows are pruned). */
    fun listReminders(ctx: Context): List<Map<String, Any?>> = ReminderScheduler.list(ctx)

    // ------------------------------------------------------------------
    // catch-up
    // ------------------------------------------------------------------

    /**
     * Bring the stored reminders back in line with the clock after a reboot
     * or an app restart. Returns how many live rows were re-armed.
     * Delegates to [ReminderScheduler.restoreAll]; see the file header for
     * why missed rows are not reported here.
     */
    fun catchUp(ctx: Context): Int {
        val live = ReminderScheduler.list(ctx)
        ReminderScheduler.restoreAll(ctx)
        if (live.isNotEmpty()) Log.i(TAG, "catch-up: re-armed ${live.size} reminder(s)")
        return live.size
    }

    // ------------------------------------------------------------------
    // cron
    // ------------------------------------------------------------------

    /** Next fire strictly after [fromMillis], or null when it can never fire. */
    fun nextCronAfter(expr: String, fromMillis: Long): Long? =
        runCatching { CronExpr.nextAfter(expr, fromMillis) }.getOrNull()

    /**
     * Validate a cron expression for a repeating todo: parses, and refuses
     * schedules finer than [CronExpr.MIN_INTERVAL_MINUTES]. Returns an error
     * message for the model to act on, or null when the expression is usable.
     */
    fun validateCron(expr: String): String? {
        val parsed = runCatching { CronExpr.parse(expr) }.getOrElse { return it.message }
        if (parsed.minGapMinutes() < CronExpr.MIN_INTERVAL_MINUTES) {
            return "cron fires more often than every ${CronExpr.MIN_INTERVAL_MINUTES} minutes; " +
                "the fastest the device can honour is ${CronExpr.MIN_INTERVAL_MINUTES} minutes"
        }
        return null
    }

    // ------------------------------------------------------------------
    // notifications
    // ------------------------------------------------------------------

    /**
     * The only thing this app can do for a promise when nothing is running
     * to act on it. It says what was promised and that it could not act on
     * it — a notification that just said the label would be a lie about who
     * is talking.
     */
    fun notifyMissed(ctx: Context, id: Int, label: String) {
        ReminderScheduler.ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, id, ReminderScheduler.launchIntent(ctx, "alarm", label),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = ReminderScheduler.builder(ctx)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Jarvis 没能叫醒你")
            .setContentText(label)
            .setStyle(android.app.Notification.BigTextStyle().bigText("到时间了，但 Jarvis 当时没在运行，没能叫醒你：$label"))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        runCatching {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        }.onFailure {
            // Almost always a missing POST_NOTIFICATIONS on Android 13+. The
            // row stays in the store either way, so this is a lost nudge,
            // not lost data.
            Log.w(TAG, "could not post the missed-alarm notification", it)
        }
    }

    // ------------------------------------------------------------------
    // time helpers (ported from Andee Scheduler)
    // ------------------------------------------------------------------

    private val LOCAL_FORMATS = listOf(
        "yyyy-MM-dd HH:mm",
        "yyyy-MM-dd HH:mm:ss",
        "yyyy-MM-dd'T'HH:mm",
        "HH:mm",
    )

    /**
     * Parse what the model wrote. `HH:mm` is accepted and read as **today**,
     * or tomorrow when that moment has already passed — "remind me at 8" said
     * at 9pm means tomorrow morning, and making the model work that out is a
     * needless chance to get it wrong.
     */
    fun parseLocal(raw: String): Long? {
        val s = raw.trim()
        if (s.isEmpty()) return null
        if (s.matches(Regex("\\d{1,2}:\\d{2}"))) {
            val today = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())
            return parseLocal("$today $s")?.let { ms ->
                if (ms <= System.currentTimeMillis()) ms + 86_400_000L else ms
            }
        }
        for (pattern in LOCAL_FORMATS) {
            val fmt = SimpleDateFormat(pattern, Locale.US).apply { isLenient = false }
            runCatching { fmt.parse(s) }.getOrNull()?.let { return it.time }
        }
        return null
    }

    fun formatLocal(ms: Long): String =
        SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date(ms))
}
