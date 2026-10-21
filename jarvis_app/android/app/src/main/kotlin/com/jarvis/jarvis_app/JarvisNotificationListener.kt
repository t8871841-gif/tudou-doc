package com.jarvis.jarvis_app

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
        if (sbn.packageName == packageName || sbn.isOngoing) return
        val n = sbn.notification ?: return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
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
        buf.add(mapOf("pkg" to sbn.packageName, "app" to label, "title" to title.take(60), "text" to text.take(200), "time" to sbn.postTime))
        while (buf.size > 60) buf.removeAt(0)
    }
}
