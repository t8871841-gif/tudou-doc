package com.jarvis.app.task

import android.os.SystemClock
import java.io.ByteArrayOutputStream

/**
 * Shizuku UserService：跑在独立的 shell 进程（UID 2000）里，替 ShizukuHelper 跑命令。
 *
 * 机制（13.1.5，官方 README + 服务端 UserService.create 源码，已核实）：
 * - 客户端经 Shizuku.bindUserService 绑定；服务端经 app_process 用咱们 APK 的类加载器
 *   按类名加载这个类，先试 Context 构造器（没有），再用默认构造器实例化，实例必须实现
 *   IBinder——这里直接继承 AIDL Stub（Stub 就是 Binder）。所以：公开类 + 公开无参构造器。
 *   注意：13.1.5 的库里根本没有 `Shizuku.UserService` 这个基类，不要继承不存在的东西；
 *   也不要继承 android.app.Service（服务端不走 Android 的 Service 机制）。
 * - 不要给这个类加 manifest `<service>` 声明：服务端经 app_process 拉起，不查 manifest。
 * - 自包含：不用 AuditLog、不用任何应用单例、不用 Context。自定义 Application 不会在
 *   这个进程里实例化，Context 相关的 API 大多不可用；这里只用 Runtime + IO。
 * - 进程是 daemon（默认）：比应用活得久，调用方复用绑定，命令随叫随到；Shizuku 停掉
 *   或重启手机后才消失。这是符合设计的，不主动 unbind。
 * - 超时语义与旧的本地 runProcess 一致：超时就杀掉进程、回退出码 -1；起不来也回 -1。
 */
class ShellUserService : IShellService.Stub() {

    override fun runCommand(cmd: Array<String>?, timeoutMs: Long): Array<String> {
        return try {
            runCommandInner(cmd, timeoutMs)
        } catch (t: Throwable) {
            // Binder 线程上的任何异常都不要往外抛，翻成 -1 回去，客户端按失败处理。
            arrayOf("-1", "", "service error: ${t.javaClass.simpleName}: ${t.message ?: ""}")
        }
    }

    private fun runCommandInner(cmd: Array<String>?, timeoutMs: Long): Array<String> {
        if (cmd.isNullOrEmpty()) return arrayOf("-1", "", "empty command")
        val proc: Process = try {
            Runtime.getRuntime().exec(cmd)
        } catch (e: Exception) {
            return arrayOf("-1", "", "exec failed: ${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
        val outBuf = ByteArrayOutputStream()
        val errBuf = ByteArrayOutputStream()
        val outThread = Thread { copyStream(proc.inputStream, outBuf) }.apply { isDaemon = true; start() }
        val errThread = Thread { copyStream(proc.errorStream, errBuf) }.apply { isDaemon = true; start() }
        if (!waitForExit(proc, timeoutMs.coerceIn(0L, MAX_TIMEOUT_MS))) {
            try { proc.destroy() } catch (_: Exception) { }
            return arrayOf(
                "-1",
                outBuf.toString(Charsets.UTF_8.name()).take(MAX_STDOUT_CHARS),
                errBuf.toString(Charsets.UTF_8.name()).take(MAX_STDERR_CHARS)
            )
        }
        try { outThread.join(500) } catch (_: Exception) { }
        try { errThread.join(500) } catch (_: Exception) { }
        val exitCode = try { proc.exitValue() } catch (_: Exception) { return arrayOf("-1", "", "exitValue failed") }
        return arrayOf(
            exitCode.toString(),
            outBuf.toString(Charsets.UTF_8.name()).take(MAX_STDOUT_CHARS),
            errBuf.toString(Charsets.UTF_8.name()).take(MAX_STDERR_CHARS)
        )
    }

    /**
     * 等进程退出。手动轮询 exitValue()，不用 Process.waitFor(long, TimeUnit)——
     * 那个重载要 API 26+，本工程 minSdk 是 24，在老设备上会直接 NoSuchMethodError
     *（与旧的本地 runProcess 同款写法）。
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

    companion object {
        private const val MAX_TIMEOUT_MS = 10_000L
        private const val MAX_STDOUT_CHARS = 2000
        private const val MAX_STDERR_CHARS = 500
        private const val MAX_DRAIN_BYTES = 65536
    }
}
