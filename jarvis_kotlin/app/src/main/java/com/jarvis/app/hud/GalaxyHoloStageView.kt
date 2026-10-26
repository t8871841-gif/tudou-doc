package com.jarvis.app.hud

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 设计稿确认版舞台：待机是一团按螺旋臂排列、缓缓自转的等离子星系；一收到指令（state 从
 * idle 切到非 idle）就坍缩+炸裂成一块真 3D 透视倾斜的全息面板，内容随聆听/思考/应答切换；
 * 回到 idle 时同一套动画反着放一遍，星系重新展开。批二十八：取代主界面原来的反应堆舞台。
 *
 * 3D 效果用 [android.graphics.Camera] 在 2D Canvas 上做透视投影（Android 做"卡片翻转"
 * 那类伪 3D 效果的标准手法），不是真三维引擎——旋转角度小的时候看着跟真 3D 没什么差别，
 * 这里用到的倾斜角度都不大（面板 16°/-10°，地面网格 78°），实测应该够用。
 *
 * 过渡动画只有一条"进度" [t]：0 = 星系满屏，1 = 全息面板满屏，中间自动分三段
 * （星系收缩→冲击波→面板展开），方向不管开合都走同一套公式，不用维护一堆状态枚举。
 *
 * 应答态是打字机逐字揭示（文本由 MainActivity 经 JarvisAgent.onReplyText 转发进来，
 * 回调给的是整句，这里按时间逐字揭示，不是真流式）；聆听仍用声波条、思考仍用虚线。
 */
class GalaxyHoloStageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: () -> String = { "idle" }
    var micLevel: () -> Float = { 0f }

    // ---------- 应答打字机（批二十八：speaking 态不用声波条，改逐字揭示） ----------

    /** 当前这句回复全文（MainActivity 经 onReplyText 转发）；空表示无可显示文本。 */
    private var replyText = ""
    private var typeStartMs = 0L
    private var lastMode = "idle"
    private val TYPE_MS_PER_CHAR = 35L
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    /** MainActivity 在 runOnUiThread 里调：换新句就从头揭示。 */
    fun setReplyText(text: String) {
        replyText = text
        typeStartMs = System.currentTimeMillis()
        postInvalidate()
    }

    // ---------- 时间基准 ----------

    private var startNanos = 0L
    private fun tSec(): Float {
        if (startNanos == 0L) startNanos = System.nanoTime()
        return (System.nanoTime() - startNanos) / 1_000_000_000f
    }

    // ---------- 星系粒子（开机算一次，不每帧重算） ----------

    private data class Particle(val x: Float, val y: Float, val size: Float, val alpha: Float, val color: Int)

    /** 逻辑坐标系 0..260，画的时候按舞台半径缩放、平移到实际屏幕位置。 */
    private val particles: List<Particle> by lazy { generateGalaxy() }

    private fun generateGalaxy(): List<Particle> {
        val rnd = Random(7)
        val list = ArrayList<Particle>(190)
        fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun arm(offset: Float, n: Int, a: Float = 3.2f, b: Float = 0.26f, maxTheta: Float = 4.3f, jitter: Float = 0.22f) {
            for (i in 0 until n) {
                val tt = i / (n - 1f)
                val theta = tt * maxTheta
                val r = a * exp(b * theta)
                val rr = r * (1 + (rnd.nextFloat() * 2 - 1) * jitter * (0.3f + tt))
                val ang = theta + offset + (rnd.nextFloat() * 2 - 1) * 0.05f
                val x = 130 + rr * cos(ang)
                val y = 130 + rr * sin(ang)
                val color = if (tt < 0.35f) {
                    HudPalette.lerp(HudPalette.GALAXY_CORE, HudPalette.GALAXY_MID, tt / 0.35f)
                } else {
                    HudPalette.lerp(HudPalette.GALAXY_MID, HudPalette.GALAXY_OUTER, (tt - 0.35f) / 0.65f)
                }
                val size = max(0.4f, lerpF(2.6f, 0.7f, tt) + (rnd.nextFloat() * 2 - 1) * 0.3f)
                val alpha = (lerpF(0.95f, 0.25f, tt) + (rnd.nextFloat() * 2 - 1) * 0.05f).coerceIn(0.12f, 1f)
                list.add(Particle(x, y, size, alpha, color))
            }
        }
        arm(0f, 46); arm(2.05f, 46); arm(4.1f, 40)
        repeat(50) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val r = 70 + rnd.nextFloat() * 58
            val x = 130 + r * cos(ang)
            val y = 130 + r * sin(ang)
            list.add(Particle(x, y, 0.4f + rnd.nextFloat() * 0.7f, 0.08f + rnd.nextFloat() * 0.22f, HudPalette.GALAXY_OUTER))
        }
        return list
    }

    // ---------- 星系 ↔ 全息面板 过渡进度 ----------

    private var lastWantsHolo = false
    private var transitionFromT = 0f
    private var transitionStartMs = 0L
    private var t = 0f // 0=星系，1=全息面板
    private val TRANSITION_MS = 700L

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val tt = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return tt * tt * (3 - 2 * tt)
    }

    private fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1
        val xm1 = x - 1
        return 1 + c3 * xm1 * xm1 * xm1 + c1 * xm1 * xm1
    }

    private fun stepTransition() {
        val now = System.currentTimeMillis()
        val wantsHolo = mode() != "idle"
        if (wantsHolo != lastWantsHolo) {
            lastWantsHolo = wantsHolo
            transitionFromT = t
            transitionStartMs = now
        }
        val target = if (wantsHolo) 1f else 0f
        val elapsed = now - transitionStartMs
        val raw = (elapsed / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val eased = easeInOutCubic(raw)
        t = transitionFromT + (target - transitionFromT) * eased
    }

    // ---------- 画 ----------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val camera = Camera()

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        choreographer.postFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        running = false
        choreographer.removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        stepTransition()
        // 离开 speaking：清掉上一句，免得下一轮面板展开时闪出旧句
        val curMode = mode()
        if (curMode != lastMode) {
            if (lastMode == "speaking") replyText = ""
            lastMode = curMode
        }
        val tt = tSec()
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.36f
        if (r <= 0f) return

        // 星系这一段：t 从 0 收缩到约 0.35 就该消失了（坍缩阶段），之后不用再画。
        val galaxyShrink = smoothstep(0f, 0.35f, t)
        if (galaxyShrink < 1f) drawGalaxy(canvas, cx, cy, r, tt, 1f - galaxyShrink)

        // 冲击波：坍缩快结束、全息面板还没完全出现这段时间窗口内画，开合方向通用。
        if (t in 0.02f..0.98f) drawShock(canvas, cx, cy, r, smoothstep(0.25f, 0.85f, t))

        // 全息面板：t 从约 0.5 开始展开。
        val holoReveal = smoothstep(0.5f, 1f, t)
        if (holoReveal > 0f) drawHologram(canvas, cx, cy, r, tt, holoReveal)
    }

    // ---------- 星系 ----------

    private fun drawGalaxy(canvas: Canvas, cx: Float, cy: Float, r: Float, tt: Float, alphaMul: Float) {
        val scaleBase = r / 130f // 逻辑 260 空间 -> 实际半径
        val collapseScale = 1f - smoothstep(0f, 0.35f, t) * 0.92f // 坍缩时整体缩小
        val scale = scaleBase * collapseScale
        val rotationDeg = (tt / 70f) * 360f // 70 秒转一圈，很慢

        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(rotationDeg)
        canvas.scale(scale, scale)
        for (p in particles) {
            paint.reset(); paint.isAntiAlias = true
            paint.color = HudPalette.withAlpha(p.color, p.alpha * alphaMul)
            canvas.drawCircle(p.x - 130f, p.y - 130f, p.size, paint)
        }
        canvas.restore()

        // 核心脉动光
        val pulse = 0.75f + 0.25f * abs(sin(tt * (2 * PI.toFloat() / 3.6f)))
        val coreR = 13f * scaleBase * collapseScale * pulse
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(
            cx, cy, max(1f, coreR * 2.2f),
            intArrayOf(
                HudPalette.withAlpha(Color.parseColor("#fff6e0"), alphaMul),
                HudPalette.withAlpha(HudPalette.GALAXY_CORE, 0.8f * alphaMul),
                HudPalette.withAlpha(0xFFFF6A3D.toInt(), 0.5f * alphaMul),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, max(1f, coreR * 2.2f), paint)
        paint.shader = null
    }

    // ---------- 冲击波 ----------

    private fun drawShock(canvas: Canvas, cx: Float, cy: Float, r: Float, shockT: Float) {
        if (shockT <= 0f || shockT >= 1f) return
        val radius = 5f + (r * 2.4f) * easeOutBack(shockT).coerceIn(0f, 1.3f)
        val alpha = (1f - shockT).coerceIn(0f, 1f)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, 3f * alpha)
        paint.color = HudPalette.withAlpha(HudPalette.GALAXY_MID, alpha * 0.9f)
        canvas.drawCircle(cx, cy, radius, paint)
    }

    // ---------- 全息面板（Camera 伪 3D） ----------

    private fun drawHologram(canvas: Canvas, cx: Float, cy: Float, r: Float, tt: Float, reveal: Float) {
        val panelW = r * 1.85f
        val panelH = r * 1.2f
        val floatOffset = 6f * sin(tt * (2 * PI.toFloat() / 4.2f)) // 轻微漂浮
        val panelCy = cy + floatOffset
        val scale = 0.6f + 0.4f * easeOutBack(reveal).coerceIn(0f, 1.15f)

        // 地面网格：78° 近乎贴地的透视
        drawTiltedPlane(canvas, cx, cy + panelH * 0.62f, 78f, 0f) { local ->
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            val gridColor = HudPalette.colorFor(mode())
            var gx = -panelW
            while (gx <= panelW) {
                val distFrac = 1f - (abs(gx) / panelW).coerceIn(0f, 1f)
                paint.color = HudPalette.withAlpha(gridColor, 0.28f * distFrac * reveal)
                local.drawLine(gx, -panelH, gx, panelH, paint)
                gx += panelW / 7f
            }
            var gy = -panelH
            while (gy <= panelH) {
                val distFrac = 1f - (abs(gy) / panelH).coerceIn(0f, 1f)
                paint.color = HudPalette.withAlpha(gridColor, 0.28f * distFrac * reveal)
                local.drawLine(-panelW, gy, panelW, gy, paint)
                gy += panelH / 5f
            }
        }

        // 面板本体：16°/-10° 倾斜
        drawTiltedPlane(canvas, cx, panelCy, 16f, -10f) { local ->
            val accent = HudPalette.colorFor(mode())
            val hw = panelW * scale / 2f
            val hh = panelH * scale / 2f

            paint.reset(); paint.isAntiAlias = true
            paint.shader = RadialGradient(
                0f, 0f, max(hw, hh) * 1.3f,
                intArrayOf(HudPalette.withAlpha(accent, 0.22f * reveal), HudPalette.withAlpha(HudPalette.GALAXY_OUTER, 0.08f * reveal)),
                null, Shader.TileMode.CLAMP
            )
            local.drawRoundRect(-hw, -hh, hw, hh, 14f, 14f, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = HudPalette.withAlpha(accent, 0.8f * reveal)
            local.drawRoundRect(-hw, -hh, hw, hh, 14f, 14f, paint)

            // 扫描线
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            var ly = -hh + 6f
            while (ly < hh) {
                paint.color = HudPalette.withAlpha(accent, 0.1f * reveal)
                local.drawLine(-hw, ly, hw, ly, paint)
                ly += 8f
            }

            if (reveal > 0.5f) drawPanelContent(local, mode(), accent, hw, hh, tt, reveal)
        }
    }

    /** 用 Camera 把一块本地坐标系（原点在中心）按给定角度"贴"到 (cx,cy)，回调里用变换后的 canvas 画本地内容。 */
    private inline fun drawTiltedPlane(
        canvas: Canvas, cx: Float, cy: Float,
        rotateXDeg: Float, rotateYDeg: Float, draw: (Canvas) -> Unit
    ) {
        camera.save()
        camera.rotateX(rotateXDeg)
        camera.rotateY(rotateYDeg)
        camera.getMatrix(matrix)
        camera.restore()
        // 本地内容都是围绕 (0,0) 这个"平面中心"画的，不用额外 pivot 调整，直接平移到屏幕位置。
        matrix.postTranslate(cx, cy)
        canvas.save()
        canvas.concat(matrix)
        draw(canvas)
        canvas.restore()
    }

    /** 面板里的内容：聆听是跳动的声波条，思考是流动的虚线，应答是打字机——都在面板的本地（已倾斜）坐标系里画。 */
    private fun drawPanelContent(canvas: Canvas, state: String, accent: Int, hw: Float, hh: Float, tt: Float, reveal: Float) {
        when (state) {
            "thinking" -> {
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.6f
                paint.color = HudPalette.withAlpha(accent, 0.8f * reveal)
                val n = 5
                for (i in 0 until n) {
                    val ly = -hh * 0.6f + (hh * 1.2f) * (i / (n - 1f))
                    val phase = (tt * (40f + i * 8f)) % 24f
                    paint.pathEffect = DashPathEffect(floatArrayOf(10f, 6f), -phase)
                    canvas.drawLine(-hw * 0.82f, ly, hw * 0.82f, ly, paint)
                }
                paint.pathEffect = null
            }
            "listening" -> { // 聆听：声波条（跟随麦克风能量）
                val micL = micLevel()
                val n = 13
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.strokeCap = Paint.Cap.ROUND
                for (i in 0 until n) {
                    val lx = -hw * 0.75f + (hw * 1.5f) * (i / (n - 1f))
                    val base = 0.25f + 0.5f * abs(sin(tt * (3f + i % 4) + i * 0.6f))
                    val energy = (base * 0.5f + micL * 0.6f).coerceIn(0.08f, 1f)
                    val barH = hh * 0.65f * energy
                    paint.color = HudPalette.withAlpha(accent, (0.55f + 0.35f * energy) * reveal)
                    canvas.drawLine(lx, -barH, lx, barH, paint)
                }
            }
            else -> drawTypewriter(canvas, accent, hw, hh, reveal) // speaking：打字机
        }
    }

    /** 应答打字机：整句按 35ms/字揭示，居中多行，竖向装不下截断（完整句反正玻璃框里有）。 */
    private fun drawTypewriter(canvas: Canvas, accent: Int, hw: Float, hh: Float, reveal: Float) {
        val text = replyText
        if (text.isEmpty()) return
        val shown = ((System.currentTimeMillis() - typeStartMs) / TYPE_MS_PER_CHAR).toInt().coerceIn(0, text.length)
        if (shown <= 0) return
        val visible = text.substring(0, shown)
        textPaint.reset(); textPaint.isAntiAlias = true
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.color = HudPalette.withAlpha(accent, reveal)
        textPaint.textSize = hh * 0.16f
        val fm = textPaint.fontMetrics
        val lineH = (fm.descent - fm.ascent) * 1.45f
        val maxLines = (hh * 1.5f / lineH).toInt().coerceAtLeast(1)
        val maxW = hw * 1.5f
        val lines = ArrayList<String>()
        var rest = visible
        while (rest.isNotEmpty() && lines.size < maxLines) {
            // 按字符折行（中文无空格，breakText 逐字切正合适）
            val n = textPaint.breakText(rest, true, maxW, null)
            if (n <= 0) break
            lines.add(rest.substring(0, n))
            rest = rest.substring(n)
        }
        var y = -((lines.size - 1) * lineH) / 2f - fm.ascent
        for (line in lines) {
            canvas.drawText(line, 0f, y, textPaint)
            y += lineH
        }
        // 还没揭示完：在末字后跟一个光标块（lastBaseline = 末行基线）
        if (shown < text.length && lines.isNotEmpty()) {
            val lastW = textPaint.measureText(lines.last())
            val lastBaseline = y - lineH
            canvas.drawRect(lastW / 2f + 6f, lastBaseline + fm.ascent, lastW / 2f + 10f, lastBaseline + fm.descent, textPaint)
        }
    }
}
