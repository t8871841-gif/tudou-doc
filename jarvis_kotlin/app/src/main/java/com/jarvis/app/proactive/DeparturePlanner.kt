package com.jarvis.app.proactive

import android.content.Context
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.ReminderScheduler

/**
 * 批六：出发提醒（简化版，按需求第 7 项的降级口径）。扫今日带地点的日程，
 * 按固定提前量（设置里的分钟数）用 ReminderScheduler 排一条提醒。
 * **没有路程估算**——不接地图/路线 API，提醒文案里明说"没算路程"，不假装算过。
 * 同一日程重复扫描用同一个 id 覆盖，不会重复排。
 */
object DeparturePlanner {

    /** 返回本次排上的条数。出发提醒没开或日历没授权时为 0。 */
    fun scan(ctx: Context): Int {
        if (!JarvisPrefs.isDepartureEnabled(ctx)) return 0
        val lead = JarvisPrefs.getDepartureLeadMin(ctx)
        val now = System.currentTimeMillis()
        var count = 0
        for (e in BriefComposer.todaysEvents(ctx)) {
            if (e.location.isBlank()) continue
            val at = e.beginMs - lead * 60_000L
            if (at <= now) continue
            val id = 700000 + (e.eventId % 200000L).toInt()
            val body = "该出发了：${e.title}（${e.location}）。这是按固定提前 $lead 分钟排的提醒，没有算路程时间。"
            if (ReminderScheduler.schedule(ctx, id, at, "出发提醒", body, "departure")) count++
        }
        return count
    }
}
