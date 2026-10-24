package com.jarvis.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View

/**
 * 背景层：移植自 `hud_painters.dart` 的 AuroraPainter（三团缓慢漂移的大光晕，颜色跟随状态）。
 * 2026-10-24 用户真机看了钢铁侠蜂窝版后明确不要格纹：蜂巢六边形网格已整层撤除，
 * 背景只剩纯净深蓝 + 光晕，无任何网格/点阵（要恢复格纹从 git 历史取本文件旧版）。
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

        // 清澈化（2026-10-24 用户定）：三团漂移光晕撤除，背景只留一层实色深蓝；
        // 玻璃对话框是界面上唯一的半透明元素，周围改实之后层次才出得来。
        canvas.drawColor(0xFF050B18.toInt())
    }
}
