package com.jarvis.app.brain

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.OrbController
import com.jarvis.app.TakeoverBorder
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.screen.TapMarkerUi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 换脑第 5 批：任务大脑的唯一入口。JarvisAgent（用户任务）与 TripLoop
 * （内部高德核验）都走这里，不再直接调 TaskLoop.run。
 *
 * 每次任务现场建一个 LocalBrain（配置按模型开关 + TaskRouter 现算，
 * 见 JarvisBrain.forTask）。不复用实例：LocalBrain.stop() 会关掉它自己
 * 的线程池，停过的大脑不能再跑第二轮；现场建也等于每任务历史清零，
 * 与原来 TaskLoop 每任务新历史的行为一致（跨任务记忆走 Notebook）。
 *
 * 清理（接管框收起、球回原位、自建控制器注销、current 槽清空）是幂等的，
 * 三处触发共用同一份：onFinal（正常结束 / 出错 / 没 Key）、onTurnEnd
 * （launch 的 finally 里调，正常结束时走这里）与 stopCurrent（急停时
 * onFinal 与 onTurnEnd 都不调——generation 已变，runTurn 直接 return，
 * launch 的 finally 也跳过 onTurnEnd——所以停的那一下由 stopCurrent
 * 自己清）。三处谁先跑到谁清，后到的都是空操作。
 */
object BrainRunner {

    @Volatile
    private var current: LocalBrain? = null

    @Volatile
    private var pendingCleanup: (() -> Unit)? = null

    fun isRunning(): Boolean = current?.isBusy() == true

    /**
     * 急停：停掉当前大脑（实例此后作废），并把任务期 UI 状态清掉。幂等，
     * 任务已结束时调用也只是空操作。
     */
    fun stopCurrent() {
        val brain = current
        current = null
        brain?.stop()
        val cleanup = pendingCleanup
        pendingCleanup = null
        cleanup?.invoke()
    }

    /**
     * 跑一个任务：现场建大脑、建分发器、提交。异步返回（大脑在自己的
     * 线程上跑），结果走 [onFinal]，只调一次。
     *
     * 已有任务在跑时直接回绝（TaskLoop.run 的 running 守卫同款），
     * 无障碍服务没开、或当前模式的 Key 没填时也直接回绝——三种情况
     * 都走 onFinal 回话，返回 null。正常提交返回建好的大脑实例。
     */
    fun runTask(
        ctx: Context, taskText: String,
        isAlive: () -> Boolean,
        onProgress: (String) -> Unit = {},
        onFinal: (String) -> Unit,
    ): LocalBrain? {
        if (isRunning()) {
            onFinal("已经有一个任务在进行中，等它做完或喊停再说新的。")
            return null
        }
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            onFinal("无障碍服务没有开启，跨应用任务做不了。请到设置里打开 Jarvis 无障碍服务。")
            return null
        }
        val appCtx = ctx.applicationContext
        val cfg = JarvisBrain.forTask(appCtx, taskText)
        if (cfg.apiKey.isBlank()) {
            // 没 Key 时 LocalBrain.submit 也只会回这句、不启动 turn；这里先拦，
            // 不建大脑也不挂接管框，并返回 null 让阻塞调用方按“没跑起来”处理，
            // 别把这句当成高德核实结果喂给改行程模型。
            onFinal(AppLocale.str(appCtx, R.string.brain_no_key))
            return null
        }
        // 控制器复用服务持有的长期实例（TaskLoop.runBlocking 同款 M14）；
        // 拿不到才新建一个当兜底，并注册上去、结束时注销。
        val existingController = JarvisAccessibilityService.screenController
        val controller = existingController ?: ScreenController(svc, TapMarkerUi(svc))
        val ownsController = existingController == null
        if (ownsController) JarvisAccessibilityService.screenController = controller
        controller.clearImeCache()
        AuditLog.append(ctx, "任务大脑", taskText, "开始（LocalBrain，上限 ${JarvisBrain.MAX_STEPS} 步）")
        TakeoverBorder.show()
        OrbController.setTaskHidden(true)
        var brain: LocalBrain? = null
        var cleaned = false
        val cleanup: () -> Unit = {
            if (!cleaned) {
                cleaned = true
                TakeoverBorder.hide()
                OrbController.setTaskHidden(false)
                if (ownsController && JarvisAccessibilityService.screenController === controller) {
                    JarvisAccessibilityService.screenController = null
                }
                if (current === brain) current = null
                pendingCleanup = null
            }
        }
        val dispatcher = JarvisDispatcher(appCtx, controller)
        val created = LocalBrain(
            cfg = cfg,
            appContext = appCtx,
            dispatcher = dispatcher,
            registry = CallExtensionRegistry(),
            market = MarketTools(),
            onProgress = { prog -> if (isAlive()) onProgress(prog) },
            onFinal = { reply ->
                cleanup()
                if (isAlive()) onFinal(reply)
            },
            onTurnEnd = { cleanup() },
        )
        brain = created
        current = created
        pendingCleanup = cleanup
        try {
            created.submit(taskText)
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            // 极窄的竞态：submit 之前另一路（新指令的 hardResetToIdle /
            // TripLoop 核验的 stopCurrent）已经把这个刚建好的大脑停了，
            // 线程池已关，submit 递不进去。停的那一下已经清过场，这里
            // 只补一次清理，并返回 null（turn 没跑起来），不回话——
            // 打断者自己会回话；阻塞调用方按“没返回结果”处理，不会
            // 空等满超时。
            cleanup()
            return null
        }
        return created
    }

    /**
     * 阻塞版：给 TripLoop 内部核验用。等 onFinal 回结果，超时或被打断
     * 时 stopCurrent 停掉核验、返回说明串（前缀与原来一字不差，
     * isVerifyFailure 靠它们认失败）。大脑被拒（已有任务在跑 / 服务
     * 没开）时直接返回“核实没返回结果”，不把回绝语当成高德事实喂给
     * 改行程模型。
     */
    fun runTaskBlocking(
        ctx: Context, taskText: String,
        isAlive: () -> Boolean, timeoutMs: Long,
    ): String {
        val latch = CountDownLatch(1)
        var result: String? = null
        val brain = runTask(ctx, taskText, isAlive = isAlive) { reply ->
            result = reply
            latch.countDown()
        }
        if (brain == null) return "核实没返回结果"
        val deadline = System.currentTimeMillis() + timeoutMs
        while (true) {
            if (!isAlive()) {
                stopCurrent()
                return "核实被打断（用户发了新指令），没拿到结果"
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                stopCurrent()
                return "核实超时（${timeoutMs / 1000 / 60} 分钟没跑完），没拿到结果"
            }
            if (latch.await(minOf(remaining, 10_000), TimeUnit.MILLISECONDS)) break
        }
        val r = result
        if (r.isNullOrBlank()) return "核实没返回结果"
        return r
    }
}
