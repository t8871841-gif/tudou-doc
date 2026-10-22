package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 需求文档总原则里的「接管状态要看得见」：看屏代理在跑的时候，屏幕四周显示跑马灯式的
 * 动态彩光边框，任务结束/急停立即消失。只负责"看得见"，不拦截触摸（FLAG_NOT_TOUCHABLE +
 * FLAG_NOT_FOCUSABLE），不影响用户随时自己操作手机。
 */
object TakeoverBorder {
    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var view: BorderView? = null
    private val main = Handler(Looper.getMainLooper())

    fun attach(s: AccessibilityService) {
        svc = s
        wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    fun detach() {
        hide()
        svc = null
        wm = null
    }

    fun show() {
        main.post {
            if (view != null) return@post
            val s = svc ?: return@post
            val w = wm ?: return@post
            val d = s.resources.displayMetrics
            val lp = WindowManager.LayoutParams(
                d.widthPixels, d.heightPixels,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val v = BorderView(s)
            try {
                w.addView(v, lp)
                view = v
            } catch (_: Exception) {
                // 极少数系统拒绝叠加层：降级为"看不见接管边框"，不影响任务本身执行
            }
        }
    }

    fun hide() {
        main.post {
            view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            view = null
        }
    }

    fun isShowing(): Boolean = view != null

    private class BorderView(ctx: Context) : View(ctx) {
        private val paint = Paint().apply { style = Paint.Style.STROKE; strokeWidth = 10f }
        private var phase = 0f
        private var anim: ValueAnimator? = null

        init {
            anim = ValueAnimator.ofFloat(0f, 1f).apply {
                duration = 1600
                repeatCount = ValueAnimator.INFINITE
                addUpdateListener { phase = it.animatedValue as Float; invalidate() }
                start()
            }
        }

        override fun onDetachedFromWindow() {
            anim?.cancel(); anim = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            if (width <= 0 || height <= 0) return
            val colors = intArrayOf(
                Color.CYAN, Color.MAGENTA, Color.YELLOW, Color.GREEN, Color.CYAN
            )
            val offset = phase * (width + height) * 2f
            // 用跨越对角线方向平移的线性渐变模拟"跑马灯"流动效果，只画在四条边框上。
            paint.shader = LinearGradient(
                -offset, 0f, width - offset + height * 2f, height.toFloat(),
                colors, null, Shader.TileMode.REPEAT
            )
            val inset = paint.strokeWidth / 2f
            c.drawRect(inset, inset, width - inset, height - inset, paint)
        }
    }
}
