package com.jarvis.app.task

import android.content.Context
import android.content.pm.PackageManager
import android.os.SystemClock
import com.jarvis.app.AuditLog
import rikka.shizuku.Shizuku
import java.io.ByteArrayOutputStream

/**
 * Shizuku 帮手：关应用的强停通道（比最近任务划卡片稳），以及一个仅供诊断的 shell。
 *
 * 版本钉住 12.2.0（见 gradle/libs.versions.toml 与 app/build.gradle 的注释）：
 * Shizuku.newProcess(String[], String[], String) 在 12.2.0 是 public，13.0 起改成
 * private，只能走 bindUserService + UserService（代码量大、这里没法验）。12.2.0 +
 * newProcess 可直接以 shell 身份跑 `am force-stop <pkg>`。若 12.2.0 解析失败，
 * 先报出来，不要悄悄升到 13.x。
 *
 * 纪律：所有函数都不抛异常——Shizuku 没装、没启动、没授权、命令超时、退出码非 0，
 * 一律返回 false / 空结果，让调用方回落到现有的最近任务划卡片兜底。绝不强停自己。
 */
object ShizukuHelper {

    /** Shizuku 应用的包名（isShizukuAvailable 查它是否已装；manifest <queries> 里也声明了它）。 */
    const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

    private const val PERMISSION_REQUEST_CODE = 1001
    private const val DEFAULT_TIMEOUT_MS = 5000L
    private const val MAX_TIMEOUT_MS = 10000L
    private const val MAX_SHELL_OUTPUT_CHARS = 2000
    private const val MAX_DRAIN_BYTES = 65536

    data class ShellResult(val stdout: String, val exitCode: Int)

    @Volatile
    private var listenerRegistered = false
    private val listenerLock = Any()

    /**
     * Shizuku 是不是真能用：应用已装，并且守护进程的 binder 还活着。
     * binder 死掉（Shizuku 被杀、重启后没再启动）时 pingBinder() 返回 false。
     */
    fun isShizukuAvailable(ctx: Context): Boolean {
        return try {
            if (!isAppInstalled(ctx, SHIZUKU_PACKAGE)) return false
            Shizuku.pingBinder()
        } catch (_: Exception) {
            false
        }
    }

    /** 当前有没有 Shizuku 授权（PERMISSION_GRANTED 才算有）。 */
    fun hasPermission(): Boolean {
        return try {
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 弹一次 Shizuku 授权框（不阻塞等结果——结果由注册好的监听器记进审计日志，
     * 下次关应用时再看 hasPermission()）。返回 true = 请求确实发出去了。
     */
    fun requestPermission(ctx: Context): Boolean {
        return try {
            ensureListener(ctx)
            Shizuku.requestPermission(PERMISSION_REQUEST_CODE)
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * 强停一个应用：以 shell 身份跑 `am force-stop <pkg>`。
     * 只有退出码为 0 才算成功；命令超时、进程起不来、退出码非 0、抛异常，一律 false。
     * 自己绝不能强停（pkg 是本应用包名时直接拒绝）。
     */
    fun forceStopPackage(ctx: Context, pkg: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): Boolean {
        if (pkg.isBlank() || pkg == ctx.packageName) return false
        val result = runProcess(arrayOf("am", "force-stop", pkg), clampTimeout(timeoutMs)) ?: return false
        return result.exitCode == 0
    }

    /**
     * 仅供诊断的 shell：跑一条命令，回 stdout（截断到约 2000 字）+ 退出码。
     * 超时 / 起不来 / 抛异常时回 exitCode = -1。本批没有对用户开放的入口。
     */
    fun runShell(cmd: Array<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val result = runProcess(cmd, clampTimeout(timeoutMs)) ?: return ShellResult("", -1)
        return result
    }

    // ---------- 内部 ----------

    private fun clampTimeout(timeoutMs: Long): Long = timeoutMs.coerceIn(0L, MAX_TIMEOUT_MS)

    private fun isAppInstalled(ctx: Context, pkg: String): Boolean {
        return try {
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                ctx.packageManager.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                ctx.packageManager.getPackageInfo(pkg, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /** 授权结果监听器只注册一次；结果记进审计日志（已授权/被拒绝），供下次关应用参考。 */
    private fun ensureListener(ctx: Context) {
        if (listenerRegistered) return
        synchronized(listenerLock) {
            if (listenerRegistered) return
            val appCtx = ctx.applicationContext
            try {
                Shizuku.addRequestPermissionResultListener(
                    Shizuku.OnRequestPermissionResultListener { requestCode, grantResult ->
                        if (requestCode != PERMISSION_REQUEST_CODE) return@OnRequestPermissionResultListener
                        val granted = grantResult == PackageManager.PERMISSION_GRANTED
                        AuditLog.append(appCtx, "Shizuku", "授权请求", if (granted) "已授权" else "被拒绝")
                    }
                )
                listenerRegistered = true
            } catch (_: Exception) {
                // 注册失败不影响本次关闭（授权框照常弹，只是结果不记日志）。
            }
        }
    }

    /**
     * 跑一条命令并等它退出。stdout/stderr 后台抽干（防管道写满卡死），
     * 超时就杀掉进程回 null。返回 null = 没跑成；非 null 时 exitCode 是真实退出码。
     */
    private fun runProcess(cmd: Array<String>, timeoutMs: Long): ShellResult? {
        val proc: Process = try {
            Shizuku.newProcess(cmd, null, null) ?: return null
        } catch (_: Exception) {
            return null
        }
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outThread = Thread { copyStream(proc.inputStream, outBuf) }.apply { isDaemon = true; start() }
        val errThread = Thread { copyStream(proc.errorStream, errBuf) }.apply { isDaemon = true; start() }
        if (!waitForExit(proc, timeoutMs)) {
            try { proc.destroy() } catch (_: Exception) { }
            return null
        }
        try { outThread.join(500) } catch (_: Exception) { }
        try { errThread.join(500) } catch (_: Exception) { }
        val exitCode = try { proc.exitValue() } catch (_: Exception) { return null }
        val stdout = outBuf.toString(Charsets.UTF_8.name()).take(MAX_SHELL_OUTPUT_CHARS)
        val stderr = errBuf.toString(Charsets.UTF_8.name()).take(500)
        val combined = if (stderr.isBlank()) stdout else "$stdout\n[stderr]\n$stderr"
        return ShellResult(combined.take(MAX_SHELL_OUTPUT_CHARS), exitCode)
    }

    /**
     * 等进程退出。手动轮询 exitValue()，不用 Process.waitFor(long, TimeUnit)——
     * 那个重载要 API 26+，本工程 minSdk 是 24，在老设备上会直接 NoSuchMethodError。
     */
    private fun waitForExit(proc: Process, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (true) {
            try {
                proc.exitValue()
                return true
            } catch (_: IllegalThreadStateException) {
                // 还在跑，继续等。
            }
            if (SystemClock.uptimeMillis() >= deadline) return false
            try { Thread.sleep(50) } catch (_: InterruptedException) { return false }
        }
    }

    private fun copyStream(input: java.io.InputStream, out: ByteArrayOutputStream) {
        try {
            val buf = ByteArray(4096)
            while (true) {
                if (out.size() >= MAX_DRAIN_BYTES) return
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
            }
        } catch (_: Exception) {
            // 流关了就停，不算错。
        }
    }
}
