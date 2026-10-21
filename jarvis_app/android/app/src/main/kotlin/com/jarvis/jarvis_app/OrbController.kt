package com.jarvis.jarvis_app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowManager
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.min

/**
 * 悬浮球：用无障碍服务的 TYPE_ACCESSIBILITY_OVERLAY 窗口画在所有 App 之上，不需要“悬浮窗”权限。
 *  - 只在 Jarvis 不在前台时显示（前台时界面本身就在）；
 *  - 点一下：常驻服务在跑 → 通知 Dart 开始听；否则拉起 Jarvis 再听（后台没有前台服务时系统不让用麦克风）；
 *  - 长按：打开 Jarvis；拖动：随手摆位，松手吸附左右边缘并记住位置；
 *  - 球的颜色跟随状态（待命/聆听/思考/播报），回答文字在球旁弹出小气泡。
 * 全部 UI 操作在主线程。
 */
object OrbController {
    private const val PREFS = "jarvis_orb"

    private var svc: AccessibilityService? = null
    private var wm: WindowManager? = null
    private var orb: OrbView? = null
    private var orbLp: WindowManager.LayoutParams? = null
    private var bubble: TextView? = null
    private var enabled = false
    private var appForeground = false
    private var state = "idle"
    private var startX = 0
    private var startY = 0
    private val main = Handler(Looper.getMainLooper())
    private val hideBubbleRunnable = Runnable { removeBubble() }

    @Volatile
    var dartAlive = false

    @Volatile
    var eventSink: ((String) -> Unit)? = null

    fun attach(s: AccessibilityService) {
        svc = s
        wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
        enabled = s.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean("on", false)
        refresh()
    }

    fun detach() {
        removeAll()
        svc = null
        wm = null
    }

    /** 返回 false = 无障碍服务没在运行（开关值已记住，服务起来后自动显示）。 */
    fun setEnabled(on: Boolean): Boolean {
        enabled = on
        svc?.getSharedPreferences(PREFS, Context.MODE_PRIVATE)?.edit()?.putBoolean("on", on)?.apply()
        refresh()
        return svc != null
    }

    fun setAppForeground(fg: Boolean) {
        appForeground = fg
        refresh()
    }

    fun setState(s: String) {
        state = s
        main.post { orb?.setState(s) }
    }

    fun showBubble(text: String) {
        main.post { doShowBubble(text) }
    }

    private fun refresh() {
        main.post { if (enabled && !appForeground && svc != null) show() else removeAll() }
    }

    private fun density(): Float = svc?.resources?.displayMetrics?.density ?: 3f

    private fun show() {
        if (orb != null) return
        val s = svc ?: return
        val w = wm ?: return
        val d = s.resources.displayMetrics
        val size = (56 * d.density).toInt()
        val p = s.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val lp = WindowManager.LayoutParams(
            size, size, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = p.getInt("x", d.widthPixels - size).coerceIn(0, (d.widthPixels - size).coerceAtLeast(0))
        lp.y = p.getInt("y", d.heightPixels / 3).coerceIn(0, (d.heightPixels - size).coerceAtLeast(0))
        val v = OrbView(s, object : OrbView.Host {
            override fun dragStart() {
                startX = lp.x
                startY = lp.y
                removeBubble()
            }

            override fun dragBy(dx: Float, dy: Float) {
                lp.x = (startX + dx).toInt().coerceIn(0, (d.widthPixels - size).coerceAtLeast(0))
                lp.y = (startY + dy).toInt().coerceIn(0, (d.heightPixels - size).coerceAtLeast(0))
                try { w.updateViewLayout(orb, lp) } catch (_: Exception) {}
            }

            override fun dragEnd() {
                val target = if (lp.x + size / 2 < d.widthPixels / 2) 0 else (d.widthPixels - size).coerceAtLeast(0)
                ValueAnimator.ofInt(lp.x, target).apply {
                    duration = 180
                    addUpdateListener {
                        lp.x = it.animatedValue as Int
                        try { w.updateViewLayout(orb, lp) } catch (_: Exception) {}
                    }
                    start()
                }
                p.edit().putInt("x", target).putInt("y", lp.y).apply()
            }

            override fun tap() {
                if (dartAlive && JarvisForegroundService.running) eventSink?.invoke("tap") else launchApp("assist")
            }

            override fun longPress() {
                launchApp(null)
            }
        })
        v.setState(state)
        try {
            w.addView(v, lp)
            orb = v
            orbLp = lp
        } catch (_: Exception) {
            // 系统拒绝（极少见）：不显示，不影响其他功能
        }
    }

    private fun launchApp(action: String?) {
        val s = svc ?: return
        val i = Intent(s, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            if (action != null) putExtra(MainActivity.EXTRA_ACTION, action)
        }
        try { s.startActivity(i) } catch (_: Exception) {}
    }

    private fun doShowBubble(text: String) {
        val s = svc ?: return
        val w = wm ?: return
        val olp = orbLp ?: return
        if (orb == null || text.isBlank()) return
        removeBubble()
        val d = s.resources.displayMetrics
        val tv = TextView(s).apply {
            this.text = if (text.length > 120) text.take(120) + "…" else text
            setTextColor(Color.WHITE)
            textSize = 13f
            maxWidth = (240 * d.density).toInt()
            val pad = (10 * d.density).toInt()
            setPadding(pad, pad - 2, pad, pad - 2)
            background = GradientDrawable().apply {
                cornerRadius = 14 * d.density
                setColor(0xE6101828.toInt())
            }
        }
        tv.measure(
            View.MeasureSpec.makeMeasureSpec(tv.maxWidth, View.MeasureSpec.AT_MOST),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        )
        val gap = (8 * d.density).toInt()
        val onLeft = olp.x + olp.width / 2 < d.widthPixels / 2
        val x = if (onLeft) olp.x + olp.width + gap else olp.x - tv.measuredWidth - gap
        val y = olp.y + olp.height / 2 - tv.measuredHeight / 2
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            this.x = x.coerceAtLeast(0)
            this.y = y.coerceAtLeast(0)
        }
        try {
            w.addView(tv, lp)
            bubble = tv
            main.postDelayed(hideBubbleRunnable, (3500L + text.length * 60L).coerceAtMost(12000L))
        } catch (_: Exception) {}
    }

    private fun removeBubble() {
        main.removeCallbacks(hideBubbleRunnable)
        bubble?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        bubble = null
    }

    private fun removeAll() {
        removeBubble()
        orb?.let { try { wm?.removeView(it) } catch (_: Exception) {} }
        orb = null
        orbLp = null
    }

    /** 球本体：状态着色 + 脉冲 + 触摸（拖动 / 点击 / 长按）。 */
    private class OrbView(ctx: Context, private val host: Host) : View(ctx) {
        interface Host {
            fun dragStart()
            fun dragBy(dx: Float, dy: Float)
            fun dragEnd()
            fun tap()
            fun longPress()
        }

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val dp = ctx.resources.displayMetrics.density
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
        private var st = "idle"
        private var phase = 0f
        private var anim: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longFired = false
        private val longRunnable = Runnable {
            longFired = true
            performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
            host.longPress()
        }

        fun setState(s: String) {
            st = s
            if (s == "idle") {
                anim?.cancel()
                anim = null
                phase = 0f
            } else if (anim == null) {
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        invalidate()
                    }
                    start()
                }
            }
            invalidate()
        }

        override fun onDetachedFromWindow() {
            anim?.cancel()
            anim = null
            super.onDetachedFromWindow()
        }

        override fun onDraw(c: Canvas) {
            val cx = width / 2f
            val cy = height / 2f
            val half = min(cx, cy)
            val r = half * 0.78f
            val color = when (st) {
                "listening" -> 0xFF4DD0E1.toInt()
                "thinking" -> 0xFFFFB74D.toInt()
                "speaking" -> 0xFF81C784.toInt()
                else -> 0xFF8AB4F8.toInt()
            }
            if (st != "idle") {
                paint.style = Paint.Style.FILL
                paint.color = (color and 0x00FFFFFF) or (((1f - phase) * 90).toInt() shl 24)
                c.drawCircle(cx, cy, r + (half - r) * phase, paint)
            }
            paint.style = Paint.Style.FILL
            paint.color = 0xF0101828.toInt()
            c.drawCircle(cx, cy, r, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * dp
            paint.color = color
            c.drawCircle(cx, cy, r - 1.25f * dp, paint)
            // 麦克风：胶囊 + 底部弧 + 立柱
            val w = r * 0.26f
            val top = cy - r * 0.50f
            val bottom = cy + r * 0.08f
            paint.style = Paint.Style.FILL
            c.drawRoundRect(RectF(cx - w, top, cx + w, bottom), w, w, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f * dp
            paint.strokeCap = Paint.Cap.ROUND
            val aw = r * 0.46f
            c.drawArc(RectF(cx - aw, cy - r * 0.30f, cx + aw, cy + r * 0.34f), 0f, 180f, false, paint)
            c.drawLine(cx, cy + r * 0.34f, cx, cy + r * 0.52f, paint)
        }

        override fun onTouchEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX
                    downY = e.rawY
                    moved = false
                    longFired = false
                    host.dragStart()
                    postDelayed(longRunnable, 600)
                    return true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (!moved && (abs(dx) > slop || abs(dy) > slop)) {
                        moved = true
                        removeCallbacks(longRunnable)
                    }
                    if (moved) host.dragBy(dx, dy)
                    return true
                }
                MotionEvent.ACTION_UP -> {
                    removeCallbacks(longRunnable)
                    if (moved) host.dragEnd() else if (!longFired) host.tap()
                    return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    removeCallbacks(longRunnable)
                    if (moved) host.dragEnd()
                    return true
                }
            }
            return super.onTouchEvent(e)
        }
    }
}
