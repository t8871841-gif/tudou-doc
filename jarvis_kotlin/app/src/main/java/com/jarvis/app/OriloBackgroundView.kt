package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orilo 主界面第 1 批（2026-11-15 用户补的要求）：整页流动液态玻璃底。
 * 底色是蓝紫纵向渐变（照用户给的 Orilo/Henry 截图），两团淡色光斑沿不同周期缓慢漂移，
 * 底色本身还 30 秒一圈慢慢偏色（蓝 → 紫粉 → 蓝），整个页面看起来在流动。
 * 只在可见时跑动画（藏起就停，省电）。
 * 挂在根布局上、内容列之下：Orilo 档下 homePanel 背景置空，整页（含底部麦克风行）都透出它。
 */
class OriloBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaintA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaintB = Paint(Paint.ANTI_ALIAS_FLAG)

    // 光斑位置（0..1 的相对坐标），每帧由动画算。
    private var ax = 0.28f
    private var ay = 0.24f
    private var bx = 0.74f
    private var by = 0.72f
    // 底色偏色量（度）：0 → 60 → 0，30 秒一圈，蓝 → 紫粉 → 蓝。
    private var hueShift = 0f

    private var flowAnimator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFlowAnim()
    }

    override fun onDetachedFromWindow() {
        stopFlowAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateFlowAnim()
    }

    private fun updateFlowAnim() {
        if (isShown) startFlowAnim() else stopFlowAnim()
    }

    private fun startFlowAnim() {
        if (flowAnimator != null) return
        flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 30000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = (animatedValue as Float) * Math.PI.toFloat() * 2f
                // 两团光斑走不同周期，慢慢漂，不抢眼；sin/cos 在 0/2π 处接得上，循环不断档。
                ax = 0.30f + 0.18f * sin(t * 0.9f)
                ay = 0.26f + 0.14f * cos(t * 0.7f)
                bx = 0.72f + 0.16f * cos(t * 0.6f + 1.3f)
                by = 0.70f + 0.15f * sin(t * 0.8f + 2.1f)
                // 底色偏色：0 → 60 → 0 度，蓝 → 紫粉 → 蓝（cos 在两端都回 0，不断档）。
                hueShift = 30f - 30f * cos(t)
                invalidate()
            }
            start()
        }
    }

    private fun stopFlowAnim() {
        flowAnimator?.cancel()
        flowAnimator = null
    }

    /** 底色随时间慢慢偏色（蓝 → 紫粉 → 蓝，30 秒一圈），照视频里整页色调流动的样子。 */
    private fun hueColor(h: Float, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf((h + hueShift) % 360f, s, v))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        // 底色：上浅下深的蓝紫渐变（截图那味），色相随 hueShift 慢慢偏（蓝 → 紫粉 → 蓝）。
        basePaint.shader = LinearGradient(
            0f, 0f, w * 0.25f, h,
            intArrayOf(
                hueColor(232f, 0.50f, 0.95f),
                hueColor(232f, 0.68f, 0.91f),
                hueColor(243f, 0.64f, 0.91f),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, basePaint)
        // 光斑 A：淡薰衣草，左上附近漂。
        blobPaintA.shader = RadialGradient(
            ax * w, ay * h, w * 0.55f,
            intArrayOf(0x59B9C2FF.toInt(), 0x00B9C2FF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, blobPaintA)
        // 光斑 B：淡紫，右下附近漂。
        blobPaintB.shader = RadialGradient(
            bx * w, by * h, w * 0.6f,
            intArrayOf(0x4DB388FF.toInt(), 0x00B388FF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, blobPaintB)
    }
}
