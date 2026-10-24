package com.jarvis.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * 批十八：主界面徽章反应堆动效（2026-10-24 用户定方案）。
 *
 * 取代已删舞台的视觉生命力，但载体只剩中央徽章：全部用细线、圆点、亮段弧光
 * 实现，不恢复大面积雾状光晕、蜂巢、扫描扇；对话玻璃框仍是界面唯一半透明元素。
 *
 * 三层（方案原文）：
 * - 常驻：中环刻度环反向转 + 轨道粒子 + 徽章呼吸（呼吸在 MainActivity 里用
 *   ObjectAnimator 做在徽章/虚线环上，本 View 只画环与粒子）；
 * - 状态反应：聆听/说话时声波环向外扩散（幅度跟能量走），思考时亮段弧光快扫，
 *   状态切换瞬间一圈粒子向外炸开再收回（0.6 秒）；
 * - 交互涟漪由调用方触发（[pulse]，点麦克风/点徽章时调一次）。
 *
 * 只在主界面可见时跑（attached 才接 Choreographer），切后台/息屏即停。
 */
class EmblemMotionView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: () -> String = { "idle" }
    var micLevel: () -> Float = { 0f }

    private var startNanos = 0L
    private fun t(): Float {
        if (startNanos == 0L) startNanos = System.nanoTime()
        return (System.nanoTime() - startNanos) / 1_000_000_000f
    }

    private var displayedTheme = HudPalette.BLUE2
    private var displayedSec = HudPalette.PURPLE

    private var lastMode = "idle"
    private var burstStart = -10f
    private val sonicRings = ArrayList<Float>()
    private var lastSonicSpawn = -10f

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

    /** 交互涟漪：调用方在点麦克风/点徽章时调一次，从徽章边缘扩一圈。 */
    fun pulse() {
        sonicRings.add(t())
    }

    private fun dp(v: Float): Float = v * resources.displayMetrics.density

    override fun onDraw(canvas: Canvas) {
        val tt = t()
        val m = mode()
        if (m != lastMode) {
            burstStart = tt
            lastMode = m
        }
        val target = HudPalette.colorFor(m)
        displayedTheme = HudPalette.lerp(displayedTheme, target, 0.08f)
        displayedSec = HudPalette.lerp(displayedSec, HudPalette.secondaryTertiary(target).first, 0.08f)

        val cx = width / 2f
        val cy = height / 2f
        val badgeR = dp(88f) // 徽章 176dp 的半径
        val tickR = dp(108f) // 刻度环与轨道粒子半径（虚线环 194dp 之外）
        if (cx <= 0f || cy <= 0f) return
        val maxR = min(width, height) / 2f - dp(4f)
        if (tickR > maxR) return

        val mic = micLevel()
        val level = when (m) {
            "listening" -> 0.06f + mic * 0.95f
            "speaking" -> 0.3f + 0.5f * abs(sin(tt * 9) * cos(tt * 4.3)).toFloat()
            "thinking" -> 0.25f
            else -> 0.08f + 0.03f * sin(tt * 2).toFloat()
        }

        paint.reset()
        paint.isAntiAlias = true

        // 1. 中环刻度环：反向慢转（与徽章虚线环顺时针 60 秒一圈差速反向）。
        val tickPath = Path().apply { addCircle(cx, cy, tickR, Path.Direction.CW) }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = HudPalette.withAlpha(displayedTheme, 0.35f)
        paint.pathEffect = DashPathEffect(floatArrayOf(2f, 10f), tt * 0.12f * tickR)
        canvas.drawPath(tickPath, paint)
        paint.pathEffect = null

        // 2. 轨道粒子：4 个小光点，速度各不相同。
        paint.style = Paint.Style.FILL
        for (i in 0 until 4) {
            val a = tt * (0.45f + i * 0.17f) + i * 1.5708f
            val px = cx + cos(a.toDouble()).toFloat() * tickR
            val py = cy + sin(a.toDouble()).toFloat() * tickR
            paint.color = HudPalette.withAlpha(if (i % 2 == 0) displayedTheme else displayedSec, 0.85f)
            canvas.drawCircle(px, py, dp(2.5f), paint)
        }

        // 3. 思考态扫描弧：亮段弧光绕徽章快扫。
        if (m == "thinking") {
            val sweep = 70f
            val start = (tt * 180f) % 360f
            val oval = RectF(cx - dp(102f), cy - dp(102f), cx + dp(102f), cy + dp(102f))
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = HudPalette.withAlpha(displayedTheme, 0.9f)
            canvas.drawArc(oval, start, sweep, false, paint)
        }

        // 4. 声波环：聆听/说话时持续扩散，幅度跟能量走；交互 pulse() 也复用此通道。
        if (m == "listening" || m == "speaking") {
            val interval = if (m == "speaking") 0.7f else 0.9f
            if (tt - lastSonicSpawn > interval) {
                sonicRings.add(tt)
                lastSonicSpawn = tt
            }
        }
        sonicRings.removeAll { tt - it > 1.2f }
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1.5f
        for (born in sonicRings) {
            val p = ((tt - born) / 1.2f).coerceIn(0f, 1f)
            val r = badgeR + p * (maxR - badgeR)
            paint.color = HudPalette.withAlpha(displayedTheme, (1f - p) * (0.25f + level * 0.5f))
            canvas.drawCircle(cx, cy, r, paint)
        }

        // 5. 状态切换粒子爆发：12 个光点 0.6 秒内从徽章边缘炸开收回。
        val bp = ((tt - burstStart) / 0.6f)
        if (bp in 0f..1f) {
            paint.style = Paint.Style.FILL
            for (i in 0 until 12) {
                val a = (i / 12f * Math.PI * 2).toFloat()
                val r = badgeR + bp * dp(45f)
                val px = cx + cos(a.toDouble()).toFloat() * r
                val py = cy + sin(a.toDouble()).toFloat() * r
                paint.color = HudPalette.withAlpha(
                    if (i % 2 == 0) displayedTheme else displayedSec, (1f - bp) * 0.9f
                )
                canvas.drawCircle(px, py, dp(2f), paint)
            }
        }
    }
}
