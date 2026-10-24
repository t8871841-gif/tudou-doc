package com.jarvis.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin

/**
 * 移植自 Flutter 版 `hud_painters.dart` 的 ReactorPainter（中央光球 + 光环 + 声波点阵），
 * 按 60fps 用 Choreographer 驱动重绘，逻辑和取值尽量照抄 Dart 版的 paint()/step()。
 *
 * 没有照搬的部分（有意简化，留给以后要做得更像再补）：
 *  - FxPainter 的粒子轨迹 + 两颗卫星（视觉上是点缀层，权重最低，先略过）；
 *  - 地球贴图（原版 assets/images/earth_bg.jpg 裁圆嵌在环内，这边没有这张图，不画）；
 *  - 状态切换的精确频闪（shockwave 用了简化过的单层实现，观感接近但参数没有逐帧比对过）。
 * 这些都不影响状态可读性（配色/呼吸/声波仍然跟手），真机看过观感不对再调。
 */
class HudStageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: () -> String = { "idle" }
    var micLevel: () -> Float = { 0f }

    private var startNanos = 0L
    private fun t(): Float {
        if (startNanos == 0L) startNanos = System.nanoTime()
        return (System.nanoTime() - startNanos) / 1_000_000_000f
    }

    private var hue = 0f // 未使用具体色相，直接在 displayedTheme 上做色彩 lerp
    private var spin = 1f
    private var level = 0.08f
    private var lastMode = "idle"
    private val shocks = ArrayList<Float>()

    private var displayedTheme = HudPalette.BLUE2
    private var displayedSec = HudPalette.PURPLE
    private var displayedTer = HudPalette.ROSE

    private fun step(tt: Float) {
        val m = mode()
        if (m != lastMode) {
            shocks.add(tt)
            lastMode = m
        }
        shocks.removeAll { tt - it > 0.9f }

        val target = HudPalette.colorFor(m)
        displayedTheme = HudPalette.lerp(displayedTheme, target, 0.08f)
        val (sec, ter) = HudPalette.secondaryTertiary(target)
        displayedSec = HudPalette.lerp(displayedSec, sec, 0.08f)
        displayedTer = HudPalette.lerp(displayedTer, ter, 0.08f)

        spin += ((if (m == "thinking") 5f else 1f) - spin) * 0.05f

        val mic = micLevel()
        val levelTarget = when (m) {
            "listening" -> 0.06f + mic * 0.95f
            "speaking" -> 0.3f + 0.5f * abs(sin(tt * 9) * cos(tt * 4.3)).toFloat()
            "thinking" -> 0.25f
            else -> 0.08f + 0.03f * sin(tt * 2).toFloat()
        }
        level += (levelTarget - level) * 0.12f
    }

    private fun col(a: Float) = HudPalette.withAlpha(displayedTheme, a)
    private fun secCol(a: Float) = HudPalette.withAlpha(displayedSec, a)
    private fun terCol(a: Float) = HudPalette.withAlpha(displayedTer, a)

    /** 沿 主色→辅色→第三色→主色 的环形渐变取色，u 自动取小数部分。 */
    private fun spectrumCol(u: Float, a: Float): Int {
        val w = u - kotlin.math.floor(u)
        val s3 = w * 3
        val (from, to, f) = when {
            s3 < 1 -> Triple(displayedTheme, displayedSec, s3)
            s3 < 2 -> Triple(displayedSec, displayedTer, s3 - 1)
            else -> Triple(displayedTer, displayedTheme, s3 - 2)
        }
        return HudPalette.withAlpha(HudPalette.lerp(from, to, f), a)
    }

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

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
        val tt = t()
        step(tt)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) * 0.36f
        if (r <= 0f) return
        val lvl = level

        // 1. 大光晕
        val haloR = r * (1.35f + lvl * 0.3f)
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(
            cx, cy, haloR,
            intArrayOf(col(0.30f + lvl * 0.25f), secCol(0.12f + lvl * 0.10f), terCol(0f)),
            floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, haloR, paint)
        paint.shader = null

        // 2. 星云光球：三团色块——改用径向渐变做柔和衰减，不用像素模糊（要求"高清，不要模糊"）。
        // BlurMaskFilter 在部分设备/分辨率下会让整块画面发虚、边缘发糊；RadialGradient 是矢量
        // 渐变，任何分辨率下都是锐利的，衰减感来自颜色透明度渐变而不是采样模糊。
        val orbit = r * 0.2f * (1 + lvl * 1.2f)
        val blobR = r * (0.52f + lvl * 0.14f)
        for (i in 0 until 3) {
            val a = tt * (0.45f + i * 0.17f) * spin + i * 2.094f
            val px = cx + cos(a.toDouble()).toFloat() * orbit
            val py = cy + sin((a * 1.15f).toDouble()).toFloat() * orbit
            val c = when (i) { 0 -> displayedTheme; 1 -> displayedSec; else -> displayedTer }
            paint.shader = RadialGradient(
                px, py, blobR,
                intArrayOf(HudPalette.withAlpha(c, 0.95f), HudPalette.withAlpha(c, 0.55f), HudPalette.withAlpha(c, 0f)),
                floatArrayOf(0f, 0.6f, 1f), Shader.TileMode.CLAMP
            )
            canvas.drawCircle(px, py, blobR, paint)
        }
        paint.shader = null

        // 3. 柔和高光核心
        val coreR = r * 0.26f * (1 + lvl * 0.8f)
        paint.shader = RadialGradient(
            cx, cy, coreR,
            intArrayOf(Color.argb(217, 255, 255, 255), Color.argb(0, 255, 255, 255)),
            null, Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, coreR, paint)
        paint.shader = null

        // 4. 细光环 + 渐隐彩色弧光
        val ringR = r * 0.98f
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = col(0.14f)
        canvas.drawCircle(cx, cy, ringR, paint)

        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(terCol(0f), col(0.9f), secCol(0.9f), terCol(0f)),
            floatArrayOf(0f, 0.35f, 0.7f, 1f)
        ).also {
            val m = android.graphics.Matrix()
            m.postRotate(tt * 0.8f * spin * 57.3f, cx, cy)
            it.setLocalMatrix(m)
        }
        paint.strokeWidth = 2.2f
        paint.strokeCap = Paint.Cap.ROUND
        canvas.drawCircle(cx, cy, ringR, paint)
        paint.shader = null

        // 虚线外环
        val dashPath = Path().apply { addCircle(cx, cy, r * 1.3f, Path.Direction.CW) }
        paint.pathEffect = DashPathEffect(floatArrayOf(2f, 10f), (-tt * 0.12f * spin * r * 1.3f))
        paint.color = col(0.16f)
        paint.strokeWidth = 1f
        canvas.drawPath(dashPath, paint)
        paint.pathEffect = null

        // 4b. 霓虹渐变环：电光青 → 霓虹紫 → 翡翠绿 → 电光青
        val neonR = r * 1.15f
        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(0xFF00F0FF.toInt(), 0xFF7000FF.toInt(), 0xFF00FF9D.toInt(), 0xFF00F0FF.toInt()),
            null
        ).also {
            val m = android.graphics.Matrix()
            m.postRotate(tt * 0.5f * spin * 57.3f, cx, cy)
            it.setLocalMatrix(m)
        }
        paint.strokeWidth = 2f
        canvas.drawCircle(cx, cy, neonR, paint)
        paint.shader = null

        // 5. 声波点阵：72 根，长度随能量变化，颜色沿主/辅/第三色循环
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2.4f
        paint.strokeCap = Paint.Cap.ROUND
        val n = 72
        for (i in 0 until n) {
            val a = (i.toFloat() / n * Math.PI * 2).toFloat()
            val r0 = r * 1.1f
            val len = r * 0.3f * lvl *
                (0.3f + 0.7f * abs(sin((i * 0.7f + tt * 6).toDouble()) * cos((i * 0.31f - tt * 3).toDouble())).toFloat()) + 2f
            paint.color = spectrumCol(i / n.toFloat() + tt * 0.06f, 0.85f)
            canvas.drawLine(
                cx + cos(a.toDouble()).toFloat() * r0, cy + sin(a.toDouble()).toFloat() * r0,
                cx + cos(a.toDouble()).toFloat() * (r0 + len), cy + sin(a.toDouble()).toFloat() * (r0 + len),
                paint
            )
        }

        // 状态切换涟漪
        paint.pathEffect = null
        for (s in shocks) {
            val p = (tt - s) / 0.9f
            val a = 0.9f * 0.06f.toDouble().pow(p.toDouble()).toFloat()
            val rr = r * (0.3f + p * 1.8f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f + a * 3f
            paint.color = col(a.coerceIn(0f, 1f))
            canvas.drawCircle(cx, cy, rr, paint)
        }

        // 6. 雷达扫描扇：钢铁侠 HUD 标志性的那道"转一圈、尾巴渐隐"的扫描光，跟 72 根声波点阵
        // 叠在一起但半径更大，视觉上是独立的一层。SweepGradient 本身就是矢量扇形渐变，没有
        // 任何像素模糊成分。
        val sweepR = r * 1.45f
        paint.style = Paint.Style.FILL
        paint.shader = SweepGradient(
            cx, cy,
            intArrayOf(col(0f), col(0f), col(0.55f), Color.WHITE),
            floatArrayOf(0f, 0.82f, 0.97f, 1f)
        ).also {
            val m = android.graphics.Matrix()
            m.postRotate(tt * -0.6f * spin * 57.3f, cx, cy)
            it.setLocalMatrix(m)
        }
        canvas.drawCircle(cx, cy, sweepR, paint)
        paint.shader = null

        // 7. 四角机甲式取景框——钢铁侠 HUD 的另一个标志性元素，贴着整块舞台 View 的四个角，
        // 跟中心光球无关，纯粹是"这是一块科技仪表盘"的边框语言。
        drawCornerBrackets(canvas)
    }

    /** 四个角各画一个"L + 短斜切角"的取景框记号，颜色跟随当前状态主题色。 */
    private fun drawCornerBrackets(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val m = min(w, h) * 0.09f // 括号臂长
        val inset = 6f
        val chamfer = m * 0.4f // 斜切角长度，避免方方正正显得呆板
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 2f
        paint.strokeCap = Paint.Cap.ROUND
        paint.color = col(0.55f)

        fun bracket(cornerX: Float, cornerY: Float, dx: Int, dy: Int) {
            val p = Path()
            val x0 = cornerX + inset * dx
            val y0 = cornerY + inset * dy
            p.moveTo(x0 + m * dx, y0)
            p.lineTo(x0 + chamfer * dx, y0)
            p.lineTo(x0, y0 + chamfer * dy)
            p.lineTo(x0, y0 + m * dy)
            canvas.drawPath(p, paint)
        }
        bracket(0f, 0f, 1, 1)
        bracket(w, 0f, -1, 1)
        bracket(0f, h, 1, -1)
        bracket(w, h, -1, -1)
    }
}
