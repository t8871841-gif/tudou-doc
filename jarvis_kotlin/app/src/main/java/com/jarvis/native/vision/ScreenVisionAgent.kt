package com.jarvis.native.vision

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.util.Base64
import com.jarvis.native.AuditLog
import com.jarvis.native.JarvisAccessibilityService
import com.jarvis.native.TakeoverBorder
import com.jarvis.native.net.AgnesClient
import org.json.JSONObject
import kotlin.math.abs

/**
 * 第 4 条「屏幕理解」的落地：节点树优先、读不到/读不全时才走这条视觉兜底通道。
 * 本类只负责"视觉找一个目标元素并点一下"和"看一眼屏幕回答问题"这两件事，
 * 不是完整的多步任务规划器——多步跨应用流程仍然走 JarvisAccessibilityService 现有的
 * 任务引擎（startTask），那是第 3/8 条的地盘，这里不重复造。
 */
object ScreenVisionAgent {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    private var genSeq = 0

    // 同位置连点防呆：记录同一次任务里最近一次成功换算出的点击坐标，连续第 4 次落在同一片
    // 小区域（±24dp 等效像素内，这里简化为按屏宽 3% 判定）就拒绝执行并停下来问用户，防止模型原地打转。
    private var lastPoint: Pair<Float, Float>? = null
    private var samePointStreak = 0
    private const val MAX_SAME_POINT = 4

    fun cancelInFlight() {
        genSeq++
        lastPoint = null
        samePointStreak = 0
    }

    private fun resetRepeatGuard() {
        lastPoint = null
        samePointStreak = 0
    }

    /**
     * 看一眼屏幕回答问题：截图(受黑名单限制) → 问视觉模型 → 用自然语言回答，适合直接拿去 TTS 朗读。
     */
    fun describeScreen(ctx: Context, question: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val mySeq = ++genSeq
        captureAllowed(ctx, { bmp, _, _ ->
            val b64 = GridOverlay.toJpegBase64(bmp, quality = 70)
            val prompt = "这是用户当前手机屏幕的截图。用户的问题是：「$question」。" +
                "请直接、简短地用自然语言回答，这段话会被原样朗读给用户听，不要用markdown、不要分点、不要读坐标数字。"
            AgnesClient.askVision(ctx, prompt, b64,
                onResult = { reply -> if (mySeq == genSeq) main.post { onResult(reply) } },
                onError = { msg -> if (mySeq == genSeq) main.post { onError(msg) } }
            )
        }, { reason -> main.post { onError(reason) } })
    }

    /**
     * 找到目标元素并点一下。onDone(ok, detail)：ok=false 时 detail 是失败原因（黑名单拦截 / 没找到 /
     * 连点防呆触发 / 网络出错等），不许静默失败。
     */
    fun locateAndTap(ctx: Context, targetDescription: String, taskLabel: String, onDone: (Boolean, String) -> Unit) {
        val mySeq = ++genSeq
        resetRepeatGuard()
        TakeoverBorder.show()
        AuditLog.append(ctx, taskLabel, "视觉看屏定位「$targetDescription」", "进行中")
        captureAllowed(ctx, { bmp, sw, sh ->
            askGridCell(ctx, mySeq, bmp, sw, sh, targetDescription, taskLabel, onDone)
        }, { reason ->
            TakeoverBorder.hide()
            AuditLog.append(ctx, taskLabel, "视觉看屏定位「$targetDescription」", "失败：$reason")
            main.post { onDone(false, reason) }
        })
    }

    private fun askGridCell(
        ctx: Context, mySeq: Int, bmp: Bitmap, sw: Int, sh: Int,
        target: String, taskLabel: String, onDone: (Boolean, String) -> Unit
    ) {
        val gridded = GridOverlay.drawGrid(bmp)
        val b64 = GridOverlay.toJpegBase64(gridded, quality = 75)
        val prompt = "这张截图上叠加了标号网格（行用字母 A-H，列用数字 1-12，格子号如 C5）。" +
            "用户想点击的目标是：「$target」。只允许回复一个 JSON 对象，不要任何其他文字：" +
            "{\"cell\":\"C5\",\"sub\":\"left|center|right\",\"zoom\":false,\"not_found\":false}。" +
            "cell 是目标所在的格子号；sub 是目标在格子内偏左/居中/偏右；" +
            "如果目标很小、不确定具体位置，把 zoom 设为 true，我会再给你一张放大图确认；" +
            "如果这张截图里根本找不到这个目标，把 not_found 设为 true。不许直接给出像素坐标。"
        AgnesClient.askVision(ctx, prompt, b64,
            onResult = { raw ->
                if (mySeq != genSeq) return@askVision
                main.post {
                    val json = parseJsonLoose(raw)
                    if (json == null) {
                        finishFail(ctx, taskLabel, target, "视觉模型没有按约定格式回答")
                        onDone(false, "视觉模型没有按约定格式回答")
                        return@post
                    }
                    if (json.optBoolean("not_found", false)) {
                        finishFail(ctx, taskLabel, target, "没有在屏幕上找到「$target」")
                        onDone(false, "没有在屏幕上找到「$target」")
                        return@post
                    }
                    val cell = GridOverlay.parseLabel(json.optString("cell", ""))
                    if (cell == null) {
                        finishFail(ctx, taskLabel, target, "格子号解析失败：${json.optString("cell")}")
                        onDone(false, "格子号解析失败")
                        return@post
                    }
                    val sub = json.optString("sub", "center")
                    if (json.optBoolean("zoom", false)) {
                        askZoomConfirm(ctx, mySeq, bmp, cell, sw, sh, target, taskLabel, onDone)
                    } else {
                        val (ix, iy) = GridOverlay.cellToScreenPoint(cell, sub, bmp.width, bmp.height)
                        val (x, y) = GridOverlay.imageToScreenPoint(ix, iy, bmp.width, bmp.height, sw, sh)
                        tapWithGuard(ctx, mySeq, x, y, target, taskLabel, onDone)
                    }
                }
            },
            onError = { msg ->
                if (mySeq != genSeq) return@askVision
                main.post {
                    finishFail(ctx, taskLabel, target, "视觉模型请求失败：$msg")
                    onDone(false, "视觉模型请求失败：$msg")
                }
            }
        )
    }

    private fun askZoomConfirm(
        ctx: Context, mySeq: Int, bmp: Bitmap, cell: GridOverlay.CellSpec,
        sw: Int, sh: Int, target: String, taskLabel: String, onDone: (Boolean, String) -> Unit
    ) {
        val zoom = GridOverlay.zoomRegion(bmp, cell)
        val b64 = GridOverlay.toJpegBase64(zoom.bitmap, quality = 80)
        val prompt = "这是刚才那个格子区域的放大图，图中的红色十字是当前的粗略猜测位置。" +
            "目标是：「$target」。请只回复 JSON：{\"x_frac\":0.5,\"y_frac\":0.5,\"not_found\":false}，" +
            "x_frac/y_frac 是目标中心点在这张放大图里的相对比例坐标（左上角是 0,0，右下角是 1,1）。"
        AgnesClient.askVision(ctx, prompt, b64,
            onResult = { raw ->
                if (mySeq != genSeq) return@askVision
                main.post {
                    val json = parseJsonLoose(raw)
                    if (json == null || json.optBoolean("not_found", false)) {
                        finishFail(ctx, taskLabel, target, "放大确认后仍未找到「$target」")
                        onDone(false, "放大确认后仍未找到「$target」")
                        return@post
                    }
                    val xf = json.optDouble("x_frac", 0.5).toFloat()
                    val yf = json.optDouble("y_frac", 0.5).toFloat()
                    val (ix, iy) = GridOverlay.fracToImagePoint(zoom.sourceRect, xf, yf)
                    val (x, y) = GridOverlay.imageToScreenPoint(ix, iy, bmp.width, bmp.height, sw, sh)
                    tapWithGuard(ctx, mySeq, x, y, target, taskLabel, onDone)
                }
            },
            onError = { msg ->
                if (mySeq != genSeq) return@askVision
                main.post {
                    finishFail(ctx, taskLabel, target, "放大确认请求失败：$msg")
                    onDone(false, "放大确认请求失败：$msg")
                }
            }
        )
    }

    /** 同位置连点防呆：第 4 次又落在同一片小区域时拒绝执行，停下来问用户，不许继续原地打转。 */
    private fun tapWithGuard(
        ctx: Context, mySeq: Int, x: Float, y: Float, target: String, taskLabel: String,
        onDone: (Boolean, String) -> Unit
    ) {
        val sw = (JarvisAccessibilityService.instance?.resources?.displayMetrics?.widthPixels ?: 1080)
        val tol = sw * 0.03f
        val prev = lastPoint
        if (prev != null && abs(prev.first - x) < tol && abs(prev.second - y) < tol) {
            samePointStreak++
        } else {
            samePointStreak = 1
        }
        lastPoint = x to y
        if (samePointStreak >= MAX_SAME_POINT) {
            TakeoverBorder.hide()
            AuditLog.append(ctx, taskLabel, "视觉看屏定位「$target」", "停下：同一位置已连续尝试 $samePointStreak 次，疑似打转，已拒绝继续点击并询问用户")
            resetRepeatGuard()
            onDone(false, "同一个位置已经连续点了好几次，可能找错了，先停下来问你")
            return
        }
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            TakeoverBorder.hide()
            AuditLog.append(ctx, taskLabel, "视觉看屏定位「$target」", "失败：无障碍服务不可用，点不了")
            onDone(false, "无障碍服务不可用")
            return
        }
        svc.tapXY(x, y) { ok, reason ->
            main.post {
                if (mySeq != genSeq) return@post
                TakeoverBorder.hide()
                if (ok) {
                    resetRepeatGuard()
                    AuditLog.append(ctx, taskLabel, "视觉看屏点击「$target」于坐标附近", "成功")
                    onDone(true, "")
                } else {
                    AuditLog.append(ctx, taskLabel, "视觉看屏点击「$target」", "失败：$reason")
                    onDone(false, "点击被拒绝：$reason")
                }
            }
        }
    }

    private fun finishFail(ctx: Context, taskLabel: String, target: String, reason: String) {
        TakeoverBorder.hide()
        AuditLog.append(ctx, taskLabel, "视觉看屏定位「$target」", "失败：$reason")
        resetRepeatGuard()
    }

    /** 黑名单(银行/支付/钱包/系统设置)在这里统一拦，命中时明确告诉上层——而不是悄悄跳过。 */
    private fun captureAllowed(
        ctx: Context,
        onOk: (Bitmap, Int, Int) -> Unit,
        onBlocked: (String) -> Unit
    ) {
        val svc = JarvisAccessibilityService.instance
        if (svc == null) {
            onBlocked("无障碍服务未开启，看不了屏幕")
            return
        }
        svc.captureScreen { result ->
            main.post {
                val err = result["error"] as? String
                if (err != null) {
                    val msg = when (err) {
                        "blocked_app" -> "当前界面在隐私黑名单里（银行/支付/钱包/系统设置类），不允许截图上传"
                        "self" -> "当前前台是 Jarvis 自己，没什么好看的"
                        "unknown_app", "secure_window" -> "这个界面读不到（可能是安全窗口），看不了"
                        else -> "截图失败：$err"
                    }
                    onBlocked(msg)
                    return@post
                }
                try {
                    val b64 = result["b64"] as String
                    val sw = result["sw"] as Int
                    val sh = result["sh"] as Int
                    val bytes = Base64.decode(b64, Base64.NO_WRAP)
                    val bmp = BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
                    if (bmp == null) onBlocked("截图解码失败") else onOk(bmp, sw, sh)
                } catch (e: Exception) {
                    onBlocked("截图处理失败：${e.message}")
                }
            }
        }
    }

    /** 容错 JSON 解析：模型偶尔会在 JSON 前后多说几句话，截取第一个 {...} 块再解析。 */
    private fun parseJsonLoose(raw: String): JSONObject? {
        return try {
            JSONObject(raw)
        } catch (_: Exception) {
            val s = raw.indexOf('{')
            val e = raw.lastIndexOf('}')
            if (s in 0 until e) {
                try { JSONObject(raw.substring(s, e + 1)) } catch (_: Exception) { null }
            } else null
        }
    }
}
