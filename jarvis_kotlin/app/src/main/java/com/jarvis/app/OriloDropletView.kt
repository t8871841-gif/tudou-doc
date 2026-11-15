package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.DecelerateInterpolator
import android.view.animation.LinearInterpolator
import kotlin.math.min
import kotlin.math.sin

/**
 * Orilo 主界面第 2 批（2026-11-15 用户反馈"水滴也还不够透、不够活"后的优化）：
 * 水滴真玻璃折射版。
 *
 * 第 1 批的问题：水滴本体就是一个径向渐变圆——不管背景长什么样，水滴里面看到的
 * 永远是同一圈渐变，没有"透过玻璃看见后面内容在变形流动"的感觉，跟真水珠差一截。
 *
 * 这一批在 API 33+（RuntimeShader/AGSL）把水滴本体换成一个球面透镜着色器：按球体
 * 法线把每个像素的采样位置往外推（越靠边缘推得越多，模拟光线穿过球面的折射角），
 * 推完的位置拿去分析式重算 OriloBackgroundView 那套"流动场"公式（见
 * OriloBackgroundView.phaseAt() / hueColor()，两边调同一个函数，不是抄一份），
 * 这样水滴里真的能看到背景在随时间扭动流过——不是采样 OriloBackgroundView 的
 * 真实像素（不用截屏/不用 Bitmap，省了那份开销和线程同步的麻烦），而是水滴自己
 * 知道"此刻、此处背景应该是什么颜色"再按球面物理公式扭一下，效果等价但更便宜。
 * 边缘再叠一点菲涅尔反射（越靠边缘越亮、微弱色散）。API 33 以下（minSdk 24 兜底）
 * 保留第 1 批的纯径向渐变实现。
 *
 * 右上大高光、左下小高光、底部亮边——这是"定向光源"的反光，两条路径都保留且完全
 * 不变：着色器算的菲涅尔是各向同性的（不知道光从哪来），代替不了这几笔看起来像
 * 真的定向反光，所以两条路径画完各自的"球体"之后都叠这一层。
 *
 * 动态（不变）：水滴颜色固定玻璃色、不跟 JarvisAgent 状态走；大小跟待机走——
 * idle 缩到 0.8 倍、干活胀回满，700ms 平滑过渡；平时再叠一层慢呼吸（6 秒一圈）+
 * 轻微晃形；原地动，只缩放不平移，中心不动（2026-11-15 用户纠正过的行为，保留）。
 * 公开方法名（setAwake）、MainActivity 调用方式不变。
 */
class OriloDropletView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---------- 兜底路径（API < 33）----------
    private val bodyPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- 着色器路径（API ≥ 33）----------
    private val useShader = Build.VERSION.SDK_INT >= 33
    private var runtimeShader: RuntimeShader? = null
    private var shaderPaint: Paint? = null
    private val screenLoc = IntArray(2)

    // ---------- 两条路径共用：右上大高光 / 左下小高光 / 亮边 ----------
    private val shadowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val highlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val smallHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rimPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3f
        color = Color.WHITE
        alpha = 140
        strokeCap = Paint.Cap.ROUND
    }
    private val rimSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
        alpha = 60
    }

    // ---------- 动态水滴：待机缩小（睡觉的感觉）+ 原地动 ----------
    private var awake = false
    private var currentScale = IDLE_SCALE
    private var breathT = 0f
    private var scaleAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    init {
        if (useShader) {
            runtimeShader = RuntimeShader(AGSL_SOURCE)
            shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        }
        applyMotion()
    }

    /** 调用方在状态变化时调：待机（idle）缩、干活胀。颜色不跟状态，只动大小。 */
    fun setAwake(awake: Boolean) {
        if (this.awake == awake) return
        this.awake = awake
        val target = if (awake) AWAKE_SCALE else IDLE_SCALE
        scaleAnimator?.cancel()
        scaleAnimator = ValueAnimator.ofFloat(currentScale, target).apply {
            duration = 700L
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                currentScale = animatedValue as Float
                applyMotion()
            }
            start()
        }
    }

    private fun applyMotion() {
        // 原地动：宽高错相晃形，中心不动，不平移。
        scaleX = currentScale * (1f + 0.015f * sin(breathT))
        scaleY = currentScale * (1f + 0.015f * sin(breathT + 1.5f))
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateMotionAnim()
    }

    override fun onDetachedFromWindow() {
        stopMotionAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateMotionAnim()
    }

    private fun updateMotionAnim() {
        if (isShown) startMotionAnim() else stopMotionAnim()
    }

    private fun startMotionAnim() {
        if (motionAnimator != null) return
        motionAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                breathT = animatedValue as Float
                applyMotion() // setScaleX/Y 本身会触发 invalidate，顺带把着色器的 iTime 也带新了
            }
            start()
        }
    }

    private fun stopMotionAnim() {
        motionAnimator?.cancel()
        motionAnimator = null
        scaleX = currentScale
        scaleY = currentScale
    }

    companion object {
        private const val IDLE_SCALE = 0.8f
        private const val AWAKE_SCALE = 1.0f

        // ---------- 可调参数（真机调效果只改这里，数值不变、只是集中命名） ----------
        // 折射强度：越大水滴里的背景扭得越厉害（46.0 是第一版拍的数；显花就调小）。
        private const val LENS_BEND = 46.0f
        // 色散偏移：RGB 三通道采样点的错开距离。
        private const val DISPERSE_X = 2.2f
        private const val DISPERSE_Y = 1.1f
        // 菲涅尔：边缘增亮的陡峭程度（指数）与强度（透明度 / 白光）。
        private const val FRESNEL_POWER = 2.5f
        private const val FRESNEL_ALPHA = 0.55f
        private const val FRESNEL_WHITE = 0.85f
        // 折射像的亮度（0.6 = 压暗四成）与水滴透明度（中心 0.20 → 边缘 0.62）。
        private const val REFRACT_DIM = 0.6f
        private const val ALPHA_CENTER = 0.20f
        private const val ALPHA_EDGE = 0.62f

        // AGSL：球面透镜——按法线把采样位置往外推，推完的页面坐标拿去算
        // OriloBackgroundView 那套三色渐变 + 两团光斑（公式细节见该文件companion，
        // 这里的 fbm/hash 与它逐字一致，保证两边"布"的纹理长得一样）。
        // 菲涅尔项做边缘增亮 + 极轻微色散（RGB 三通道推的距离略有差异）。
        private const val AGSL_SOURCE = """
            uniform float2 iOrigin;
            uniform float2 iPageSize;
            uniform float  iTime;
            uniform float3 gradTop;
            uniform float3 gradMid;
            uniform float3 gradBot;
            uniform float2 blobA;
            uniform float2 blobB;
            uniform float2 lensCenter;
            uniform float  lensRadius;

            float hash(float2 p) {
                return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453123);
            }
            float noise(float2 p) {
                float2 i = floor(p);
                float2 f = fract(p);
                float a = hash(i);
                float b = hash(i + float2(1.0, 0.0));
                float c = hash(i + float2(0.0, 1.0));
                float d = hash(i + float2(1.0, 1.0));
                float2 u = f * f * (3.0 - 2.0 * f);
                return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
            }
            float fbm(float2 p) {
                float v = 0.0;
                float amp = 0.55;
                for (int i = 0; i < 3; i++) {
                    v += amp * noise(p);
                    p *= 2.02;
                    amp *= 0.55;
                }
                return v;
            }

            float3 fieldAt(float2 pagePx) {
                float2 uv = pagePx / iPageSize;
                float2 warp = float2(
                    fbm(uv * 2.2 + float2(iTime * 0.02, 0.0)),
                    fbm(uv * 2.2 + float2(0.0, iTime * 0.017) + 9.3)
                );
                float2 wuv = uv + (warp - 0.5) * 0.16;
                float2 dir = float2(0.25, 1.0);
                float t = clamp(dot(wuv, dir) / dot(dir, dir), 0.0, 1.0);
                float wgt = step(0.55, t);
                float3 base = mix(
                    mix(gradTop, gradMid, clamp(t / 0.55, 0.0, 1.0)),
                    mix(gradMid, gradBot, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                    wgt
                );
                float2 pxA = wuv * iPageSize - blobA;
                float fa = clamp(1.0 - length(pxA) / (iPageSize.x * 0.55), 0.0, 1.0);
                float2 pxB = wuv * iPageSize - blobB;
                float fb = clamp(1.0 - length(pxB) / (iPageSize.x * 0.6), 0.0, 1.0);
                return base
                    + float3(0.30, 0.33, 1.0) * fa * 0.35
                    + float3(0.32, 0.20, 1.0) * fb * 0.30;
            }

            half4 main(float2 fragCoord) {
                float2 local = fragCoord - lensCenter;
                float dist = length(local);
                if (dist > lensRadius) {
                    return half4(0.0);
                }
                float2 n2 = local / lensRadius;
                float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
                float fresnel = pow(1.0 - nz, ${FRESNEL_POWER});
                float bend = ${LENS_BEND};
                float2 refractPx = iOrigin + fragCoord + n2 * bend * (1.0 - nz);

                float3 cR = fieldAt(refractPx + float2(${DISPERSE_X}, -${DISPERSE_Y}));
                float3 cG = fieldAt(refractPx);
                float3 cB = fieldAt(refractPx - float2(${DISPERSE_X}, ${DISPERSE_Y}));
                float3 refracted = float3(cR.r, cG.g, cB.b);

                float edge = smoothstep(lensRadius * 0.80, lensRadius, dist);
                float alpha = clamp(mix(${ALPHA_CENTER}, ${ALPHA_EDGE}, edge) + fresnel * ${FRESNEL_ALPHA}, 0.0, 1.0);
                float3 colorOut = refracted * ${REFRACT_DIM} + float3(1.0, 1.0, 1.0) * fresnel * ${FRESNEL_WHITE};
                return half4(float4(colorOut * alpha, alpha));
            }
        """
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 布局给 220dp 的框，水滴本身画 0.78 倍、圆心略靠上，给右下方的软影留地方。
        val r = min(width, height) / 2f * 0.78f
        val cx = width / 2f
        val cy = height * 0.45f
        if (r <= 0f) return

        // 影子：右下方一团软影（两条路径共用，照 Orilo 截图）。
        shadowPaint.shader = RadialGradient(
            cx + r * 0.1f, cy + r * 1.02f, r * 0.8f,
            intArrayOf(0x26000000, 0x00000000),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(
            RectF(cx - r * 0.75f, cy + r * 0.75f, cx + r * 0.95f, cy + r * 1.3f),
            shadowPaint,
        )

        if (useShader) drawBodyWithShader(canvas, cx, cy, r) else drawBodyLegacy(canvas, cx, cy, r)
        drawHighlightsAndRim(canvas, cx, cy, r)
    }

    private fun drawBodyWithShader(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        val s = runtimeShader ?: return
        val paint = shaderPaint ?: return
        val phase = OriloBackgroundView.phaseAt(SystemClock.uptimeMillis())
        val top = OriloBackgroundView.hueColor(232f, 0.50f, 0.95f, phase.hueShift)
        val mid = OriloBackgroundView.hueColor(232f, 0.68f, 0.91f, phase.hueShift)
        val bot = OriloBackgroundView.hueColor(243f, 0.64f, 0.91f, phase.hueShift)
        getLocationOnScreen(screenLoc)
        val pageW = resources.displayMetrics.widthPixels.toFloat()
        val pageH = resources.displayMetrics.heightPixels.toFloat()
        s.setFloatUniform("iOrigin", screenLoc[0].toFloat(), screenLoc[1].toFloat())
        s.setFloatUniform("iPageSize", pageW, pageH)
        s.setFloatUniform("iTime", SystemClock.uptimeMillis() / 1000f)
        s.setFloatUniform("gradTop", Color.red(top) / 255f, Color.green(top) / 255f, Color.blue(top) / 255f)
        s.setFloatUniform("gradMid", Color.red(mid) / 255f, Color.green(mid) / 255f, Color.blue(mid) / 255f)
        s.setFloatUniform("gradBot", Color.red(bot) / 255f, Color.green(bot) / 255f, Color.blue(bot) / 255f)
        s.setFloatUniform("blobA", phase.ax * pageW, phase.ay * pageH)
        s.setFloatUniform("blobB", phase.bx * pageW, phase.by * pageH)
        s.setFloatUniform("lensCenter", cx, cy)
        s.setFloatUniform("lensRadius", r)
        canvas.drawCircle(cx, cy, r, paint)
    }

    private fun drawBodyLegacy(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 体：清水滴——顶部带一点亮、边缘带一点淡淡的白，中间几乎透明；底色偏色时也不串色。
        bodyPaint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.45f, r * 1.5f,
            intArrayOf(0x59FFFFFF, 0x14FFFFFF, 0x08FFFFFF, 0x1AFFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(cx, cy, r, bodyPaint)
    }

    private fun drawHighlightsAndRim(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 大高光：右上一道斜的亮斑。
        canvas.save()
        canvas.translate(cx + r * 0.42f, cy - r * 0.48f)
        canvas.rotate(-35f)
        canvas.scale(1f, 0.45f)
        highlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.3f,
            intArrayOf(0xE6FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.3f, highlightPaint)
        canvas.restore()
        // 小高光：左下一小点。
        canvas.save()
        canvas.translate(cx - r * 0.45f, cy + r * 0.42f)
        canvas.rotate(-35f)
        canvas.scale(1f, 0.6f)
        smallHighlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.14f,
            intArrayOf(0xB3FFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.14f, smallHighlightPaint)
        canvas.restore()
        // 亮边：整圈淡淡描边 + 下半圈亮弧（35°→145° 经过正下方 90°）。
        canvas.drawCircle(cx, cy, r - 1f, rimSoftPaint)
        val rimRect = RectF(cx - r * 0.82f, cy - r * 0.82f, cx + r * 0.82f, cy + r * 0.82f)
        canvas.drawArc(rimRect, 35f, 110f, false, rimPaint)
    }
}
