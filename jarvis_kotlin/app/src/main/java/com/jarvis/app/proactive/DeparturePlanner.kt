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

    // 第二梯队批四新增：相邻两个日程间隔短于这个阈值（分钟）就算冲突，提前提醒。
    private const val CONFLICT_GAP_MIN = 15

    /**
     * 日程冲突提醒：按开始时间排序后，看相邻两个日程间隔够不够。够不上
     * [CONFLICT_GAP_MIN] 分钟（含前一个日程还没结束、后一个就开始了的重叠情况——
     * 这里简化处理，只用相邻日程的"开始时间"算间隔，不读日程的结束时间/时长，
     * 因为 [BriefComposer.todaysEvents] 目前没取 DTEND 字段，不在本批顺带改查询）
     * 就排一条提醒，提前量固定在后一个日程开始前 [CONFLICT_GAP_MIN] 分钟，让用户
     * 有时间决定怎么处理。用同一个 id（跟日程 eventId 绑定）覆盖，不会重复排。
     */
    fun scanConflicts(ctx: Context): Int {
        if (!JarvisPrefs.isDepartureEnabled(ctx)) return 0 // 复用出发提醒的总开关，不单独加一个
        val events = BriefComposer.todaysEvents(ctx).sortedBy { it.beginMs }
        if (events.size < 2) return 0
        val now = System.currentTimeMillis()
        var count = 0
        for (i in 0 until events.size - 1) {
            val a = events[i]
            val b = events[i + 1]
            val gapMin = (b.beginMs - a.beginMs) / 60_000L
            if (gapMin in 0 until CONFLICT_GAP_MIN) {
                val at = b.beginMs - CONFLICT_GAP_MIN * 60_000L
                if (at <= now) continue
                val id = 710000 + (b.eventId % 200000L).toInt()
                val body = "日程有点挤：「${a.title}」和「${b.title}」只隔了 $gapMin 分钟，" +
                    "可能来不及，提前看看要不要调整。"
                if (ReminderScheduler.schedule(ctx, id, at, "日程冲突提醒", body, "conflict")) count++
            }
        }
        return count
    }

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
