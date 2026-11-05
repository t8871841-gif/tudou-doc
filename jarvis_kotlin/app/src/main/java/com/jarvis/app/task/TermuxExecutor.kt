package com.jarvis.app.task

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import java.security.SecureRandom
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Termux 执行器（阶段 1，2026-11-05）：通过 Termux 的 RUN_COMMAND Intent
 * 跑命令/脚本，结果经 PendingIntent 回传。做法抄 mcp-android 的 TermuxBridge
 *（它已在真机上验证过这条路），改 Kotlin + 去掉与 Jarvis 无关的校验。
 *
 * 为什么用 PendingIntent 而不用落盘回读：Termux 跑完把 stdout/stderr/exitCode
 * 直接经 PendingIntent 送回来，不用共享存储中转文件（Android 11+ 上
 * /sdcard 读写要 MANAGE_EXTERNAL_STORAGE，Jarvis 现在没有也不想为此加）。
 * 脚本走 `bash -lc`，Python 代码用 heredoc 传进去，不用先写文件。
 *
 * 前提（用户侧一次性配置）：装 Termux（F-Droid/GitHub 版）+ 装 python +
 * Termux 里开 allow-external-apps=true + 授权 RUN_COMMAND 权限 +
 * 电池设“无限制”+ termux-wake-lock（防 Android 12+ 幻影进程查杀）。
 */
object TermuxExecutor {
    private const val TERMUX_PACKAGE = "com.termux"
    private const val TERMUX_SERVICE = "com.termux.app.RunCommandService"
    private const val ACTION = "com.termux.RUN_COMMAND"
    private const val EXTRA_PATH = "com.termux.RUN_COMMAND_PATH"
    private const val EXTRA_ARGS = "com.termux.RUN_COMMAND_ARGUMENTS"
    private const val EXTRA_STDIN = "com.termux.RUN_COMMAND_STDIN"
    private const val EXTRA_WORKDIR = "com.termux.RUN_COMMAND_WORKDIR"
    private const val EXTRA_BACKGROUND = "com.termux.RUN_COMMAND_BACKGROUND"
    private const val EXTRA_PENDING = "com.termux.RUN_COMMAND_PENDING_INTENT"
    private const val BASH = "\$PREFIX/bin/bash"
    private const val HOME = "~/"

    /** 脚本最长 5 万字（intent extra 的 binder 限制约 1MB，留足余量）。 */
    const val MAX_SCRIPT_CHARS = 50_000

    /** 默认等 30 秒（Python 脚本可能慢；Shizuku 的 run_shell 另有 10 秒上限）。 */
    const val DEFAULT_TIMEOUT_MS = 30_000L

    data class TermuxResult(
        val stdout: String,
        val stderr: String,
        val exitCode: Int,
        val errorMessage: String,
    ) {
        val ok: Boolean get() = exitCode == 0 && errorMessage.isBlank()
    }

    private val RANDOM = SecureRandom()
    private val WAITING = ConcurrentHashMap<Int, CompletableFuture<TermuxResult>>()

    /** Termux 装了没有。 */
    fun isInstalled(ctx: Context): Boolean {
        return try {
            ctx.packageManager.getPackageInfo(TERMUX_PACKAGE, 0)
            true
        } catch (_: PackageManager.NameNotFoundException) {
            false
        }
    }

    /** RUN_COMMAND 权限授了没有（Termux 侧授权）。 */
    fun hasPermission(ctx: Context): Boolean {
        return ctx.checkSelfPermission("com.termux.permission.RUN_COMMAND") ==
            PackageManager.PERMISSION_GRANTED
    }

    /**
     * 跑一段 bash 脚本（经 Termux），阻塞等结果。脚本走 `bash -lc`，所以可以
     * 是任意 shell 命令、多行脚本、heredoc。超时未回时抛 IllegalStateException
     *（命令本身可能还在 Termux 里跑，结果未知）。
     */
    fun runScript(ctx: Context, script: String, timeoutMs: Long = DEFAULT_TIMEOUT_MS): TermuxResult {
        if (script.isBlank()) throw IllegalArgumentException("脚本为空")
        if (script.length > MAX_SCRIPT_CHARS) throw IllegalArgumentException("脚本超过 $MAX_SCRIPT_CHARS 字")
        if (!isInstalled(ctx)) throw IllegalStateException("Termux 没有安装")
        if (!hasPermission(ctx)) throw IllegalStateException("没有 Termux RUN_COMMAND 权限（去 Termux 里授权）")

        // SecureRandom requestId：顺序 id 会被别的 App 猜到、提前占位。
        var id: Int
        do {
            id = RANDOM.nextInt(Int.MAX_VALUE - 1000) + 1000
        } while (WAITING.containsKey(id))
        val future = CompletableFuture<TermuxResult>()
        WAITING[id] = future
        val callback = Intent(ctx, TermuxResultService::class.java).putExtra("requestId", id)
        var flags = PendingIntent.FLAG_ONE_SHOT
        // FLAG_MUTABLE 是 Termux RUN_COMMAND 的约定：结果 Intent 由 Termux 填。
        if (Build.VERSION.SDK_INT >= 31) flags = flags or PendingIntent.FLAG_MUTABLE
        val pending = PendingIntent.getService(ctx, id, callback, flags)

        val command = Intent().apply {
            setClassName(TERMUX_PACKAGE, TERMUX_SERVICE)
            action = ACTION
            putExtra(EXTRA_PATH, BASH)
            putExtra(EXTRA_ARGS, arrayOf("-lc", script))
            putExtra(EXTRA_STDIN, "")
            putExtra(EXTRA_WORKDIR, HOME)
            putExtra(EXTRA_BACKGROUND, true)
            putExtra(EXTRA_PENDING, pending)
        }
        try {
            ctx.startService(command)
        } catch (_: IllegalStateException) {
            // Android 12+ 可能以“后台启动不允许”拒绝 startService，改走
            // startForegroundService 重试（Termux 目标 SDK 老，不受前台超时限制）。
            try {
                ctx.startForegroundService(command)
            } catch (e: RuntimeException) {
                WAITING.remove(id)
                throw IllegalStateException("启动 Termux 命令失败：${e.message}")
            }
        } catch (e: SecurityException) {
            WAITING.remove(id)
            throw IllegalStateException("Termux RUN_COMMAND 权限被拒：${e.message}")
        } catch (e: RuntimeException) {
            WAITING.remove(id)
            throw IllegalStateException("启动 Termux 命令失败：${e.message}")
        }

        try {
            return future.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: java.util.concurrent.TimeoutException) {
            throw IllegalStateException("Termux 命令超时（${timeoutMs / 1000} 秒没回），结果未知")
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            throw IllegalStateException("Termux 命令被打断，结果未知")
        } catch (_: java.util.concurrent.ExecutionException) {
            throw IllegalStateException("Termux 命令结果解析失败")
        } finally {
            WAITING.remove(id)
        }
    }

    /** TermuxResultService 收到结果后调这里，把结果交给等着的 future。 */
    fun acceptResult(intent: Intent?) {
        if (intent == null) return
        val id = intent.getIntExtra("requestId", -1)
        val future = WAITING[id] ?: return
        try {
            future.complete(parseResult(intent))
        } catch (e: RuntimeException) {
            future.completeExceptionally(e)
        }
    }

    /**
     * 解析 Termux 回传的结果 Intent。Termux 的 extra 键名在不同版本有出入，
     * 这里按“含 stdout/stderr/exit code”等关键词归一化匹配（抄 mcp-android）。
     */
    private fun parseResult(intent: Intent): TermuxResult {
        val extras = intent.extras
        var payload: Bundle? = null
        if (extras != null) {
            for (key in extras.keySet()) {
                val value = extras.get(key)
                if (value is Bundle) {
                    payload = value
                    break
                }
            }
        }
        if (payload == null) payload = extras ?: Bundle()
        var stdout = ""
        var stderr = ""
        var errorMessage = ""
        var exitCode = Int.MIN_VALUE
        var stdoutOriginal = -1
        var stderrOriginal = -1
        for (key in payload.keySet()) {
            val value = payload.get(key)
            val normalized = key.lowercase().replace("-", "_")
            when {
                normalized.contains("stdout") && normalized.contains("original") ->
                    stdoutOriginal = intValue(value, -1)
                normalized.contains("stderr") && normalized.contains("original") ->
                    stderrOriginal = intValue(value, -1)
                normalized.endsWith("stdout") || normalized == "stdout" ->
                    stdout = stringValue(value)
                normalized.endsWith("stderr") || normalized == "stderr" ->
                    stderr = stringValue(value)
                normalized.contains("exit") && normalized.contains("code") ->
                    exitCode = intValue(value, Int.MIN_VALUE)
                normalized.endsWith("errmsg") || normalized.contains("error_message") ->
                    errorMessage = stringValue(value)
            }
        }
        return TermuxResult(
            stdout = cap(stdout),
            stderr = cap(stderr),
            exitCode = exitCode,
            errorMessage = cap(errorMessage),
        )
    }

    private fun intValue(value: Any?, fallback: Int): Int =
        if (value is Number) value.toInt() else fallback

    private fun stringValue(value: Any?): String = value?.toString() ?: ""

    private fun cap(value: String): String =
        if (value.length <= 200_000) value else value.substring(0, 200_000)
}
