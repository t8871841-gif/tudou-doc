package com.moss.app.ui.ball

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.Looper
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import android.view.animation.PathInterpolator
import kotlin.math.roundToInt

/**
 * The MOSS orb: a hand-written [View] translation of the user-supplied Compose
 * design (`MossOrb`, five layers, five states). Every number below is copied
 * 1:1 from that design — layer order, colours, radii, stroke widths, and the
 * four animation clocks — only the drawing API changed (Compose `DrawScope`
 * calls became `android.graphics.Canvas` calls).
 *
 * Layers, back to front: ① a dark-red radial halo behind the sphere,
 * ② the dark sphere body with a metal rim and a top highlight arc,
 * ③ the optical eye (outer glow, iris ring, pupil, bright core, glint),
 * ④ state effects (listening ripple, thinking scan arc, error flash),
 * ⑤ breathing scale and the thinking rotation, which are the clock values
 * themselves rather than drawn shapes.
 *
 * Driven with [setOrbState] from the overlay window's own state machine; the
 * view fills the ball area and sizes everything off the shorter side, the way
 * the old GL ball did. The animation clocks run while the view is attached
 * and stop when it is detached. This replaces the whole OpenGL character
 * ball (`EmotionBallRenderer` and friends, deleted with this change): there
 * are no moods, faces, looks, gestures, or dozing anymore — the orb is the
 * only thing the ball area ever shows.
 */
enum class OrbState { IDLE, LISTENING, THINKING, SPEAKING, ERROR }

class MossOrbView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    @Volatile
    private var orbState: OrbState = OrbState.IDLE

    // Animation clocks, same values as the Compose design: breath 1.6 s
    // reversing, rotation 3 s linear, ripple 1.5 s linear, flicker 250 ms
    // reversing.
    private var breath = 0f
    private var rotation = 0f
    private var ripple = 0f
    private var flicker = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val arcRect = RectF()

    private val breathAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1600L
        // Cubic-bezier(0.4, 0, 0.2, 1): the Compose FastOutSlowInEasing
        // curve, as a framework interpolator (there is no framework
        // FastOutSlowInInterpolator to import).
        interpolator = PathInterpolator(0.4f, 0f, 0.2f, 1f)
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { breath = it.animatedValue as Float; invalidate() }
    }
    private val rotationAnim = ValueAnimator.ofFloat(0f, 360f).apply {
        duration = 3000L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { rotation = it.animatedValue as Float; invalidate() }
    }
    private val rippleAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 1500L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        addUpdateListener { ripple = it.animatedValue as Float; invalidate() }
    }
    private val flickerAnim = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 250L
        interpolator = LinearInterpolator()
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        addUpdateListener { flicker = it.animatedValue as Float; invalidate() }
    }

    init {
        setWillNotDraw(false)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        breathAnim.start()
        rotationAnim.start()
        rippleAnim.start()
        flickerAnim.start()
    }

    override fun onDetachedFromWindow() {
        breathAnim.cancel()
        rotationAnim.cancel()
        rippleAnim.cancel()
        flickerAnim.cancel()
        super.onDetachedFromWindow()
    }

    fun setOrbState(state: OrbState) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            post { setOrbState(state) }
            return
        }
        if (orbState != state) {
            orbState = state
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val maxR = minOf(w, h) / 2f
        val r = maxR / 1.35f // 35% reserved for halo/ripple, as in the design
        val cx = w / 2f
        val cy = h / 2f
        val density = resources.displayMetrics.density
        fun dp(v: Float): Float = v * density

        // ---------- State parameters ----------
        val eyeIntensity: Float
        val eyeScale: Float
        val glowAlpha: Float
        val orbScale: Float
        when (orbState) {
            OrbState.IDLE -> {
                eyeIntensity = 0.45f + 0.20f * breath
                eyeScale = 1f
                glowAlpha = 0.15f + 0.10f * breath
                orbScale = 0.97f + 0.03f * breath
            }
            OrbState.LISTENING -> {
                eyeIntensity = 0.95f
                eyeScale = 0.80f
                glowAlpha = 0.35f
                orbScale = 1f
            }
            OrbState.THINKING -> {
                eyeIntensity = 0.55f + 0.35f * breath
                eyeScale = 0.95f
                glowAlpha = 0.20f
                orbScale = 1f
            }
            OrbState.SPEAKING -> {
                eyeIntensity = 0.70f + 0.25f * breath
                eyeScale = 0.95f + 0.10f * breath
                glowAlpha = 0.25f
                orbScale = 0.98f + 0.02f * breath
            }
            OrbState.ERROR -> {
                eyeIntensity = 0.60f + 0.40f * flicker
                eyeScale = 0.90f
                glowAlpha = 0.30f + 0.30f * flicker
                orbScale = 1f
            }
        }
        val rr = r * orbScale

        // ---------- ① Floating halo ----------
        paint.style = Paint.Style.FILL
        paint.shader = RadialGradient(
            cx, cy, rr * 1.35f,
            intArrayOf(argb(0.18f, 0xFF, 0x1A, 0x1A), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, rr * 1.35f, paint)

        // ---------- ② Sphere body ----------
        paint.shader = RadialGradient(
            cx - rr * 0.25f, cy - rr * 0.25f, rr * 1.2f,
            intArrayOf(0xFF1E1E24.toInt(), 0xFF0A0A0D.toInt(), 0xFF000000.toInt()),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, rr, paint)

        // Metal rim
        paint.shader = null
        paint.style = Paint.Style.STROKE
        paint.color = 0xFF2A2A33.toInt()
        paint.strokeWidth = dp(1f)
        canvas.drawCircle(cx, cy, rr - dp(0.5f), paint)

        // Top highlight arc
        paint.shader = LinearGradient(
            0f, cy - rr, 0f, cy,
            intArrayOf(0x33FFFFFF, Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        paint.strokeWidth = dp(1.5f)
        paint.strokeCap = Paint.Cap.ROUND
        arcRect.set(cx - rr, cy - rr, cx + rr, cy + rr)
        canvas.drawArc(arcRect, 200f, 140f, false, paint)

        // ---------- ③ Optical eye ----------
        paint.style = Paint.Style.FILL
        // Outer glow
        paint.shader = RadialGradient(
            cx, cy, rr * 0.85f,
            intArrayOf(argb(glowAlpha, 0xFF, 0x20, 0x20), Color.TRANSPARENT),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, rr * 0.85f, paint)

        // Iris ring
        val irisR = rr * 0.38f * eyeScale
        paint.shader = RadialGradient(
            cx, cy, irisR,
            intArrayOf(
                argb(eyeIntensity, 0xFF, 0x40, 0x40),
                argb(eyeIntensity * 0.9f, 0x8B, 0x00, 0x00),
            ),
            null, Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, irisR, paint)

        // Pupil
        paint.shader = null
        val pupilR = rr * 0.22f * eyeScale
        paint.color = argb(eyeIntensity, 0xFF, 0x1A, 0x1A)
        canvas.drawCircle(cx, cy, pupilR, paint)

        // Bright core
        paint.color = argb(eyeIntensity * 0.9f, 0xFF, 0xAA, 0xAA)
        canvas.drawCircle(cx, cy, pupilR * 0.5f, paint)

        // Glint
        paint.color = argb(0.35f * eyeIntensity, 0xFF, 0xFF, 0xFF)
        canvas.drawCircle(cx - pupilR * 0.3f, cy - pupilR * 0.3f, pupilR * 0.22f, paint)

        // ---------- ④ State effects ----------
        if (orbState == OrbState.LISTENING) {
            paint.style = Paint.Style.STROKE
            paint.color = argb((1f - ripple) * 0.45f, 0xFF, 0x20, 0x20)
            paint.strokeWidth = dp(1.5f)
            canvas.drawCircle(cx, cy, rr * (0.85f + ripple * 0.55f), paint)
        }

        if (orbState == OrbState.THINKING) {
            paint.style = Paint.Style.STROKE
            paint.color = argb(0.65f, 0xFF, 0x20, 0x20)
            paint.strokeWidth = dp(2f)
            paint.strokeCap = Paint.Cap.ROUND
            arcRect.set(cx - rr * 0.92f, cy - rr * 0.92f, cx + rr * 0.92f, cy + rr * 0.92f)
            canvas.drawArc(arcRect, rotation, 70f, false, paint)
        }

        if (orbState == OrbState.ERROR) {
            paint.style = Paint.Style.STROKE
            paint.color = argb(0.25f * flicker, 0xFF, 0x00, 0x00)
            paint.strokeWidth = dp(2f)
            canvas.drawCircle(cx, cy, rr * 1.1f, paint)
        }
        paint.shader = null
    }

    private fun argb(a: Float, r: Int, g: Int, b: Int): Int =
        Color.argb((a.coerceIn(0f, 1f) * 255f).roundToInt(), r, g, b)
}
