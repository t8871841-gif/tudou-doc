package com.jarvis.app.task

import android.content.ComponentName
import android.content.Context
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.Looper
import com.jarvis.app.AuditLog
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Shizuku 帮手：关应用的强停通道（比最近任务划卡片稳），以及一个 shell（小窗展开用）。
 *
 * 版本 13.1.5（2026-11-12 由 12.2.0 升上来；见 gradle/libs.versions.toml）：
 * 13.x 里旧的直接跑进程入口已是 private（13.1.5 的 jar 经 javap 核实，标注
 * 计划在 API 14 移除），官方 changelog 明说改走 UserService。手机上的 Shizuku
 * 应用是 13.6.0，12.2.0 库的旧入口调用对它发不出去——jk-v74 真机上 shell
 * “命令没跑起来”就是这个原因。13.1.5 是 Maven Central 最新的客户端库
 * （api/provider/aidl/shared；aidl 与 shared 是 api 的传递依赖，不用另加坐标）。
 * minSdk 24，13.1.0 说的 desugaring 只针对 min API 23，不用加。
 *
 * 机制（官方 README + 服务端 UserService.create 源码，已核实）：
 * - 客户端经 `Shizuku.bindUserService(UserServiceArgs, ServiceConnection)` 绑定；
 *   服务端经 app_process 用咱们 APK 的类加载器按类名加载 ShellUserService，先试
 *   Context 构造器（没有），再用默认构造器实例化，实例必须实现 IBinder（直接继承
 *   AIDL Stub）。注意：13.1.5 的库里根本没有 `Shizuku.UserService` 这个基类，
 *   不要继承不存在的东西；也不要给 ShellUserService 加 manifest `<service>` 声明
 *   （服务端不查 manifest）。
 * - `processNameSuffix` 是必填（UserServiceArgs.forAdd 里 requireNonNull，缺了
 *   bind 时直接 NPE）；tag 显式给，免得 R8 后类名不稳；daemon 默认 true——服务进程
 *   比应用活得久（直到 Shizuku 停掉或重启手机），调用方复用绑定，符合设计，我们不
 *   主动 unbind。
 * - 绑定是异步的：onServiceConnected 走主线程回调，这里用 CountDownLatch 有界等
 *   （10 秒）；主线程上的调用不等（会死锁），这次先回 null，绑定好后下次调用可用。
 * - runShell 没有 ctx 参数：绑定要的 Context 靠各入口（isShizukuAvailable /
 *   requestPermission / forceStopPackage / pairingState）进来时顺手记下来。两处
 *   调用方（tryShellExpand、关应用）都是先调 isShizukuAvailable 再调
 *   runShell/forceStopPackage，所以用到时一定已经记下来了。
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
    private const val BIND_TIMEOUT_MS = 10_000L
    private const val SERVICE_TAG = "jarvis-shell"
    private const val SERVICE_PROCESS_SUFFIX = "shell"
    private const val SERVICE_VERSION = 1

    data class ShellResult(val stdout: String, val exitCode: Int)

    @Volatile
    private var listenerRegistered = false
    private val listenerLock = Any()

    /** 绑定用的应用 Context：runShell 没有 ctx 参数，靠各入口进来时顺手记下来。 */
    @Volatile
    private var appContext: Context? = null

    private fun rememberContext(ctx: Context) {
        if (appContext == null) appContext = ctx.applicationContext
    }

    /**
     * Shizuku 是不是真能用：应用已装，并且守护进程的 binder 还活着。
     * binder 死掉（Shizuku 被杀、重启后没再启动）时 pingBinder() 返回 false。
     */
    fun isShizukuAvailable(ctx: Context): Boolean {
        rememberContext(ctx)
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

    /** 配对状态四档：未装应用 / 应用已装但服务没启动 / 服务运行中但未授权 / 已授权。
     * isShizukuAvailable 把前两档混在一起（都是 false），设置页要分开显示才加的。 */
    enum class PairingState { NOT_INSTALLED, NOT_RUNNING, NOT_GRANTED, GRANTED }

    /** 设置页「Shizuku 配对」的状态：复用已有的 isAppInstalled + pingBinder，
     * 不在别处重写包名检查；pingBinder 抛异常按服务没启动算。 */
    fun pairingState(ctx: Context): PairingState {
        rememberContext(ctx)
        return try {
            if (!isAppInstalled(ctx, SHIZUKU_PACKAGE)) return PairingState.NOT_INSTALLED
            if (!Shizuku.pingBinder()) return PairingState.NOT_RUNNING
            if (hasPermission()) PairingState.GRANTED else PairingState.NOT_GRANTED
        } catch (_: Exception) {
            PairingState.NOT_RUNNING
        }
    }

    /**
     * 弹一次 Shizuku 授权框（不阻塞等结果——结果由注册好的监听器记进审计日志，
     * 下次关应用时再看 hasPermission()）。返回 true = 请求确实发出去了。
     */
    fun requestPermission(ctx: Context): Boolean {
        rememberContext(ctx)
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
        rememberContext(ctx)
        if (pkg.isBlank() || pkg == ctx.packageName) return false
        val result = runProcess(arrayOf("am", "force-stop", pkg), clampTimeout(timeoutMs)) ?: return false
        return result.exitCode == 0
    }

    /**
     * 跑一条命令，回 stdout（截断到约 2000 字）+ 退出码。
     * 超时 / 起不来 / 抛异常时回 exitCode = -1。小窗展开（tryShellExpand）经这里走 shell。
     */
    fun runShell(cmd: Array<String>, timeoutMs: Long = DEFAULT_TIMEOUT_MS): ShellResult {
        val result = runProcess(cmd, clampTimeout(timeoutMs)) ?: return ShellResult("", -1)
        return result
    }

    // ---------- UserService 绑定 ----------

    @Volatile
    private var shellStub: IShellService? = null

    @Volatile
    private var bindLatch: CountDownLatch? = null
    private val bindLock = Any()

    private val shellConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            try {
                shellStub = IShellService.Stub.asInterface(service)
            } catch (_: Exception) {
                shellStub = null
            }
            bindLatch?.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            shellStub = null
            synchronized(bindLock) { bindLatch = null }
        }
    }

    /**
     * 取到能跑命令的 stub：已绑定的直接复用；没绑就 bind 一次、有界等连接。
     * 任一步失败回 null（调用方照旧回落）。主线程上不等待——onServiceConnected
     * 走主线程回调，主线程上等就是死锁；这次先回 null，绑定好后下次调用可用。
     */
    private fun ensureShellBound(): IShellService? {
        shellStub?.let { return it }
        val appCtx = appContext ?: return null
        val latch: CountDownLatch
        synchronized(bindLock) {
            shellStub?.let { return it }
            val existing = bindLatch
            if (existing != null) {
                latch = existing
            } else {
                latch = CountDownLatch(1)
                bindLatch = latch
                try {
                    val args = Shizuku.UserServiceArgs(
                        ComponentName(appCtx.packageName, ShellUserService::class.java.name)
                    )
                        .processNameSuffix(SERVICE_PROCESS_SUFFIX)
                        .tag(SERVICE_TAG)
                        .version(SERVICE_VERSION)
                    Shizuku.bindUserService(args, shellConnection)
                } catch (_: Exception) {
                    latch.countDown()
                    bindLatch = null
                    return null
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) return shellStub
        val connected = try {
            latch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        return if (connected) shellStub else null
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
     * 跑一条命令：经绑定的 UserService 在 shell 进程里执行，回 stdout + 退出码。
     * 绑定不上、服务挂了、AIDL 返回异常，一律回 null（调用方按失败回落）。
     * stdout 里的 stderr 沿用老格式缀在后面（stderr 非空时）。
     */
    private fun runProcess(cmd: Array<String>, timeoutMs: Long): ShellResult? {
        val stub = ensureShellBound() ?: return null
        val out = try {
            stub.runCommand(cmd, clampTimeout(timeoutMs))
        } catch (_: Exception) {
            // 服务挂了：清掉 stub 与绑定状态，下次调用重新绑定。
            shellStub = null
            synchronized(bindLock) { bindLatch = null }
            return null
        } ?: return null
        if (out.size < 3) return null
        val exitCode = out[0].toIntOrNull() ?: -1
        val stdout = out[1]
        val stderr = out[2]
        val combined = if (stderr.isBlank()) stdout else "$stdout\n[stderr]\n$stderr"
        return ShellResult(combined.take(MAX_SHELL_OUTPUT_CHARS), exitCode)
    }
}
