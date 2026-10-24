package com.jarvis.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat

/**
 * 录屏截图兜底专用前台服务（类型 mediaProjection），2026-10-24 为修录屏授权失败而加。
 *
 * 背景：工程 targetSdk 34（Android 14）。Android 14 起，调
 * `MediaProjectionManager.getMediaProjection()` 之前必须先跑一个 mediaProjection
 * 类型的前台服务，否则系统直接抛 SecurityException——之前清单里只有 microphone
 * 类型服务，授权回调回来后 start() 一律失败，界面上只看到“录屏没启动起来”。
 * 本服务不做别的：只在投影存活期间占住这个类型，投影释放时由
 * ScreenCapture.release() 停掉。
 */
class CaptureForegroundService : Service() {
    companion object {
        const val CHANNEL_ID = "jarvis_capture"
        const val NOTIF_ID = 7002

        fun start(ctx: Context): Boolean = try {
            ContextCompat.startForegroundService(ctx, Intent(ctx, CaptureForegroundService::class.java))
            true
        } catch (e: Exception) {
            false
        }

        fun stop(ctx: Context) {
            try {
                ctx.stopService(Intent(ctx, CaptureForegroundService::class.java))
            } catch (_: Exception) {
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Jarvis 录屏截图", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
        val b = if (Build.VERSION.SDK_INT >= 26) Notification.Builder(this, CHANNEL_ID)
        else @Suppress("DEPRECATION") Notification.Builder(this)
        val n = b.setSmallIcon(android.R.drawable.ic_menu_camera)
            .setContentTitle("Jarvis 录屏截图兜底")
            .setContentText("正在为跨应用看屏提供截图")
            .setOngoing(true)
            .build()
        try {
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
            } else {
                startForeground(NOTIF_ID, n)
            }
        } catch (e: Exception) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_NOT_STICKY
    }
}
