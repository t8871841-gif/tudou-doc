package com.jarvis.app.hud

import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import android.view.View

/**
 * 静态线性渐变层：用来画主页背景的两层渐变——
 *  - 深邃夜蓝对角渐变 #050811→#0B132B→#050811（diagonal=true）；
 *  - 顶/底压暗叠层 黑0.65→透明→黑0.8（diagonal=false，即从上到下）。
 * GradientDrawable 在 minSdk 24 上不支持自定义 stop 位置，所以用 Canvas+Shader 自己画。
 */
class GradientLayerView(
    context: Context,
    private val colors: IntArray,
    private val positions: FloatArray,
    private val diagonal: Boolean
) : View(context) {
    private val paint = Paint()

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        paint.shader = if (diagonal) {
            LinearGradient(0f, 0f, w, h, colors, positions, Shader.TileMode.CLAMP)
        } else {
            LinearGradient(0f, 0f, 0f, h, colors, positions, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, w, h, paint)
    }
}
