package com.jarvis.app

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 通知读取：只在内存里留最近 60 条（不落盘），仅在用户说“读一下通知”时才被取走。
 * 需要用户在系统“通知使用权”里手动授权。
 */
class JarvisNotificationListener : NotificationListenerService() {
    companion object {
        private val buf = CopyOnWriteArrayList<Map<String, Any>>()

        fun recent(limit: Int): List<Map<String, Any>> = buf.takeLast(limit).reversed()

        /** v3：Dart 开了“播报”时，新通知实时推给 Dart（Dart 决定播不播）。关着时不推，也不落盘。 */
        @Volatile
        var announceOn = false

        @Volatile
        var sink: ((Map<String, Any>) -> Unit)? = null
    }

    override fun onListenerConnected() {
        try {
            activeNotifications?.forEach { add(it) }
        } catch (_: Exception) {
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        add(sbn)
    }

    private fun add(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        // 来电通知常带 ONGOING 标志，按类别放行；其他常驻通知（音乐、下载进度等）照旧忽略。
        if (sbn.isOngoing && n.category != Notification.CATEGORY_CALL) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isEmpty() && text.isEmpty()) return
        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString()
        } catch (e: Exception) {
            sbn.packageName
        }
        val last = buf.lastOrNull()
        if (last != null && last["pkg"] == sbn.packageName && last["title"] == title.take(60) && last["text"] == text.take(200)) return
        // 批六：把类别标记进缓存，主动播报（Announcer）据此分来电/消息决策，不用猜包名。
        val m = mapOf<String, Any>(
            "pkg" to sbn.packageName, "app" to label, "title" to title.take(60), "text" to text.take(200),
            "time" to sbn.postTime,
            "call" to (n.category == Notification.CATEGORY_CALL),
            "msg" to (n.category == Notification.CATEGORY_MESSAGE)
        )
        buf.add(m)
        if (announceOn) sink?.invoke(m)
        while (buf.size > 60) buf.removeAt(0)
    }
}
