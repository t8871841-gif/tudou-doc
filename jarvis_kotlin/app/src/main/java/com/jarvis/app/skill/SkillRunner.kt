package com.jarvis.app.skill

import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.jarvis.app.AuditLog
import com.jarvis.app.JarvisAccessibilityService
import com.jarvis.app.command.DeviceCommands
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.screen.TapMarkerUi
import com.jarvis.app.vision.ScreenVisionAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * 批四：技能重播引擎。按序执行步骤，全部走现成原语（open=系统启动意图、
 * tap=ScreenVisionAgent 网格看屏、swipe=无障碍现成方法）。
 *
 * 第一梯队批三升级：
 * 1）新增 input 步骤（{"t":"input","text":"要打的字"}）——假设紧邻的上一个 tap 步骤
 *    已经点进了一个输入框，直接对"当前焦点编辑框"调用 ScreenController.typeText，
 *    不额外做目标定位。这是刻意的简化：技能录制时本来就是"先点输入框、再打字"
 *    的顺序，复用这个顺序假设比让 input 步骤自己重新找一遍输入框目标更省事、也更
 *    符合用户录制时的真实操作习惯；如果技能文件手改成 input 排在 open 后面没有
 *    紧跟 tap，大概率会因为"没有焦点编辑框"直接失败，如实报错，不会假装打上了字。
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
 */
object SkillRunner {

    private val main = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private const val STEP_GAP_MS = 500L
    private const val OPEN_SETTLE_MS = 1200L

    /**
     * [fromIndex] 默认 0（从头执行）；断点续播时调用方传入失败时的步骤序号。
     * [onDone] 的第三个参数是失败时停在哪一步（0-based），成功时为 null——调用方
     * 拿它登记 [SkillResumeState]，不用在这里直接碰那个状态机（SkillRunner 不该知道
     * "用户要不要继续"这种对话层的事，只负责执行和如实报告停在哪）。
     */
    fun run(ctx: Context, skill: SkillItem, fromIndex: Int = 0, onDone: (Boolean, String, Int?) -> Unit) {
        val steps = SkillService.parseSteps(skill.stepsJson)
        if (steps.isEmpty()) {
            scope.launch { SkillService.recordFailure(ctx, skill.id) }
            onDone(false, "技能里没有步骤，可能已损坏", null)
            return
        }
        val start = fromIndex.coerceIn(0, steps.size - 1)
        val label = if (start > 0) "从第 ${start + 1} 步续播，共 ${steps.size} 步" else "开始重播 ${steps.size} 步"
        AuditLog.append(ctx, "技能「${skill.name}」", label, "进行中")
        // input 步骤需要 ScreenController 操作焦点编辑框；只在技能真的含 input/tap 步骤时才
        // 建一个，没有的话（纯 open/wait/swipe 技能）不白建一个用不上的实例。
        val needsController = steps.any { it.optString("t") == "input" }
        val controller = if (needsController) {
            val svc = JarvisAccessibilityService.instance
            if (svc != null) ScreenController(svc, TapMarkerUi(svc)) else null
        } else null
        runStep(ctx, skill, steps, start, controller, onDone)
    }

    private fun runStep(
        ctx: Context, skill: SkillItem, steps: List<org.json.JSONObject>, index: Int,
        controller: ScreenController?,
        onDone: (Boolean, String, Int?) -> Unit
    ) {
        if (index >= steps.size) {
            scope.launch { SkillService.recordSuccess(ctx, skill.id) }
            AuditLog.append(ctx, "技能「${skill.name}」", "重播完成，共 ${steps.size} 步", "成功")
            onDone(true, "", null)
            return
        }
        val step = steps[index]
        val n = index + 1
        fun next(delayMs: Long) {
            main.postDelayed({ runStep(ctx, skill, steps, index + 1, controller, onDone) }, delayMs)
        }
        fun fail(detail: String) {
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
            "input" -> {
                val text = step.optString("text")
                if (text.isBlank()) {
                    fail("输入步骤没有写要打的字")
                } else if (controller == null) {
                    fail("无障碍服务不可用，打不了字")
                } else {
                    try {
                        val result = controller.typeText(text, null)
                        if (result.optBoolean("performed")) {
                            next(STEP_GAP_MS)
                        } else {
                            fail("打字没成功：${result.optString("error", "原因不明")}")
                        }
                    } catch (e: IllegalStateException) {
                        fail("打字失败：${e.message}（通常是上一步没真的点进输入框）")
                    } catch (e: Exception) {
                        fail("打字出错：${e.message}")
                    }
                }
            }
            "sys" -> {
                val action = step.optString("action")
                if (action.isBlank()) {
                    fail("场景步骤没有写 action")
                } else {
                    var detail = ""
                    val recognized = DeviceCommands.runSceneAction(ctx, action) { detail = it }
                    if (recognized) next(STEP_GAP_MS) else fail("不认识的场景动作：$action（可用：${DeviceCommands.sceneActionHints()}）")
                }
            }
            "wait" -> {
                val ms = step.optLong("ms", 800L).coerceIn(0L, 15000L)
                main.postDelayed({ runStep(ctx, skill, steps, index + 1, controller, onDone) }, ms)
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
