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
 * 批二十二（2026-10-25 用户定方案 A 修订版）：反应堆放大到 300dp 级。
 * 新增霓虹渐变环（电光青→霓虹紫→翡翠绿三段固定色）与 72 根声波点阵（长度随
 * 能量、颜色主/辅/第三色循环），刻度环与轨道粒子外移到 118dp；仍不恢复三团
 * 漂移光晕、格纹、大面积雾；徽章本体保持 176dp 不动，虚线环 drawable 放大到
 * 330dp（MainActivity 侧）；对话玻璃框仍是唯一半透明元素。
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
    private var displayedTer = HudPalette.ROSE

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
        val (sec, ter) = HudPalette.secondaryTertiary(target)
        displayedSec = HudPalette.lerp(displayedSec, sec, 0.08f)
        displayedTer = HudPalette.lerp(displayedTer, ter, 0.08f)

        val cx = width / 2f
        val cy = height / 2f
        val badgeR = dp(88f) // 徽章 176dp 的半径
        val tickR = dp(118f) // 刻度环与轨道粒子半径（批二十二从 108dp 外移）
        val neonR = dp(150f) // 霓虹渐变环半径（300dp 直径）
        val dotR0 = dp(170f) // 声波点阵起点半径（虚线环 330dp 之外）
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

        // 3. 霓虹渐变环（批二十二）：电光青→霓虹紫→翡翠绿三段固定色，从顶部起顺时针。
        if (neonR < maxR) {
            val oval = RectF(cx - neonR, cy - neonR, cx + neonR, cy + neonR)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2.5f)
            paint.strokeCap = Paint.Cap.BUTT
            val neon = arrayOf(
                Triple(-90f, 120f, HudPalette.AQUA),
                Triple(30f, 120f, HudPalette.PURPLE),
                Triple(150f, 120f, HudPalette.EMERALD)
            )
            for ((start, sweep, col) in neon) {
                paint.color = col
                canvas.drawArc(oval, start, sweep, false, paint)
            }
        }

        // 4. 声波点阵（批二十二）：72 根，长度随能量变化、每根长短有起伏，
        // 颜色主/辅/第三色循环；外端不超出本 View 半径（窄屏自动截短）。
        if (dotR0 < maxR) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(2f)
            paint.strokeCap = Paint.Cap.ROUND
            val dotCols = arrayOf(displayedTheme, displayedSec, displayedTer)
            for (i in 0 until 72) {
                val a = (i * 5f * Math.PI / 180).toFloat()
                val wobble = 0.7f + 0.6f * abs(sin(i * 1.7f))
                val len = dp(6f + level * 14f) * wobble
                val r1 = min(dotR0 + len, maxR)
                paint.color = HudPalette.withAlpha(dotCols[i % 3], 0.85f)
                canvas.drawLine(
                    cx + cos(a.toDouble()).toFloat() * dotR0,
                    cy + sin(a.toDouble()).toFloat() * dotR0,
                    cx + cos(a.toDouble()).toFloat() * r1,
                    cy + sin(a.toDouble()).toFloat() * r1,
                    paint
                )
            }
        }

        // 5. 思考态扫描弧：亮段弧光绕徽章快扫。
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

        // 6. 声波环：聆听/说话时持续扩散，幅度跟能量走；交互 pulse() 也复用此通道。
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

        // 7. 状态切换粒子爆发：12 个光点 0.6 秒内从徽章边缘炸开收回。
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
