package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orilo 主界面第 2 批（2026-11-15 用户反馈"动态玻璃差距不小"后的优化）：
 * 整页流动液态玻璃底，真扭曲版。
 *
 * 第 1 批的问题：只是渐变 + 两团光斑平移，没有玻璃感——光斑是刚性圆形在挪位置，
 * 不是"内容在玻璃后面流动"。这一批在 API 33+（RuntimeShader/AGSL，真机魅族 20Pro
 * Android16 够用）整段换成着色器：先用两层 fbm 噪声把采样坐标本身扭一下（这才是
 * "透过玻璃看东西在晃"的关键——不是颜色块在动，是底下这块"布"本身在抖、在变形），
 * 扭完的坐标再去算三色渐变 + 两团光斑的颜色。API 33 以下（minSdk 24 兜底）保留
 * 第 1 批纯 Canvas 实现，不升级（旧设备没有 AGSL 管线）。
 *
 * 颜色公式（渐变三色 hueColor() / 光斑位置 ax,ay,bx,by / 30 秒偏色一圈：蓝→紫粉→蓝）
 * 完全不变，只把相位来源从"动画器自身 elapsed 进度"改成"挂钟绝对时间取模"——这样
 * OriloDropletView 不管什么时候挂到窗口上，用同一份公式在同一个挂钟时刻必然算出
 * 完全相同的渐变色/光斑位置，两个 View 不用互相持有引用也不用传 Bitmap，水滴那边
 * "看透"的背景天然就是对的（水滴的着色器也是分析式重算同一套场，不是采样本 View
 * 的真实像素，所以也不存在截屏/跨进程的额外开销）。
 *
 * 动画只在可见时跑（省电）；公开方法名、MainActivity 调用方式不变（这个 View 本来
 * 就没有被外部调用的公开方法，纯粹是挂在根布局上的背景层）。
 */
class OriloBackgroundView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    // ---------- 兜底路径（API < 33）：第 1 批原样保留 ----------
    private val basePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaintA = Paint(Paint.ANTI_ALIAS_FLAG)
    private val blobPaintB = Paint(Paint.ANTI_ALIAS_FLAG)

    // ---------- 着色器路径（API ≥ 33）----------
    private val useShader = Build.VERSION.SDK_INT >= 33
    private var runtimeShader: RuntimeShader? = null
    private var shaderPaint: Paint? = null

    // 光斑位置（页面相对坐标 0..1）+ 偏色量：每帧按挂钟时间重算，两个 View 共用同一套公式，
    // 初始值随便填，attach 后第一帧 recomputePhase() 就会覆盖。
    private var ax = 0f
    private var ay = 0f
    private var bx = 0f
    private var by = 0f
    private var hueShift = 0f

    private var flowAnimator: ValueAnimator? = null

    init {
        if (useShader) {
            runtimeShader = RuntimeShader(AGSL_SOURCE)
            shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFlowAnim()
    }

    override fun onDetachedFromWindow() {
        stopFlowAnim()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        updateFlowAnim()
    }

    private fun updateFlowAnim() {
        if (isShown) startFlowAnim() else stopFlowAnim()
    }

    private fun startFlowAnim() {
        if (flowAnimator != null) return
        // 这个动画器只当"帧泵"用（持续触发 invalidate），真正的相位改成挂钟时间算，
        // 所以 duration 多少、animatedValue 是什么都不影响视觉，只要它一直在跑。
        flowAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 1000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                recomputePhase()
                invalidate()
            }
            start()
        }
    }

    private fun stopFlowAnim() {
        flowAnimator?.cancel()
        flowAnimator = null
    }

    /** 相位完全按挂钟绝对时间算（不依赖动画器自身 elapsed）：30 秒一圈，蓝→紫粉→蓝。
     *  公式提到了 companion 里的 phaseAt()，OriloDropletView 直接调用同一个函数，
     *  不是各写一份——保证两边任何时刻算出的光斑位置/偏色量在代码层面就是同一个来源，
     *  不存在"两份公式抄错一处就对不上"的风险。 */
    private fun recomputePhase() {
        val p = phaseAt(SystemClock.uptimeMillis())
        ax = p.ax; ay = p.ay; bx = p.bx; by = p.by; hueShift = p.hueShift
    }

    /** 底色随时间慢慢偏色（蓝 → 紫粉 → 蓝，30 秒一圈），照视频里整页色调流动的样子。 */
    private fun hueColor(h: Float, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf((h + hueShift) % 360f, s, v))

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        if (useShader) drawWithShader(canvas, w, h) else drawLegacy(canvas, w, h)
    }

    private fun drawWithShader(canvas: Canvas, w: Float, h: Float) {
        val s = runtimeShader ?: return
        val paint = shaderPaint ?: return
        val top = hueColor(232f, 0.50f, 0.95f)
        val mid = hueColor(232f, 0.68f, 0.91f)
        val bot = hueColor(243f, 0.64f, 0.91f)
        s.setFloatUniform("iResolution", w, h)
        s.setFloatUniform("iTime", SystemClock.uptimeMillis() / 1000f)
        s.setFloatUniform("gradTop", Color.red(top) / 255f, Color.green(top) / 255f, Color.blue(top) / 255f)
        s.setFloatUniform("gradMid", Color.red(mid) / 255f, Color.green(mid) / 255f, Color.blue(mid) / 255f)
        s.setFloatUniform("gradBot", Color.red(bot) / 255f, Color.green(bot) / 255f, Color.blue(bot) / 255f)
        s.setFloatUniform("blobA", ax * w, ay * h)
        s.setFloatUniform("blobB", bx * w, by * h)
        canvas.drawRect(0f, 0f, w, h, paint)
    }

    private fun drawLegacy(canvas: Canvas, w: Float, h: Float) {
        basePaint.shader = LinearGradient(
            0f, 0f, w * 0.25f, h,
            intArrayOf(
                hueColor(232f, 0.50f, 0.95f),
                hueColor(232f, 0.68f, 0.91f),
                hueColor(243f, 0.64f, 0.91f),
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, basePaint)
        blobPaintA.shader = RadialGradient(
            ax * w, ay * h, w * 0.55f,
            intArrayOf(0x59B9C2FF.toInt(), 0x00B9C2FF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, blobPaintA)
        blobPaintB.shader = RadialGradient(
            bx * w, by * h, w * 0.6f,
            intArrayOf(0x4DB388FF.toInt(), 0x00B388FF),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, blobPaintB)
    }

    /** 某一挂钟时刻的流动相位：两团光斑的页面相对坐标 + 底色偏色量。 */
    data class FlowPhase(val ax: Float, val ay: Float, val bx: Float, val by: Float, val hueShift: Float)

    companion object {
        const val FLOW_PERIOD_MS = 30000L

        /** 唯一的相位公式来源：OriloDropletView 直接调用这个函数算"此刻背景长什么样"，
         *  不再各自抄一份 sin/cos——这样两个 View 永远用同一套数，不用互相持有引用。 */
        fun phaseAt(uptimeMs: Long): FlowPhase {
            val t = (uptimeMs % FLOW_PERIOD_MS) / FLOW_PERIOD_MS.toFloat() * (Math.PI * 2).toFloat()
            return FlowPhase(
                ax = 0.30f + 0.18f * sin(t * 0.9f),
                ay = 0.26f + 0.14f * cos(t * 0.7f),
                bx = 0.72f + 0.16f * cos(t * 0.6f + 1.3f),
                by = 0.70f + 0.15f * sin(t * 0.8f + 2.1f),
                hueShift = 30f - 30f * cos(t),
            )
        }

        /** 底色随时间慢慢偏色用的 HSV 转换，供两个 View 共用（避免重复实现一份换色逻辑）。 */
        fun hueColor(baseHue: Float, s: Float, v: Float, hueShift: Float): Int =
            Color.HSVToColor(floatArrayOf((baseHue + hueShift) % 360f, s, v))

        // ---------- 可调参数（真机调效果只改这里，数值不变、只是集中命名） ----------
        // 扭曲强度：越大背景扭得越厉害（觉得太闹就调小）。
        private const val WARP_STRENGTH = 0.16f
        // 噪声疏密：越大纹理越细碎。
        private const val WARP_SCALE = 2.2f
        // 流动速度：两层噪声各自的时间速度。
        private const val TIME_SPEED_X = 0.02f
        private const val TIME_SPEED_Y = 0.017f
        // 两团光斑的增亮强度。
        private const val BLOB_A_STRENGTH = 0.35f
        private const val BLOB_B_STRENGTH = 0.30f

        // AGSL：两层 fbm 噪声先扭一下采样坐标（玻璃后面的内容在"晃"），
        // 扭完的坐标再算三色渐变 + 两团光斑——光斑不再是刚性圆形平移，
        // 而是整块"布"本身在流动变形，边缘也跟着抖，这才是液态玻璃该有的扭曲感。
        const val AGSL_SOURCE = """
            uniform float2 iResolution;
            uniform float  iTime;
            uniform float3 gradTop;
            uniform float3 gradMid;
            uniform float3 gradBot;
            uniform float2 blobA;
            uniform float2 blobB;

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

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / iResolution;
                float2 warp = float2(
                    fbm(uv * ${WARP_SCALE} + float2(iTime * ${TIME_SPEED_X}, 0.0)),
                    fbm(uv * ${WARP_SCALE} + float2(0.0, iTime * ${TIME_SPEED_Y}) + 9.3)
                );
                float2 wuv = uv + (warp - 0.5) * ${WARP_STRENGTH};

                float2 dir = float2(0.25, 1.0);
                float t = clamp(dot(wuv, dir) / dot(dir, dir), 0.0, 1.0);
                float wgt = step(0.55, t);
                float3 base = mix(
                    mix(gradTop, gradMid, clamp(t / 0.55, 0.0, 1.0)),
                    mix(gradMid, gradBot, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                    wgt
                );

                float2 pxA = wuv * iResolution - blobA;
                float fa = clamp(1.0 - length(pxA) / (iResolution.x * 0.55), 0.0, 1.0);
                float2 pxB = wuv * iResolution - blobB;
                float fb = clamp(1.0 - length(pxB) / (iResolution.x * 0.6), 0.0, 1.0);

                float3 colorOut = base
                    + float3(0.30, 0.33, 1.0) * fa * ${BLOB_A_STRENGTH}
                    + float3(0.32, 0.20, 1.0) * fb * ${BLOB_B_STRENGTH};
                return half4(float4(colorOut, 1.0));
            }
        """
    }
}
