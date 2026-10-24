package com.jarvis.app.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * 批九移植说明：Andee 的 TapMarkerUi 是在屏幕上画「上次手势落点圆环」的悬浮
 * 标记层，供模型在手势后的截图里核对落点。
 *
 * 2026-10-24 跨应用修复第 4 件：绘制层补上了——此前 revealNow() 是空操作，
 * 用户在真机上看不到任何点击动作（只有接管边框在转），Andee 却能看到落点
 * 涟漪。本实现在落点画一个 600ms 的扩散圈：tap/longPress 后由 ScreenController
 * 调 mark() 触发；截图时调 revealNow() 可把新鲜的圈重新亮出来（与原版语义一致，
 * 顺带让圈进截图，模型也能核对落点）。
 *
 * 覆盖层是全屏透明 View（TYPE_ACCESSIBILITY_OVERLAY + NOT_TOUCHABLE，触摸
 * 直通，不拦截手势；importantForAccessibility=NO，不污染界面树）。svc 为空时
 * 只记录坐标不画图，绝不崩。绘制与 WindowManager 操作一律 post 到主线程。
 */
class TapMarkerUi(private val svc: AccessibilityService? = null) {

    enum class Kind { TAP, HOLD }

    @Volatile
    private var lastPx: IntArray? = null

    @Volatile
    private var lastAtMs = 0L

    private val main = Handler(Looper.getMainLooper())
    private var overlay: RingView? = null
    private var wm: WindowManager? = null
    private var hideRunnable: Runnable? = null

    fun mark(x: Int, y: Int, kind: Kind) {
        lastPx = intArrayOf(x, y)
        lastAtMs = System.currentTimeMillis()
        showRing(x, y)
    }

    /** 原版语义：手势通道把标记藏起来了，此刻让它重新可见（新鲜的圈才重亮）。 */
    fun revealNow() {
        val px = lastPx ?: return
        if (System.currentTimeMillis() - lastAtMs > 800) return
        showRing(px[0], px[1])
    }

    fun lastMarkPx(): IntArray? = lastPx

    fun lastMarkAgeMs(): Long? =
        if (lastAtMs == 0L) null else System.currentTimeMillis() - lastAtMs

    private fun showRing(x: Int, y: Int) {
        val s = svc ?: return
        main.post {
            try {
                if (wm == null) {
                    wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                }
                val w = wm ?: return@post
                var v = overlay
                if (v == null) {
                    v = RingView(s)
                    val lp = WindowManager.LayoutParams(
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.MATCH_PARENT,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
                        PixelFormat.TRANSLUCENT
                    )
                    lp.gravity = Gravity.TOP or Gravity.START
                    w.addView(v, lp)
                    overlay = v
                }
                v.setCenter(x, y)
                hideRunnable?.let { main.removeCallbacks(it) }
                val hide = Runnable {
                    try {
                        overlay?.let { w.removeView(it) }
                    } catch (_: Exception) {
                    }
                    overlay = null
                }
                hideRunnable = hide
                main.postDelayed(hide, 700)
            } catch (_: Exception) {
                // 覆盖层加不上不影响任务本身：最坏是看不到圈（旧行为）
            }
        }
    }

    /** 落点扩散圈：600ms 内从 20dp 扩到 64dp、同步淡出；到期后靠自己停刷。 */
    private class RingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var cx = 0f
        private var cy = 0f
        private var shownAt = 0L

        init {
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        }

        fun setCenter(x: Int, y: Int) {
            cx = x.toFloat()
            cy = y.toFloat()
            shownAt = System.currentTimeMillis()
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val age = System.currentTimeMillis() - shownAt
            if (shownAt == 0L || age > 600) return
            val p = age / 600f
            val d = resources.displayMetrics.density
            val radius = (20f + p * 44f) * d
            val alpha = ((1f - p) * 0.9f * 255).toInt().coerceIn(0, 255)
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * d
            paint.color = Color.argb(alpha, 0x00, 0xF0, 0xFF)
            canvas.drawCircle(cx, cy, radius, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, 4f * d, paint)
            postInvalidateOnAnimation()
        }
    }
}
