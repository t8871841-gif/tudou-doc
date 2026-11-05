package com.jarvis.app.skill

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.accessibility.AccessibilityNodeInfo
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.command.DeviceCommands
import com.jarvis.app.command.LocalCommands
import com.jarvis.app.config.ScenePresets
import com.jarvis.app.screen.A11yIme
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.screen.TapMarkerUi
import com.jarvis.app.vision.ScreenVisionAgent
import java.util.ArrayDeque
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 批四：技能重播引擎。按序执行步骤，全部走现成原语（open=系统启动意图、
 * tap=ScreenVisionAgent 网格看屏、swipe=无障碍现成方法）。
 *
 * 第一梯队批三升级：
 * 1）input 步骤（{"t":"input","text":"要打的字"}）——每步 input 前重新取服务、
 *    校验焦点节点是可编辑输入框；没有焦点时先在树里找可点的输入框点进去再打，
 *    找不到就明说"没点进输入框"，不假设上一步 tap 一定点进了框。
 * 2）失败不再是终点——记下失败的步骤序号登记进 [SkillResumeState]，调用方
 *    （JarvisAgent）据此可以在用户说"继续"时用 [fromIndex] 重新发起，跳过已经
 *    成功过的步骤，不整条重放。
 *
 * 第二梯队批六新增：sys 步骤（{"t":"sys","action":"dnd_on"}）——"场景联动"复用技能机制
 * 实现，不是另起一套场景系统：把开勿扰、调音量这类系统动作也做成技能的一种步骤类型，
 * 用户用"新建技能"把几个系统动作和/或跨应用步骤编排在一起，就是一个"场景"（比如
 * "睡觉模式"="sys:dnd_on" + "sys:volume_down"）。可用的 action 关键字见
 * `DeviceCommands.sceneActionHints()`，执行逻辑直接复用 `DeviceCommands.runSceneAction`，
 * 不重复实现一遍系统调用。
 *
 * 2026-11-05 审计修复（只改本文件）：
 * S1 open 步骤校验 launchApp 返回值 + 前台包名，不再丢弃返回值继续跑；
 * S19 加 cancelInFlight()，每步校验 seq（JarvisAgent.hardResetToIdle() 需另行接线调它）；
 * M14 tap 失败重试 1–2 次，成功后校验界面是否变化；
 * M15 swipe 显式匹配 up/down/left/right，未知方向直接 fail；
 * M16 input 每步重取服务、校验可编辑焦点、找不到输入框就明说；
 * M17 sys 步骤对可校验的动作（勿扰/音量/静音/亮度）读回实际状态核对，
 *   校验不了的（手电/面板/只读上报）只记审计"已尝试，但未确认是否生效"；
 *   注意 DeviceCommands.runSceneAction() 除未知动作外一律返回 true，
 *   返回值本身仍不可信，需另一路修 DeviceCommands；
 * L9 续播起点越界返回明确错误，不再静默钳制；
 * L10 不再在开头按全部步骤建控制器（input 每步现建现用，swipe 左右向同理）；
 * L11 wait 上限放宽到 120 秒、按 15 秒分段等待，超限截断时写审计说明。
 */
object SkillRunner {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private const val STEP_GAP_MS = 500L
    private const val OPEN_SETTLE_MS = 1200L
    private const val UI_SETTLE_MS = 800L
    private const val MAX_WAIT_MS = 120_000L
    private const val WAIT_CHUNK_MS = 15_000L
    private const val TAP_MAX_ATTEMPTS = 3 // 首次 + 重试 2 次
    private const val INPUT_FOCUS_WAIT_MS = 700L
    /** M16：等界面签名（captureScreenSig）的超时——回调不到就报错返回，不要永远等。 */
    private const val CAPTURE_SIG_TIMEOUT_MS = 5000L

    /**
     * S19：取消序号。run() 启动新一轮时自增（作废上一轮），cancelInFlight() 自增；
     * runStep/next() 及各异步回调每步先校验 seq，不一致就悄悄停下，不再点按/打字。
     * 注意：JarvisAgent.hardResetToIdle() 目前只取消 TaskLoop/TripLoop 等，
     * 不调这里——需要它在 hardReset 时调 SkillRunner.cancelInFlight()（总协调接线）。
     */
    private val runSeq = AtomicInteger(0)

    fun cancelInFlight() {
        runSeq.incrementAndGet()
    }

    // ---------- 场景接线（Andee ScenePresets 移植，M15/L15） ----------
    //
    // Jarvis 的“场景”以前只是 sys 步骤的拼盘（几个系统动作编排在一个技能里），
    // 没有生命周期：进没进不知道，退出不恢复，触发应用也不会自动进。
    // 这里只接三根线，执行引擎本身（run/runStep）一个字没动：
    //  - 进入：ScenePresets.beginEnter 先拍进入前快照、记活动场景，再把场景的
    //    stepsJson 当成一个技能跑一遍（id=0 的合成 SkillItem，只占跑步骤的道，
    //    不进技能库；成功/失败不记技能的 successCount/failCount，因为库里没它）。
    //  - 退出：停掉还在跑的场景步骤，按快照把系统状态恢复回去。
    //  - 自动进入：触发应用命中时（由总协调从前台包变化处调），和手动进入走同一条路。
    // 注意：进入场景会自增 runSeq，等于取消上一轮还在跑的技能——一次只做一件事。

    /**
     * 进入场景。先做模式切换（快照 + 记活动场景），再跑场景的步骤。
     * [onDone] 的 ok 只代表步骤跑没跑完——场景本身只要 beginEnter 成功就算进了：
     * 步骤中途失败，场景仍在活动，调用方按 detail 播报失败即可。
     * 纯规则场景（stepsJson 为空数组）没有步骤可跑，直接报成功。
     */
    fun enterScene(ctx: Context, scene: ScenePresets.Scene, onDone: (Boolean, String) -> Unit) {
        val entered = ScenePresets.beginEnter(ctx, scene.name)
        if (entered == null) {
            onDone(false, "场景「${scene.name}」找不到了（可能已被删除）")
            return
        }
        val steps = SkillService.parseSteps(entered.stepsJson)
        if (steps.isEmpty()) {
            AuditLog.append(ctx, "场景「${entered.title}」", "进入场景", "已进入，无步骤可跑")
            onDone(true, "")
            return
        }
        val skill = SkillItem(id = 0L, name = "场景「${entered.title}」", triggerText = "", stepsJson = entered.stepsJson)
        run(ctx, skill) { ok, detail, _ -> onDone(ok, detail) }
    }

    /**
     * 退出场景：停掉还在跑的场景步骤，清活动标记，按进入快照恢复系统状态。
     * 返回播报用的一句话。没在任何场景里也返回一句话，不抛异常。
     */
    fun exitScene(ctx: Context): String {
        val (left, snapshot) = ScenePresets.exitScene(ctx)
        if (left == null) return "现在没在任何场景里"
        // 退出时停掉场景步骤的重播（如果还在跑）；注意这也会取消同一时刻
        // 在跑的普通技能——一次只做一件事，退出场景优先。
        cancelInFlight()
        val title = ScenePresets.scene(ctx, left)?.title ?: left
        if (snapshot == null) return "已退出「$title」"
        val restored = ScenePresets.restoreSnapshot(ctx, snapshot)
        AuditLog.append(ctx, "场景「$title」", "退出场景，恢复进入前状态", restored.joinToString("；"))
        return if (restored.isEmpty()) "已退出「$title」" else "已退出「$title」，${restored.joinToString("，")}"
    }

    /**
     * 前台包变成 [pkg] 时的自动进入（触发应用）。挑中场景后和手动进入走同一条路。
     * [isTaskActive] 由调用方提供（任务执行中不许被触发应用拖进场景）；
     * [onEntered] 只在真的进入了时回调一次，参数是播报用的一句话。
     * 接线点（总协调）：无障碍服务的前台包变化处，先调
     * ScenePresets.noteForegroundPackage(pkg)，再调这里。
     */
    fun maybeAutoEnterScene(
        ctx: Context,
        pkg: String,
        isTaskActive: () -> Boolean = { false },
        onEntered: (String) -> Unit,
    ) {
        val picked = ScenePresets.pickAutoEnterScene(ctx, pkg, isTaskActive) ?: return
        enterScene(ctx, picked) { ok, detail ->
            val msg = if (ok) {
                "打开 $pkg，自动进入了「${picked.title}」场景"
            } else {
                "打开 $pkg，自动进入了「${picked.title}」场景，但步骤没跑完：$detail"
            }
            onEntered(msg)
        }
    }

    /**
     * [fromIndex] 默认 0（从头执行）；断点续播时调用方传入失败时的步骤序号。
     * [onDone] 的第三个参数是失败时停在哪一步（0-based），成功时为 null——调用方
     * 拿它登记 [SkillResumeState]，不用在这里直接碰那个状态机（SkillRunner 不该知道
     * "用户要不要继续"这种对话层的事，只负责执行和如实报告停在哪）。
     */
    fun run(ctx: Context, skill: SkillItem, fromIndex: Int = 0, onDone: (Boolean, String, Int?) -> Unit) {
        val mySeq = runSeq.incrementAndGet()
        // M16：onDone 只调一次——取消分支、失败分支、成功分支都可能走到这里，
        // 用这个 guard 兜住，不让调用方收到两次“已取消/已完成”。
        val doneOnce = AtomicBoolean(false)
        val safeOnDone: (Boolean, String, Int?) -> Unit = { ok, msg, idx ->
            if (doneOnce.compareAndSet(false, true)) {
                // 差距 8c：技能重播结束（成功/失败/取消）恢复球的位置；挂起由下面的
                // setTaskHidden(true) 做，两个调用成对。
                com.jarvis.app.OrbController.setTaskHidden(false)
                onDone(ok, msg, idx)
            }
        }
        // 差距 8c：技能重播驱动屏幕期间球沉底沿（TaskLoop 同款），不只 TaskLoop 才沉底。
        com.jarvis.app.OrbController.setTaskHidden(true)
        if (fromIndex > 0) {
            // M33：续跑时重新读取最新的 SkillItem，不要用 offer 时存的旧对象——
            // 技能可能在失败后被用户改过（改短、改步骤），旧快照会重放已不存在的步骤。
            // 读不到（已被删除）就明确报错，不拿旧对象硬跑。
            scope.launch {
                val fresh = try {
                    SkillService.getAllSkills(ctx).firstOrNull { it.id == skill.id }
                } catch (_: Exception) {
                    null
                }
                if (fresh == null) {
                    AuditLog.append(ctx, "技能「${skill.name}」", "续跑前重读失败：技能找不到了（可能已被删除）", "失败")
                    safeOnDone(false, "技能「${skill.name}」找不到了（可能已被删除），没法接着做", null)
                    return@launch
                }
                startRun(ctx, fresh, fromIndex, mySeq, safeOnDone)
            }
            return
        }
        startRun(ctx, skill, fromIndex, mySeq, safeOnDone)
    }

    private fun startRun(ctx: Context, skill: SkillItem, fromIndex: Int, mySeq: Int, onDone: (Boolean, String, Int?) -> Unit) {
        val steps = SkillService.parseSteps(skill.stepsJson)
        if (steps.isEmpty()) {
            scope.launch { SkillService.recordFailure(ctx, skill.id) }
            onDone(false, "技能里没有步骤，可能已损坏", null)
            return
        }
        // L9：续播起点越界不再静默钳制——技能可能已被改短，直接报明确错误，让调用方从头重播。
        if (fromIndex < 0 || fromIndex >= steps.size) {
            scope.launch { SkillService.recordFailure(ctx, skill.id) }
            AuditLog.append(ctx, "技能「${skill.name}」", "续播起点越界：$fromIndex，共 ${steps.size} 步", "失败")
            onDone(false, "续播起点越界（技能已变，请从头重播）", null)
            return
        }
        val start = fromIndex
        val label = if (start > 0) "从第 ${start + 1} 步续播，共 ${steps.size} 步" else "开始重播 ${steps.size} 步"
        AuditLog.append(ctx, "技能「${skill.name}」", label, "进行中")
        // M33：重跑失败步之前先核对该步是否已生效（已生效就跳过，不要重放一次——
        // 比如 open 那步其实已经把应用打开了，再开一次是白跑；sys 那步想要的状态
        // 已经在了，再执行一次可能反向拨回去）。只有能核对的步骤才跳过，核对不了
        // 的（tap/input/swipe/wait）照旧重跑。
        if (start > 0 && isStepAlreadyApplied(ctx, steps[start])) {
            AuditLog.append(ctx, "技能「${skill.name}」", "第 ${start + 1} 步续跑前核对：这一步其实已经生效了，跳过不重放", "进行中")
            val nextIndex = start + 1
            if (nextIndex >= steps.size) {
                scope.launch { SkillService.recordSuccess(ctx, skill.id) }
                AuditLog.append(ctx, "技能「${skill.name}」", "重播完成，共 ${steps.size} 步（失败步已生效，跳过）", "成功")
                onDone(true, "", null)
                return
            }
            runStep(ctx, skill, steps, nextIndex, mySeq, onDone)
            return
        }
        runStep(ctx, skill, steps, start, mySeq, onDone)
    }

    /**
     * M33：失败步是否其实已经生效了。只对能通用核对的步骤回答 true：
     * open——目标应用已经在前台；sys——想要的状态已经在了（读回核对）。
     * tap/input/swipe/wait 没有通用 oracle，返回 false，照旧重跑。
     */
    private fun isStepAlreadyApplied(ctx: Context, step: org.json.JSONObject): Boolean {
        return when (step.optString("t")) {
            "open" -> {
                val pkg = step.optString("pkg")
                if (pkg.isEmpty()) false else try {
                    JarvisAccessibilityService.instance?.foregroundPackage() == pkg
                } catch (_: Exception) {
                    false
                }
            }
            "sys" -> isSysAlreadyInDesiredState(ctx, step.optString("action"))
            else -> false
        }
    }

    /** M33：sys 动作想要的终态是不是已经在了（读回当前状态比对）。校验不了的动作返回 false。 */
    private fun isSysAlreadyInDesiredState(ctx: Context, action: String): Boolean {
        val cur = readSysState(ctx, action) ?: return false
        return when (action) {
            "dnd_on" -> cur == NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "dnd_off" -> cur == NotificationManager.INTERRUPTION_FILTER_ALL
            "mute_on" -> cur == AudioManager.RINGER_MODE_SILENT
            "mute_off" -> cur == AudioManager.RINGER_MODE_NORMAL
            "volume_up" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                cur >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
            }
            "volume_down" -> cur <= 0
            "brightness_up" -> cur >= 255
            "brightness_down" -> cur <= 5
            else -> false
        }
    }

    private fun runStep(
        ctx: Context, skill: SkillItem, steps: List<org.json.JSONObject>, index: Int,
        mySeq: Int,
        onDone: (Boolean, String, Int?) -> Unit
    ) {
        // S19：每步先校验取消序号；M16：被取消就调 onDone 回调一次明确说“已取消”，
        // 不要静默返回（调用方靠它收尾播报；onDone 有 once guard，不会重复触发）。
        if (mySeq != runSeq.get()) {
            AuditLog.append(ctx, "技能「${skill.name}」", "第 ${index + 1} 步前已取消", "已取消")
            onDone(false, "已取消", index)
            return
        }
        if (index >= steps.size) {
            scope.launch { SkillService.recordSuccess(ctx, skill.id) }
            AuditLog.append(ctx, "技能「${skill.name}」", "重播完成，共 ${steps.size} 步", "成功")
            onDone(true, "", null)
            return
        }
        val step = steps[index]
        val n = index + 1
        fun cancelled(): Boolean = mySeq != runSeq.get()
        /** M16：中途被取消时向调用方报一次“已取消”（onDone 有 once guard，多处调也只生效一次）。 */
        fun reportCancelled() {
            onDone(false, "已取消", index)
        }
        fun next(delayMs: Long) {
            main.postDelayed({ runStep(ctx, skill, steps, index + 1, mySeq, onDone) }, delayMs)
        }
        fun fail(detail: String) {
            if (cancelled()) {
                reportCancelled()
                return
            }
            scope.launch { SkillService.recordFailure(ctx, skill.id) }
            AuditLog.append(ctx, "技能「${skill.name}」", "第 $n 步失败", detail)
            onDone(false, "第 $n 步失败：$detail", index)
        }
        when (step.optString("t")) {
            "open" -> {
                val pkg = step.optString("pkg")
                val intent = try {
                    ctx.packageManager.getLaunchIntentForPackage(pkg)
                } catch (_: Exception) {
                    null
                }
                if (intent == null) {
                    fail("打不开应用 $pkg（可能没装）")
                } else {
                    try {
                        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        // 全屏启动（jk-v66）：前台拉起，Flyme 给全屏；反射已死、拖展开靠不住。
                        // S1：不再丢弃返回值——只有返回值以"正在打开"开头，且前台包名等于目标包，
                        // 才算打开成功；否则按失败处理，把返回的失败串写进结果。
                        val result = LocalCommands.launchAppFullscreenViaActivity(ctx, pkg, pkg)
                        val svc = JarvisAccessibilityService.instance
                        val fg = try {
                            svc?.foregroundPackage().orEmpty()
                        } catch (_: Exception) {
                            ""
                        }
                        if (!result.startsWith("正在打开")) {
                            fail("打开 $pkg 失败：$result")
                        } else if (svc != null && fg != pkg) {
                            fail("打开 $pkg 失败：$result，但前台是 ${fg.ifEmpty { "未知" }}，目标没到前台")
                        } else {
                            if (svc == null) {
                                // M32：无障碍缺失的 open 步不要只记审计就 next()——没法确认
                                // 目标是不是真的到前台了，停下明确报错，不当成功继续跑。
                                fail("打开 $pkg 后无障碍服务不可用，没法确认目标是不是真的打开了")
                            } else {
                                next(OPEN_SETTLE_MS)
                            }
                        }
                    } catch (e: Exception) {
                        fail("打开 $pkg 出错：${e.message}")
                    }
                }
            }
            "tap" -> {
                val target = step.optString("target")
                if (target.isBlank()) {
                    fail("点按步骤没有写目标")
                } else {
                    // M14：失败先等界面稳定后重试 1–2 次；成功后校验界面是否变化，
                    // 没变化也重试一次，仍没变化才上报（可能没点中目标）。
                    fun tapAttempt(attempt: Int) {
                        if (cancelled()) {
                            reportCancelled()
                            return
                        }
                        captureScreenSig { preSig, preTimedOut ->
                            if (cancelled()) {
                                reportCancelled()
                                return@captureScreenSig
                            }
                            // M16：点按前的签名回调不到（超时）就报错返回，不要永远等下去。
                            if (preTimedOut) {
                                fail("读界面签名超时：等了 ${CAPTURE_SIG_TIMEOUT_MS / 1000} 秒没回来，没法确认点按前后界面变没变")
                                return@captureScreenSig
                            }
                            ScreenVisionAgent.locateAndTap(ctx, target, "技能「${skill.name}」第 $n 步") { ok, detail ->
                                if (cancelled()) {
                                    reportCancelled()
                                    return@locateAndTap
                                }
                                if (!ok) {
                                    if (attempt + 1 < TAP_MAX_ATTEMPTS) {
                                        AuditLog.append(ctx, "技能「${skill.name}」", "第 $n 步点按失败，界面稳定后重试（第 ${attempt + 2} 次）", detail)
                                        main.postDelayed({ tapAttempt(attempt + 1) }, UI_SETTLE_MS)
                                    } else {
                                        fail(detail)
                                    }
                                } else {
                                    main.postDelayed({
                                        if (cancelled()) {
                                            reportCancelled()
                                            return@postDelayed
                                        }
                                        captureScreenSig { postSig, postTimedOut ->
                                            if (cancelled()) {
                                                reportCancelled()
                                                return@captureScreenSig
                                            }
                                            // M16：点按后的签名回调不到（超时）就报错返回，不要永远等下去。
                                            if (postTimedOut) {
                                                fail("点了「$target」之后等界面签名超时，没法确认点中没有")
                                                return@captureScreenSig
                                            }
                                            // 截图取不到签名（安全窗口/前台是 Jarvis 自己等）时不校验，直接认成功。
                                            if (preSig != null && postSig != null && preSig == postSig) {
                                                if (attempt + 1 < TAP_MAX_ATTEMPTS) {
                                                    AuditLog.append(ctx, "技能「${skill.name}」", "第 $n 步点了但界面没变化，重试一次", "进行中")
                                                    main.postDelayed({ tapAttempt(attempt + 1) }, STEP_GAP_MS)
                                                } else {
                                                    fail("点了「$target」但界面没变化，可能没点中目标")
                                                }
                                            } else {
                                                next(STEP_GAP_MS)
                                            }
                                        }
                                    }, UI_SETTLE_MS)
                                }
                            }
                        }
                    }
                    tapAttempt(0)
                }
            }
            "input" -> {
                val text = step.optString("text")
                if (text.isBlank()) {
                    fail("输入步骤没有写要打的字")
                } else {
                    // M16：每步 input 前重新取服务并校验焦点节点是可编辑输入框；
                    // 没有焦点时先定位输入框点进去再打，失败明说"没点进输入框"。
                    // M14：控制器用无障碍服务持有的长期 ScreenController，拿不到才现建一个兜底。
                    fun inputAttempt(focusedOnce: Boolean) {
                        if (cancelled()) {
                            reportCancelled()
                            return
                        }
                        val svc = JarvisAccessibilityService.instance
                        if (svc == null) {
                            fail("无障碍服务不可用，打不了字")
                            return
                        }
                        val fresh = sharedController(svc)
                        // 树被遮住（安全窗口）时焦点查不到，但自家输入法若已绑编辑器仍能打字。
                        if (hasEditableFocus(svc) || A11yIme.editing(svc)) {
                            try {
                                val result = fresh.typeText(text, null)
                                if (result.optBoolean("performed")) {
                                    next(STEP_GAP_MS)
                                } else {
                                    fail("打字没成功：${result.optString("error", "原因不明")}")
                                }
                            } catch (e: IllegalStateException) {
                                fail("打字失败：${e.message}（没点进输入框）")
                            } catch (e: Exception) {
                                fail("打字出错：${e.message}")
                            }
                        } else if (!focusedOnce && clickFirstEditable(svc)) {
                            main.postDelayed({ inputAttempt(true) }, INPUT_FOCUS_WAIT_MS)
                        } else {
                            fail("没点进输入框：当前没有可编辑的输入焦点，也找不到可点的输入框")
                        }
                    }
                    inputAttempt(false)
                }
            }
            "sys" -> {
                val action = step.optString("action")
                if (action.isBlank()) {
                    fail("场景步骤没有写 action")
                } else {
                    var detail = ""
                    // M17：不要只看返回值就继续——DeviceCommands.runSceneAction()
                    // 除未知动作外一律返回 true，成功与否它不告诉你。先读执行前状态，
                    // 执行后对可校验的动作读回实际状态核对；校验不了的动作只记审计。
                    val before = readSysState(ctx, action)
                    val recognized = DeviceCommands.runSceneAction(ctx, action) { detail = it }
                    if (!recognized) {
                        fail("不认识的场景动作：$action（可用：${DeviceCommands.sceneActionHints()}）")
                    } else {
                        when (verifySysState(ctx, action, before)) {
                            SysCheck.OK -> next(STEP_GAP_MS)
                            SysCheck.UNVERIFIABLE -> {
                                // M32：不可校验的 sys 步骤不要当成功继续跑——停下并明确报错，
                                // 只记一句审计就 next() 会让没生效的步骤混进“成功”。
                                AuditLog.append(ctx, "技能「${skill.name}」", "第 $n 步 sys:$action 已尝试，但未确认是否生效", detail)
                                fail("已尝试 $action，但没法确认是否生效（这步校验不了），不敢当成功继续跑")
                            }
                            SysCheck.FAILED -> fail("已尝试 $action，但未确认是否生效：状态没变（可能没权限或被系统拒绝）")
                        }
                    }
                }
            }
            "wait" -> {
                // L11：上限放宽到 120 秒、按 15 秒分段等待（每段都可被取消）；
                // 超过上限才截断，并在审计里说明已截断，不再静默截断。
                val asked = step.optLong("ms", 800L)
                val total = asked.coerceIn(0L, MAX_WAIT_MS)
                if (asked > MAX_WAIT_MS) {
                    AuditLog.append(ctx, "技能「${skill.name}」", "第 $n 步 wait 要求 ${asked}ms，超过上限 ${MAX_WAIT_MS}ms，已截断为 ${MAX_WAIT_MS}ms", "进行中")
                }
                fun waitChunk(remaining: Long) {
                    if (cancelled()) {
                        reportCancelled()
                        return
                    }
                    if (remaining <= 0) {
                        runStep(ctx, skill, steps, index + 1, mySeq, onDone)
                        return
                    }
                    val chunk = minOf(remaining, WAIT_CHUNK_MS)
                    main.postDelayed({ waitChunk(remaining - chunk) }, chunk)
                }
                waitChunk(total)
            }
            "swipe" -> {
                val svc = JarvisAccessibilityService.instance
                if (svc == null) {
                    fail("无障碍服务不可用，滑不了")
                } else {
                    // M15：显式匹配四个方向，未知方向直接 fail，不再除 up 外全当 down。
                    // 左右向无障碍服务没有现成方法，用现建的 ScreenController 按坐标划。
                    when (val dir = step.optString("dir", "down")) {
                        "up" -> svc.swipeScreen(false) { ok, _ ->
                            if (cancelled()) {
                                reportCancelled()
                                return@swipeScreen
                            }
                            if (ok) next(STEP_GAP_MS) else fail("滑动被拒绝")
                        }
                        "down" -> svc.swipeScreen(true) { ok, _ ->
                            if (cancelled()) {
                                reportCancelled()
                                return@swipeScreen
                            }
                            if (ok) next(STEP_GAP_MS) else fail("滑动被拒绝")
                        }
                        "left", "right" -> {
                            try {
                                val m = svc.resources.displayMetrics
                                val w = m.widthPixels
                                val y = m.heightPixels / 2
                                val (x1, x2) = if (dir == "left") (w * 3 / 4) to (w / 4) else (w / 4) to (w * 3 / 4)
                                // M14：左右向滑动也用服务持有的长期控制器，拿不到才现建一个兜底。
                                val controller = sharedController(svc)
                                val result = controller.swipe(x1, y, x2, y, 320L, false)
                                if (result.optBoolean("completed")) next(STEP_GAP_MS) else fail("向 $dir 滑动没完成")
                            } catch (e: Exception) {
                                fail("向 $dir 滑动出错：${e.message}")
                            }
                        }
                        else -> fail("不认识的滑动方向：$dir（只认 up/down/left/right）")
                    }
                }
            }
            else -> fail("看不懂的步骤类型：${step.optString("t")}")
        }
    }

    // ---------- M14：界面变化校验 ----------

    /** M14：优先用无障碍服务持有的长期 ScreenController，拿不到才新建一个当兜底。 */
    private fun sharedController(svc: JarvisAccessibilityService): ScreenController =
        JarvisAccessibilityService.screenController ?: ScreenController(svc, TapMarkerUi(svc))

    /**
     * 取当前屏 16x16 灰度签名（captureScreen 的 sig），取不到返回 null（不因此判失败）。回调一律回主线程。
     * M16：加超时兜底——[CAPTURE_SIG_TIMEOUT_MS] 内回调不到就以 timedOut=true 回一次，
     * 不要永远等；调用方据此报错返回。已触发过一次后，后到的回调直接丢弃。
     */
    private fun captureScreenSig(done: (sig: String?, timedOut: Boolean) -> Unit) {
        val svc = JarvisAccessibilityService.instance ?: run { done(null, false); return }
        val fired = AtomicBoolean(false)
        fun fire(sig: String?, timedOut: Boolean) {
            if (fired.compareAndSet(false, true)) main.post { done(sig, timedOut) }
        }
        try {
            svc.captureScreen { result -> fire(result["sig"] as? String, false) }
        } catch (_: Exception) {
            fire(null, false)
            return
        }
        main.postDelayed({ fire(null, true) }, CAPTURE_SIG_TIMEOUT_MS)
    }

    // ---------- M16：输入焦点校验 ----------

    /** 当前焦点节点是不是可编辑输入框。 */
    private fun hasEditableFocus(svc: JarvisAccessibilityService): Boolean {
        val root = svc.rootInActiveWindow ?: return false
        var focused: AccessibilityNodeInfo? = null
        try {
            focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            return focused?.isEditable == true
        } catch (_: Exception) {
            return false
        } finally {
            try {
                if (focused !== root) focused?.recycle()
            } catch (_: Exception) {
            }
            try {
                root.recycle()
            } catch (_: Exception) {
            }
        }
    }

    /** 在无障碍树里找第一个可见的可编辑节点并点它（让它拿到输入焦点）。找不到返回 false。 */
    private fun clickFirstEditable(svc: JarvisAccessibilityService): Boolean {
        val root = svc.rootInActiveWindow ?: return false
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var checked = 0
        try {
            while (queue.isNotEmpty() && checked < 300) {
                val node = queue.removeFirst()
                checked++
                try {
                    if (node.isEditable && node.isVisibleToUser) {
                        val clicked = try {
                            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        } catch (_: Exception) {
                            false
                        }
                        if (clicked) return true
                    }
                    for (i in 0 until node.childCount) {
                        try {
                            node.getChild(i)?.let { queue.add(it) }
                        } catch (_: Exception) {
                        }
                    }
                } finally {
                    try {
                        node.recycle()
                    } catch (_: Exception) {
                    }
                }
            }
            return false
        } finally {
            while (queue.isNotEmpty()) {
                try {
                    queue.removeFirst().recycle()
                } catch (_: Exception) {
                }
            }
        }
    }

    // ---------- M17：sys 步骤执行后状态核对 ----------

    private enum class SysCheck { OK, UNVERIFIABLE, FAILED }

    /** 能读回实际状态核对的动作；其余（手电/面板/只读上报）校验不了。 */
    private val VERIFIABLE_SYS_ACTIONS = setOf(
        "dnd_on", "dnd_off", "volume_up", "volume_down",
        "mute_on", "mute_off", "brightness_up", "brightness_down"
    )

    /**
     * 读 [action] 对应的当前状态值（勿扰=打断过滤器，音量=媒体音量，
     * 静音=响铃模式，亮度=0–255）。动作不可校验或读数失败返回 null。
     */
    private fun readSysState(ctx: Context, action: String): Int? = try {
        when (action) {
            "dnd_on", "dnd_off" -> {
                val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                if (!nm.isNotificationPolicyAccessGranted) null else nm.currentInterruptionFilter
            }
            "volume_up", "volume_down" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.getStreamVolume(AudioManager.STREAM_MUSIC)
            }
            "mute_on", "mute_off" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                am.ringerMode
            }
            "brightness_up", "brightness_down" ->
                Settings.System.getInt(ctx.contentResolver, Settings.System.SCREEN_BRIGHTNESS, 128)
            else -> null
        }
    } catch (_: Exception) {
        null
    }

    /** 执行后重读状态核对。已到极限（音量最大/最小、亮度 255/5）导致没变也算成功。 */
    private fun verifySysState(ctx: Context, action: String, before: Int?): SysCheck {
        if (action !in VERIFIABLE_SYS_ACTIONS) return SysCheck.UNVERIFIABLE
        if (before == null) return SysCheck.FAILED
        val after = readSysState(ctx, action) ?: return SysCheck.FAILED
        return when (action) {
            // 注意：DeviceCommands 的 dnd_on 用的是 INTERRUPTION_FILTER_PRIORITY，不是 NONE。
            "dnd_on" -> if (after == NotificationManager.INTERRUPTION_FILTER_PRIORITY) SysCheck.OK else SysCheck.FAILED
            "dnd_off" -> if (after == NotificationManager.INTERRUPTION_FILTER_ALL) SysCheck.OK else SysCheck.FAILED
            "volume_up" -> {
                val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                if (after > before || before >= am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)) SysCheck.OK else SysCheck.FAILED
            }
            "volume_down" -> if (after < before || before <= 0) SysCheck.OK else SysCheck.FAILED
            "mute_on" -> if (after == AudioManager.RINGER_MODE_SILENT) SysCheck.OK else SysCheck.FAILED
            "mute_off" -> if (after == AudioManager.RINGER_MODE_NORMAL) SysCheck.OK else SysCheck.FAILED
            "brightness_up" -> if (after > before || before >= 255) SysCheck.OK else SysCheck.FAILED
            "brightness_down" -> if (after < before || before <= 5) SysCheck.OK else SysCheck.FAILED
            else -> SysCheck.UNVERIFIABLE
        }
    }
}
