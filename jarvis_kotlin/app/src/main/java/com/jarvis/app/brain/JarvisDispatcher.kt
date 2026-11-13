package com.jarvis.app.brain

import android.content.Context
import com.jarvis.app.net.DeepSeekLlmClient
import com.jarvis.app.net.ToolSchemas
import com.jarvis.app.screen.ScreenController
import com.jarvis.app.task.TaskLoop
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicInteger

/**
 * Jarvis 版 CommandDispatcher.dispatch：LocalBrain 调执行层的唯一口子。
 *
 * Andee 那边这个口子是 2123 行的 CommandDispatcher（场景、保险库、会议、
 * 机器狗、集市全在里面）；Jarvis 不需要那套——大脑真正调得到的只有工具表
 * 里的 29 个手机操作（ToolSchemas.all()），所以这个适配器只做三件事：
 *
 * 1. 带图的四件（ui_tree / wait / screenshot / look_region）直接调
 *    ScreenController、原样返回它的 JSON——里面已经有真正的 png_base64，
 *    LocalBrain 的 extractImages 会把它抽出来附到下一轮。不要走
 *    dispatchToolCall 那条路：那边的 StepResult 把图转成了 JPEG，key 却
 *    还是 png_base64，标错格式。
 * 2. 其余 25 个按方法名反查工具名，拼成 ToolCall 走 TaskLoop 现成的
 *    dispatchToolCall（手势/输入/拉起走 execute() 的 deadline 与自动 zoom
 *    补救；设备类直接实现；5 个没执行器的如实报“暂不支持”）。
 * 3. Andee dispatch 门口的两道守卫原样保留：静默轮只准 note.*（Jarvis 没
 *    有 note.* 执行器，静默轮一律拒——Jarvis 也不跑 Andee 那种夜间扫读）；
 *    不可信轮（通知触发）关掉 vault 与场景写操作（Jarvis 没有这两套，守
 *    卫只是防以后接上时漏掉）。
 *
 * send_chat_message（Jarvis 自加的五步核验）不在这里：它不在 1:1 工具表
 * 里，LocalBrain 的工具列表里没有它，模型调不到——这道关换脑后就是没了，
 * 见换脑方案（用户已点头）。方法名反查不到时回 "No such tool"，与
 * LocalBrain 自己的兜底同款。
 *
 * 构造时就要传 controller（无障碍服务里的 ScreenController）；JarvisAgent
 * 在第 5 批入口接线时负责建这个适配器、传给 LocalBrain。
 */
class JarvisDispatcher(
    private val ctx: Context,
    private val controller: ScreenController,
) {

    /** 方法名 → 工具名（ToolSchemas.all() 的 spec 里 name 和 method 都有）。 */
    private val methodToName: Map<String, String> by lazy {
        val map = mutableMapOf<String, String>()
        val specs = ToolSchemas.all()
        for (i in 0 until specs.length()) {
            val spec = specs.optJSONObject(i) ?: continue
            val method = spec.optString("method")
            val name = spec.optString("name")
            if (method.isNotEmpty() && name.isNotEmpty()) map[method] = name
        }
        map
    }

    private val callSeq = AtomicInteger(0)

    fun dispatch(
        method: String,
        params: JSONObject?,
        driving: Boolean = true,
        silent: Boolean = false,
        untrusted: Boolean = false,
    ): Any? {
        if (silent && !method.startsWith("note.")) {
            throw IllegalStateException(
                "A silent turn may only use the notebook (note.*); it must not touch the device: $method",
            )
        }
        if (untrusted && method.startsWith("device.vault.")) {
            throw IllegalStateException(
                "This turn started from a notification somebody else sent, so the vault is closed " +
                    "for it: $method. Tell the user what you were about to do and let them ask for " +
                    "it themselves.",
            )
        }
        if (untrusted && method in setOf("scene.save", "scene.enter", "scene.delete")) {
            throw IllegalStateException(
                "This turn started from a notification somebody else sent, so scenes cannot be " +
                    "created, entered or deleted from it: $method. If it seems worth doing, ask the " +
                    "user next time they talk to you.",
            )
        }
        val args = params ?: JSONObject()
        return when (method) {
            "screen.ui_tree" -> controller.dumpUiTree(
                verbose = args.optBoolean("verbose", false),
                withShot = args.optBoolean("with_shot", ToolSchemas.UI_TREE_WITH_SHOT_DEFAULT),
            )
            "screen.wait" -> {
                val seconds = args.optDouble("seconds", Double.NaN)
                if (seconds.isNaN() || seconds <= 0) {
                    "wait_for_screen 需要正数的 seconds"
                } else {
                    Thread.sleep((seconds * 1000).toLong().coerceAtMost(20000))
                    controller.dumpUiTree(verbose = false, withShot = true)
                }
            }
            "screen.screenshot" -> controller.screenshot()
            "screen.look_region" -> {
                val cell = args.optString("cell").ifEmpty { null }
                when {
                    cell != null -> controller.zoomAim(cell, args.optString("part").ifEmpty { null })
                    args.has("x") && args.has("y") ->
                        controller.zoomAimAt(args.optInt("x"), args.optInt("y"))
                    else -> "zoom_screen_region 需要 cell 或者 x/y"
                }
            }
            else -> {
                val name = methodToName[method] ?: return "No such tool: $method"
                val call = DeepSeekLlmClient.ToolCall(
                    id = "jarvis-${callSeq.incrementAndGet()}",
                    name = name,
                    argumentsRaw = args.toString(),
                )
                val r = TaskLoop.dispatchToolCall(ctx, controller, call)
                r.summary
            }
        }
    }
}
