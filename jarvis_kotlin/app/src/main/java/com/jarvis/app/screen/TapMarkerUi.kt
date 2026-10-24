package com.jarvis.app.screen

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.DecelerateInterpolator
import kotlin.math.max

/**
 * Andee 的 TapMarkerUi 落点标记层（2026-10-25 按 Andee 照搬）。
 *
 * 两类观众，所以它停留而不是一闪而过：用户看得到按了哪里；模型在下一张截图里
 * 看到圈，才能核对“你让它点的 500,500 到底落在了哪里”——圈落在按钮旁边和圈
 * 落在按钮上没反应，是两种完全不同的问题。
 *
 * ## 为什么窗口只有 144dp 见方（批十九全屏窗口的教训）
 * Android 按窗口面积算遮挡上限：一个可见的全屏覆盖层自己就占满 0.80 上限，
 * 再叠加悬浮球、接管边框就会静默吃掉触摸。窗口只做到标记本身的大小（144dp），
 * 并且只在落点处出现，就永远碰不到那条线。批十九的全屏实现已废弃。
 *
 * ## 时间线（Andee 原值）
 * 冲击：两圈先后扩散（480ms，间隔 110ms）→ 停留 6000ms → 淡出 400ms，
 * 总计约 7 秒；淡出用整体透明度渐变，绝不在截图中途突然消失。
 * 手势发出前先由 ScreenController 调 hideNow() 把上一圈藏起来——注入的手势
 * 落点正好就是圈坐着的位置，不能让圈挡在那里。
 *
 * 窗口定位用落点像素减半盒（fitInsetsTypes=0，不受状态栏偏移），svc 为空时
 * 只记录坐标不画图。所有 WindowManager 操作都在主线程。
 */
class TapMarkerUi(private val svc: AccessibilityService? = null) {

    enum class Kind { TAP, HOLD }

    @Volatile
    private var lastPx: IntArray? = null

    @Volatile
    private var lastAtMs = 0L

    private val main = Handler(Looper.getMainLooper())
    private var view: MarkView? = null
    private var params: WindowManager.LayoutParams? = null
    private var wm: WindowManager? = null

    // 当前标记的状态（revealNow 重亮时复用，不重启时间线）。
    private var markKind = ""
    private var markCoord = ""
    private var markStartedAt = 0L

    fun mark(x: Int, y: Int, kind: Kind) {
        lastPx = intArrayOf(x, y)
        lastAtMs = SystemClock.uptimeMillis()
        val s = svc ?: return
        val dm = s.resources.displayMetrics
        val nx = (x * 1000 / max(1, dm.widthPixels)).coerceIn(0, 1000)
        val ny = (y * 1000 / max(1, dm.heightPixels)).coerceIn(0, 1000)
        markKind = if (kind == Kind.HOLD) "HOLD" else "TAP"
        markCoord = "$nx,$ny"
        markStartedAt = SystemClock.uptimeMillis()
        showMark(x, y)
    }

    /** 原版语义：手势通道把标记藏起来了，此刻让它重新可见（时间线不重启）。 */
    fun revealNow() {
        val px = lastPx ?: return
        if (SystemClock.uptimeMillis() - markStartedAt > STRIKE_MS + HOLD_MS + FADE_MS) return
        showMark(px[0], px[1])
    }

    /** 手势发出前藏圈：注入落点正好是圈坐着的位置，不能让它挡在那里。 */
    fun hideNow() {
        main.post { removeWindow() }
    }

    fun lastMarkPx(): IntArray? = lastPx

    /** 距上次标记的毫秒数；超过冲击+停留+淡出，圈已消失。 */
    fun lastMarkAgeMs(): Long? =
        if (lastAtMs == 0L) null else SystemClock.uptimeMillis() - lastAtMs

    private fun showMark(x: Int, y: Int) {
        val s = svc ?: return
        main.post {
            try {
                if (wm == null) {
                    wm = s.getSystemService(Context.WINDOW_SERVICE) as WindowManager
                }
                val w = wm ?: return@post
                val d = s.resources.displayMetrics.density
                val box = (BOX_DP * d).toInt()
                var v = view
                if (v == null) {
                    v = MarkView(s) { main.post { removeWindow() } }
                    val lp = WindowManager.LayoutParams(
                        box, box,
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                        PixelFormat.TRANSLUCENT
                    ).apply {
                        gravity = Gravity.TOP or Gravity.START
                        // 落点像素就是显示像素：API 30+ 窗口默认适配刘海/状态栏会把
                        // 标记往下顶一个状态栏的高度，必须关掉（Andee 原注释）。
                        if (Build.VERSION.SDK_INT >= 30) fitInsetsTypes = 0
                        if (Build.VERSION.SDK_INT >= 28) {
                            layoutInDisplayCutoutMode =
                                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                        }
                    }
                    w.addView(v, lp)
                    view = v
                    params = lp
                }
                params?.let {
                    it.x = x - box / 2
                    it.y = y - box / 2
                    try { w.updateViewLayout(v, it) } catch (_: Exception) {}
                }
                v.bind(markKind, markCoord, markStartedAt)
            } catch (_: Exception) {
                // 覆盖层加不上不影响任务本身：最坏是看不到圈（旧行为）
            }
        }
    }

    private fun removeWindow() {
        try {
            view?.let { wm?.removeView(it) }
        } catch (_: Exception) {
        }
        view = null
        params = null
    }

    private class MarkView(
        context: Context,
        private val onIdle: () -> Unit
    ) : View(context) {
        private val d = context.resources.displayMetrics.density
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = ACCENT
            textSize = 9.5f * d
            letterSpacing = 0.18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val coordPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 11.5f * d
            letterSpacing = 0.04f
            typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
            textAlign = Paint.Align.CENTER
        }
        private val ease = DecelerateInterpolator(1.6f)

        private var labelText = ""
        private var coordText = ""
        private var startedAt = 0L
        private var running = false

        fun bind(kind: String, coord: String, startedAtMs: Long) {
            labelText = kind
            coordText = coord
            startedAt = startedAtMs
            running = true
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!running) return
            val age = SystemClock.uptimeMillis() - startedAt
            if (age > STRIKE_MS + HOLD_MS + FADE_MS) {
                running = false
                // Posted, not called：监听器动窗口布局，不能发生在绘制流程里。
                post(onIdle)
                return
            }
            val cx = width / 2f
            val cy = height / 2f
            // 淡出阶段整体透明度渐变，绝不在截图中途突然消失。
            val life = if (age > STRIKE_MS + HOLD_MS) {
                1f - (age - STRIKE_MS - HOLD_MS) / FADE_MS.toFloat()
            } else {
                1f
            }.coerceIn(0f, 1f)

            // 冲击：两圈先后扩散，减速向外（只有用户看得到这部分，截图前已结束）。
            drawPulse(canvas, cx, cy, age, 0L, life)
            drawPulse(canvas, cx, cy, age, PULSE_STAGGER_MS, life)

            // 常驻标记：环 + 圆点 + 种类与 0-1000 坐标——这才是进截图的部分。
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2.5f * d
            paint.color = Color.argb((life * 0.95f * 255).toInt().coerceIn(0, 255), 0x00, 0xF0, 0xFF)
            canvas.drawCircle(cx, cy, 20f * d, paint)
            paint.style = Paint.Style.FILL
            canvas.drawCircle(cx, cy, 4f * d, paint)
            labelPaint.alpha = (life * 255).toInt().coerceIn(0, 255)
            coordPaint.alpha = (life * 255).toInt().coerceIn(0, 255)
            canvas.drawText(labelText, cx, cy - 28f * d, labelPaint)
            canvas.drawText(coordText, cx, cy + 40f * d, coordPaint)

            postInvalidateOnAnimation()
        }

        private fun drawPulse(canvas: Canvas, cx: Float, cy: Float, age: Long, delay: Long, life: Float) {
            val t = age - delay
            if (t < 0 || t > PULSE_MS) return
            val p = ease.getInterpolation(t / PULSE_MS.toFloat())
            val radius = (12f + p * 40f) * d
            val alpha = ((1f - t / PULSE_MS.toFloat()) * 0.9f * life * 255).toInt().coerceIn(0, 255)
            paint.reset()
            paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3f * d
            paint.color = Color.argb(alpha, 0x00, 0xF0, 0xFF)
            canvas.drawCircle(cx, cy, radius, paint)
        }
    }

    companion object {
        private const val PULSE_MS = 480L
        private const val PULSE_STAGGER_MS = 110L
        private const val STRIKE_MS = PULSE_MS + PULSE_STAGGER_MS
        private const val HOLD_MS = 6_000L
        private const val FADE_MS = 400L

        /** 窗口边长 dp：够装扩散圈与最宽标注（“HOLD 1000,1000”），又小到遮不了多少。 */
        private const val BOX_DP = 144
        private const val ACCENT = 0xFF00F0FF.toInt()
    }
}
