package com.jarvis.app

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
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
import kotlin.math.cos
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
 *
 * 2026-11-16 第 9 批（用户给了详细的真机录像逐帧量测报告 + 一篇思路文章，按报告里
 * "跟我们现在的差距"5 条逐条改，文章里说反的"高光方向"没有跟）：
 * 1. **颤动模型整个换掉**（最要紧）：第 6/8 批一直是 scaleX/scaleY 整体挤压成椭圆——
 *    不管频率怎么叠，本质还是"整个水滴一起变形"，跟量测到的"边缘局部此起彼伏、
 *    整体大小基本不动"对不上。这批彻底换掉：不再动 scaleX/scaleY 做形变（待机缩放
 *    的整体大小还是用它们，但不叠加挤压了），改成按角度算半径 r(θ,t)（3 组不成
 *    整数比的频率叠加在角度和时间上），每帧生成一个局部起伏的闭合 Path，水滴的
 *    本体（不管哪条渲染路径）改成画这个 Path 而不是画正圆。待机时（`awake=false`）
 *    冻结这个相位（量测说待机时水滴"缩着不动"，Z 消失后才恢复颤动）。
 *    着色器那边不需要跟着算这个角度公式——Path 本身已经精确定义了可见区域，
 *    着色器只需要把自己内部的硬裁切半径放宽一圈（新增 `uShapeMargin`，按颤动能
 *    鼓出去多少留够余量），不然鼓出去的部分会被着色器自己的旧裁切吃掉变透明。
 * 2. **新增内部折射环**：之前完全没有。加了一圈淡淡的椭圆形光环，中心跟着背景的
 *    光斑 A 位置（`OriloBackgroundView.phaseAt().ax/ay`）缓慢漂移，强度很弱
 *    （`TUNE_RING_STRENGTH`），不抢高光的戏。
 * 3. **内部更透、折射色往边缘集中**：量测说中心跟背景亮度只差 0~15（255 制），
 *    之前 `uTintStrength`/`uAlphaMin` 太高导致中心颜色太实。这批把两个默认值都
 *    降了不少，并且让折射强度本身按"离中心多近"做了个权重（`tintEdgeFactor`）——
 *    中心几乎不显色，颜色和环境反射主要在靠近边缘的地方才明显，这样"中心透、
 *    边缘才看出玻璃感"两头都顾到。
 * 4. **顶部细亮边改成真的弧形**：第 7 批那版方向性亮边本质是个"哪个方向最亮"的
 *    连续权重，没有明确的"只在 10 点到 2 点这段范围亮、过了就没有"的边界感。这批
 *    换成按夹角做 smoothstep（两个新常量 `TUNE_RIM_ARC_INNER_COS`/`_OUTER_COS`
 *    控制亮区半角和淡出范围），方向也从"偏左上"改成"偏正上方、略偏左"，更接近
 *    "10 点到 2 点"这个描述；原来的 `TUNE_FRESNEL_POWER`/`uFresnelPower` 被这两个
 *    新常量取代，不再使用。
 * 5. 高光位置、底部"笑脸"亮弧、影子位置、Z 的位置和淡入淡出、待机缩放 0.8 倍、
 *    13 秒光晕呼吸——这些量测报告说已经对的，一个字没动。
 *
 * 2026-11-17 第 13 批（用户拿 Henry 原图逐点量过：我们水滴"太突兀，用力过猛"）：
 * 原图实测（水滴中心约 450,615，半径约 180）：顶部（12 点，约 40° 宽）是暗带、
 * 最暗 -46；底部（6 点，约 60° 宽）是亮弧"笑脸"、最亮 +40、很柔和；影子核心
 * 亮度 51（背景约 119），150px 内平滑褪净；内部只比背景亮约 +15，几乎全透明。
 * 所以边缘是"上有暗带、下有亮弧"的方向性结构，而且每层都很淡。这批：
 * 1. **顶部亮边去掉**：第 9 批加的顶部亮弧方向反了（原图顶部是最暗的点），
 *    `TUNE_RIM_STRENGTH`/`TUNE_RIM_ARC_*`/`uFresnelAlpha` 全部删掉。
 * 2. **暗带只留顶部**：原来整圈均匀的暗带改成只在顶部（新 `TUNE_TOP_DARK_*`，
 *    约 40° 宽），底部不再变暗。
 * 3. **底部"笑脸"以柔光加回来**：新 `TUNE_SMILE_*`（约 60° 宽，最亮 +40 的目标），
 *    是着色器里的漫射亮斑、不是线；第 11 批删掉的 rimPaint 弧是硬线，别恢复它。
 * 4. **内部再透一点**：`TUNE_TINT_STRENGTH` 0.3→0.2、`TUNE_RING_STRENGTH`
 *    0.10→0.06、`TUNE_DIFFUSE_STRENGTH` 0.04→0.02、`TUNE_AMBIENT_BOOST` 0.15→0.10；
 *    影子核心 0x42→0x30、铺得更开；整圈白描边 alpha 60→25。
 *
 * 2026-11-17 第 14 批（用户把第 13 批代码发给 DeepSeek 审，方向对、但渲染结果
 * "太重、太实"，像实心塑料球、不像玻璃水滴）：参数给得太猛了，玻璃的质感来自对比——
 * 边缘极度锐利（高光硬核）、内部极度虚无（高透明度）、暗部极度柔和（影子）。这批
 * 全是调参，没动结构：透明度大砍（ALPHA_MIN 0.05→0.01、ALPHA_MAX 0.50→0.25）、
 * 环境白光和漫反射再压（AMBIENT_BOOST 0.10→0.02、DIFFUSE 0.02→0.01）、染色再淡
 * （TINT 0.2→0.08）、笑脸 0.30→0.12 且内外余弦放宽、暗带 0.4→0.15、影子核心
 * 0x30→0x18 且褪得更快、铺得更开、高光改成硬核 + 极速衰减且椭圆压扁（0.45→0.3）。
 * 保留意见：笑脸（0.12 × 边缘透明度 0.25 ≈ 0.03）和暗带（0.15）都比原图实测值
 * （笑脸 +40、暗带 -46）弱不少，真机上可能淡到看不见，装了看效果再定。
 *
 * 2026-11-17 第 15 批（用户令"笑脸、暗带继续改"）：第 14 批砍过头了，两个都回调
 * 到中间值——笑脸 0.12→0.20（≈ +13，仍比原图 +40 淡，但看得见；亮带还是
 * 第 14 批放宽后的柔光，不是线）、暗带 0.15→0.25（≈ 暗 6%，仍比原图 -46 淡）。
 * 余弦没动。边缘透明度 0.25 把 rim 效果稀释了，想回到原图的强度得把强度推到
 * 1.0 以上（不可能），所以这两个值只能是"看得见的淡"，真机说了算。
 *
 * 2026-11-17 第 16 批（用户令"先改"）：jk-v90 真机验收——水滴看着像肥皂泡/
 * 扁圆盘，不像水滴。实测内部跟背景几乎一样（只差 0~15），因为背景是平滑渐变、
 * 折射位移（46px）扭不出可见差别，内部读着是平的；透明度砍过头也把"体"抽掉
 * 了。改动：TUNE_REFRACT_BEND 46→120（让内部采样到明显更远处的场、看出透镜
 * 的放大感），TUNE_ALPHA_MAX 0.25→0.30（稍微回一点、让折射和亮弧暗带有地方
 * 落）。120 是估计值——如果内部颜色跟周围对不上、看着像"贴了张别的图"，就
 * 往回调；如果还是平的，就再往上加。真机说了算。
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
    private val rimSoftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f
        color = Color.WHITE
        // 第 13 批：整圈白描边太抢戏（原图边缘不是整圈亮的），alpha 60→25。
        alpha = 25
    }

    // ---------- 第 7 批新增：待机时的 💤 提示（两个 "Z"/"z"，淡入淡出） ----------
    private val sleepPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = Typeface.DEFAULT_BOLD
        setShadowLayer(6f, 0f, 2f, 0x40000000)
    }
    // 初始就是 1：View 生来就是待机态（awake=false），冷启动第一帧就有 Z；
    // 之前是 0，setAwake(false) 又直接 return，冷启动要过一次干活→待机才冒出来。
    private var idleIndicatorAlpha = 1f
    private var idleIndicatorAnimator: ValueAnimator? = null

    // ---------- 动态水滴：待机缩小（睡觉的感觉）+ 原地动 ----------
    private var awake = false
    private var currentScale = IDLE_SCALE
    private var scaleAnimator: ValueAnimator? = null
    private var motionAnimator: ValueAnimator? = null

    // ---------- 第 9 批新增：按角度颤动的 Path（替换原来的 scaleX/Y 挤压） ----------
    // 待机时冻结在这个时间点上，水滴形状保持不动；干活时置回 null，相位继续用挂钟时间走。
    private var frozenWobbleTimeSec: Double? = null
    private val wobblePath = Path()
    private val wobblePts = FloatArray(WOBBLE_POINTS * 2)

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
        // 第 9 批：待机时把颤动相位冻结在这一刻（量测报告：待机时水滴形状不再颤动，
        // Z 消失、干活恢复后才继续晃），干活时置回 null 让相位继续走挂钟时间。
        frozenWobbleTimeSec = if (awake) null else SystemClock.uptimeMillis() / 1000.0
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
        // 第 9 批：这里不再做"挤压成椭圆"——量测报告说原版是边缘局部此起彼伏、整体
        // 大小基本不变，不是整个水滴一起变椭圆。真正的颤动形状改到 onDraw() 里按
        // 角度现算一个 Path（见 buildWobblePath()）；这里只管"待机缩小/干活胀回"的
        // 整体大小，中心不动、不平移。
        scaleX = currentScale
        scaleY = currentScale
    }

    /** 第 9 批新增：按角度算这一刻（或冻结时刻）水滴边缘该鼓出/缩进多少，3 组不成
     *  整数比的频率叠加在角度 θ 和时间 t 上——θ 上必须是整数倍频率（不然 θ=0 和
     *  θ=2π 接不上，边缘会有裂缝），t 上则要连续（不能用会循环重置的值，道理跟
     *  第 8 批一样）。返回的是"半径倍数"，1.0 就是不鼓不缩。 */
    private fun wobbleFactor(theta: Double, tSec: Double): Double {
        return 1.0 +
            TUNE_WOBBLE_AMP_1 * cos(TUNE_WOBBLE_K_1 * theta + TUNE_WOBBLE_SPEED_1 * tSec) +
            TUNE_WOBBLE_AMP_2 * cos(TUNE_WOBBLE_K_2 * theta + TUNE_WOBBLE_SPEED_2 * tSec) +
            TUNE_WOBBLE_AMP_3 * cos(TUNE_WOBBLE_K_3 * theta + TUNE_WOBBLE_SPEED_3 * tSec)
    }

    /** 把当前（或冻结的）颤动形状算成一个闭合 Path，供 onDraw() 画水滴本体用。
     *  相邻顶点间用 quadTo 经过中点，闭合曲线不会有折角（跟参考文章里的手法一样）。 */
    private fun buildWobblePath(cx: Float, cy: Float, baseR: Float, tSec: Double): Path {
        val n = WOBBLE_POINTS
        for (i in 0 until n) {
            val theta = i.toDouble() / n * Math.PI * 2
            val rr = baseR * wobbleFactor(theta, tSec).toFloat()
            wobblePts[i * 2] = cx + (cos(theta) * rr).toFloat()
            wobblePts[i * 2 + 1] = cy + (sin(theta) * rr).toFloat()
        }
        wobblePath.reset()
        val lastX = wobblePts[(n - 1) * 2]
        val lastY = wobblePts[(n - 1) * 2 + 1]
        wobblePath.moveTo((wobblePts[0] + lastX) / 2f, (wobblePts[1] + lastY) / 2f)
        for (i in 0 until n) {
            val curX = wobblePts[i * 2]
            val curY = wobblePts[i * 2 + 1]
            val nextIdx = (i + 1) % n
            val nextX = wobblePts[nextIdx * 2]
            val nextY = wobblePts[nextIdx * 2 + 1]
            wobblePath.quadTo(curX, curY, (curX + nextX) / 2f, (curY + nextY) / 2f)
        }
        wobblePath.close()
        return wobblePath
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
        // 第 8/9 批：这个动画器只当"帧泵"用，不提供相位——颤动相位改从挂钟时间连续算
        // （见 buildWobblePath()），这里的 0f..2π 和 duration 具体是多少不影响视觉。
        // 第 9 批修正：applyMotion() 现在只设一次性的整体缩放，稳态时 scaleX/Y 不再
        // 每帧变化，View.setScaleX/Y 发现值没变会跳过 invalidate——所以这里改成显式
        // 调用 invalidate()，不能再指望 setScaleX/Y 的副作用来驱动持续重绘。
        motionAnimator = ValueAnimator.ofFloat(0f, (Math.PI * 2).toFloat()).apply {
            duration = 6000L
            repeatCount = ValueAnimator.INFINITE
            interpolator = LinearInterpolator()
            addUpdateListener {
                invalidate()
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
         *  第 16 批（用户令"先改"）：46 太小了——水滴内部跟背景几乎一样（实测只差
         *  0~15），因为背景是平滑渐变、46px 的位移扭不出可见差别，内部读着是平的、
         *  像个肥皂泡。提到 120，让内部采样到明显更远处的场、看出透镜的放大感。 */
        const val TUNE_REFRACT_BEND = 120f
        /** 色散幅度（像素）：边缘红蓝分离的程度，越大越明显的"彩虹边"，调到 0 就是没有色散。 */
        const val TUNE_CHROMATIC_OFFSET = 2.2f
        /** 边缘增厚的起始位置，0..1（相对半径）：越小，变厚/变实的范围越宽，水滴显得越"肉"。 */
        const val TUNE_EDGE_SOFT_START = 0.80f
        /** 水滴中心的不透明度：第 9 批按量测报告（中心跟背景亮度只差 0~15/255）大幅调低，
         *  越小中心越透，越接近"看不见水滴只看见一圈边"。第 10 批再调低一档（0.08→0.05），
         *  真机反馈内部还是偏奶白、背景光斑透不出来。第 14 批（DeepSeek 审）再压到 0.01：
         *  内部要极度透明，玻璃感来自边缘和折射、不是中心那点白。 */
        const val TUNE_ALPHA_MIN = 0.01f
        /** 水滴边缘的不透明度：第 9 批也调低了一点（配合"中心更透"的整体方向），
         *  越大边缘越实。第 14 批（DeepSeek 审）0.50→0.25：边缘太实了，看着像实心球。
         *  第 16 批 0.25→0.30：0.25 时内部太空、没"体"，稍微回一点、让折射和
         *  亮弧暗带有地方落。 */
        const val TUNE_ALPHA_MAX = 0.30f
        /** 背景颜色透进水滴的浓度（这是"边缘"位置的浓度上限，中心会按
         *  `tintEdgeFactor` 再打折，见 main() 里的说明）：越大水滴边缘背景色越明显，
         *  越小越像一颗纯白/无色的玻璃珠。第 9 批调低了默认值——量测报告说中心
         *  几乎就是背景本身，之前的 0.6 让中心颜色太实。第 13 批再调低一档（0.3→0.2）：
         *  用户反馈"用力过猛"，内部要再透一点。第 14 批（DeepSeek 审）再压到 0.08。 */
        const val TUNE_TINT_STRENGTH = 0.08f
        /** 中心相对边缘的折射强度折扣：中心是这个值 × TUNE_TINT_STRENGTH，边缘是
         *  完整的 TUNE_TINT_STRENGTH——越小中心越透明，环境色反射越集中在边缘。 */
        const val TUNE_TINT_CENTER_FACTOR = 0.35f
        /** 第 13 批新增：底部"笑脸"亮弧的强度——一圈柔和的漫射亮斑（不是线），
         *  目标是最亮处比背景亮约 +40/255（Henry 原图实测峰值）。第 11 批删掉的
         *  rimPaint 弧是硬线，别恢复它；第 9 批的顶部亮弧方向反了，已整个删掉。
         *  第 14 批（DeepSeek 审）0.30→0.12：0.30 在图上看着像一根亮线，太实了；
         *  内外余弦也放宽一点（0.87→0.85、0.50→0.40），让亮带褪得更平滑。
         *  第 15 批：0.12 太淡了（0.12 × 边缘透明度 0.25 ≈ +8，远低于原图的
         *  +40），回调到 0.20（≈ +13），亮带还是柔光、不是线。 */
        const val TUNE_SMILE_STRENGTH = 0.20f
        /** 第 13 批新增：底部"笑脸"亮弧的半角，夹角余弦——cos(30°)≈0.87，数字越大
         *  （越接近 1）这段全亮的弧越窄；配合 `TUNE_SMILE_OUTER_COS` 控制往外的
         *  淡出范围，两个一起大致框出"4 点半到 7 点半"（约 60° 宽）这段弧。 */
        const val TUNE_SMILE_INNER_COS = 0.85f
        /** 第 13 批新增：底部"笑脸"亮弧彻底消失的夹角，cos(60°)≈0.50——比这个夹角更大
         *  （更偏向水滴两侧/上方）就完全没有底部亮弧了。 */
        const val TUNE_SMILE_OUTER_COS = 0.40f
        /** 第 13 批新增：顶部暗带的半角，夹角余弦——cos(20°)≈0.94。原图顶部暗带只有
         *  约 40° 宽（12 点附近最暗 -46），比第 9 批亮弧的 120° 窄得多，所以单独给
         *  暗带一对更窄的余弦，不要复用亮弧那对。 */
        const val TUNE_TOP_DARK_INNER_COS = 0.94f
        /** 第 13 批新增：顶部暗带彻底消失的夹角，cos(40°)≈0.77。 */
        const val TUNE_TOP_DARK_OUTER_COS = 0.77f
        /** 第 4 批新增、第 7 批调弱、第 10 批再调弱：球面漫反射强度。采样目标截图发现球体中央其实相当平，
         *  不是明显的明暗渐变，所以这个只给一丝若有若无的立体感，不是主要观感来源，
         *  默认值比第 4 批小了一大截。调到 0 就是完全没有这一丝渐变。 */
        const val TUNE_DIFFUSE_STRENGTH = 0.01f
        /** 第 7 批新增：贴着最外缘的窄暗带强度——采样目标截图发现边缘有一圈比球体内部和
         *  背景都暗的细边（玻璃全反射那种"黑边"感），这是让边缘看着"是玻璃"而不是
         *  "一张半透明贴纸"的关键细节。第 13 批起只在顶部出现（topDarkMask），
         *  底部被"笑脸"亮弧盖住的位置不再变暗。第 14 批（DeepSeek 审）0.4→0.15：
         *  左侧和顶部太黑了，暗带要更淡。第 15 批：0.15 太淡了（0.15 × 边缘透明度
         *  0.25 ≈ 只暗 4%，原图是 -46），回调到 0.25（≈ 暗 6%），还是比原图淡，
         *  但至少看得见。 */
        const val TUNE_DARK_BAND_STRENGTH = 0.25f
        /** 第 5 批新增：水滴跟着背景"光晕"一起发亮的强度——数值越大，光晕最强的那几秒
         *  水滴被照得越白越亮；调到 0 就是水滴亮度只跟折射出来的颜色有关，不跟光晕强度挂钩，
         *  退回第 4 批那种"颜色变但亮度不变"的效果。第 10 批从 0.35 砍到 0.15：它是内部
         *  加白的大头，真机反馈内部偏奶白、背景光斑透不出来。第 13 批再砍到 0.10。
         *  第 14 批（DeepSeek 审）再砍到 0.02：环境白光叠加太多，水滴自己发光
         *  变成实心球了。 */
        const val TUNE_AMBIENT_BOOST = 0.02f
        // 第 9 批：原来第 6/8 批"整体挤压成椭圆"的 TUNE_WOBBLE_AMPLITUDE 被下面这组
        // 按角度颤动的参数取代——量测报告说原版是边缘局部此起彼伏，不是整体变椭圆。
        // K 是角度上的"花瓣数"（必须是整数，不然 θ=0 和 θ=2π 接不上，边缘会裂开）；
        // AMP 是这组花瓣让半径多鼓/缩百分之多少；SPEED 是这组花瓣转动的快慢（弧度/秒，
        // 跟 K 搭配决定"看起来转得快不快"）。3 组 K/SPEED 都选了互不成整数比的数字，
        // 三组叠加后的总波形理论上不会精确重复（量测报告：没有固定周期）。
        /** 第 9 批新增：颤动花瓣数 1（鼓缩 2 瓣，整体偏椭圆感的那一层）。 */
        const val TUNE_WOBBLE_K_1 = 2.0
        /** 第 9 批新增：花瓣 1 的幅度——0.012 就是这一层单独最多让半径多鼓/缩 1.2%。
         *  第 10 批（2026-11-16 真机反馈“奇形怪状”）从 0.035 砍下来：原版每一帧都是
         *  干净正圆，颤动只在动的时候才感觉得到，7.8% 总量太大、每帧都看着像疙瘩。 */
        const val TUNE_WOBBLE_AMP_1 = 0.012
        /** 第 9 批新增：花瓣 1 转动的快慢（弧度/秒）。 */
        const val TUNE_WOBBLE_SPEED_1 = 0.23
        /** 第 9 批新增：颤动花瓣数 2（鼓缩 3 瓣，更碎一点的起伏）。 */
        const val TUNE_WOBBLE_K_2 = 3.0
        const val TUNE_WOBBLE_AMP_2 = 0.008
        const val TUNE_WOBBLE_SPEED_2 = 0.15
        /** 第 9 批新增：颤动花瓣数 3（鼓缩 5 瓣，最碎的那一层，贡献"局部此起彼伏"的细节）。 */
        const val TUNE_WOBBLE_K_3 = 5.0
        const val TUNE_WOBBLE_AMP_3 = 0.006
        const val TUNE_WOBBLE_SPEED_3 = 0.31
        /** 第 9 批新增：构成颤动轮廓 Path 用多少个点——点数越多边缘越顺滑，但每帧要多算
         *  几个三角函数；48 在普通手机上绰绰有余，没有卡顿的必要不建议调太高。 */
        const val WOBBLE_POINTS = 48

        /** 第 9 批新增：着色器自己那套"圆形裁切"的半径放宽倍数——颤动会让水滴边缘鼓出去
         *  最多 `TUNE_WOBBLE_AMP_1+2+3` 这么多（第 10 批起约 2.6%），如果着色器自己还按原来的
         *  半径硬裁切，鼓出去的部分会被着色器判成"圆外"直接透明，等于白鼓了。这个值要
         *  略大于 1+幅度总和，留点安全余量；改了上面几个 AMP 的话记得这个也要跟着调大。 */
        const val TUNE_SHAPE_MARGIN = 1.18f

        /** 第 9 批新增：内部折射环的强度——很淡的一圈，别调太大，抢了高光的戏就不像了。
         *  第 13 批再调弱一档（0.10→0.06）：原图内部几乎全透明，环要更淡。 */
        const val TUNE_RING_STRENGTH = 0.06f
        /** 第 9 批新增：折射环的横向半径（相对水滴半径，-1..1 本地坐标系下的尺度）。 */
        const val TUNE_RING_RADIUS_X = 0.55f
        /** 第 9 批新增：折射环的纵向半径——跟横向不一样，做成一个椭圆（量测报告原话：
         *  "一圈淡淡的椭圆形折射环"）。 */
        const val TUNE_RING_RADIUS_Y = 0.36f
        /** 第 9 批新增：折射环本身的粗细（越小越细、越像一条线；越大越像一片朦胧的光带）。 */
        const val TUNE_RING_WIDTH = 0.09f

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
            uniform float  uChromaticOffset;
            uniform float  uEdgeSoftStart;
            uniform float  uAlphaMin;
            uniform float  uAlphaMax;
            uniform float  uTintStrength;
            uniform float  uTintCenterFactor;
            uniform float  uSmileStrength;
            uniform float  uSmileInnerCos;
            uniform float  uSmileOuterCos;
            uniform float  uTopDarkInnerCos;
            uniform float  uTopDarkOuterCos;
            uniform float  uDiffuseStrength;
            uniform float  uAmbientBoost;
            uniform float  uDarkBandStrength;
            uniform float  uShapeMargin;
            uniform float  uRingStrength;
            uniform float  uRingRadiusX;
            uniform float  uRingRadiusY;
            uniform float  uRingWidth;
            uniform float2 uRingCenter;
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
                // 第 9 批：这里不再用严格的 lensRadius 裁切——水滴现在画的是 Kotlin 那边
                // 算出来的颤动 Path，边缘有的地方会鼓出 lensRadius 一截；uShapeMargin
                // 放宽这个硬裁切，真正决定"画不画"的是 Path 本身的光栅化范围，这里只是
                // 别在鼓出去的地方误判成"圆外"而提前判透明。
                if (dist > lensRadius * uShapeMargin) {
                    return half4(0.0);
                }
                float2 n2 = local / lensRadius;
                float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
                float2 refractPx = iOrigin + fragCoord + n2 * uBend * (1.0 - nz);

                float3 cR = fieldAt(refractPx + float2(uChromaticOffset, -uChromaticOffset * 0.5));
                float3 cG = fieldAt(refractPx);
                float3 cB = fieldAt(refractPx - float2(uChromaticOffset, uChromaticOffset * 0.5));
                float3 refracted = float3(cR.r, cG.g, cB.b);

                // 第 13 批：边缘改成方向性的——顶部暗带、底部亮弧（"笑脸"），跟 Henry 原图
                // 实测一致。原图量测：顶部（12 点，约 40° 宽）最暗 -46，底部（6 点，
                // 约 60° 宽）最亮 +40。第 9 批加的"顶部亮弧"方向反了（原图顶部是最暗
                // 的点），第 13 批去掉；第 11 批删掉的底部亮弧其实原图有，只是很柔
                // （+40，不是线），第 13 批以柔光的方式加回来。topDir 偏正上、略偏左；
                // bottomDir 偏正下、略偏右。
                float2 topDir = normalize(float2(-0.12, -0.99));
                float cosAngle = dot(n2, topDir);
                float topDarkMask = smoothstep(uTopDarkOuterCos, uTopDarkInnerCos, cosAngle);
                float2 bottomDir = normalize(float2(0.12, 0.99));
                float cosBottom = dot(n2, bottomDir);
                float smileMask = smoothstep(uSmileOuterCos, uSmileInnerCos, cosBottom);
                float edge = smoothstep(lensRadius * uEdgeSoftStart, lensRadius, dist);
                float smileBright = edge * smileMask;

                // 贴着最外缘的一圈窄暗带（玻璃全反射那种"黑边"）；第 13 批起只在顶部出现
                // （topDarkMask），底部被"笑脸"亮弧盖住的位置不再变暗。
                float darkBand = smoothstep(lensRadius * 0.90, lensRadius * 0.975, dist)
                    * (1.0 - smoothstep(lensRadius * 0.975, lensRadius, dist));

                // 漫反射：球体中央其实相当平（见第 7 批说明），这里只给一丝若有若无的立体感。
                float3 lightDir = normalize(float3(-0.45, -0.65, 0.6));
                float3 normal = float3(n2, nz);
                float diffuse = clamp(dot(normal, lightDir), 0.0, 1.0);

                // 第 9 批新增：内部折射环——一圈淡淡的椭圆，中心由 Kotlin 那边传进来、
                // 跟着背景光斑的位置缓慢漂移（见 drawBodyWithShader() 里 uRingCenter 的
                // 计算），强度很弱，不抢高光和亮弧的戏。
                float2 ringLocal = float2(
                    (n2.x - uRingCenter.x) / uRingRadiusX,
                    (n2.y - uRingCenter.y) / uRingRadiusY
                );
                float ringDist = abs(length(ringLocal) - 1.0);
                float ringGlow = smoothstep(uRingWidth, 0.0, ringDist) * uRingStrength;

                // 第 9 批新增：折射强度按"离中心多近"打折——中心只有 uTintCenterFactor
                // 这么多折射强度（接近透明，几乎就是背景本身），边缘是完整强度，环境色
                // 反射集中体现在靠近边缘的地方，不是整个水滴均匀染色。
                float tintEdgeFactor = mix(uTintCenterFactor, 1.0, edge);

                // 环境光耦合：水滴这个位置的"布"本身有多亮，光晕强的时候水滴跟着一起发白。
                float ambientLum = (refracted.r + refracted.g + refracted.b) / 3.0;
                // 第 13 批：不透明度不再因亮边额外叠加（顶部亮弧已删；底部"笑脸"是柔光，
                // 不是实边），就是中心到边缘的 mix。
                float alpha = clamp(mix(uAlphaMin, uAlphaMax, edge), 0.0, 1.0);
                float3 colorOut = refracted * uTintStrength * tintEdgeFactor
                    + float3(1.0, 1.0, 1.0) * smileBright * uSmileStrength
                    + float3(1.0, 1.0, 1.0) * diffuse * uDiffuseStrength
                    + float3(1.0, 1.0, 1.0) * ambientLum * uAmbientBoost
                    + float3(1.0, 1.0, 1.0) * ringGlow;
                colorOut *= (1.0 - darkBand * uDarkBandStrength * topDarkMask);
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

        // 影子：右下方一团软影（两条路径共用）。第 10 批按原版实测重调：影子中心最暗、
        // 偏右下，向外平滑变淡——之前是均匀的一团淡影，中心不够实。核心 alpha 0x26→0x38，
        // 中心再往右下挪一点（0.10r→0.18r，1.02r→1.08r），中间加一档过渡、影子铺得更开。
        // 第 14 批（DeepSeek 审）：影子再放淡、褪得更快——核心 0x30→0x18，中间
        // 0x14→0x08，中间点 0.45→0.35；铺得更开（渐变半径 0.9r→1.0r，椭圆左右
        // 各放宽 0.2r）。原图影子核心暗、但极快地向外消散。
        shadowPaint.shader = RadialGradient(
            cx + r * 0.18f, cy + r * 1.08f, r * 1.0f,
            intArrayOf(0x18000000, 0x08000000, 0x00000000),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(
            RectF(cx - r * 1.0f, cy + r * 0.7f, cx + r * 1.2f, cy + r * 1.6f),
            shadowPaint,
        )

        // 第 9 批：本体改成画"颤动 Path"而不是正圆——冻结时刻（待机）用 setAwake()
        // 里记下的时间，没冻结（干活）就用这一帧的挂钟时间，相位连续、没有接缝
        // （跟第 8 批"去机械感"的道理一样）。
        val tSec = frozenWobbleTimeSec ?: (SystemClock.uptimeMillis() / 1000.0)
        val path = buildWobblePath(cx, cy, r, tSec)

        if (useShader) drawBodyWithShader(canvas, path, cx, cy, r) else drawBodyLegacy(canvas, path, cx, cy, r)
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

    private fun drawBodyWithShader(canvas: Canvas, path: Path, cx: Float, cy: Float, r: Float) {
        val s = runtimeShader ?: return
        val paint = shaderPaint ?: return
        val phase = OriloBackgroundView.phaseAt(SystemClock.uptimeMillis())
        val top = OriloBackgroundView.hueColor(232f, 0.50f, 0.95f, phase.hueShift, phase.satScale)
        val mid = OriloBackgroundView.hueColor(232f, 0.68f, 0.91f, phase.hueShift, phase.satScale)
        val bot = OriloBackgroundView.hueColor(243f, 0.64f, 0.91f, phase.hueShift, phase.satScale)
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
            OriloBackgroundView.TUNE_BLOB_A_VAL, phase.hueShift, phase.satScale,
        )
        val blobBColor = OriloBackgroundView.hueColor(
            OriloBackgroundView.TUNE_BLOB_B_HUE, OriloBackgroundView.TUNE_BLOB_B_SAT,
            OriloBackgroundView.TUNE_BLOB_B_VAL, phase.hueShift, phase.satScale,
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
        s.setFloatUniform("uChromaticOffset", TUNE_CHROMATIC_OFFSET)
        s.setFloatUniform("uEdgeSoftStart", TUNE_EDGE_SOFT_START)
        s.setFloatUniform("uAlphaMin", TUNE_ALPHA_MIN)
        s.setFloatUniform("uAlphaMax", TUNE_ALPHA_MAX)
        s.setFloatUniform("uTintStrength", TUNE_TINT_STRENGTH)
        s.setFloatUniform("uTintCenterFactor", TUNE_TINT_CENTER_FACTOR)
        s.setFloatUniform("uSmileStrength", TUNE_SMILE_STRENGTH)
        s.setFloatUniform("uSmileInnerCos", TUNE_SMILE_INNER_COS)
        s.setFloatUniform("uSmileOuterCos", TUNE_SMILE_OUTER_COS)
        s.setFloatUniform("uTopDarkInnerCos", TUNE_TOP_DARK_INNER_COS)
        s.setFloatUniform("uTopDarkOuterCos", TUNE_TOP_DARK_OUTER_COS)
        s.setFloatUniform("uDiffuseStrength", TUNE_DIFFUSE_STRENGTH)
        s.setFloatUniform("uAmbientBoost", TUNE_AMBIENT_BOOST)
        s.setFloatUniform("uDarkBandStrength", TUNE_DARK_BAND_STRENGTH)
        s.setFloatUniform("uShapeMargin", TUNE_SHAPE_MARGIN)
        s.setFloatUniform("uRingStrength", TUNE_RING_STRENGTH)
        s.setFloatUniform("uRingRadiusX", TUNE_RING_RADIUS_X)
        s.setFloatUniform("uRingRadiusY", TUNE_RING_RADIUS_Y)
        s.setFloatUniform("uRingWidth", TUNE_RING_WIDTH)
        // 内部折射环的中心跟着背景光斑 A 的位置缓慢漂移（量测报告：环的位置跟着背景
        // 流场移形）；ax/ay 在 0.12~0.48 一带晃，这里重新映射到水滴本地 -1..1 坐标系
        // 里一个比较居中、留得出椭圆范围的小范围漂移。
        s.setFloatUniform("uRingCenter", (phase.ax - 0.30f) * 2.0f, (phase.ay - 0.26f) * 2.2f)
        canvas.drawPath(path, paint)
    }

    private fun drawBodyLegacy(canvas: Canvas, path: Path, cx: Float, cy: Float, r: Float) {
        // 体：清水滴——顶部带一点亮、边缘带一点淡淡的白，中间几乎透明；底色偏色时也不串色。
        // 第 13 批：顶部亮再压一档（0x59→0x33），跟"顶部最暗"的新认识对齐。
        bodyPaint.shader = RadialGradient(
            cx - r * 0.3f, cy - r * 0.45f, r * 1.5f,
            intArrayOf(0x33FFFFFF, 0x14FFFFFF, 0x08FFFFFF, 0x12FFFFFF),
            floatArrayOf(0f, 0.35f, 0.7f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawPath(path, bodyPaint)
    }

    private fun drawHighlightsAndRim(canvas: Canvas, cx: Float, cy: Float, r: Float) {
        // 大高光：右上一道斜的亮斑。第 14 批（DeepSeek 审）：原来 RadialGradient 的
        // 柔光像一坨奶油，没有玻璃锐利的反光感。改成硬核 + 极速衰减（前 15% 是纯白
        // 硬核），椭圆压扁一点（0.45→0.3），更像条形光源的倒影。
        canvas.save()
        canvas.translate(cx + r * 0.42f, cy - r * 0.48f)
        canvas.rotate(-35f)
        canvas.scale(1f, 0.3f)
        highlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.3f,
            intArrayOf(0xFFFFFFFF.toInt(), 0x40FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.15f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.3f, highlightPaint)
        canvas.restore()
        // 小高光：左下一小点。第 14 批：跟大高光同一套硬核 + 极速衰减。
        canvas.save()
        canvas.translate(cx - r * 0.45f, cy + r * 0.42f)
        canvas.rotate(-35f)
        canvas.scale(1f, 0.6f)
        smallHighlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.14f,
            intArrayOf(0xB3FFFFFF.toInt(), 0x30FFFFFF, 0x00FFFFFF),
            floatArrayOf(0f, 0.15f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.14f, smallHighlightPaint)
        canvas.restore()
        // 亮边：整圈淡淡描边（第 13 批 alpha 60→25）。第 11 批删掉的下半圈硬线亮弧
        // 别恢复——底部"笑脸"亮弧在着色器里（smileMask，柔光）；顶部亮边已去掉
        // （原图顶部最暗）。
        canvas.drawCircle(cx, cy, r - 1f, rimSoftPaint)
    }
}
