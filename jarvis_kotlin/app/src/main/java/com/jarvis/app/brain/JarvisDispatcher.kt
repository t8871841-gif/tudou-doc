package com.jarvis.app.brain

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.core.content.ContextCompat
import com.jarvis.app.command.DeviceCommands
import com.jarvis.app.command.LocalCommands
import org.json.JSONObject

/**
 * Jarvis 版 CommandDispatcher.dispatch：LocalBrain 调执行层的唯一口子。
 *
 * 换手第 3 批（2026-11-13）：这层只做路由，方法名直通 Andee 的手——
 * screen.* 走 ScreenController（Andee 原版），device.launch/volume/
 * brightness/open_url/alarm/facts/sensors 走 DeviceActionsController
 * （Andee 原版）；device.contacts/dial/connectivity/battery/apps/location
 * 是 Jarvis 自有的现成实现（通讯录、拨号盘、DeviceCommands），原样保留；
 * device.steps/call_log/sms.* 在 Jarvis 里没有执行器，如实报不支持。
 * TaskLoop 的整套执行层（dispatchToolCall / execute / FlowRuntime /
 * sendChatMessage）已随换手删掉，不再经过它。
 *
 * Andee 那边这个口子是 2123 行的 CommandDispatcher（场景、保险库、会议、
 * 机器狗、集市、ChatHistory 折叠、Scheduler 待办全在里面）；Jarvis 不需要
 * 那套——大脑真正调得到的只有工具表里的 29 个手机操作
 * （ToolSchemas.all()），把那 2123 行搬过来等于把半个 Andee 也搬过来，
 * 所以这里只留路由表。手本身（ScreenController / DeviceActionsController）
 * 是 Andee 原版，一行没改。
 *
 * 带图的四件（ui_tree / wait / screenshot / look_region）直接调
 * ScreenController、原样返回它的 JSON——里面已经有真正的 png_base64，
 * LocalBrain 的 extractImages 会把它抽出来附到下一轮。
 *
 * Andee dispatch 门口的两道守卫原样保留：静默轮只准 note.*（Jarvis 没有
 * note.* 执行器，静默轮一律拒——Jarvis 也不跑 Andee 那种夜间扫读）；
 * 不可信轮（通知触发）关掉 vault 与场景写操作（Jarvis 没有这两套，守
 * 卫只是防以后接上时漏掉）。
 *
 * send_chat_message（Jarvis 自加的五步核验）不在这里：它不在 1:1 工具表
 * 里，LocalBrain 的工具列表里没有它，模型调不到——这道关换脑后就是没了，
 * 见换脑方案（用户已点头）。方法名不在表里时回 "No such tool"，与
 * LocalBrain 自己的兜底同款。
 *
 * 构造时就要传 controller（无障碍服务里的 ScreenController）；BrainRunner
 * 负责建这个适配器、传给 LocalBrain。
 */
class JarvisDispatcher(
    private val ctx: Context,
    private val controller: ScreenController,
) {
    private val deviceActions = DeviceActionsController(ctx)

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
                        controller.lookRegion(
                            args.optInt("x"),
                            args.optInt("y"),
                            args.optInt("w", 0),
                            args.optInt("h", 0),
                        )
                    else -> "zoom_screen_region 需要 cell 或者 x/y"
                }
            }
            "screen.tap" -> controller.tapAim(
                on = args.optString("on").ifBlank { null },
                x = args.optInt("x", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                y = args.optInt("y", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                cell = args.optString("cell").ifBlank { null },
                part = args.optString("part").ifBlank { null },
            )
            "screen.tap_id" -> {
                val eid = args.optString("eid")
                require(eid.isNotEmpty()) { "screen.tap_id requires string eid (e.g. \"e7\", from get_screen_element's element list)" }
                controller.tapByEid(eid)
            }
            "screen.swipe" -> {
                val x1 = args.getInt("x1")
                val y1 = args.getInt("y1")
                val x2 = args.getInt("x2")
                val y2 = args.getInt("y2")
                val dur = args.optLong("duration_ms", 300L)
                val fling = args.optBoolean("fling", false)
                val screens = args.optInt("screens", 0).takeIf { it > 0 }
                controller.swipeNorm(x1, y1, x2, y2, dur, fling, screens)
            }
            "screen.long_press" -> {
                val dur = args.optLong("duration_ms", 600L)
                controller.longPressAim(
                    on = args.optString("on").ifBlank { null },
                    x = args.optInt("x", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                    y = args.optInt("y", Int.MIN_VALUE).takeIf { it != Int.MIN_VALUE },
                    cell = args.optString("cell").ifBlank { null },
                    part = args.optString("part").ifBlank { null },
                    durationMs = dur,
                )
            }
            "screen.long_press_id" -> {
                val eid = args.optString("eid")
                require(eid.isNotEmpty()) { "screen.long_press_id requires string eid" }
                controller.longPressByEid(eid, 600L)
            }
            "screen.type" -> {
                val text = args.optString("text")
                val id = args.optString("id").ifEmpty { null }
                controller.typeText(text, id)
            }
            "screen.submit_input" -> controller.submitInput(args.optString("id").ifEmpty { null })
            "screen.global" -> {
                val action = args.optString("action")
                require(action.isNotEmpty()) { "screen.global requires string action" }
                controller.globalAction(action)
            }
            "device.launch" -> deviceActions.launchApp(
                pkg = args.optString("pkg").ifEmpty { null },
                label = args.optString("label").ifEmpty { null },
            )
            "device.volume" -> deviceActions.volume(
                level = args.optInt("level", -1).let { if (it < 0) null else it },
                mute = if (args.has("mute")) args.optBoolean("mute") else null,
            )
            "device.brightness" -> deviceActions.brightness(
                if (args.has("brightness")) args.optInt("brightness") else null,
            )
            "device.open_url" -> {
                val url = args.optString("url")
                if (url.isEmpty()) {
                    "open_url 需要 url"
                } else if (url.startsWith("http://") || url.startsWith("https://")) {
                    // Jarvis 自有内置浏览器（Mika）：http(s) 走它，不走系统浏览器。
                    val msg = BrowserController.open(ctx, url)
                    Thread.sleep(1500)
                    BrowserController.refreshSnapshot(3000)
                    val snap = BrowserController.lastSnapshot()
                    val snapNote = if (snap != null) {
                        "（当前页：${snap.url}${if (snap.title.isNotEmpty()) "「${snap.title}」" else ""}）"
                    } else {
                        "（页面快照还没拿到，可能还在加载）"
                    }
                    "$msg$snapNote"
                } else {
                    deviceActions.openUrl(url)
                }
            }
            "device.alarm" -> deviceActions.setAlarm(
                hour = args.optInt("hour", -1),
                minute = args.optInt("minute", -1),
                message = args.optString("message").ifEmpty { null },
            )
            "device.facts" -> deviceActions.deviceInfo()
            "device.sensors" -> deviceActions.sensors(args.optLong("timeout_ms", 1500L))
            "device.contacts" -> {
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CONTACTS) !=
                    PackageManager.PERMISSION_GRANTED
                ) {
                    "没有通讯录权限：请到设置 → 功能自检页点「申请通讯录权限」，给了之后再查"
                } else {
                    val query = args.optString("query")
                    val hits = LocalCommands.queryContacts(ctx, query)
                    if (hits.isEmpty()) {
                        "通讯录里没找到${if (query.isEmpty()) "" else "「$query」"}"
                    } else {
                        "找到 ${hits.size} 个：\n" + hits.take(20).joinToString("\n") { "${it.display}|${it.number}" }
                    }
                }
            }
            "device.dial" -> {
                val number = args.optString("number")
                if (number.isEmpty()) {
                    "dial 需要 number"
                } else if (args.optBoolean("direct", false)) {
                    "直接拨号需要用户明确同意：先问用户一句，得到同意后再调 dial（direct=true）——不要自己直接拨"
                } else {
                    try {
                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$number")).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        ctx.startActivity(intent)
                        "拨号盘已打开，号码 $number 已填好，等用户点拨出"
                    } catch (e: Exception) {
                        "拨号盘没打开：${e.message ?: e.javaClass.simpleName}"
                    }
                }
            }
            "device.connectivity" -> {
                val type = DeviceCommands.networkType(ctx)
                val online = type != "未联网" && type != "未知"
                "联网状态：${if (online) "在线" else "离线"}（$type）"
            }
            "device.battery" -> {
                val info = DeviceCommands.batteryInfo(ctx)
                if (info == null) "电量读不到"
                else "电量 ${info.first}%，${if (info.second) "正在充电" else "未充电"}"
            }
            "device.apps" -> {
                val pm = ctx.packageManager
                val apps = try {
                    pm.getInstalledApplications(0)
                } catch (_: Exception) {
                    emptyList()
                }
                val lines = apps.mapNotNull { app ->
                    val label = try { pm.getApplicationLabel(app)?.toString().orEmpty() } catch (_: Exception) { "" }
                    val pkg = app.packageName.orEmpty()
                    if (label.isEmpty() || pkg.isEmpty()) null else "$label|$pkg"
                }.sorted().take(100)
                "已装应用 ${lines.size} 个（只列前 100 个）：\n" + lines.joinToString("\n")
            }
            "device.location" -> {
                val loc = DeviceCommands.requestFreshLocation(ctx)
                if (loc == null) "定位拿不到（可能没给定位权限、或定位服务没开）"
                else "位置：纬度 ${loc.latitude}，经度 ${loc.longitude}（精度约 ${loc.accuracy} 米）"
            }
            // Jarvis 里没有对应的执行器：如实报不支持，让模型换路线，不要假装做了。
            "device.steps", "device.call_log", "device.sms.read", "device.sms.send" ->
                "$method 在 Jarvis 里暂不支持（没有对应的执行器），换一条路线做这件事"
            else -> "No such tool: $method"
        }
    }
}
