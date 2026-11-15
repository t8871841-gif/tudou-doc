package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Orilo 主界面第 1 批：水滴主视觉。
 * 2026-11-15 用户要求：动态水滴要好好设计，不要跟随状态走，照 Orilo 的效果来。
 * 画法照用户给的 Orilo/Henry 截图（workspace/user/files/4386_0_dzts.png）：
 * 一颗清水滴——本身几乎透明（透出后面的流动底），右下方一团软影、右上一道亮高光、
 * 左下一点小高光、底部一圈亮边。颜色是固定的玻璃色，不跟 JarvisAgent 状态走。
 * 动态：上下慢漂（±8dp，6 秒一圈）+ 轻微呼吸缩放，只在可见时跑动画（藏起就停，省电）。
 * 陀螺仪联动是第 3 批的事，本批只做自主慢漂。
 */
class OriloDropletView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        alpha = 140
        strokeCap = Paint.Cap.ROUND
    }
    private val rimSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
        alpha = 60
    }

    private var driftAnimator: ValueAnimator? = null

    // ---------- 动态水滴：自主慢漂 ----------
    // 只在可见时跑：onVisibilityChanged 会在祖先显隐变化时也回调，isShown 兜底。

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateDriftAnim()
    }

    override fun onDetachedFromWindow() {
        stopDriftAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateDriftAnim()
    }

    private fun updateDriftAnim() {
        if (isShown) startDriftAnim() else stopDriftAnim()
    }

    private fun startDriftAnim() {
        if (driftAnimator != null) return
        val ampPx = 8f * resources.displayMetrics.density
        driftAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                val t = animatedValue as Float
                // 慢漂：上下 8dp + 轻微呼吸缩放；translation/scale 走合成层，不重画。
                translationY = sin(t) * ampPx
                val s = 1f + 0.02f * sin(t + 1.2f)
                scaleX = s
                scaleY = s
            }
            start()
        }
    }

    private fun stopDriftAnim() {
        driftAnimator?.cancel()
        driftAnimator = null
        translationY = 0f
        scaleX = 1f
        scaleY = 1f
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 布局给 220dp 的框，水滴本身画 0.78 倍、圆心略靠上，给右下方的软影留地方。
        val r = min(width, height) / 2f * 0.78f
        val cx = width / 2f
        val cy = height * 0.45f
        if (r <= 0f) return
        // 影子：右下方一团软影（照 Orilo 截图）。
        shadowPaint.shader = RadialGradient(
            cx + r * 0.1f, cy + r * 1.02f, r * 0.8f,
            intArrayOf(0x26000000, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(
            RectF(cx - r * 0.75f, cy + r * 0.75f, cx + r * 0.95f, cy + r * 1.3f),
            shadowPaint,
        )
        // 体：清水滴——顶部带一点亮、边缘带一点背景蓝的折射感，中间几乎透明。
        bodyPaint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.45f, r * 1.5f,
            intArrayOf(0x59FFFFFF, 0x14FFFFFF, 0x08FFFFFF, 0x2EB9C2FF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, bodyPaint)
        // 大高光：右上一道斜的亮斑。
        canvas.save()
        canvas.translate(cx + r * 0.42f, cy - r * 0.48f)
        canvas.rotate(-35f)
        canvas.scale(1f, 0.45f)
        highlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.3f,
            intArrayOf(0xE6FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.3f, highlightPaint)
        canvas.restore()
        // 小高光：左下一小点。
        canvas.save()
        canvas.translate(cx - r * 0.45f, cy + r * 0.42f)
        canvas.rotate(-35f)
        canvas.scale(1f, 0.6f)
        smallHighlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.14f,
            intArrayOf(0xB3FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.14f, smallHighlightPaint)
        canvas.restore()
        // 亮边：整圈淡淡描边 + 下半圈亮弧（35°→145° 经过正下方 90°）。
        canvas.drawCircle(cx, cy, r - 1f, rimSoftPaint)
        val rimRect = RectF(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f)
        canvas.drawArc(rimRect, 35f, 110f, false, rimPaint)
    }
}
