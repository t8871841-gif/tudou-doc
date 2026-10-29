package com.jarvis.app

import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import org.json.JSONArray
import org.json.JSONObject
import java.util.Calendar

/**
 * 提醒 / 每日晨报：AlarmManager.setAlarmClock（精确、熄屏和 Doze 都准点，
 * 且不需要 SCHEDULE_EXACT_ALARM 权限）。条目存在 SharedPreferences，开机后重新注册。
 */
object ReminderScheduler {
    private const val PREFS = "jarvis_reminders"
    private const val KEY_LIST = "list"
    const val MORNING_ID = 1001
    const val CHANNEL_REMINDER = "jarvis_reminder"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun launchIntent(ctx: Context, kind: String, body: String): Intent =
        Intent(ctx, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            putExtra(MainActivity.EXTRA_ACTION, if (kind == "brief") "morning_brief" else "reminder")
            putExtra(MainActivity.EXTRA_TEXT, body)
        }

    private fun operation(ctx: Context, id: Int, title: String, body: String, kind: String): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", id).putExtra("title", title).putExtra("body", body).putExtra("kind", kind)
        return PendingIntent.getBroadcast(ctx, id, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    fun schedule(ctx: Context, id: Int, atMs: Long, title: String, body: String, kind: String, persist: Boolean = true): Boolean {
        return try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val show = PendingIntent.getActivity(
                ctx, id, launchIntent(ctx, kind, body),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            am.setAlarmClock(AlarmManager.AlarmClockInfo(atMs, show), operation(ctx, id, title, body, kind))
            if (persist) store(ctx, id, atMs, title, body, kind)
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun readList(ctx: Context): JSONArray =
        try { JSONArray(prefs(ctx).getString(KEY_LIST, "[]")) } catch (e: Exception) { JSONArray() }

    private fun store(ctx: Context, id: Int, atMs: Long, title: String, body: String, kind: String) {
        val old = readList(ctx)
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optInt("id") != id) out.put(o)
        }
        out.put(JSONObject().put("id", id).put("atMs", atMs).put("title", title).put("body", body).put("kind", kind))
        prefs(ctx).edit().putString(KEY_LIST, out.toString()).apply()
    }

    fun remove(ctx: Context, id: Int) {
        val old = readList(ctx)
        val out = JSONArray()
        for (i in 0 until old.length()) {
            val o = old.getJSONObject(i)
            if (o.optInt("id") != id) out.put(o)
        }
        prefs(ctx).edit().putString(KEY_LIST, out.toString()).apply()
    }

    /** 还没触发的提醒（已过期的顺手清掉）。 */
    fun list(ctx: Context): List<Map<String, Any?>> {
        val now = System.currentTimeMillis()
        val arr = readList(ctx)
        val keep = JSONArray()
        val out = ArrayList<Map<String, Any?>>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("atMs") > now) {
                keep.put(o)
                out.add(mapOf("id" to o.optInt("id"), "atMs" to o.optLong("atMs"), "title" to o.optString("title"), "body" to o.optString("body")))
            }
        }
        prefs(ctx).edit().putString(KEY_LIST, keep.toString()).apply()
        return out.sortedBy { it["atMs"] as Long }
    }

    fun cancelAll(ctx: Context) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val arr = readList(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            try {
                am.cancel(operation(ctx, o.optInt("id"), "", "", o.optString("kind")))
            } catch (_: Exception) {
            }
        }
        prefs(ctx).edit().putString(KEY_LIST, "[]").apply()
    }

    fun scheduleDailyBrief(ctx: Context, hour: Int, minute: Int): Boolean {
        prefs(ctx).edit().putBoolean("brief_on", true).putInt("brief_h", hour).putInt("brief_m", minute).apply()
        val c = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour); set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= System.currentTimeMillis()) add(Calendar.DAY_OF_YEAR, 1)
        }
        return schedule(ctx, MORNING_ID, c.timeInMillis, "Jarvis 晨报", "点开听今天的晨报", "brief", persist = false)
    }

    fun cancelDailyBrief(ctx: Context) {
        prefs(ctx).edit().putBoolean("brief_on", false).apply()
        try {
            val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(operation(ctx, MORNING_ID, "", "", "brief"))
        } catch (_: Exception) {
        }
    }

    // 第二梯队批五新增：语音指令临时排的一次性提醒（"10分钟后提醒我喝水"这类）用这个
    // 拿不会撞车的 id——出发提醒用 700000 段、冲突提醒用 710000 段，这里单独开 720000 段，
    // 持久化一个自增计数器，不用随机数赌不撞车。
    fun nextAdHocId(ctx: Context): Int {
        val p = prefs(ctx)
        val next = p.getInt("adhoc_seq", 0) + 1
        p.edit().putInt("adhoc_seq", next).apply()
        return 720000 + (next % 200000)
    }

    fun isBriefOn(ctx: Context): Boolean = prefs(ctx).getBoolean("brief_on", false)
    fun briefHour(ctx: Context): Int = prefs(ctx).getInt("brief_h", 7)
    fun briefMinute(ctx: Context): Int = prefs(ctx).getInt("brief_m", 30)

    fun rescheduleBriefIfOn(ctx: Context) {
        val p = prefs(ctx)
        if (p.getBoolean("brief_on", false)) scheduleDailyBrief(ctx, p.getInt("brief_h", 7), p.getInt("brief_m", 30))
    }

    fun restoreAll(ctx: Context) {
        rescheduleBriefIfOn(ctx)
        val now = System.currentTimeMillis()
        val arr = readList(ctx)
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            if (o.optLong("atMs") > now) {
                schedule(ctx, o.optInt("id"), o.optLong("atMs"), o.optString("title"), o.optString("body"), o.optString("kind"), persist = false)
            }
        }
    }

    fun ensureChannel(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_REMINDER) == null) {
                nm.createNotificationChannel(NotificationChannel(CHANNEL_REMINDER, "Jarvis 提醒与晨报", NotificationManager.IMPORTANCE_HIGH))
            }
        }
    }

    fun builder(ctx: Context): Notification.Builder =
        if (Build.VERSION.SDK_INT >= 26) Notification.Builder(ctx, CHANNEL_REMINDER)
        else @Suppress("DEPRECATION") Notification.Builder(ctx)
            .setPriority(Notification.PRIORITY_HIGH).setDefaults(Notification.DEFAULT_ALL)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val title = intent.getStringExtra("title") ?: "Jarvis 提醒"
        var body = intent.getStringExtra("body") ?: ""
        val kind = intent.getStringExtra("kind") ?: "reminder"
        if (kind == "brief") {
            // 第二梯队批四：晨报组稿现在带天气查询（网络 I/O），BroadcastReceiver.onReceive
            // 默认在主线程跑、系统给的执行窗口很短，直接 runBlocking 会有 ANR 风险——
            // 用 goAsync() 拿到一个延长的执行窗口，把组稿挪到后台线程，完事调 finish()。
            val pending = goAsync()
            val appCtx = ctx.applicationContext
            Thread {
                try {
                    val composed = kotlinx.coroutines.runBlocking {
                        com.jarvis.app.proactive.BriefComposer.compose(appCtx)
                    }
                    com.jarvis.app.proactive.DeparturePlanner.scan(appCtx)
                    com.jarvis.app.proactive.DeparturePlanner.scanConflicts(appCtx)
                    deliverBriefNotification(appCtx, id, title, composed)
                    ReminderScheduler.rescheduleBriefIfOn(appCtx)
                } finally {
                    pending.finish()
                }
            }.start()
            return
        }
        ReminderScheduler.ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, id, ReminderScheduler.launchIntent(ctx, kind, body),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nb = ReminderScheduler.builder(ctx)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body)
            .setContentIntent(pi).setAutoCancel(true)
        val n = nb.build()
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, n)
        } catch (_: Exception) {
        }
        ReminderScheduler.remove(ctx, id)
    }

    private fun deliverBriefNotification(ctx: Context, id: Int, title: String, body: String) {
        ReminderScheduler.ensureChannel(ctx)
        val pi = PendingIntent.getActivity(
            ctx, id, ReminderScheduler.launchIntent(ctx, "brief", body),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val nb = ReminderScheduler.builder(ctx)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(title).setContentText(body)
            .setContentIntent(pi).setAutoCancel(true)
            .setStyle(android.app.Notification.BigTextStyle().bigText(body))
        try {
            (ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).notify(id, nb.build())
        } catch (_: Exception) {
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        ReminderScheduler.restoreAll(ctx)
    }
}
