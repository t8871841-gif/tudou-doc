package com.jarvis.app.hud

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import android.util.AttributeSet
import android.view.View
import android.view.View.MeasureSpec
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.jarvis.app.R
import kotlin.math.sqrt

/**
 * 星系照片横版舞台（2026-10-27，用户定：横版、比例匹配照片 16:9、完整星系带）。
 * 自量高：宽由父容器定，高 = 宽 × 9/16，任何机型上都是 16:9。
 * 层叠：照片（CENTER_CROP，比例与舞台一致故实测无裁切）→ 光晕 → 渐隐 → 椭圆虚线环。
 * 呼吸：照片 1.00→1.03 缩放 + 光晕透明度同步，4 秒一周期；onResume 起、onPause 停。
 */
class GalaxyPhotoStageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : FrameLayout(context, attrs) {

    private val density = resources.displayMetrics.density
    private fun dp(v: Float): Float = v * density

    private val photoView = ImageView(context).apply {
        setImageResource(R.drawable.galaxy_stage)
        scaleType = ImageView.ScaleType.CENTER_CROP
    }
    private val glowView = View(context)
    private val vignetteView = View(context)
    private val ringView = RingView(context)

    private var breather: ValueAnimator? = null

    init {
        addView(photoView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(glowView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(vignetteView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        addView(ringView, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = (w * 9f / 16f).toInt()
        super.onMeasure(
            MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
            MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY)
        )
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        // 光晕：中心淡蓝 → 透明，罩在照片上提亮星系。
        glowView.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0x402E7FE8.toInt(), 0x1A2E7FE8.toInt(), 0x002E7FE8.toInt())
            gradientRadius = 0.75f * minOf(w, h).toFloat()
        }
        // 渐隐：中心透明 → 边缘融入背景深蓝（与 HudBackgroundView 实色一致），照片无硬矩形边。
        vignetteView.background = GradientDrawable().apply {
            gradientType = GradientDrawable.RADIAL_GRADIENT
            colors = intArrayOf(0x00000000, 0x00000000, 0xFF050B18.toInt())
            gradientRadius = 0.5f * sqrt((w * w + h * h).toFloat())
        }
        glowView.alpha = 0.85f
    }

    fun startBreathing() {
        if (breather?.isRunning == true) return
        val anim = ValueAnimator.ofFloat(1f, 1.03f).apply {
            duration = 4000L
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            interpolator = AccelerateDecelerateInterpolator()
            addUpdateListener {
                val v = it.animatedValue as Float
                photoView.scaleX = v
                photoView.scaleY = v
                glowView.alpha = 0.7f + 0.3f * ((v - 1f) / 0.03f)
            }
        }
        breather = anim
        anim.start()
    }

    fun stopBreathing() {
        breather?.cancel()
        breather = null
        photoView.scaleX = 1f
        photoView.scaleY = 1f
        glowView.alpha = 0.85f
    }

    override fun onDetachedFromWindow() {
        stopBreathing()
        super.onDetachedFromWindow()
    }

    /** 椭圆虚线环：按照片里星系盘的包围框比例（宽 0.94、高 0.63）框住星系。 */
    private inner class RingView(context: Context) : View(context) {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = dp(1.5f)
            color = 0x668FD6FF.toInt()
            pathEffect = DashPathEffect(floatArrayOf(dp(10f), dp(14f)), 0f)
        }

        override fun onDraw(canvas: Canvas) {
            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0f || h <= 0f) return
            val rw = w * 0.94f
            val rh = h * 0.63f
            canvas.drawOval((w - rw) / 2f, (h - rh) / 2f, (w + rw) / 2f, (h + rh) / 2f, paint)
        }
    }
}
