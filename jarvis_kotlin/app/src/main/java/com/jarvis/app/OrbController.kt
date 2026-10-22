package com.jarvis.app

import android.accessibilityservice.AccessibilityService
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.SweepGradient
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

    /** 投递事件给 Dart，并带回执：cb(true)=Dart 已处理；cb(false)/超时=没处理，调用方必须兜底。 */
    @Volatile
    var eventSink: ((String, (Boolean) -> Unit) -> Unit)? = null

    private var tapSeq = 0
    private var stopUntil = 0L
    private var lastTapAt = 0L

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
        main.post { orb?.setState(displayState()) }
    }

    fun isEnabled(): Boolean = enabled

    // 急停后的 1.8 秒内球显示“已停止”（红色），不被 Dart 的状态更新盖掉。
    private fun displayState(): String =
        if (System.currentTimeMillis() < stopUntil) "stopped" else state

    /**
     * 一键急停：取消原生任务引擎、球变红 + 气泡“已停止”，并通知 Dart 取消 agent 循环 / 播报 / 聆听。
     * 来源：悬浮球双击、音量+/-同按、Dart 自己发起（notifyDart=false，避免回环）。
     * 降级：Dart 不在时只做原生侧取消和反馈。
     */
    fun emergencyStop(notifyDart: Boolean = true) {
        try { JarvisAccessibilityService.cancelTask() } catch (_: Throwable) {}
        stopUntil = System.currentTimeMillis() + 1800
        main.post {
            orb?.setState("stopped")
            buzz(orb, HapticFeedbackConstants.LONG_PRESS)
            doShowBubble("已停止")
            main.postDelayed({ orb?.setState(displayState()) }, 1850)
        }
        if (notifyDart) eventSink?.invoke("estop") { }
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
                handleTap()
            }

            override fun longPress() {
                launchApp(null)
            }
        })
        v.setState(displayState())
        try {
            w.addView(v, lp)
            orb = v
            orbLp = lp
        } catch (_: Exception) {
            // 系统拒绝（极少见）：不显示，不影响其他功能
        }
    }

    /**
     * 短按：优先通知 Dart 开始聆听（要求 Dart 回执）；投递不可用 / Dart 回 false / 1.5 秒无回执，
     * 一律兜底 launchApp("assist") 拉起并聆听。点中立即震动；350ms 内再点一次 = 急停。
     * 任何分支都有反馈：震动 + 状态变色或气泡，不允许静默。
     */
    private fun handleTap() {
        val now = System.currentTimeMillis()
        buzz(orb, HapticFeedbackConstants.KEYBOARD_TAP) // 点中立即震动，先给手感反馈
        // 350ms 内连点两次 = 急停（第一下已按原逻辑开始聆听，第二下把它连同后续一并停掉）
        if (now - lastTapAt < 350) {
            lastTapAt = 0
            emergencyStop()
            return
        }
        lastTapAt = now
        val sink = eventSink
        if (!(dartAlive && JarvisForegroundService.running) || sink == null) {
            doShowBubble("正在唤起 Jarvis…")
            launchApp("assist")
            return
        }
        val my = ++tapSeq
        var answered = false
        main.postDelayed({
            if (!answered && my == tapSeq) {
                answered = true
                doShowBubble("正在唤起 Jarvis…")
                launchApp("assist")
            }
        }, 1500)
        try {
            sink("tap") { handled ->
                main.post {
                    if (answered) return@post
                    answered = true
                    if (!handled) {
                        doShowBubble("正在唤起 Jarvis…")
                        launchApp("assist")
                    }
                }
            }
        } catch (_: Throwable) {
            if (!answered) {
                answered = true
                doShowBubble("正在唤起 Jarvis…")
                launchApp("assist")
            }
        }
    }

    /** 触觉反馈：用 View.performHapticFeedback，不需要 VIBRATE 权限；忽略系统“触感反馈”总开关，保证点中有感觉。 */
    @Suppress("DEPRECATION")
    private fun buzz(v: View?, kind: Int) {
        v?.performHapticFeedback(
            kind,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
        )
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

    /**
     * 球本体：同心三层、全部渐变色（无图标）。
     *  外层：环绕渐变环（SweepGradient，非待命时缓慢旋转）
     *  中层：径向渐变圆盘
     *  核心：高光渐变 + 光晕
     * 颜色随状态：待命蓝 / 聆听青 / 思考橙 / 播报绿 / 急停红。呼吸脉冲沿用原动画机制。
     * 拖动阈值 = 系统 touch slop × 2.5：点按时的轻微位移不再被当成拖动吃掉。
     */
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
        private val slop = ViewConfiguration.get(ctx).scaledTouchSlop * 2.5f
        private var st = "idle"
        private var phase = 0f
        private var rot = 0f
        private var anim: ValueAnimator? = null
        private var downX = 0f
        private var downY = 0f
        private var moved = false
        private var longFired = false
        private var ringShader: Shader? = null
        private var diskShader: Shader? = null
        private var glowShader: Shader? = null
        private var coreShader: Shader? = null
        private var pulseShader: Shader? = null
        private val longRunnable = Runnable {
            longFired = true
            performHapticFeedback(
                HapticFeedbackConstants.LONG_PRESS,
                HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING or HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING
            )
            host.longPress()
        }

        // (高光, 主色, 暗部)
        private fun palette(): IntArray = when (st) {
            "listening" -> intArrayOf(0xFFB2F5FF.toInt(), 0xFF26C6DA.toInt(), 0xFF006978.toInt())
            "stopped" -> intArrayOf(0xFFFFCDD2.toInt(), 0xFFEF5350.toInt(), 0xFF8E0000.toInt())
            "thinking" -> intArrayOf(0xFFFFE0B2.toInt(), 0xFFFFA726.toInt(), 0xFFB45F06.toInt())
            "speaking" -> intArrayOf(0xFFC8F7CC.toInt(), 0xFF43C463.toInt(), 0xFF1B5E20.toInt())
            else -> intArrayOf(0xFFB3D4FF.toInt(), 0xFF4F8DF7.toInt(), 0xFF1F3C88.toInt())
        }

        private fun alpha(c: Int, a: Int) = (c and 0x00FFFFFF) or (a.coerceIn(0, 255) shl 24)

        private fun buildShaders() {
            val cx = width / 2f
            val cy = height / 2f
            if (cx <= 0f) return
            val half = min(cx, cy)
            val (light, base, dark) = palette().let { Triple(it[0], it[1], it[2]) }
            val r1 = half * 0.80f // 外环外半径
            val ringW = 3.5f * dp
            val r2 = r1 - ringW - 1.5f * dp // 中层半径
            val r3 = r2 * 0.52f // 核心半径
            ringShader = SweepGradient(cx, cy, intArrayOf(light, base, dark, base, light), null)
            diskShader = RadialGradient(
                cx, cy, r2, intArrayOf(alpha(light, 235), alpha(base, 235), alpha(dark, 245)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            glowShader = RadialGradient(
                cx, cy, r3 * 1.9f, intArrayOf(alpha(light, 170), alpha(base, 70), alpha(base, 0)),
                floatArrayOf(0f, 0.55f, 1f), Shader.TileMode.CLAMP
            )
            coreShader = RadialGradient(
                cx - r3 * 0.28f, cy - r3 * 0.30f, r3 * 1.25f,
                intArrayOf(0xFFFFFFFF.toInt(), light, base), floatArrayOf(0f, 0.45f, 1f), Shader.TileMode.CLAMP
            )
            pulseShader = RadialGradient(
                cx, cy, r1, intArrayOf(alpha(base, 0), alpha(light, 150), alpha(base, 0)),
                floatArrayOf(0.62f, 0.86f, 1f), Shader.TileMode.CLAMP
            )
        }

        override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) {
            super.onSizeChanged(w, h, ow, oh)
            buildShaders()
        }

        fun setState(s: String) {
            if (s != st) {
                st = s
                buildShaders()
            }
            if (s == "idle") {
                anim?.cancel()
                anim = null
                phase = 0f
            } else if (anim == null) {
                // 沿用原呼吸机制：1.2s 循环的 phase；顺带让外环缓慢旋转（约 36°/秒）
                anim = ValueAnimator.ofFloat(0f, 1f).apply {
                    duration = 1200
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener {
                        phase = it.animatedValue as Float
                        rot = (rot + 0.6f) % 360f
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
            if (cx <= 0f) return
            val half = min(cx, cy)
            val r1 = half * 0.80f
            val ringW = 3.5f * dp
            val r2 = r1 - ringW - 1.5f * dp
            val r3 = r2 * 0.52f
            paint.reset()
            paint.isAntiAlias = true
            // 呼吸脉冲（非待命）：渐变环向外扩散并淡出
            if (st != "idle") {
                val s = 1f + (half / r1 - 1f) * phase
                c.save()
                c.scale(s, s, cx, cy)
                paint.style = Paint.Style.FILL
                paint.shader = pulseShader
                paint.alpha = ((1f - phase) * 255).toInt()
                c.drawCircle(cx, cy, r1, paint)
                c.restore()
                paint.reset()
                paint.isAntiAlias = true
            }
            // 外层：环绕渐变环
            c.save()
            c.rotate(rot, cx, cy)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = ringW
            paint.shader = ringShader
            c.drawCircle(cx, cy, r1 - ringW / 2f, paint)
            c.restore()
            // 中层：径向渐变圆盘
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            paint.shader = diskShader
            c.drawCircle(cx, cy, r2, paint)
            // 核心：光晕 + 高光
            paint.shader = glowShader
            c.drawCircle(cx, cy, r3 * 1.9f, paint)
            paint.shader = coreShader
            c.drawCircle(cx, cy, r3, paint)
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
