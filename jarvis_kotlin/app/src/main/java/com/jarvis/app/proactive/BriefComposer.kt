package com.jarvis.app.proactive

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.ReminderScheduler
import com.jarvis.app.data.WeatherTool
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 批六：晨报组稿。只用本地可得数据（日期/星期、今日日历、待触发的提醒）+
 * 第二梯队批四新增的真实天气数据。日历没授权时如实说看不到，不假装没有日程；
 * 天气城市没配置/查询失败时如实说没有，不编造数值（这条红线继承自 AgnesClient
 * 的系统提示词，晨报走的是独立的本地组稿通道，同样要守）。
 */
object BriefComposer {

    data class CalEvent(val eventId: Long, val title: String, val beginMs: Long, val location: String)

    fun hasCalendarPermission(ctx: Context): Boolean =
        ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) == PackageManager.PERMISSION_GRANTED

    /** 今天 0 点到明天 0 点的日程，按开始时间排序；没授权或查询失败返回空列表（调用方靠 hasCalendarPermission 区分）。 */
    fun todaysEvents(ctx: Context): List<CalEvent> {
        if (!hasCalendarPermission(ctx)) return emptyList()
        val start = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
        }
        val end = (start.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, 1) }
        val uri = CalendarContract.Instances.CONTENT_URI.buildUpon()
            .appendPath(start.timeInMillis.toString())
            .appendPath(end.timeInMillis.toString())
            .build()
        val out = mutableListOf<CalEvent>()
        try {
            ctx.contentResolver.query(
                uri,
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.EVENT_LOCATION
                ),
                null, null, "${CalendarContract.Instances.BEGIN} ASC"
            )?.use { c ->
                while (c.moveToNext()) {
                    out.add(
                        CalEvent(
                            eventId = c.getLong(0),
                            title = c.getString(1)?.takeIf { it.isNotBlank() } ?: "日程",
                            beginMs = c.getLong(2),
                            location = c.getString(3) ?: ""
                        )
                    )
                }
            }
        } catch (_: Exception) {
        }
        return out
    }

    /** 第二梯队批四：compose 改成 suspend——天气查询是网络 I/O，调用方（ReminderReceiver/
     * MainActivity）都已经相应改成异步调用，不在主线程/BroadcastReceiver 生命周期里卡住。 */
    suspend fun compose(ctx: Context): String {
        val now = Date()
        val dateStr = SimpleDateFormat("M月d日 EEEE", Locale.CHINESE).format(now)
        val sb = StringBuilder("早上好，先生。今天是$dateStr。")

        val city = JarvisPrefs.getWeatherCity(ctx)
        if (city.isBlank()) {
            // 没配置城市就不提天气这茬，不是每条晨报都要念叨"天气查不到"——用户没配置
            // 说明可能压根不在乎这个，安静跳过比反复报错更礼貌。
        } else {
            // 第二梯队批六修正：原本这里打算新开一个 net/WeatherClient.kt，后来发现工程里
            // "批十"早就有一个更成熟的 data/WeatherTool.kt（专门给聊天里问天气用的）——
            // Nominatim 优先、中文地名消歧更准，还带今明两日高低温/降水概率，比临时新写的
            // 那版更好。晨报改接这个既有的，删掉了重复的 WeatherClient.kt，不留两套。
            val weatherLine = suspendCancellableCoroutine<String?> { cont ->
                WeatherTool.fetch(city, tomorrow = false) { result ->
                    if (cont.isActive) cont.resume(result)
                }
            }
            sb.append(weatherLine ?: "天气没查到，可能是网络问题或者城市名没填对。")
            if (!sb.endsWith("。")) sb.append("。")
        }

        if (!hasCalendarPermission(ctx)) {
            sb.append("日历没有授权，今天的日程我看不到。")
        } else {
            val events = todaysEvents(ctx)
            if (events.isEmpty()) {
                sb.append("今天没有日程安排。")
            } else {
                val timeFmt = SimpleDateFormat("HH:mm", Locale.getDefault())
                sb.append("今天有 ${events.size} 个日程：")
                events.take(5).forEach { e ->
                    sb.append("${timeFmt.format(Date(e.beginMs))} ${e.title}")
                    if (e.location.isNotBlank()) sb.append("（${e.location}）")
                    sb.append("；")
                }
                if (events.size > 5) sb.append("还有 ${events.size - 5} 个就不念了。")
            }
        }
        val upcoming = ReminderScheduler.list(ctx).count {
            (it["atMs"] as? Long ?: 0L) > System.currentTimeMillis()
        }
        if (upcoming > 0) sb.append("另外有 $upcoming 条提醒在排队。")
        sb.append("祝今天顺利。")
        return sb.toString()
    }
}
