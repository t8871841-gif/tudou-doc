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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

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
 * 颜色公式（渐变三色 hueColor() / 光斑位置 ax,ay,bx,by / 色相 120 秒一圈：青蓝↔紫，
 *  光晕呼吸 13 秒一圈；第 12 批改道，不经过品红/红）
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

    // ---------- 第 22 批：涟漪源点 ----------
    // 水滴左下（7 点半方向）那个点，本 View 自身坐标（像素）。MainActivity 在水滴
    // View 落位后算出来调 setRippleOrigin() 传进来；没传之前默认 (0,0)，涟漪
    // 几乎不可见（decay 会把它压到角落里），不会画错地方。
    private var rippleOriginX = 0f
    private var rippleOriginY = 0f

    /** 第 22 批：涟漪从哪个点往外扩——水滴左下，MainActivity 按水滴实际落位算好传进来。 */
    fun setRippleOrigin(x: Float, y: Float) {
        rippleOriginX = x
        rippleOriginY = y
    }

    private var flowAnimator: ValueAnimator? = null

    // ---------- 第 19 批：色相跳变的定时器 ----------
    // 每 20–60 秒（随机）跳一次色相，跳是瞬间的（直接改 hueShiftJump 的值，
    // 下一帧就换色，没有过渡）。Handler 挂主线程，detach 时停掉。
    private val hueJumpHandler = Handler(Looper.getMainLooper())
    private val hueJumpRunnable = object : Runnable {
        override fun run() {
            jumpHue()
            hueJumpHandler.postDelayed(this, 20000L + Random.nextInt(40000))
        }
    }

    init {
        if (useShader) {
            runtimeShader = RuntimeShader(AGSL_SOURCE)
            shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        updateFlowAnim()
        hueJumpHandler.removeCallbacks(hueJumpRunnable)
        hueJumpHandler.postDelayed(hueJumpRunnable, 20000L + Random.nextInt(40000))
    }

    override fun onDetachedFromWindow() {
        stopFlowAnim()
        hueJumpHandler.removeCallbacks(hueJumpRunnable)
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

    /** 相位完全按挂钟绝对时间算（不依赖动画器自身 elapsed）：色相 120 秒一圈（青蓝↔紫），
     *  光晕呼吸和光斑位置 13 秒一圈。
     *  公式提到了 companion 里的 phaseAt()，OriloDropletView 直接调用同一个函数，
     *  不是各写一份——保证两边任何时刻算出的光斑位置/偏色量在代码层面就是同一个来源，
     *  不存在"两份公式抄错一处就对不上"的风险。 */
    private fun recomputePhase() {
        val p = phaseAt(SystemClock.uptimeMillis())
        ax = p.ax; ay = p.ay; bx = p.bx; by = p.by; hueShift = p.hueShift; satScale = p.satScale; bloom = p.bloom
    }

    /** 底色随时间慢慢偏色（青蓝 ↔ 紫，色相 120 秒一圈，第 12 批改道，不经过红；饱和度按 13 秒走，走到淡的那头经过乳白相），照视频里整页色调流动的样子。 */
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
        // 第 22 批：涟漪——从左下源点往外扩的连续波形（不是一圈一圈画出来的环）。
        s.setFloatUniform("uRippleOrigin", rippleOriginX, rippleOriginY)
        s.setFloatUniform("uRippleStrength", TUNE_RIPPLE_STRENGTH)
        s.setFloatUniform("uRippleFreq", TUNE_RIPPLE_FREQ)
        s.setFloatUniform("uRippleSpeed", TUNE_RIPPLE_SPEED)
        s.setFloatUniform("uRippleDecay", TUNE_RIPPLE_DECAY)
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
        /** 第 5 批：按原版录屏实测的节奏改成约 13 秒一圈（原来是猜的 30 秒，慢了一倍多）。 */
        const val FLOW_PERIOD_MS = 13000L
        /** 第 19 批（用户令）：色相不再按固定时间慢慢转圈——改成"跳变"：平时
         *  色相不动（只有光斑位置/呼吸/饱和度在 13 秒轴上慢慢变幻），隔一段
         *  不固定的时间（20–60 秒随机）色相一瞬间跳到一个新值。跳变的色相
         *  存在这里（伴生对象），背景和水滴都从 phaseAt() 读同一份，保证
         *  两边跳的是一样的颜色。范围还是 0..50（底色 232→282、光斑
         *  200→250，全程青蓝到紫，最高 293°，不经过品红/红）。 */
        @Volatile
        var hueShiftJump = 0f
        /** 挑一个新色相跳过去：跟现在至少差 15°（跳得能看出来），方向随机，
         *  0..50 之间循环。直接赋值就是一瞬间，没有过渡动画。 */
        fun jumpHue() {
            val dir = if (Random.nextBoolean()) 1f else -1f
            val jump = 15f + Random.nextFloat() * 20f
            hueShiftJump = (hueShiftJump + dir * jump + 50f) % 50f
        }

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
         *  红（312+60=12），真机反馈“这种红不要，太艳丽”；青色加漂移只在蓝青之间走。 */
        const val TUNE_BLOB_A_HUE = 200f
        const val TUNE_BLOB_A_SAT = 0.55f
        const val TUNE_BLOB_A_VAL = 0.98f
        /** 光斑 B（右下附近）混合强度：0..1，同上是混合比例。 */
        const val TUNE_BLOB_B_STRENGTH = 0.45f
        /** 光斑 B 的影响半径，相对页面宽度的比例。 */
        const val TUNE_BLOB_B_RADIUS_FRAC = 0.6f
        /** 光斑 B 的颜色（HSV）：第 10 批从靛蓝紫 255 改成蓝色 220，跟光斑 A 的青色
         *  在同一段蓝青里，不再往紫粉那边偏。 */
        const val TUNE_BLOB_B_HUE = 220f
        const val TUNE_BLOB_B_SAT = 0.62f
        const val TUNE_BLOB_B_VAL = 0.85f

        // ====== 第 22 批：涟漪（连续波形，从左下源点往外扩）======
        // 参考视频实测：涟漪是一个点发出的连续波形、向外扩散，不是同心圆环、不是
        // 定时发圈；源点在水滴左下（不是垂直底部）；非常淡，单帧几乎看不见，只有
        // 动起来才感觉得到。做法：在背景着色器里按到源点的距离做正弦调制——
        // sin(距离×频率 − 时间×速度)，随距离指数衰减。波峰向外跑的速度 =
        // 速度/频率（px/秒），波长 = 2π/频率。
        /** 涟漪亮度调制幅度（0..1）：0.03 就是亮度上下只动 3%，单帧几乎看不见，
         *  只有动起来才感觉得到——跟参考一样淡。 */
        const val TUNE_RIPPLE_STRENGTH = 0.03f
        /** 涟漪空间频率（弧度/像素）：波长 = 2π/0.10 ≈ 63px，参考圆弧间距约 50–70px。 */
        const val TUNE_RIPPLE_FREQ = 0.10f
        /** 涟漪时间速度（弧度/秒）：波峰外扩速度 = 6.0/0.10 = 60px/秒，1.5 秒
         *  （一圈呼吸）跑约 90px，约 1.5 个波长，温和、不抢戏。 */
        const val TUNE_RIPPLE_SPEED = 6.0f
        /** 涟漪随距离衰减（1/像素）：300px 处剩 e^(-1.2)≈0.30，500px 处剩 0.14，
         *  约 300–500px 外基本看不见。 */
        const val TUNE_RIPPLE_DECAY = 0.004f

        /** 唯一的相位公式来源：OriloDropletView 直接调用这个函数算"此刻背景长什么样"，
         *  不再各自抄一份 sin/cos——这样两个 View 永远用同一套数，不用互相持有引用。 */
        fun phaseAt(uptimeMs: Long): FlowPhase {
            val t = (uptimeMs % FLOW_PERIOD_MS) / FLOW_PERIOD_MS.toFloat() * (Math.PI * 2).toFloat()
            return FlowPhase(
                ax = 0.30f + 0.18f * sin(t * 0.9f),
                ay = 0.26f + 0.14f * cos(t * 0.7f),
                bx = 0.72f + 0.16f * cos(t * 0.6f + 1.3f),
                by = 0.70f + 0.15f * sin(t * 0.8f + 2.1f),
                // 第 19 批：色相不再按时间慢慢转——直接读跳变值（hueShiftJump），
                // 平时不动，跳的那一帧直接换色。饱和度还按 13 秒走，走到淡的那头经过乳白相。
                hueShift = hueShiftJump,
                satScale = 0.35f + 0.65f * (0.5f + 0.5f * cos(t)),
                // 呼吸强度：0..1，比 ax/ay 这些位置项转得慢一点点（0.85x），
                // 这样"光晕涨缩"跟"光晕挪位置"不会每圈都卡在同一个点上重复。
                bloom = 0.5f + 0.5f * sin(t * 0.85f - (Math.PI / 2).toFloat()),
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
            uniform float3 gradTop;
            uniform float3 gradMid;
            uniform float3 gradBot;
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
            uniform float2 uRippleOrigin;
            uniform float  uRippleStrength;
            uniform float  uRippleFreq;
            uniform float  uRippleSpeed;
            uniform float  uRippleDecay;

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

                float2 pxA = wuv * iResolution - blobA;
                float fa = smoothstep(0.0, 1.0, clamp(1.0 - length(pxA) / (iResolution.x * uBlobARadiusFrac), 0.0, 1.0));
                float3 colorOut = mix(base, blobAColor, fa * uBlobAStrength);
                float2 pxB = wuv * iResolution - blobB;
                float fb = smoothstep(0.0, 1.0, clamp(1.0 - length(pxB) / (iResolution.x * uBlobBRadiusFrac), 0.0, 1.0));
                colorOut = mix(colorOut, blobBColor, fb * uBlobBStrength);

                // 第 22 批：涟漪——从左下源点往外扩的连续波形。sin 的波峰随时间向外跑
                // （波峰速度 = uRippleSpeed / uRippleFreq），振幅随距离指数衰减。
                // 很淡（uRippleStrength 只有 0.03），单帧几乎看不见。水滴本身盖住
                // 源点附近那一块，所以波纹只在水滴外面看得见。
                float rippleDist = length(fragCoord - uRippleOrigin);
                float ripple = sin(rippleDist * uRippleFreq - iTime * uRippleSpeed)
                    * exp(-rippleDist * uRippleDecay) * uRippleStrength;
                colorOut += ripple;

                return half4(float4(colorOut, 1.0));
            }
        """
    }
}
