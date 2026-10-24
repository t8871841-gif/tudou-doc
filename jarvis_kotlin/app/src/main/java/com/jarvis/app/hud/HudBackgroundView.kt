package com.jarvis.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 移植自 `hud_painters.dart` 的 AuroraPainter（三团缓慢漂移的大光晕，颜色跟随状态），
 * 原来的极淡圆点阵换成了六边形网格（Stark Industries/钢铁侠座舱那种蜂窝科技感）。
 * 网格是开机时按屏幕尺寸预先算好、缓存成一条 Path，每帧只是 `drawPath` 一次，
 * 不会每帧重新算几百个六边形——矢量描边，天然锐利，不存在"模糊"这回事。
 */
class HudBackgroundView @JvmOverloads constructor(
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
    private var level = 0.08f

    private val hexPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeWidth = 1f; color = Color.argb(20, 255, 255, 255)
    }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var hexPath: Path? = null
    private var hexBuiltForW = -1
    private var hexBuiltForH = -1

    /** 六边形网格只在尺寸变化时重算一次，缓存成 Path，平时每帧只是 drawPath。 */
    private fun ensureHexPath(w: Int, h: Int) {
        if (hexPath != null && hexBuiltForW == w && hexBuiltForH == h) return
        hexBuiltForW = w; hexBuiltForH = h
        val path = Path()
        val r = 30f // 六边形外接圆半径，数值越大网格越稀
        val colStep = r * 1.5f
        val rowStep = r * 1.732f // sqrt(3)
        var col = 0
        var cx = -r
        while (cx < w + r * 2) {
            val yOffset = if (col % 2 == 0) 0f else rowStep / 2f
            var cy = -r + yOffset
            while (cy < h + r * 2) {
                addHex(path, cx, cy, r)
                cy += rowStep
            }
            cx += colStep
            col++
        }
        hexPath = path
    }

    /** 平顶六边形：以圆心为基准，6 个顶点每隔 60° 取一个。 */
    private fun addHex(path: Path, cx: Float, cy: Float, r: Float) {
        for (i in 0..5) {
            val angle = Math.toRadians((60 * i).toDouble())
            val x = cx + r * cos(angle).toFloat()
            val y = cy + r * sin(angle).toFloat()
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
    }

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
        val target = HudPalette.colorFor(mode())
        displayedTheme = HudPalette.lerp(displayedTheme, target, 0.08f)
        val (sec, ter) = HudPalette.secondaryTertiary(target)
        displayedSec = HudPalette.lerp(displayedSec, sec, 0.08f)
        displayedTer = HudPalette.lerp(displayedTer, ter, 0.08f)
        val mic = micLevel()
        val m = mode()
        val levelTarget = when (m) {
            "listening" -> 0.06f + mic * 0.95f
            "thinking" -> 0.25f
            "speaking" -> 0.3f
            else -> 0.08f
        }
        level += (levelTarget - level) * 0.12f

        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        fun blob(cx: Float, cy: Float, r: Float, color: Int) {
            blobPaint.shader = RadialGradient(
                cx, cy, r, intArrayOf(color, Color.argb(0, Color.red(color), Color.green(color), Color.blue(color))),
                null, Shader.TileMode.CLAMP
            )
            canvas.drawCircle(cx, cy, r, blobPaint)
        }

        blob(
            w * (0.08f + 0.08f * sin((tt * 0.17f).toDouble()).toFloat()),
            h * (0.10f + 0.05f * cos((tt * 0.23f).toDouble()).toFloat()),
            w * 1.0f, HudPalette.withAlpha(displayedSec, 0.20f + level * 0.08f)
        )
        blob(
            w * (0.98f + 0.06f * cos((tt * 0.19f).toDouble()).toFloat()),
            h * (0.46f + 0.08f * sin((tt * 0.15f).toDouble()).toFloat()),
            w * 0.95f, HudPalette.withAlpha(displayedTer, 0.15f + level * 0.07f)
        )
        blob(
            w * (0.5f + 0.12f * sin((tt * 0.13f).toDouble()).toFloat()),
            h * (1.02f + 0.03f * cos((tt * 0.21f).toDouble()).toFloat()),
            w * 1.1f, HudPalette.withAlpha(displayedTheme, 0.22f + level * 0.10f)
        )

        ensureHexPath(width, height)
        hexPath?.let { canvas.drawPath(it, hexPaint) }
    }
}
