package com.jarvis.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import kotlin.math.min

/**
 * Orilo 主界面第 1 批：水滴主视觉（静态）。
 * 画法与悬浮球水滴同款——径向渐变 + 左上高光 + 底部亮边；颜色跟 JarvisAgent 状态走
 * （调用方在 onAgentStateChanged 里调 setStateColor）。
 * 第 2 批融合、第 3 批陀螺仪联动再动它；本批只画一颗静止的水滴。
 */
class OriloDropletView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var stateColor: Int = 0xFFBFA8F0.toInt() // 悬浮球待机紫

    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        alpha = 140
    }
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        alpha = 90
        strokeCap = Paint.Cap.ROUND
    }

    fun setStateColor(color: Int) {
        if (stateColor == color) return
        stateColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val cx = width / 2f
        val cy = height / 2f
        val r = min(width, height) / 2f * 0.92f
        if (r <= 0f) return
        // 球体：光心偏左上，边缘收深，与悬浮球同款。
        bodyPaint.shader = RadialGradient(
            cx - r * 0.35f, cy - r * 0.4f, r * 1.6f,
            intArrayOf(Color.WHITE, stateColor, darken(stateColor)),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, bodyPaint)
        // 左上高光：一颗斜椭圆。
        canvas.save()
        canvas.translate(cx - r * 0.38f, cy - r * 0.42f)
        canvas.rotate(-30f)
        canvas.drawOval(RectF(-r * 0.28f, -r * 0.13f, r * 0.28f, r * 0.13f), highlightPaint)
        canvas.restore()
        // 底部亮边：下半圈细弧（35°→145° 经过正下方 90°）。
        val rimRect = RectF(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f)
        canvas.drawArc(rimRect, 35f, 110f, false, rimPaint)
    }

    private fun darken(color: Int): Int {
        val f = 0.45f
        return Color.rgb(
            (Color.red(color) * f).toInt(),
            (Color.green(color) * f).toInt(),
            (Color.blue(color) * f).toInt(),
        )
    }
}
