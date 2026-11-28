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
 * 颜色公式（渐变三色 hueColor() / 光斑位置 ax,ay,bx,by / 色相连续缓慢漂移：青↔蓝↔紫，
 *  第 31 批改，2–5°/秒，不再跳变，偶尔褪成乳白；光晕呼吸 13 秒一圈；第 12 批改道，
 *  不经过品红/红）
 * 完全不变，只把相位来源从"动画器自身 elapsed 进度"改成"挂钟绝对时间取模"——这样
 * OriloDropletView 不管什么时候挂到窗口上，用同一份公式在同一个挂钟时刻必然算出
 * 完全相同的渐变色/光斑位置，两个 View 不用互相持有引用也不用传 Bitmap，水滴那边
 * "看透"的背景天然就是对的（水滴的着色器也是分析式重算同一套场，不是采样本 View
 * 的真实像素，所以也不存在截屏/跨进程的额外开销）。
 *
 * 动画只在可见时跑（省电）；公开方法名、MainActivity 调用方式不变（这个 View 本来
 * 就没有被外部调用的公开方法，纯粹是挂在根布局上的背景层）。
 *
 * 2026-11-15 第 3 批（用户要求"不用每次都发回来等改"）：把折射/噪声/光斑强度这些
 * 容易想调的数字集中到 companion 里的 `TUNE_*` 常量（见下方"调参区"），改完重新
 * 编译装机就能看效果，不用动 AGSL 代码本身。这几个常量 OriloDropletView 也在用
 * （直接引用 `OriloBackgroundView.TUNE_*`），改这里两个 View 的"布"会一起变。
 *
 * 2026-11-15 第 4 批（用户反馈真机截图"背景和玻璃球都不对，没有原版的灵动"）：
 * 背景这边的根因——上一批光斑是"原色 + 光斑颜色 × 强度"直接相加，光斑颜色本身
 * 又跟底色一样偏浅蓝浅紫，加上去数值很快顶到死白，肉眼基本看不出色斑在哪，所以
 * 真机上看着就是一片均匀的蓝或紫。这批改成真正的颜色混合（mix，不是相加），
 * 且把两个光斑换成明显偏离底色的饱和色（左上偏品红紫、右下偏靛蓝），这样光斑会
 * 读成清楚的色块（照目标截图左上那片粉紫、右下那片蓝的反差），不会被底色吃掉。
 *
 * 2026-11-15 第 5 批（用户发了 orilo 原版的真机录屏，逐帧看了一遍）：之前当成
 * "30 秒一圈、两团色斑挪位置"的理解是错的——原版是一团"光晕"在呼吸式涨缩：从一小片
 * 淡淡的紫粉，大概 10 秒左右涨到几乎盖住大半屏幕（同时水滴也跟着被照得发白），再收
 * 回去，一圈大概 12~13 秒。关键是**强度（半径 × 浓度）在呼吸**，不只是位置在挪。
 * 这批把周期从 30000ms 改成 13000ms，并在 `FlowPhase` 里加了 `bloom`（0→1→0 的呼吸
 * 强度），两团光斑的半径和强度现在都乘了这个 `bloom`——弱的时候一小片，强的时候
 * 接近盖满屏幕，这才是原版"活"的真正来源。
 *
 * 2026-11-17 第 22 批（用户发 Morning Coffee 参考视频，20 帧/秒量过）：
 * 涟漪是一个点发出的连续波形、向外扩散（不是同心圆环、不是定时发圈），源点在
 * 水滴左下（不是垂直底部），非常淡、单帧几乎看不见。做法：着色器里按到源点的
 * 距离做正弦调制 sin(距离×频率 − 时间×速度)，随距离指数衰减；源点由
 * MainActivity 按水滴实际落位算好、调 setRippleOrigin() 传进来。呼吸那半
 * （幅度/速度）在 OriloDropletView，不在这里。
 *
 * 2026-11-17 第 23 批（用户纠正：涟漪是沿着水珠自己的表面、水珠外面区域没有；
 * 一束光模型）：v93 把涟漪做在背景上是错的——圈跑到水滴外面去了。改法：背景
 * 上的涟漪删掉（外面没有圈），涟漪挪到 OriloDropletView 的着色器里（从左下源
 * 点出发、沿着水滴表面往里扩，每 15 秒一波的波包）。背景这边只留"外面左下聚
 * 光"：光透过水珠，照亮水珠外面左下一片区域——背景着色器里加一个柔和亮斑，
 * 位置在水滴左下再往外 0.5r（1.5r 处、225° 方向），由 MainActivity 按水滴
 * 实际落位算好、调 setCausticSpot() 传进来（位置 + 半径）。
 *
 * 2026-11-19 第 34 批：水滴的影子挪到这里画。以前影子在 220dp 的水滴小框里
 * 画，半径 1.8r 直径 308dp 比框还大，被框切成矩形（v101 真机 30fps：暗区宽
 * 603px 上下不变）。背景是全屏的，影子在着色器里画（uShadowSpot / uShadowRadius
 * / uShadowStrength），中心在水滴底下 1.3r、半径 1.8r、强度 0.75，平滑褪到 0。
 * 位置由 MainActivity.updateCausticSpot() 一起算好、调 setShadowSpot() 传进来，
 * 跟外面聚光同一套路。
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
    private var satScale = 1f
    private var bloom = 0f

    // ---------- 第 23 批：外面左下聚光 ----------
    // 光透过水珠，照亮水珠外面左下一片区域。本 View 自身坐标（像素）。MainActivity
    // 在水滴 View 落位后算出来调 setCausticSpot() 传进来（位置 + 半径）；没传之前
    // 默认 (0,0)、半径 1px——半径不能是 0（smoothstep(0, 0, x) 是未定义行为），
    // 1px 的亮斑在左上角、肉眼看不见，不会画错地方。
    // 第 22 批的涟漪（setRippleOrigin / rippleOriginX/Y）已删——涟漪是沿着水珠
    // 自己的表面、水珠外面区域没有，挪到 OriloDropletView 的着色器里了。
    private var causticSpotX = 0f
    private var causticSpotY = 0f
    private var causticRadius = 1f

    /** 第 23 批：外面左下聚光在哪、多大——水滴左下再往外 0.5r（1.5r 处、225°
     *  方向），MainActivity 按水滴实际落位算好传进来。 */
    fun setCausticSpot(x: Float, y: Float, radiusPx: Float) {
        causticSpotX = x
        causticSpotY = y
        causticRadius = radiusPx
    }

    // 第 34 批：水滴的影子在哪、多大——水滴底下 1.3r 处、半径 1.8r，
    // MainActivity 按水滴实际落位算好传进来。跟外面聚光同一套路。
    private var shadowSpotX = 0f
    private var shadowSpotY = 0f
    private var shadowRadius = 1f

    fun setShadowSpot(x: Float, y: Float, radiusPx: Float) {
        shadowSpotX = x
        shadowSpotY = y
        shadowRadius = radiusPx
    }

    private var flowAnimator: ValueAnimator? = null

    // ---------- 第 19 批：色相跳变的定时器 ----------
    // 第 30 批已删：Henry 视频 30fps 实测没有跳变，色相是连续缓慢漂移；
    // 跳变定时器（hueJumpHandler/hueJumpRunnable）和 jumpHue() 全删，
    // 色相改成 phaseAt() 里按挂钟时间连续算。这里留空是防以后有人照着
    // 旧笔记把跳变加回来。

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

    /** 相位完全按挂钟绝对时间算（不依赖动画器自身 elapsed）：色相连续缓慢漂移
     *  （第 30 批：青蓝↔紫，2–5°/秒，不再跳变），光晕呼吸和光斑位置 13 秒一圈。
     *  公式提到了 companion 里的 phaseAt()，OriloDropletView 直接调用同一个函数，
     *  不是各写一份——保证两边任何时刻算出的光斑位置/偏色量在代码层面就是同一个来源，
     *  不存在"两份公式抄错一处就对不上"的风险。 */
    private fun recomputePhase() {
        val p = phaseAt(SystemClock.uptimeMillis())
        ax = p.ax; ay = p.ay; bx = p.bx; by = p.by; hueShift = p.hueShift; satScale = p.satScale; bloom = p.bloom
    }

    /** 底色随时间慢慢偏色（青 ↔ 蓝 ↔ 紫，连续缓慢漂移，第 12 批改道，不经过红；
     *  第 31 批：饱和度跟色相耦合，变蓝/青时浓、变紫时淡；偶尔褪成乳白相），
     *  照视频里整页色调流动的样子。 */
    private fun hueColor(h: Float, s: Float, v: Float): Int =
        Color.HSVToColor(floatArrayOf((h + hueShift) % 360f, (s * satScale).coerceIn(0f, 1f), v))

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
        s.setFloatUniform("iResolution", w, h)
        s.setFloatUniform("iTime", SystemClock.uptimeMillis() / 1000f)
        // 第 55 批（补）：底色色相改由着色器内噪声场直接定（hsv2rgb），
        // gradTop/Mid/Bot 不再用（色相不再由 CPU 的 hueColor 定），
        // 这里不再传。光斑颜色仍由 CPU 的 hueColor 算（全局漂移）。
        s.setFloatUniform("blobA", ax * w, ay * h)
        s.setFloatUniform("blobB", bx * w, by * h)
        val blobAColor = hueColor(TUNE_BLOB_A_HUE, TUNE_BLOB_A_SAT, TUNE_BLOB_A_VAL)
        val blobBColor = hueColor(TUNE_BLOB_B_HUE, TUNE_BLOB_B_SAT, TUNE_BLOB_B_VAL)
        s.setFloatUniform("blobAColor", Color.red(blobAColor) / 255f, Color.green(blobAColor) / 255f, Color.blue(blobAColor) / 255f)
        s.setFloatUniform("blobBColor", Color.red(blobBColor) / 255f, Color.green(blobBColor) / 255f, Color.blue(blobBColor) / 255f)
        s.setFloatUniform("uWarpStrength", TUNE_WARP_STRENGTH)
        s.setFloatUniform("uFbmScale", TUNE_FBM_SCALE)
        s.setFloatUniform("uFlowSpeedX", TUNE_FLOW_SPEED_X)
        s.setFloatUniform("uFlowSpeedY", TUNE_FLOW_SPEED_Y)
        s.setFloatUniform("uBlobAStrength", bloomedStrength(TUNE_BLOB_A_STRENGTH, bloom))
        s.setFloatUniform("uBlobARadiusFrac", bloomedRadiusFrac(TUNE_BLOB_A_RADIUS_FRAC, bloom))
        s.setFloatUniform("uBlobBStrength", bloomedStrength(TUNE_BLOB_B_STRENGTH, bloom))
        s.setFloatUniform("uBlobBRadiusFrac", bloomedRadiusFrac(TUNE_BLOB_B_RADIUS_FRAC, bloom))
        // 第 23 批：外面左下聚光——光透过水珠，照亮水珠外面左下一片区域。
        // 位置和半径由 MainActivity 按水滴实际落位算好传进来。
        s.setFloatUniform("uCausticSpot", causticSpotX, causticSpotY)
        s.setFloatUniform("uCausticRadius", causticRadius)
        s.setFloatUniform("uCausticStrength", TUNE_CAUSTIC_STRENGTH)
        // 第 34 批：水滴的影子——位置和半径由 MainActivity 传进来。
        s.setFloatUniform("uShadowSpot", shadowSpotX, shadowSpotY)
        s.setFloatUniform("uShadowRadius", shadowRadius)
        s.setFloatUniform("uShadowStrength", TUNE_SHADOW_STRENGTH)
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
        // 第 4 批：兜底路径的光斑颜色也换成跟着色器路径一致的饱和品红/靛蓝（原来的浅蓝浅紫
        // 太接近底色，容易被吃掉）。第 5 批：半径和 alpha 也跟着 bloom 呼吸涨缩。
        val radiusA = w * bloomedRadiusFrac(TUNE_BLOB_A_RADIUS_FRAC, bloom)
        val alphaA = (0x8F * bloomedStrength(1f, bloom)).toInt().coerceIn(0, 0xFF)
        blobPaintA.shader = RadialGradient(
            ax * w, ay * h, radiusA,
            intArrayOf((alphaA shl 24) or 0xFF64C8, 0x00FF64C8),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, blobPaintA)
        val radiusB = w * bloomedRadiusFrac(TUNE_BLOB_B_RADIUS_FRAC, bloom)
        val alphaB = (0x85 * bloomedStrength(1f, bloom)).toInt().coerceIn(0, 0xFF)
        blobPaintB.shader = RadialGradient(
            bx * w, by * h, radiusB,
            intArrayOf((alphaB shl 24) or 0x7646E0, 0x007646E0),
            floatArrayOf(0f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawRect(0f, 0f, w, h, blobPaintB)
    }

    /** 某一挂钟时刻的流动相位：两团光斑的页面相对坐标 + 底色偏色量 + 呼吸强度（0..1）
     *  + 饱和度系数（1..0.35，第 10 批新增：跟着色相一起走到青色那头时饱和度掉下来，
     *  背景经过乳白相，不是一直浓到发艳）。 */
    data class FlowPhase(
        val ax: Float, val ay: Float, val bx: Float, val by: Float,
        val hueShift: Float, val bloom: Float, val satScale: Float,
    )

    companion object {
        /** 第 19 批（用户令）：色相不再按固定时间慢慢转圈——改成"跳变"：平时
         *  色相不动（只有光斑位置/呼吸/饱和度在 13 秒轴上慢慢变幻），隔一段
         *  不固定的时间（20–60 秒随机）色相一瞬间跳到一个新值。跳变的色相
         *  存在这里（伴生对象），背景和水滴都从 phaseAt() 读同一份，保证
         *  两边跳的是一样的颜色。范围还是 0..50（底色 232→282、光斑
         *  200→250，全程青蓝到紫，最高 293°，不经过品红/红）。
         *  第 30 批（Henry 视频 30fps 实测，用户令"全部改完"）：跳变模型是错的——
         *  994 帧量出来根本没有跳变，色相是连续缓慢漂移（2–5°/秒，20–25 秒
         *  走完蓝↔紫一圈）。跳变定时器和 jumpHue() 全删，色相改成 phaseAt()
         *  里按挂钟时间连续算（两组不成整数比的正弦叠加，不重复、没有固定周期）。
         *  hueShiftJump 和 jumpHue() 已删，别再找。 */

        // ====== 调参区：想自己试效果就改这几个数，不用懂 AGSL、不用改下面的着色器代码 ======
        // 改完重新编译装机就能看到效果；这几个数两个 View（背景 + 水滴）共用同一份，
        // OriloDropletView 里引用的是 OriloBackgroundView.TUNE_*，改这里两边一起变。
        /** 噪声扭曲幅度：越大背景"布"扭得越夸张，调到 0 就是纯渐变、完全不扭。
         *  第 18 批 0.16→0.25：背景太素、折射 120 也看不出区别——水滴内部
         *  采样到的颜色跟周围差不多。把布纹加粗一点，让水滴内部有"扭曲的
         *  流动感"可看；背景本身也会更有纹理。 */
        const val TUNE_WARP_STRENGTH = 0.25f
        /** 噪声密度：越大纹理越碎越密，越小纹理越大团、更像缓慢的云雾。
         *  第 18 批 2.2→2.8：配合扭曲幅度，让纹理更碎一点，折射扭起来更明显。 */
        const val TUNE_FBM_SCALE = 2.8f
        /** 噪声横向流动速度（越大流得越快）。 */
        const val TUNE_FLOW_SPEED_X = 0.02f
        /** 噪声纵向流动速度（越大流得越快）。 */
        const val TUNE_FLOW_SPEED_Y = 0.017f
        /** 光斑 A（左上附近）混合强度：0..1，是跟底色"混合"的比例（不是相加），
         *  1.0 就是光斑中心完全显示光斑色，0 就是看不见这团光斑。 */
        const val TUNE_BLOB_A_STRENGTH = 0.55f
        /** 光斑 A 的影响半径，相对页面宽度的比例：越大这团光斑铺得越开。 */
        const val TUNE_BLOB_A_RADIUS_FRAC = 0.55f
        /** 光斑 A 的颜色（HSV）：第 10 批从品红紫 312 改成青色 200——品红加漂移会走到
         *  红（312+60=12），真机反馈“这种红不要，太艳丽”；青色加漂移只在蓝青之间走。
         *  第 77 批（用户令按 95% 贴近 4454 参考改）：4454 参考左上是粉紫（暖紫），
         *  不是青——真机截图左上那片薄荷绿/青就是这团光斑。改回暖紫 285°：
         *  285+漂移（-45..+50）最高 335°，仍在紫粉区，不到红（360°/0°），
         *  不会重蹈第 10 批“太艳丽”的问题。
         *  第 82 批（用户令：背景只保留图片上颜色）：图里左上是 261°，285°
         *  超出图里颜色了。改成 255°——加上收窄后的漂移（±14°）是 241°–269°，
         *  仍在图里 230°–267° 这一家子紫里。 */
        const val TUNE_BLOB_A_HUE = 255f
        const val TUNE_BLOB_A_SAT = 0.45f
        const val TUNE_BLOB_A_VAL = 0.98f
        /** 光斑 B（右下附近）混合强度：0..1，同上是混合比例。 */
        const val TUNE_BLOB_B_STRENGTH = 0.80f
        /** 光斑 B 的影响半径，相对页面宽度的比例。 */
        const val TUNE_BLOB_B_RADIUS_FRAC = 0.6f
        /** 光斑 B 的颜色（HSV）：第 10 批从靛蓝紫 255 改成蓝色 220，跟光斑 A 的青色
         *  在同一段蓝青里，不再往紫粉那边偏。
         *  第 77 批：4454 参考右下是靛蓝/冷蓝，比 220° 更深更冷。改成 245°。 */
        /** 第 89 批（用户令：背景以紫为主、淡蓝缓缓变化）：光斑 B 改成淡蓝——
         *  色相 245°→230°（真蓝），饱和度 0.62→0.45（淡），亮度 0.85→0.95
         *  （亮）。它是全屏唯一的蓝色，漂移速度（19 秒一圈）不动，缓缓变化。 */
        const val TUNE_BLOB_B_HUE = 230f
        const val TUNE_BLOB_B_SAT = 0.85f
        const val TUNE_BLOB_B_VAL = 0.95f

        // ====== 第 23 批：外面左下聚光（光透过水珠，照亮水珠外面左下一片）======
        // 第 22 批的涟漪常量（TUNE_RIPPLE_*）已删——涟漪是沿着水珠自己的表面、
        // 水珠外面区域没有，挪到 OriloDropletView 里了（强度/频率/速度/衰减/
        // 周期都在那边）。背景这边只留外面聚光：一个柔和亮斑，位置在水滴左下
        // 再往外 0.5r（1.5r 处、225° 方向），半径 0.6r——位置和半径都由
        // MainActivity 按水滴实际落位算好传进来（uCausticSpot / uCausticRadius），
        // 这里只定强度。
        /** 外面左下聚光的峰值亮度（0..1）：0.10，就是那片区域亮 10%——光穿过水珠
         *  散开了，比里面聚光（0.15）淡、比涟漪（0.03）浓。太亮像灯泡就往 0.06
         *  降，太淡看不见就往 0.15 升。 */
        const val TUNE_CAUSTIC_STRENGTH = 0.10f
        /** 第 34 批：水滴影子的强度（0..1）——中心最暗处压暗多少。背景约 170，
         *  参考最暗 39–52，170 × (1-0.75) ≈ 42，对得上。太黑像墨块就往
         *  0.6 降，太淡看不见就往 0.85 升。 */
        const val TUNE_SHADOW_STRENGTH = 0.75f

        /** 唯一的相位公式来源：OriloDropletView 直接调用这个函数算"此刻背景长什么样"，
         *  不再各自抄一份 sin/cos——这样两个 View 永远用同一套数，不用互相持有引用。 */
        /** 三角波 0→1→0：fract 取小数部分，负数先 +1 转正（Kotlin % 对负数给负余数）。 */
        private fun tri(x: Double): Float {
            val f = x % 1.0
            val ff = if (f < 0) f + 1.0 else f
            return (if (ff < 0.5) ff * 2.0 else 2.0 - ff * 2.0).toFloat()
        }

        fun phaseAt(uptimeMs: Long): FlowPhase {
            // 第 87 批（用户令：主界面动态不流畅，参考云朵快速变化）：第 84 批
            // 的互质周期正弦还是原地来回摆（47–71 秒一圈），位置在原地摆、色相
            // 在原地闪，看着像呼吸不像流动。改成定向漂移：光斑像风吹的云，朝一
            // 个方向横穿屏幕、从另一边再进来；纵向仍保留缓慢正弦起伏（61/71 秒），
            // 轨迹带一点波浪。ax/bx 是锯齿波（fract）：A 15 秒、B 19 秒走完一个
            // 周期（屏幕宽 + 两倍光斑半径），速度不同、方向相同，像不同高度的云
            // 层被同一阵风吹。ax2/bx2 是各错半个周期的副本（A 7.5 秒、B 9.5 秒）：
            // 着色器里两份一起画，换头瞬间副本正好接上领头的位置，没有跳变。
            // 第 95 批修正（2026-11-28 评审查实）：原来 ax2 = ax - 1.9 是错的——
            // ax 已经取过 mod，减常数只是刚性平移，ax2 永远在 -2.35~-0.45、
            // 光斑右缘刚贴左边界，画面上恒为 0，换头从来没人接棒。1.9/1.8 的来历：
            // 着色器里光斑半径是 0.45/0.40 屏宽，中心从 -0.45（完全出左屏）走到
            // 1.45（完全出右屏）正好 1.9 个屏宽，B 同理 1.8。
            // 第 96 批（2026-11-28 用户令：参考是来回变换）：上面说的锯齿波 +
            // 副本已改成三角波（往右扫到头再原路往左扫回来），见下面；副本删掉。
            // 第 31 批（用户令：背景要蓝、紫、青、乳白轮着变，颜色转化随机）：
            // 色相范围拉到 -45..+50（第 30 批是 0..50，只到蓝紫），青（约 190°）
            // 和紫（约 282°）都能走到。两组不成整数比的正弦叠加（53 秒和 79 秒），
            // 漂移不重复、没有固定周期；最快漂移约 4.9°/秒（实测 2–5°/秒）。
            // 饱和度跟色相耦合：变蓝/青（hueShift 小）时饱和度高，变紫（hueShift 大）
            // 时饱和度低。hueShift -45→1.0、+50→0.70。
            // 第 82 批（用户令：背景只保留图片上颜色）：漂移 -45..+50 会把光斑
            // 颜色带出图里 230°–267° 的范围（285°+50°=335° 是品红），乳白相
            // （约每 2–3 分钟一次、持续十来秒把背景褪成乳白）更是图里没有的颜色。
            // 漂移收窄到 ±14°，乳白相整段去掉（satScale 只剩 baseSat）。
            // 两组正弦周期（53/79 秒）不动，漂移仍不重复；最快漂移约 1.5°/秒。
            // 第 96 批（2026-11-28 用户令：紫偏红、蓝太深——实测）：±14° 摆到顶
            // 是 281°，已经进了品红（底色 267°+14°、光斑 B 230°+14°=244° 偏紫），
            // 底色偏红、蓝变紫蓝的根子就是它。收窄到 ±5°：底色最高 272°、光斑 B
            // 最高 235°，基本不出图里 230°–267° 这一家子；最快漂移约 0.5°/秒。
            // 色相的快变化不靠全局漂移，靠光斑来回扫过（见下面的三角波）。
            val tSec = uptimeMs / 1000.0
            val hueShift = (3.0 * sin(2.0 * Math.PI * tSec / 53.0) + 2.0 * sin(2.0 * Math.PI * tSec / 79.0)).toFloat()
            val baseSat = (1.0f - 0.30f * ((hueShift + 5f) / 10f)).coerceIn(0.70f, 1.0f)
            val satScale = baseSat
            // 第 96 批：三角波来回扫（见上面第 96 批注释）。ax2/bx2 副本删掉。
            val ax = -0.45f + 1.9f * tri(tSec / 22.0)
            val bx = -0.40f + 1.8f * tri(tSec / 26.0)
            return FlowPhase(
                ax = ax,
                ay = 0.26f + 0.14f * cos(2.0 * Math.PI * tSec / 61.0).toFloat(),
                bx = bx,
                by = 0.70f + 0.15f * sin(2.0 * Math.PI * tSec / 71.0 + 2.1).toFloat(),
                hueShift = hueShift,
                satScale = satScale,
                // 呼吸强度：0..1，比 ax/ay 这些位置项转得慢一点点（0.85x），
                // 这样"光晕涨缩"跟"光晕挪位置"不会每圈都卡在同一个点上重复。
                bloom = 0.5f + 0.5f * sin(2.0 * Math.PI * tSec / 57.0 - Math.PI / 2).toFloat(),
            )
        }

        /** 底色随时间慢慢偏色用的 HSV 转换，供两个 View 共用（避免重复实现一份换色逻辑）。
         *  第 10 批：饱和度也跟着相位走（satScale），走到青色那头时颜色变淡、经过乳白相。 */
        fun hueColor(baseHue: Float, s: Float, v: Float, hueShift: Float, satScale: Float = 1f): Int =
            Color.HSVToColor(floatArrayOf((baseHue + hueShift) % 360f, (s * satScale).coerceIn(0f, 1f), v))

        /** 第 5 批新增：光斑半径随 bloom 呼吸——弱的时候只有基准值的一半出头，
         *  强的时候涨到基准值的接近 1.5 倍，接近盖满屏幕。两个 View 共用，保证同步。 */
        fun bloomedRadiusFrac(baseFrac: Float, bloom: Float): Float =
            baseFrac * (0.55f + 0.9f * bloom)

        /** 第 5 批新增：光斑浓度随 bloom 呼吸——弱的时候很淡，强的时候接近基准值的 1.5 倍
         *  （会被 main() 里的 mix 自动夹到 1.0，所以峰值时是"完全显示光斑色"）。 */
        fun bloomedStrength(baseStrength: Float, bloom: Float): Float =
            (baseStrength * (0.35f + 1.15f * bloom)).coerceIn(0f, 1f)

        // AGSL：两层 fbm 噪声先扭一下采样坐标（玻璃后面的内容在"晃"），
        // 扭完的坐标再算三色渐变 + 两团光斑——光斑不再是刚性圆形平移，
        // 而是整块"布"本身在流动变形，边缘也跟着抖，这才是液态玻璃该有的扭曲感。
        const val AGSL_SOURCE = """
            uniform float2 iResolution;
            uniform float  iTime;
            uniform float2 blobA;
            uniform float2 blobB;
            uniform float3 blobAColor;
            uniform float3 blobBColor;
            uniform float  uWarpStrength;
            uniform float  uFbmScale;
            uniform float  uFlowSpeedX;
            uniform float  uFlowSpeedY;
            uniform float  uBlobAStrength;
            uniform float  uBlobARadiusFrac;
            uniform float  uBlobBStrength;
            uniform float  uBlobBRadiusFrac;
            uniform float2 uCausticSpot;
            uniform float  uCausticRadius;
            uniform float  uCausticStrength;
            uniform float2 uShadowSpot;
            uniform float  uShadowRadius;
            uniform float  uShadowStrength;

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
            // 第 55 批（补）：hsv2rgb——青要作为饱和色出现，不能只靠 YIQ 旋转。
            // 之前那版是 ±25° 旋转：蓝（232°）上下只到 207°–257°，到不了青
            // （190°），青只在乳白斑里以淡青出现，从没有饱和的青。
            // 改成色相由噪声场直接定：hue = mix(190, 267, hueN)，青↔蓝↔紫
            // 全程在场，饱和的青、蓝、紫同框、互相流。标准紧凑实现。
            float3 hsv2rgb(float3 c) {
                float4 K = float4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
                float3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
                return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
            }

            half4 main(float2 fragCoord) {
                float2 uv = fragCoord / iResolution;
                float2 warp = float2(
                    fbm(uv * uFbmScale + float2(iTime * uFlowSpeedX, 0.0)),
                    fbm(uv * uFbmScale + float2(0.0, iTime * uFlowSpeedY) + 9.3)
                );
                float2 wuv = uv + (warp - 0.5) * uWarpStrength;

                // 第 55 批（补）：底色改成 HSV 直接算。之前是纵向 RGB 渐变
                // （gradTop/Mid/Bot，CPU 上 hueColor 全局移色相）+ YIQ 旋转
                // ±25°，青到不了（蓝 232° 上下只到 207°–257°），青只在乳白
                // 斑里淡青出现。改成：色相由噪声场直接定（青 190°↔蓝 232°↔
                // 紫 267° 全程在场，饱和的青、蓝、紫同框互相流），饱和度/明度
                // 仍按纵向渐变（上 0.50/0.95、中 0.68/0.91、下 0.64/0.91），
                // 乳白场独立（另一路 noise）：noise 高的地方饱和度压到 0.08、
                // 明度抬到 0.97，褪成乳白。乳白斑和青斑位置不同、各自流动，
                // 青↔乳白互相渗。gradTop/Mid/Bot 不再用（色相不再由 CPU 定）。
                float2 dir = float2(0.25, 1.0);
                float t = clamp(dot(wuv, dir) / dot(dir, dir), 0.0, 1.0);
                float satTop = mix(0.50, 0.68, clamp(t / 0.55, 0.0, 1.0));
                float satBot = mix(0.68, 0.64, clamp((t - 0.55) / 0.45, 0.0, 1.0));
                float sat = mix(satTop, satBot, step(0.55, t));
                float valTop = mix(0.95, 0.91, clamp(t / 0.55, 0.0, 1.0));
                float valBot = mix(0.91, 0.91, clamp((t - 0.55) / 0.45, 0.0, 1.0));
                float val = mix(valTop, valBot, step(0.55, t));
                float hueN = noise(wuv * 1.6 + float2(iTime * 0.010, iTime * 0.008));
                // 第 77 批（用户令按 95% 贴近 4454 参考改）：4454 参考是紫为主，
                // 左上暖紫、右下靛蓝，没有薄荷绿/青。色相场 190°↔267° 太偏青，
                // 真机左上那片薄荷绿就是 190° 的青。改成 210°↔285°：低端仍是
                // 蓝（210°，不是绿），高端到暖紫（285°），整体往紫移，青不
                // 再抢眼。乳白场不动。
                float hue = mix(210.0, 285.0, hueN);
                float milkN = noise(wuv * 1.1 + float2(iTime * 0.006, iTime * 0.005) + 7.3);
                float milkAmt = smoothstep(0.6, 0.9, milkN);
                sat = mix(sat, 0.08, milkAmt * 0.85);
                val = mix(val, 0.97, milkAmt * 0.5);
                float3 base = hsv2rgb(float3(hue / 360.0, sat, val));

                float2 pxA = wuv * iResolution - blobA;
                float fa = smoothstep(0.0, 1.0, clamp(1.0 - length(pxA) / (iResolution.x * uBlobARadiusFrac), 0.0, 1.0));
                float3 colorOut = mix(base, blobAColor, fa * uBlobAStrength);
                float2 pxB = wuv * iResolution - blobB;
                float fb = smoothstep(0.0, 1.0, clamp(1.0 - length(pxB) / (iResolution.x * uBlobBRadiusFrac), 0.0, 1.0));
                colorOut = mix(colorOut, blobBColor, fb * uBlobBStrength);

                // 第 23 批：外面左下聚光——光透过水珠，照亮水珠外面左下一片区域。
                // 柔和亮斑，中心在水滴左下再往外 0.5r（uCausticSpot），半径
                // uCausticRadius（0.6r），往外褪到 0。注意用 1.0 - smoothstep
                // 的写法，不用 edge0 > edge1 的反向 smoothstep（未定义行为）。
                float causticDist = length(fragCoord - uCausticSpot);
                float caustic = (1.0 - smoothstep(0.0, uCausticRadius, causticDist)) * uCausticStrength;
                colorOut += caustic;

                // 第 34 批：水滴的影子——以前在 220dp 的水滴小框里画，半径 1.8r
                // 直径 308dp 比框还大，被框切成矩形（v101 真机：暗区宽 603px、
                // 上下不变）。挪到全屏背景层画，中心在水滴底下 1.3r、半径 1.8r，
                // 平滑褪到 0，没有硬边。强度 0.75：背景 170 × (1-0.75) ≈ 42，
                // 对上参考最暗 39–52。位置和半径由 MainActivity 按水滴实际落位
                // 算好传进来（setShadowSpot），跟外面聚光同一套路。
                float shadowDist = length(fragCoord - uShadowSpot);
                float shadow = (1.0 - smoothstep(0.0, uShadowRadius, shadowDist)) * uShadowStrength;
                colorOut *= (1.0 - shadow);

                return half4(float4(colorOut, 1.0));
            }
        """
    }
}
