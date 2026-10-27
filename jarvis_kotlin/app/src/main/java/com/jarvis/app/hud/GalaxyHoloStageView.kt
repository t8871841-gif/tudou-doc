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
 * 回到 idle 时同一套动画反着放一遍，星系重新展开。2026-10-27 用户令复活（取代已删的银河照片舞台），
 * 星场与坍缩动画按包里原样；不含霓虹环，悬浮球不动。
 *
 * 3D 效果用 [android.graphics.Camera] 在 2D Canvas 上做透视投影（Android 做"卡片翻转"
 * 那类伪 3D 效果的标准手法），不是真三维引擎——旋转角度小的时候看着跟真 3D 没什么差别，
 * 这里用到的倾斜角度都不大（面板 16°/-10°，地面网格 78°），实测应该够用。
 *
 * 过渡动画只有一条"进度" [t]：0 = 星系满屏，1 = 全息面板满屏，中间自动分三段
 * （星系收缩→冲击波→面板展开），方向不管开合都走同一套公式，不用维护一堆状态枚举。
 */
class GalaxyHoloStageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: () -> String = { "idle" }
    var micLevel: () -> Float = { 0f }

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

    // ---------- 背景星场（银河全景效果，应用户要求把星星数量加大） ----------

    private data class Star(val x: Float, val y: Float, val size: Float, val baseAlpha: Float, val twSpeed: Float, val twPhase: Float, val warm: Boolean)

    /** 跟星系粒子是两套独立系统：星场是固定的"天幕背景"，不随星系坍缩/自转一起动，
     * 星系在它前面收缩展开，视觉上有层次（近处星系、远处星空）。数量从几颗点缀加到
     * 400+ 颗，并且往一条斜向"亮带"里偏置密度，接近银河系全景那种带状亮区的感觉。 */
    private val stars: List<Star> by lazy { generateStars() }

    private fun generateStars(): List<Star> {
        val rnd = Random(42)
        val list = ArrayList<Star>(460)
        repeat(460) {
            val inBand = rnd.nextFloat() < 0.55f
            val x: Float
            val y: Float
            if (inBand) {
                // 沿对角线聚出一条亮带，两侧用较大的扩散半径让带状边缘柔和、不生硬
                val bt = rnd.nextFloat()
                val bx = -50f + bt * 360f
                val by = -50f + bt * 360f
                val spread = 75f
                x = bx + (rnd.nextFloat() * 2 - 1) * spread
                y = by + (rnd.nextFloat() * 2 - 1) * spread * 0.55f
            } else {
                x = rnd.nextFloat() * 360f - 50f
                y = rnd.nextFloat() * 360f - 50f
            }
            val sizeRoll = rnd.nextFloat()
            val size = when {
                sizeRoll < 0.06f -> 1.7f + rnd.nextFloat() * 0.9f // 少量亮星点
                sizeRoll < 0.28f -> 1.0f + rnd.nextFloat() * 0.5f
                else -> 0.35f + rnd.nextFloat() * 0.5f
            }
            val alpha = 0.12f + rnd.nextFloat() * 0.5f
            val speed = 0.4f + rnd.nextFloat() * 1.8f
            val phase = rnd.nextFloat() * (2 * PI.toFloat())
            val warm = rnd.nextFloat() < 0.22f
            list.add(Star(x, y, size, alpha, speed, phase, warm))
        }
        return list
    }

    private fun drawStars(canvas: Canvas, cx: Float, cy: Float, r: Float, tt: Float) {
        val scaleBase = r / 130f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scaleBase, scaleBase)
        paint.reset(); paint.isAntiAlias = true
        for (s in stars) {
            val twinkle = 0.55f + 0.45f * sin(tt * s.twSpeed + s.twPhase)
            val a = (s.baseAlpha * twinkle).coerceIn(0f, 1f)
            paint.color = if (s.warm) HudPalette.withAlpha(0xFFFFE3B0.toInt(), a) else HudPalette.withAlpha(Color.WHITE, a)
            canvas.drawCircle(s.x - 130f, s.y - 130f, s.size, paint)
        }
        canvas.restore()
    }

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
        val tt = tSec()
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.36f
        if (r <= 0f) return

        // 背景星场：固定天幕，不随星系坍缩/自转，星系在它前面开合，有近景/远景的层次感。
        drawStars(canvas, cx, cy, r, tt)

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

    /** 面板里的内容：聆听/应答是跳动的声波条，思考是流动的虚线——都在面板的本地（已倾斜）坐标系里画。 */
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
            else -> { // listening / speaking：声波条
                val micL = if (state == "listening") micLevel() else 0f
                val n = 13
                paint.reset(); paint.isAntiAlias = true
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 4f
                paint.strokeCap = Paint.Cap.ROUND
                for (i in 0 until n) {
                    val lx = -hw * 0.75f + (hw * 1.5f) * (i / (n - 1f))
                    val base = 0.25f + 0.5f * abs(sin(tt * (3f + i % 4) + i * 0.6f))
                    val energy = if (state == "listening") (base * 0.5f + micL * 0.6f) else base
                    val barH = hh * 0.65f * energy.coerceIn(0.08f, 1f)
                    paint.color = HudPalette.withAlpha(accent, (0.55f + 0.35f * energy) * reveal)
                    canvas.drawLine(lx, -barH, lx, barH, paint)
                }
            }
        }
    }
}
