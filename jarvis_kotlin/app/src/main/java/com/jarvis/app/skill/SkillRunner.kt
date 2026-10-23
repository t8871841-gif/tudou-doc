package com.jarvis.app.skill

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.vision.ScreenVisionAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 批四：技能重播引擎。按序执行步骤，全部走现成原语（open=系统启动意图、tap=ScreenVisionAgent
 * 网格看屏、swipe=无障碍现成方法），不另写截图/点击。任一步失败立即停、计数并如实上报，
 * 不自动回退现场执行——部分步骤可能已生效，重走会重复动作（需求评审时的明确决定）。
 */
object SkillRunner {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private const val STEP_GAP_MS = 500L
    private const val OPEN_SETTLE_MS = 1200L

    fun run(ctx: Context, skill: SkillItem, onDone: (Boolean, String) -> Unit) {
        val steps = SkillService.parseSteps(skill.stepsJson)
        if (steps.isEmpty()) {
            scope.launch { SkillService.recordFailure(ctx, skill.id) }
            onDone(false, "技能里没有步骤，可能已损坏")
            return
        }
        AuditLog.append(ctx, "技能「${skill.name}」", "开始重播 ${steps.size} 步", "进行中")
        runStep(ctx, skill, steps, 0, onDone)
    }

    private fun runStep(
        ctx: Context, skill: SkillItem, steps: List<org.json.JSONObject>, index: Int,
        onDone: (Boolean, String) -> Unit
    ) {
        if (index >= steps.size) {
            scope.launch { SkillService.recordSuccess(ctx, skill.id) }
            AuditLog.append(ctx, "技能「${skill.name}」", "重播完成，共 ${steps.size} 步", "成功")
            onDone(true, "")
            return
        }
        val step = steps[index]
        val n = index + 1
        fun next(delayMs: Long) {
            main.postDelayed({ runStep(ctx, skill, steps, index + 1, onDone) }, delayMs)
        }
        fun fail(detail: String) {
            scope.launch { SkillService.recordFailure(ctx, skill.id) }
            AuditLog.append(ctx, "技能「${skill.name}」", "第 $n 步失败", detail)
            onDone(false, "第 $n 步失败：$detail")
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
                        ctx.startActivity(intent)
                        next(OPEN_SETTLE_MS)
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
                    ScreenVisionAgent.locateAndTap(ctx, target, "技能「${skill.name}」第 $n 步") { ok, detail ->
                        if (ok) next(STEP_GAP_MS) else fail(detail)
                    }
                }
            }
            "wait" -> {
                val ms = step.optLong("ms", 800L).coerceIn(0L, 15000L)
                main.postDelayed({ runStep(ctx, skill, steps, index + 1, onDone) }, ms)
            }
            "swipe" -> {
                val svc = JarvisAccessibilityService.instance
                if (svc == null) {
                    fail("无障碍服务不可用，滑不了")
                } else {
                    svc.swipeScreen(step.optString("dir", "down") != "up") { ok, _ ->
                        if (ok) next(STEP_GAP_MS) else fail("滑动被拒绝")
                    }
                }
            }
            else -> fail("看不懂的步骤类型：${step.optString("t")}")
        }
    }
}
