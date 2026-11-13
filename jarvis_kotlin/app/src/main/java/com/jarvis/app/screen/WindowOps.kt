package com.jarvis.app.screen

/**
 * 窗口操作抽象层（框架第 4 步，2026-11-13）。
 *
 * 之前的问题：小窗、截图权限、Shizuku 版本这些厂商行为差异，散在各处打补丁
 * 追着真机现象跑（Claude 审出的第四条结构短板）。从这里起，窗口操作只走
 * 这一处：平台差异收在实现里，调用方只认 WindowOps。
 *
 * 实现按厂商选：本机是魅族（Build.MANUFACTURER == "Meizu"）走 Flyme 实现，
 * 其余走原生实现。原生实现目前是空壳（返回 false，调用方直接报失败）——本机
 * 是 Flyme，没有第二台原生 ROM 可验，不硬写一条没验过的路。
 *
 * Flyme 实现 = 现有行为原样搬过来（Shizuku shell `am start
 * --windowingMode 1` + 拖动兜底），不是新写法。AOSP 的 windowingMode 语义
 * 在 Flyme 上已被证实不可靠（对已存在 task 只带到前台、不追溯改模式），所
 * 以 Flyme 实现里不留“原生语义一定管用”的假设。
 */
object WindowOps {

    /** 小窗展开：按厂商选实现；展开不了返回 false，调用方直接报失败。 */
    fun tryShellExpand(
        ctx: android.content.Context,
        targetPkg: String,
        before: android.graphics.Rect,
        measure: (String) -> android.graphics.Rect?,
        isFullscreen: (android.graphics.Rect) -> Boolean,
    ): Boolean {
        val expander = if (android.os.Build.MANUFACTURER.equals("Meizu", ignoreCase = true)) {
            FlymeWindowOps
        } else {
            AospWindowOps
        }
        return expander.tryExpand(ctx, targetPkg, before, measure, isFullscreen)
    }

    /** 小窗诊断：展开两次都失败时才抓一次 dumpsys 快照记进审计。
     * 只抓含目标包名的窗口行 + 焦点行（grep 在机内过滤，stdout 截 2000 字也够）；
     * 成功时不抓，不占热路径。下次看审计就知道 Flyme 把这个窗口当成什么
     * （自由窗口/私有浮窗/windowingMode），再决定复刻哪条路。 */
    fun captureWindowDump(ctx: android.content.Context, targetPkg: String) {
        if (!com.jarvis.app.task.ShizukuHelper.isShizukuAvailable(ctx)) return
        if (!com.jarvis.app.task.ShizukuHelper.hasPermission()) return
        val pkgOut = runCatching {
            com.jarvis.app.task.ShizukuHelper.runShell(
                arrayOf("sh", "-c", "dumpsys window windows | grep -i $targetPkg"),
                8000,
            )
        }.getOrNull()
        val focusOut = runCatching {
            com.jarvis.app.task.ShizukuHelper.runShell(
                arrayOf("sh", "-c", "dumpsys window windows | grep -iE 'mCurrentFocus|mFocusedApp'"),
                8000,
            )
        }.getOrNull()
        val detail = StringBuilder()
        if (pkgOut != null && pkgOut.stdout.isNotBlank()) {
            detail.append("窗口行（退出码 ${pkgOut.exitCode}）：")
                .append(pkgOut.stdout.replace("\n", " | ").take(1500))
        }
        if (focusOut != null && focusOut.stdout.isNotBlank()) {
            if (detail.isNotEmpty()) detail.append("；")
            detail.append("焦点行：")
                .append(focusOut.stdout.replace("\n", " | ").take(500))
        }
        if (detail.isEmpty()) return
        com.jarvis.app.AuditLog.append(ctx, "小窗诊断", targetPkg, detail.toString())
    }
}

/** 小窗展开的一种平台实现。measure 量窗口，isFullscreen 判全屏（≥80%），
 * 都由 ScreenController 传进来——实现里不碰控制器的内部状态。 */
interface WindowExpander {
    fun tryExpand(
        ctx: android.content.Context,
        targetPkg: String,
        before: android.graphics.Rect,
        measure: (String) -> android.graphics.Rect?,
        isFullscreen: (android.graphics.Rect) -> Boolean,
    ): Boolean
}

/** Flyme 实现：原 ScreenController.tryShellExpand 原样搬过来（只改 ctx/量窗口/
 * 判全屏的传入方式，命令、审计、等待、重测一字不动）。 */
object FlymeWindowOps : WindowExpander {
    override fun tryExpand(
        ctx: android.content.Context,
        targetPkg: String,
        before: android.graphics.Rect,
        measure: (String) -> android.graphics.Rect?,
        isFullscreen: (android.graphics.Rect) -> Boolean,
    ): Boolean {
        if (!com.jarvis.app.task.ShizukuHelper.isShizukuAvailable(ctx)) {
            com.jarvis.app.AuditLog.append(ctx, "小窗展开", "Shizuku shell", "跳过：Shizuku 不可用（未安装或守护进程未启动）")
            return false
        }
        if (!com.jarvis.app.task.ShizukuHelper.hasPermission()) {
            com.jarvis.app.AuditLog.append(ctx, "小窗展开", "Shizuku shell", "跳过：Shizuku 未授权")
            return false
        }
        val component = try {
            ctx.packageManager.getLaunchIntentForPackage(targetPkg)?.component
        } catch (_: Exception) {
            null
        }
        if (component == null) {
            com.jarvis.app.AuditLog.append(ctx, "小窗展开", "Shizuku shell", "跳过：${targetPkg} 没有 launcher 界面")
            return false
        }
        val cls = component.className
        val fullCls = if (cls.startsWith(".")) targetPkg + cls else cls
        val compArg = "$targetPkg/$fullCls"
        val result = runCatching {
            com.jarvis.app.task.ShizukuHelper.runShell(arrayOf("am", "start", "--windowingMode", "1", "-n", compArg))
        }.getOrNull()
        if (result == null) {
            com.jarvis.app.AuditLog.append(ctx, "小窗展开", "Shizuku shell", "命令没跑起来：am start --windowingMode 1 -n $compArg")
            return false
        }
        val output = result.stdout.replace("\n", " ").replace("\r", " ").take(200)
        com.jarvis.app.AuditLog.append(
            ctx, "小窗展开", "Shizuku shell",
            "am start --windowingMode 1 -n $compArg，退出码 ${result.exitCode}，输出：$output",
        )
        try {
            Thread.sleep(1000)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return false
        }
        val after = measure(targetPkg)
        val afterText = if (after == null) "没找到窗口" else "${after.width()}x${after.height()}"
        val ok = after != null && isFullscreen(after)
        com.jarvis.app.AuditLog.append(
            ctx, "小窗展开", "Shizuku shell",
            "展开前 ${before.width()}x${before.height()}，展开后 $afterText，${if (ok) "已全屏" else "仍是小窗"}",
        )
        return ok
    }
}

/** 原生实现：空壳。没有第二台原生 ROM 可验，不硬写一条没验过的路；
 * 展开不了就返回 false，调用方直接报失败。 */
object AospWindowOps : WindowExpander {
    override fun tryExpand(
        ctx: android.content.Context,
        targetPkg: String,
        before: android.graphics.Rect,
        measure: (String) -> android.graphics.Rect?,
        isFullscreen: (android.graphics.Rect) -> Boolean,
    ): Boolean = false
}
