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
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView

/**
 * 需求文档总原则里的「接管状态要看得见」：看屏代理在跑的时候，屏幕四周显示跑马灯式的
 * 动态彩光边框，任务结束/急停立即消失。边框本身只负责"看得见"，不拦截触摸
 * （FLAG_NOT_TOUCHABLE + FLAG_NOT_FOCUSABLE），不影响用户随时自己操作手机。
 * Phase 5：边框窗口加 FLAG_KEEP_SCREEN_ON（任务中屏幕不休眠）；另有一个独立的
 * 小“停止”药丸贴在底部中央（可点自己的那一小块地方，其余触摸照常落到下面应用），
 * 点它走用户急停通道停掉当前任务；药丸跟边框同生同灭。
 */
object TakeoverBorder {
    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var view: BorderView? = null
    private var pill: TextView? = null
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
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON,
                PixelFormat.TRANSLUCENT
            ).apply { gravity = Gravity.TOP or Gravity.START }
            val v = BorderView(s)
            try {
                w.addView(v, lp)
                view = v
            } catch (_: Exception) {
                // 极少数系统拒绝叠加层：降级为"看不见接管边框"，不影响任务本身执行
            }
            // 停止钮（Phase 5）：跟边框同生同灭的小药丸，贴在屏幕底部中央；
            // 它是另一个独立的小窗口（可点），只占自己那一小块地方，点它以外
            // 的地方照常落到下面的应用。点它 = 用户急停：停任务循环、走已有
            // 的急停通道（悬浮球双击 / 音量键组合同款），边框随后由那条通道
            // 或任务收尾的 hide() 一并撤掉。
            if (pill == null) {
                try {
                    val density = d.density
                    val p = TextView(s).apply {
                        text = "停止"
                        setTextColor(Color.WHITE)
                        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
                        val padH = (28 * density).toInt()
                        val padV = (10 * density).toInt()
                        setPadding(padH, padV, padH, padV)
                        background = GradientDrawable().apply {
                            shape = GradientDrawable.RECTANGLE
                            cornerRadius = 22 * density
                            setColor(Color.parseColor("#CC1A1A1A"))
                        }
                        setOnClickListener {
                            com.jarvis.app.brain.BrainRunner.stopCurrent()
                            OrbController.emergencyStop()
                        }
                    }
                    val plp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.WRAP_CONTENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                        y = (64 * density).toInt()
                    }
                    w.addView(p, plp)
                    pill = p
                } catch (_: Exception) {
                    // 停止钮加不上只降级为"没有停止钮"，边框和任务照常
                    pill = null
                }
            }
        }
    }

    fun hide() {
        main.post {
            view?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            view = null
            pill?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
            pill = null
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
