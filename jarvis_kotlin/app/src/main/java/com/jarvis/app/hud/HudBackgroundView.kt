package com.jarvis.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.cos
import kotlin.math.sin

/**
 * 移植自 `hud_painters.dart` 的 GridBackgroundPainter（极淡点阵）+ AuroraPainter
 * （三团缓慢漂移的大光晕，颜色跟随状态）。两层合并成一个 View 省一次 overdraw。
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

    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(13, 255, 255, 255) }
    private val blobPaint = Paint(Paint.ANTI_ALIAS_FLAG)

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

        var x = 18f
        while (x < w) {
            var y = 18f
            while (y < h) {
                canvas.drawCircle(x, y, 0.9f, dotPaint)
                y += 36f
            }
            x += 36f
        }
    }
}
