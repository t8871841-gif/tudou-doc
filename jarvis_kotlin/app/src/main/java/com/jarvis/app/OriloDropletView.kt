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
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Orilo 主界面第 1 批：水滴主视觉。
 * 2026-11-15 用户要求：动态水滴要好好设计，不要跟随状态走，照 Orilo 的效果来。
 * 画法照用户给的 Orilo/Henry 截图（workspace/user/files/4386_0_dzts.png）：
 * 一颗清水滴——本身几乎透明（透出后面的流动底），右下方一团软影、右上一道亮高光、
 * 左下一点小高光、底部一圈亮边。颜色是固定的玻璃色，不跟 JarvisAgent 状态走。
 * 动态（2026-11-15 用户看视频后定）：待机时缩小、有睡觉的感觉——大小跟待机走：
 * idle 缩到 0.8 倍、干活胀回满，700ms 平滑过渡；平时再叠一层慢呼吸（6 秒一圈）。
 * 颜色固定玻璃色，不跟状态走。陀螺仪联动是第 3 批的事。
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

    // ---------- 动态水滴：待机缩小（睡觉的感觉）+ 慢呼吸 ----------
    // 大小跟待机走：idle 0.8 倍、干活 1.0 倍，700ms 平滑过渡；呼吸与慢漂 6 秒一圈。
    // 只在可见时跑呼吸动画：onVisibilityChanged 会在祖先显隐变化时也回调，isShown 兜底。
    private var awake = false
    private var currentScale = IDLE_SCALE
    private var breathT = 0f
    private val driftAmpPx = 5f * resources.displayMetrics.density
    private var scaleAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    init {
        applyMotion()
    }

    /** 调用方在状态变化时调：待机（idle）缩、干活胀。颜色不跟状态，只动大小。 */
    fun setAwake(awake: Boolean) {
        if (this.awake == awake) return
        this.awake = awake
        val target = if (awake) AWAKE_SCALE else IDLE_SCALE
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(currentScale, target).apply {
            duration = 700L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentScale = animatedValue as Float
                applyMotion()
            }
            start()
        }
    }

    private fun applyMotion() {
        val breath = 1f + 0.015f * sin(breathT)
        scaleX = currentScale * breath
        scaleY = currentScale * breath
        translationY = sin(breathT) * driftAmpPx
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotionAnim()
    }

    override fun onDetachedFromWindow() {
        stopMotionAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMotionAnim()
    }

    private fun updateMotionAnim() {
        if (isShown) startMotionAnim() else stopMotionAnim()
    }

    private fun startMotionAnim() {
        if (motionAnimator != null) return
        motionAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                breathT = animatedValue as Float
                applyMotion()
            }
            start()
        }
    }

    private fun stopMotionAnim() {
        motionAnimator?.cancel()
        motionAnimator = null
        translationY = 0f
        scaleX = currentScale
        scaleY = currentScale
    }

    companion object {
        private const val IDLE_SCALE = 0.8f
        private const val AWAKE_SCALE = 1.0f
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
        // 体：清水滴——顶部带一点亮、边缘带一点淡淡的白，中间几乎透明；底色偏色时也不串色。
        bodyPaint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.45f, r * 1.5f,
            intArrayOf(0x59FFFFFF, 0x14FFFFFF, 0x08FFFFFF, 0x1AFFFFFF),
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
