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
import android.graphics.Typeface
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
 *
 * 2026-11-15 第 3 批（用户要求"不用每次都发回来等改"）：折射强度/透明度/色散/
 * 边缘锐利度这些容易想调的数字集中到 companion 里的 `TUNE_*` 常量（见下方
 * "调参区"），改完重新编译装机就能看效果，不用动 AGSL 代码本身。噪声/光斑那几个
 * 跟 OriloBackgroundView 共用同一份常量（直接引用它的 `TUNE_*`），不在这里重复。
 *
 * 2026-11-15 第 4 批（用户反馈真机截图"玻璃球不对，没有原版的灵动"）：根因是
 * 上一批只写了菲涅尔边缘反光（哪个方向都一样亮），没有"球面哪边朝光、哪边背光"
 * 的明暗渐变——缺了这个，球看着就是一个发光的扁平色块，不是一个有受光面的球。
 * 这批加了一个固定光源方向的漫反射项（左上方向打光），球面会有真正的明暗过渡；
 * 同时背景那边的光斑混合方式也同步改了（见 OriloBackgroundView 第 4 批说明），
 * fieldAt() 跟着同步更新，保证水滴里"看到"的布纹跟背景长得一样。
 *
 * 2026-11-15 第 5 批（用户发了 orilo 原版真机录屏，逐帧看了一遍）：原版背景那团
 * "光晕"涨到最强的时候，水滴会跟着整块被照得发白发亮——不只是"颜色"变了，是
 * "亮度"本身在跳。这不是单纯"背景色透进来"（uTintStrength）能解释的，更像玻璃
 * 本身被环境光照亮了。这批加了 `uAmbientBoost`：按水滴所在位置这一刻的"布"有多亮
 * （fieldAt() 算出来的亮度），额外加一层白——光晕强的时候水滴跟着一起发亮，光晕弱
 * 的时候水滴也跟着暗下来，复现原版那种"水滴跟背景呼吸同步"的感觉。另外
 * uBlobARadiusFrac/uBlobAStrength 这些也改成从 OriloBackgroundView 新增的
 * bloomedRadiusFrac()/bloomedStrength() 取值（见该文件第 5 批说明），不再是固定
 * 常量——水滴里看到的光斑现在也会跟着呼吸涨缩，不只是背景在涨缩。
 *
 * 2026-11-15 第 6 批（用户指出"水滴不是一直圆形，有动态变换"，逐帧看了原版录屏
 * 确认）：原来的 applyMotion() 只是横纵方向两条同频率、固定相位差的正弦波在错相
 * 摆动，幅度还只有 1.5%——本质上是"教科书式的椭圆呼吸"，太规律、太小，看着就是
 * 正圆在缩放。这批改成横纵方向各叠两个不同频率的正弦波，幅度加大到默认 5%
 * （`TUNE_WOBBLE_AMPLITUDE`），打破了"同频率错相"这种机械对称感，更接近液体
 * 本身在晃、不是严丝合缝的周期性形变。
 *
 * 2026-11-15 第 7 批（用户说"目前最重要的是水滴观感，和截图差距太大"）：这次没有
 * 再凭眼睛猜，把目标截图裁出来用脚本采样了球体中央和边缘几条线的真实 RGB 值。
 * 发现跟之前的假设有偏差——**球体内部其实相当平**（中央一大片区域颜色几乎不变，
 * 第 4 批加的"漫反射明暗过渡"假设基本不成立，这批把它调弱了很多）；真正有变化的
 * 是**边缘**：左侧边缘明显偏亮、右侧/上/下边缘偏暗，而且最外缘有一圈很窄的暗带
 * （玻璃全反射那种"黑边"）。说明第 4 批加的"各向同性菲涅尔"方向性是错的——原版的
 * 亮边是有方向的（朝光源一侧亮、背光一侧暗），不是整圈均匀发亮。这批把
 * `fresnel = pow(1-nz, power)`（哪个方向都一样亮）换成按方向加权的 `rimDir`
 * （朝左上方向的一侧亮，背对的一侧暗），并加了一圈贴着最外缘的暗带
 * （`uDarkBandStrength`）。同时加了待机时的 💤 提示（两个 "Z"/"z" 字，带轻微
 * 淡入淡出），之前漏做了。
 *
 * 2026-11-15 第 8 批（用户反馈"水滴形态太机械"，确认想要的是"轻微颤动、像有生命"）：
 * 找到一个具体的机械感来源——第 6 批的挤压相位仍然取自 motionAnimator 自己的循环值
 * （每 6 秒从 2π 跳回 0），非整数倍频率在循环边界接不上，相位在每次重置瞬间其实是
 * 断开的，读起来像"在放一小段循环 GIF"。这批改成直接用挂钟时间连续算相位，不再有
 * 接缝；频率也换成互相不成整数倍关系的数字，波形理论上永远不精确重复。
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

    // ---------- 第 7 批新增：待机时的 💤 提示（两个 "Z"/"z"，淡入淡出） ----------
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 2f, 0x40000000)
    }
    private var idleIndicatorAlpha = 0f
    private var idleIndicatorAnimator: ValueAnimator? = null

    // ---------- 动态水滴：待机缩小（睡觉的感觉）+ 原地动 ----------
    private var awake = false
    private var currentScale = IDLE_SCALE
    private var scaleAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    init {
        if (useShader) {
            runtimeShader = RuntimeShader(AGSL_SOURCE)
            shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        }
        applyMotion()
    }

    /** 调用方在状态变化时调：待机（idle）缩、干活胀。颜色不跟状态，只动大小。
     *  第 7 批：顺带把 💤 提示（"Z"/"z"）的淡入淡出也绑在这里——待机时淡入，
     *  干活时淡出，不用调用方多传参数或多调一个方法。 */
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
        idleIndicatorAnimator?.cancel()
        idleIndicatorAnimator = ValueAnimator.ofFloat(idleIndicatorAlpha, if (awake) 0f else 1f).apply {
            duration = 400L
            startDelay = if (awake) 0L else 500L // 干活时立刻收起，待机时稍微等一下再冒出来，不要一停就弹
            addUpdateListener {
                idleIndicatorAlpha = animatedValue as Float
                invalidate()
            }
            start()
        }
    }

    private fun applyMotion() {
        // 原地动：挤压变形，中心不动，不平移。
        // 第 8 批（用户反馈"水滴形态太机械"，确认是想要"轻微颤动、像有生命"那种感觉）：
        // 找到一个具体的机械感来源——第 6 批虽然把单一正弦换成了两个频率叠加，但相位
        // 仍然是从 motionAnimator 自己包出来的 breathT 取的，这个值每 6 秒会从 2π
        // 跳回 0 重新开始；1.7、2.3 这种非整数倍频率在这个循环边界上接不上
        // （sin(1.7×2π) ≠ sin(1.7×0)），相位在每次循环重置的瞬间其实是断开的——人不一定
        // 看得出具体哪里不对，但直觉上会读成"在循环播放一小段 GIF"，这就是机械感的来源，
        // 不是幅度问题，是每 6 秒有一道接缝。这批改成直接用挂钟时间连续递增算相位
        // （不再从一个会"跳回去"的循环值取相位，永远不会有接缝），顺便把频率换成互相
        // 不成整数倍关系的数字——这样整个波形实际上永远不会精确重复，比"一个循环"更
        // 接近"活物在轻颤"，不是在放一段循环动画。
        val tSec = SystemClock.uptimeMillis() / 1000.0
        val amp = TUNE_WOBBLE_AMPLITUDE.toDouble()
        val wobbleX = (sin(tSec * 1.00) + 0.5 * sin(tSec * 1.31 + 0.6)) * amp
        val wobbleY = (sin(tSec * 0.74 + 1.9) + 0.5 * sin(tSec * 1.87)) * amp
        scaleX = currentScale * (1f + wobbleX.toFloat())
        scaleY = currentScale * (1f + wobbleY.toFloat())
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
        // 第 8 批：这个动画器现在只当"帧泵"用（持续触发 invalidate），不再提供相位——
        // 相位改从挂钟时间连续算（见 applyMotion()），所以这里的 0f..2π 和 duration
        // 具体是多少已经不影响晃动的视觉效果，只要它一直在跑、帧率够用就行。
        motionAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
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

        // ====== 调参区：想自己试效果就改这几个数，不用懂 AGSL ======
        // 噪声/光斑那几个（TUNE_WARP_STRENGTH 等）直接用 OriloBackgroundView 里的同名
        // 常量，没有在这里重复一份——改那边的数，水滴里"看到"的布纹也会跟着变，两边
        // 永远长得一样。下面这几个是水滴自己独有的（折射强度、透明度、色散……）。
        /** 折射强度：水滴里背景被推开/放大的力度，越大越像鱼眼/放大镜，调到 0 就是不折射。
         *  截图里的水滴偏"清澈到几乎看不见"，如果觉得现在的版本折射感太抢戏，先调小这个。 */
        const val TUNE_REFRACT_BEND = 46f
        /** 第 7 批复用：方向性亮边的"锐利度"——数字越大，朝光源那一侧亮的范围越窄越集中；
         *  越小，亮的范围越宽、越接近整圈都亮（退回第 4 批那种各向同性的效果）。 */
        const val TUNE_FRESNEL_POWER = 3f
        /** 色散幅度（像素）：边缘红蓝分离的程度，越大越明显的"彩虹边"，调到 0 就是没有色散。 */
        const val TUNE_CHROMATIC_OFFSET = 2.2f
        /** 边缘增厚的起始位置，0..1（相对半径）：越小，变厚/变实的范围越宽，水滴显得越"肉"。 */
        const val TUNE_EDGE_SOFT_START = 0.80f
        /** 水滴中心的不透明度：越小中心越透，越接近"看不见水滴只看见一圈边"。 */
        const val TUNE_ALPHA_MIN = 0.20f
        /** 水滴边缘的不透明度：越大边缘越实。 */
        const val TUNE_ALPHA_MAX = 0.62f
        /** 边缘因菲涅尔额外叠加的不透明度：越大边缘看着越"厚实有质感"。 */
        const val TUNE_FRESNEL_ALPHA = 0.55f
        /** 背景颜色透进水滴的浓度：越大水滴里背景色越明显，越小越像一颗纯白/无色的玻璃珠。
         *  如果觉得水滴看着"太有颜色、不够清水"，先调小这个（比如 0.3）。 */
        const val TUNE_TINT_STRENGTH = 0.6f
        /** 朝光一侧边缘的白色高光强度（第 7 批起改成有方向的，不再是整圈一样亮）。 */
        const val TUNE_RIM_STRENGTH = 0.6f
        /** 第 4 批新增、第 7 批调弱：球面漫反射强度。采样目标截图发现球体中央其实相当平，
         *  不是明显的明暗渐变，所以这个只给一丝若有若无的立体感，不是主要观感来源，
         *  默认值比第 4 批小了一大截。调到 0 就是完全没有这一丝渐变。 */
        const val TUNE_DIFFUSE_STRENGTH = 0.08f
        /** 第 7 批新增：贴着最外缘的窄暗带强度——采样目标截图发现边缘有一圈比球体内部和
         *  背景都暗的细边（玻璃全反射那种"黑边"感），这是让边缘看着"是玻璃"而不是
         *  "一张半透明贴纸"的关键细节。只在背光侧明显，朝光侧会被亮边盖掉。 */
        const val TUNE_DARK_BAND_STRENGTH = 0.4f
        /** 第 5 批新增：水滴跟着背景"光晕"一起发亮的强度——数值越大，光晕最强的那几秒
         *  水滴被照得越白越亮；调到 0 就是水滴亮度只跟折射出来的颜色有关，不跟光晕强度挂钩，
         *  退回第 4 批那种"颜色变但亮度不变"的效果。 */
        const val TUNE_AMBIENT_BOOST = 0.35f
        /** 第 6 批新增：挤压变形的幅度——0.05 就是横纵方向最多 ±5% 的拉伸/压缩。
         *  调到 0 就是完全不挤压（退回纯缩放，没有液体晃动的感觉）；觉得太夸张就调小，
         *  觉得还是不够"活"就调大（但太大了会看着像在抖/变形过度，不建议超过 0.12）。 */
        const val TUNE_WOBBLE_AMPLITUDE = 0.05f

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
            uniform float  uWarpStrength;
            uniform float  uFbmScale;
            uniform float  uFlowSpeedX;
            uniform float  uFlowSpeedY;
            uniform float  uBlobAStrength;
            uniform float  uBlobARadiusFrac;
            uniform float  uBlobBStrength;
            uniform float  uBlobBRadiusFrac;
            uniform float  uBend;
            uniform float  uFresnelPower;
            uniform float  uChromaticOffset;
            uniform float  uEdgeSoftStart;
            uniform float  uAlphaMin;
            uniform float  uAlphaMax;
            uniform float  uFresnelAlpha;
            uniform float  uTintStrength;
            uniform float  uRimStrength;
            uniform float  uDiffuseStrength;
            uniform float  uAmbientBoost;
            uniform float  uDarkBandStrength;
            uniform float3 blobAColor;
            uniform float3 blobBColor;

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
                    fbm(uv * uFbmScale + float2(iTime * uFlowSpeedX, 0.0)),
                    fbm(uv * uFbmScale + float2(0.0, iTime * uFlowSpeedY) + 9.3)
                );
                float2 wuv = uv + (warp - 0.5) * uWarpStrength;
                float2 dir = float2(0.25, 1.0);
                float t = clamp(dot(wuv, dir) / dot(dir, dir), 0.0, 1.0);
                float wgt = step(0.55, t);
                float3 base = mix(
                    mix(gradTop, gradMid, clamp(t / 0.55, 0.0, 1.0)),
                    mix(gradMid, gradBot, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                    wgt
                );
                float2 pxA = wuv * iPageSize - blobA;
                float fa = smoothstep(0.0, 1.0, clamp(1.0 - length(pxA) / (iPageSize.x * uBlobARadiusFrac), 0.0, 1.0));
                float3 colorOut = mix(base, blobAColor, fa * uBlobAStrength);
                float2 pxB = wuv * iPageSize - blobB;
                float fb = smoothstep(0.0, 1.0, clamp(1.0 - length(pxB) / (iPageSize.x * uBlobBRadiusFrac), 0.0, 1.0));
                colorOut = mix(colorOut, blobBColor, fb * uBlobBStrength);
                return colorOut;
            }

            half4 main(float2 fragCoord) {
                float2 local = fragCoord - lensCenter;
                float dist = length(local);
                if (dist > lensRadius) {
                    return half4(0.0);
                }
                float2 n2 = local / lensRadius;
                float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
                float2 refractPx = iOrigin + fragCoord + n2 * uBend * (1.0 - nz);

                float3 cR = fieldAt(refractPx + float2(uChromaticOffset, -uChromaticOffset * 0.5));
                float3 cG = fieldAt(refractPx);
                float3 cB = fieldAt(refractPx - float2(uChromaticOffset, uChromaticOffset * 0.5));
                float3 refracted = float3(cR.r, cG.g, cB.b);

                // 第 7 批：原来这里是"各向同性菲涅尔"（哪个方向都一样亮）——采样了目标
                // 截图的真实像素后发现方向反了：亮边是有方向的，朝光源（左上）一侧亮，
                // 背光一侧暗。rimDir 按法线跟固定光方向的点乘算权重，uFresnelPower
                // 复用来控制这个方向性的"锐利度"（越大，亮的那一侧范围越窄越集中）。
                float2 rimLightDir = normalize(float2(-0.75, -0.35));
                float rimDir = pow(clamp(dot(n2, rimLightDir) * 0.5 + 0.5, 0.0, 1.0), uFresnelPower);
                float edge = smoothstep(lensRadius * uEdgeSoftStart, lensRadius, dist);
                float rimBright = edge * rimDir;

                // 贴着最外缘的一圈窄暗带（玻璃全反射那种"黑边"，采样截图边缘能看到这一圈
                // 比球体内部和背景都暗），只在最外缘小范围内起作用，亮边（rimBright）
                // 和它不冲突——亮边在"朝光侧"盖掉暗带，背光侧暗带会更明显。
                float darkBand = smoothstep(lensRadius * 0.90, lensRadius * 0.975, dist)
                    * (1.0 - smoothstep(lensRadius * 0.975, lensRadius, dist));

                // 漫反射调弱：采样截图发现球体中央其实相当平，不是明显的明暗渐变，
                // 这一项现在只给一丝若有若无的立体感，不是主要观感来源。
                float3 lightDir = normalize(float3(-0.45, -0.65, 0.6));
                float3 normal = float3(n2, nz);
                float diffuse = clamp(dot(normal, lightDir), 0.0, 1.0);

                // 环境光耦合：水滴这个位置的"布"本身有多亮，光晕强的时候水滴跟着一起发白。
                float ambientLum = (refracted.r + refracted.g + refracted.b) / 3.0;
                float alpha = clamp(mix(uAlphaMin, uAlphaMax, edge) + rimBright * uFresnelAlpha, 0.0, 1.0);
                float3 colorOut = refracted * uTintStrength
                    + float3(1.0, 1.0, 1.0) * rimBright * uRimStrength
                    + float3(1.0, 1.0, 1.0) * diffuse * uDiffuseStrength
                    + float3(1.0, 1.0, 1.0) * ambientLum * uAmbientBoost;
                colorOut *= (1.0 - darkBand * uDarkBandStrength * (1.0 - rimDir));
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
        if (idleIndicatorAlpha > 0.01f) drawSleepIndicator(canvas, cx, cy, r)
    }

    /** 💤：待机时球右上角飘两个字，大的 "Z" 偏外、小的 "z" 贴着球边，照录屏里的位置摆。 */
    private fun drawSleepIndicator(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        sleepPaint.alpha = (idleIndicatorAlpha * 255f).toInt().coerceIn(0, 255)
        sleepPaint.textSize = r * 0.46f
        canvas.drawText("Z", cx + r * 0.66f, cy - r * 0.82f, sleepPaint)
        sleepPaint.textSize = r * 0.28f
        canvas.drawText("z", cx + r * 0.44f, cy - r * 0.46f, sleepPaint)
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
        val blobAColor = OriloBackgroundView.hueColor(
            OriloBackgroundView.TUNE_BLOB_A_HUE, OriloBackgroundView.TUNE_BLOB_A_SAT,
            OriloBackgroundView.TUNE_BLOB_A_VAL, phase.hueShift,
        )
        val blobBColor = OriloBackgroundView.hueColor(
            OriloBackgroundView.TUNE_BLOB_B_HUE, OriloBackgroundView.TUNE_BLOB_B_SAT,
            OriloBackgroundView.TUNE_BLOB_B_VAL, phase.hueShift,
        )
        s.setFloatUniform("blobAColor", Color.red(blobAColor) / 255f, Color.green(blobAColor) / 255f, Color.blue(blobAColor) / 255f)
        s.setFloatUniform("blobBColor", Color.red(blobBColor) / 255f, Color.green(blobBColor) / 255f, Color.blue(blobBColor) / 255f)
        s.setFloatUniform("lensCenter", cx, cy)
        s.setFloatUniform("lensRadius", r)
        s.setFloatUniform("uWarpStrength", OriloBackgroundView.TUNE_WARP_STRENGTH)
        s.setFloatUniform("uFbmScale", OriloBackgroundView.TUNE_FBM_SCALE)
        s.setFloatUniform("uFlowSpeedX", OriloBackgroundView.TUNE_FLOW_SPEED_X)
        s.setFloatUniform("uFlowSpeedY", OriloBackgroundView.TUNE_FLOW_SPEED_Y)
        s.setFloatUniform("uBlobAStrength", OriloBackgroundView.bloomedStrength(OriloBackgroundView.TUNE_BLOB_A_STRENGTH, phase.bloom))
        s.setFloatUniform("uBlobARadiusFrac", OriloBackgroundView.bloomedRadiusFrac(OriloBackgroundView.TUNE_BLOB_A_RADIUS_FRAC, phase.bloom))
        s.setFloatUniform("uBlobBStrength", OriloBackgroundView.bloomedStrength(OriloBackgroundView.TUNE_BLOB_B_STRENGTH, phase.bloom))
        s.setFloatUniform("uBlobBRadiusFrac", OriloBackgroundView.bloomedRadiusFrac(OriloBackgroundView.TUNE_BLOB_B_RADIUS_FRAC, phase.bloom))
        s.setFloatUniform("uBend", TUNE_REFRACT_BEND)
        s.setFloatUniform("uFresnelPower", TUNE_FRESNEL_POWER)
        s.setFloatUniform("uChromaticOffset", TUNE_CHROMATIC_OFFSET)
        s.setFloatUniform("uEdgeSoftStart", TUNE_EDGE_SOFT_START)
        s.setFloatUniform("uAlphaMin", TUNE_ALPHA_MIN)
        s.setFloatUniform("uAlphaMax", TUNE_ALPHA_MAX)
        s.setFloatUniform("uFresnelAlpha", TUNE_FRESNEL_ALPHA)
        s.setFloatUniform("uTintStrength", TUNE_TINT_STRENGTH)
        s.setFloatUniform("uRimStrength", TUNE_RIM_STRENGTH)
        s.setFloatUniform("uDiffuseStrength", TUNE_DIFFUSE_STRENGTH)
        s.setFloatUniform("uAmbientBoost", TUNE_AMBIENT_BOOST)
        s.setFloatUniform("uDarkBandStrength", TUNE_DARK_BAND_STRENGTH)
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
