package com.jarvis.app.task

import android.app.Service
import android.content.Intent
import android.os.IBinder

/**
 * 接收 Termux RUN_COMMAND 的 PendingIntent 结果（阶段 1）。
 * Termux 跑完命令后把 stdout/stderr/exitCode 经这个 Service 送回来，
 * 这里转交给 TermuxExecutor 里等着的 future，然后自己停掉。
 * Manifest 里声明 exported=false，只接本应用的 PendingIntent。
 */
class TermuxResultService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        TermuxExecutor.acceptResult(intent)
        stopSelf(startId)
        return START_NOT_STICKY
    }
}
