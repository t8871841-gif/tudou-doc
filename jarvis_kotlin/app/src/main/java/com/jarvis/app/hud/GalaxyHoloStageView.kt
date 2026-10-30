package com.jarvis.app.hud

import android.content.Context
import android.graphics.Camera
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.util.AttributeSet
import android.view.Choreographer
import android.view.View
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/**
 * 设计稿确认版舞台：待机是一团按螺旋臂排列、缓缓自转的等离子星系；一收到指令（state 从
 * idle 切到非 idle）就坍缩+炸裂成一块真 3D 透视倾斜的全息面板，内容随聆听/思考/应答切换；
 * 回到 idle 时同一套动画反着放一遍，星系重新展开。2026-10-27 用户令复活（取代已删的银河照片舞台），
 * 星场与坍缩动画按包里原样；不含霓虹环，悬浮球不动。
 *
 * 3D 效果用 [android.graphics.Camera] 在 2D Canvas 上做透视投影（Android 做"卡片翻转"
 * 那类伪 3D 效果的标准手法），不是真三维引擎——旋转角度小的时候看着跟真 3D 没什么差别，
 * 这里用到的倾斜角度都不大（面板 16°/-10°，地面网格 78°），实测应该够用。
 *
 * 过渡动画只有一条"进度" [t]：0 = 星系满屏，1 = 全息面板满屏，中间自动分三段
 * （星系收缩→冲击波→面板展开），方向不管开合都走同一套公式，不用维护一堆状态枚举。
 *
 * 第二梯队批七：按用户反馈"星团太小太密集""聆听/思考/应答 3D 效果太简陋"重做了两块——
 * 螺旋臂半径从只占逻辑空间的 1/13（核心发光球比整条臂还大，等于整个星系糊在发光核心里）
 * 放大到接近舞台半径，密度自然摊开；聆听/应答换成带顶面高光/侧面/地面倒影的伪 3D 柱体、
 * 沿浅拱形排布，思考换成绕轴旋转、近大远小的全息粒子环（简易参数化 3D 投影）。
 * 具体改动点和自查记录见 V3_CHANGES.md。
 */
class GalaxyHoloStageView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    var mode: () -> String = { "idle" }
    var micLevel: () -> Float = { 0f }

    // ---------- 时间基准 ----------

    private var startNanos = 0L
    private fun tSec(): Float {
        if (startNanos == 0L) startNanos = System.nanoTime()
        return (System.nanoTime() - startNanos) / 1_000_000_000f
    }

    // ---------- 星系粒子（开机算一次，不每帧重算） ----------

    private data class Particle(val x: Float, val y: Float, val size: Float, val alpha: Float, val color: Int)

    /** 逻辑坐标系 0..260，画的时候按舞台半径缩放、平移到实际屏幕位置。 */
    private val particles: List<Particle> by lazy { generateGalaxy() }

    // ---------- 背景星场（银河全景效果，应用户要求把星星数量加大） ----------

    private data class Star(val x: Float, val y: Float, val size: Float, val baseAlpha: Float, val twSpeed: Float, val twPhase: Float, val warm: Boolean)

    /** 跟星系粒子是两套独立系统：星场是固定的"天幕背景"，不随星系坍缩/自转一起动，
     * 星系在它前面收缩展开，视觉上有层次（近处星系、远处星空）。数量从几颗点缀加到
     * 400+ 颗，并且往一条斜向"亮带"里偏置密度，接近银河系全景那种带状亮区的感觉。 */
    private val stars: List<Star> by lazy { generateStars() }

    private fun generateStars(): List<Star> {
        val rnd = Random(42)
        val list = ArrayList<Star>(460)
        repeat(460) {
            val inBand = rnd.nextFloat() < 0.55f
            val x: Float
            val y: Float
            if (inBand) {
                // 沿对角线聚出一条亮带，两侧用较大的扩散半径让带状边缘柔和、不生硬
                val bt = rnd.nextFloat()
                val bx = -50f + bt * 360f
                val by = -50f + bt * 360f
                val spread = 75f
                x = bx + (rnd.nextFloat() * 2 - 1) * spread
                y = by + (rnd.nextFloat() * 2 - 1) * spread * 0.55f
            } else {
                x = rnd.nextFloat() * 360f - 50f
                y = rnd.nextFloat() * 360f - 50f
            }
            val sizeRoll = rnd.nextFloat()
            val size = when {
                sizeRoll < 0.06f -> 1.7f + rnd.nextFloat() * 0.9f // 少量亮星点
                sizeRoll < 0.28f -> 1.0f + rnd.nextFloat() * 0.5f
                else -> 0.35f + rnd.nextFloat() * 0.5f
            }
            val alpha = 0.12f + rnd.nextFloat() * 0.5f
            val speed = 0.4f + rnd.nextFloat() * 1.8f
            val phase = rnd.nextFloat() * (2 * PI.toFloat())
            val warm = rnd.nextFloat() < 0.22f
            list.add(Star(x, y, size, alpha, speed, phase, warm))
        }
        return list
    }

    private fun drawStars(canvas: Canvas, cx: Float, cy: Float, r: Float, tt: Float) {
        val scaleBase = r / 130f
        canvas.save()
        canvas.translate(cx, cy)
        canvas.scale(scaleBase, scaleBase)
        paint.reset(); paint.isAntiAlias = true
        for (s in stars) {
            val twinkle = 0.55f + 0.45f * sin(tt * s.twSpeed + s.twPhase)
            val a = (s.baseAlpha * twinkle).coerceIn(0f, 1f)
            paint.color = if (s.warm) HudPalette.withAlpha(0xFFFFE3B0.toInt(), a) else HudPalette.withAlpha(Color.WHITE, a)
            canvas.drawCircle(s.x - 130f, s.y - 130f, s.size, paint)
        }
        canvas.restore()
    }

    /**
     * 用户反馈"银河系星团太小、太密集"后重做：原版对数螺旋参数 a=3.2/b=0.26/maxTheta=4.3
     * 算出来外臂半径只有约 9.8（逻辑坐标系是 0..260，舞台半径对应 130），核心发光球却画了
     * 13 个单位——核心光斑比整条螺旋臂还大，等于把整个星系挤在发光核心里糊成一团，这才是
     * "又小又密"的真正原因，不是粒子数太多。现在把臂展半径拉到跟舞台半径匹配（约 120，
     * 只留小量边距），螺旋也从"不到一圈"的紧臂改成一圈多的舒展臂形，同样数量级的粒子
     * 摊开在大十几倍的面积上，密度自然就下来了；额外还是加了点粒子让臂形不至于太稀疏。
     */
    private fun generateGalaxy(): List<Particle> {
        val rnd = Random(7)
        val list = ArrayList<Particle>(360)
        fun lerpF(a: Float, b: Float, t: Float) = a + (b - a) * t
        fun arm(offset: Float, n: Int, a: Float = 6f, b: Float = 0.47f, maxTheta: Float = 6.5f, jitter: Float = 0.16f) {
            for (i in 0 until n) {
                val tt = i / (n - 1f)
                val theta = tt * maxTheta
                val r = a * exp(b * theta)
                // 抖动幅度按半径等比例缩放（原来是定值抖动，半径一放大，边缘粒子会被甩得
                // 过散；改成跟着 r 走，臂形轮廓在外圈也还认得出来）。
                val rr = r * (1 + (rnd.nextFloat() * 2 - 1) * jitter * (0.3f + tt))
                val ang = theta + offset + (rnd.nextFloat() * 2 - 1) * 0.04f
                val x = 130 + rr * cos(ang)
                val y = 130 + rr * sin(ang)
                // 第二梯队批九：配色从三段（橙核→紫→靛蓝）改四段，外圈再过渡到青蓝，
                // 对齐参考图里"旋臂外侧发青发蓝的尘埃雾"，不是一路暗下去的纯靛蓝。
                val color = when {
                    tt < 0.3f -> HudPalette.lerp(HudPalette.GALAXY_CORE, HudPalette.GALAXY_MID, tt / 0.3f)
                    tt < 0.6f -> HudPalette.lerp(HudPalette.GALAXY_MID, HudPalette.GALAXY_OUTER, (tt - 0.3f) / 0.3f)
                    else -> HudPalette.lerp(HudPalette.GALAXY_OUTER, HudPalette.GALAXY_TEAL, (tt - 0.6f) / 0.4f)
                }
                val size = max(0.5f, lerpF(3.0f, 0.8f, tt) + (rnd.nextFloat() * 2 - 1) * 0.35f)
                val alpha = (lerpF(0.95f, 0.22f, tt) + (rnd.nextFloat() * 2 - 1) * 0.05f).coerceIn(0.1f, 1f)
                list.add(Particle(x, y, size, alpha, color))
            }
        }
        arm(0f, 78); arm(2.09f, 78); arm(4.19f, 70)
        // 臂与臂之间撒一层稀疏的"弥散盘"粒子（半径覆盖到臂展的 1.15 倍左右），
        // 让整团看着是个连续的盘面而不是三条光秃秃的线。
        repeat(130) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            // 不是均匀分布半径——越靠外越稀疏（看起来更像真实星系盘的密度衰减）。
            val r = 10 + (rnd.nextFloat().let { it * it }) * 118
            val x = 130 + r * cos(ang)
            val y = 130 + r * sin(ang)
            val size = 0.35f + rnd.nextFloat() * 0.55f
            val alpha = (0.06f + rnd.nextFloat() * 0.16f) * (1f - r / 135f).coerceIn(0.25f, 1f)
            // 弥散盘颜色也跟着半径在靛蓝→青蓝之间过渡，不再是单一的 GALAXY_OUTER 纯色。
            val diskColor = HudPalette.lerp(HudPalette.GALAXY_OUTER, HudPalette.GALAXY_TEAL, (r / 118f).coerceIn(0f, 1f))
            list.add(Particle(x, y, size, alpha, diskColor))
        }
        // 最外层稀薄光晕（原来固定在 70..128，现在跟着新的臂展半径走，紧贴在弥散盘外侧），
        // 颜色用青蓝而不是靛蓝——这是离核心最远的一圈，该是色温最冷的地方。
        repeat(55) {
            val ang = rnd.nextFloat() * 2 * PI.toFloat()
            val r = 118 + rnd.nextFloat() * 20
            val x = 130 + r * cos(ang)
            val y = 130 + r * sin(ang)
            list.add(Particle(x, y, 0.4f + rnd.nextFloat() * 0.6f, 0.05f + rnd.nextFloat() * 0.14f, HudPalette.GALAXY_TEAL))
        }
        return list
    }

    // ---------- 星系 ↔ 全息面板 过渡进度 ----------

    private var lastWantsHolo = false
    private var transitionFromT = 0f
    private var transitionStartMs = 0L
    private var t = 0f // 0=星系，1=全息面板
    private val TRANSITION_MS = 700L

    private fun smoothstep(edge0: Float, edge1: Float, x: Float): Float {
        val tt = ((x - edge0) / (edge1 - edge0)).coerceIn(0f, 1f)
        return tt * tt * (3 - 2 * tt)
    }

    private fun easeInOutCubic(x: Float): Float =
        if (x < 0.5f) 4 * x * x * x else 1 - (-2 * x + 2).let { it * it * it } / 2

    private fun easeOutBack(x: Float): Float {
        val c1 = 1.70158f; val c3 = c1 + 1
        val xm1 = x - 1
        return 1 + c3 * xm1 * xm1 * xm1 + c1 * xm1 * xm1
    }

    private fun stepTransition() {
        val now = System.currentTimeMillis()
        val wantsHolo = mode() != "idle"
        if (wantsHolo != lastWantsHolo) {
            lastWantsHolo = wantsHolo
            transitionFromT = t
            transitionStartMs = now
        }
        val target = if (wantsHolo) 1f else 0f
        val elapsed = now - transitionStartMs
        val raw = (elapsed / TRANSITION_MS.toFloat()).coerceIn(0f, 1f)
        val eased = easeInOutCubic(raw)
        t = transitionFromT + (target - transitionFromT) * eased
    }

    // ---------- 画 ----------

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val matrix = Matrix()
    private val camera = Camera()

    private val choreographer = Choreographer.getInstance()
    private var running = false
    private val frameCb = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running) return
            invalidate()
            choreographer.postFrameCallback(this)
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        running = true
        choreographer.postFrameCallback(frameCb)
    }

    override fun onDetachedFromWindow() {
        running = false
        choreographer.removeFrameCallback(frameCb)
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        stepTransition()
        val tt = tSec()
        val cx = width / 2f
        val cy = height / 2f
        // 第二梯队批九：0.36 → 0.42，配合粒子层新的臂展半径，整体再占多一点舞台空间。
        val r = min(width, height) * 0.42f
        if (r <= 0f) return

        // 背景星场：固定天幕，不随星系坍缩/自转，星系在它前面开合，有近景/远景的层次感。
        drawStars(canvas, cx, cy, r, tt)

        // 星系这一段：t 从 0 收缩到约 0.35 就该消失了（坍缩阶段），之后不用再画。
        val galaxyShrink = smoothstep(0f, 0.35f, t)
        if (galaxyShrink < 1f) drawGalaxy(canvas, cx, cy, r, tt, 1f - galaxyShrink)

        // 冲击波：坍缩快结束、全息面板还没完全出现这段时间窗口内画，开合方向通用。
        if (t in 0.02f..0.98f) drawShock(canvas, cx, cy, r, smoothstep(0.25f, 0.85f, t))

        // 全息面板：t 从约 0.5 开始展开。
        val holoReveal = smoothstep(0.5f, 1f, t)
        if (holoReveal > 0f) drawHologram(canvas, cx, cy, r, tt, holoReveal)
    }

    // ---------- 星系 ----------

    // 第二梯队批九：星系的"观察视角"倾角——参考图是斜着俯瞰一个盘面（椭圆形，不是正圆），
    // 不是正对着盘面拍的。固定不随自转变化（自转是盘面自己转，观察角度不变）。
    private val GALAXY_TILT_DEG = -28f  // 整体对角倾斜角度
    private val GALAXY_SQUASH = 0.58f   // 垂直方向压扁比例，压扁得越多看起来倾角越大

    private fun drawGalaxy(canvas: Canvas, cx: Float, cy: Float, r: Float, tt: Float, alphaMul: Float) {
        val scaleBase = r / 130f // 逻辑 260 空间 -> 实际半径
        val collapseScale = 1f - smoothstep(0f, 0.35f, t) * 0.92f // 坍缩时整体缩小
        val scale = scaleBase * collapseScale
        val rotationDeg = (tt / 70f) * 360f // 70 秒转一圈，很慢

        canvas.save()
        canvas.translate(cx, cy)
        // 先定住观察倾角（倾斜 + 压扁成椭圆），盘面自转在这个倾斜之后的坐标系里转，
        // 这样转起来是"盘面在斜着转"，不是整张图在傻乎乎地平面旋转。
        canvas.rotate(GALAXY_TILT_DEG)
        canvas.scale(1f, GALAXY_SQUASH)
        canvas.rotate(rotationDeg)
        canvas.scale(scale, scale)
        for (p in particles) {
            paint.reset(); paint.isAntiAlias = true
            // 两层画法模拟"绒毛状星云"的辉光感，不用 BlurMaskFilter（之前批次已经把
            // HudStageView 里唯一一处模糊效果换成了渐变，这里延续同样的取舍——软件模糊
            // 图层开销不低，上百个粒子每帧都走一遍软件模糊层对低端机不友好）：外层画一个
            // 更大更淡的光晕，内层叠一个更小更亮的核心点，两层都是纯色圆（没有额外分配
            // Shader），比单层硬边小圆点更有星云绒毛感，开销只是多了一倍的 drawCircle。
            paint.color = HudPalette.withAlpha(p.color, p.alpha * 0.32f * alphaMul)
            canvas.drawCircle(p.x - 130f, p.y - 130f, p.size * 2.8f, paint)
            paint.color = HudPalette.withAlpha(p.color, p.alpha * alphaMul)
            canvas.drawCircle(p.x - 130f, p.y - 130f, p.size, paint)
        }
        canvas.restore()

        // 核心脉动光——跟旋臂用同一套倾角+压扁，核心光斑画出来才会是和整个盘面一致的
        // 椭圆形，不是一个圆圆的光斑悬在一个斜椭圆星系中间那种违和感。
        val pulse = 0.75f + 0.25f * abs(sin(tt * (2 * PI.toFloat() / 3.6f)))
        val coreR = 13f * scaleBase * collapseScale * pulse
        canvas.save()
        canvas.translate(cx, cy)
        canvas.rotate(GALAXY_TILT_DEG)
        canvas.scale(1f, GALAXY_SQUASH)
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(
            0f, 0f, max(1f, coreR * 2.2f),
            intArrayOf(
                HudPalette.withAlpha(Color.parseColor("#fff6e0"), alphaMul),
                HudPalette.withAlpha(HudPalette.GALAXY_CORE, 0.8f * alphaMul),
                HudPalette.withAlpha(0xFFFF6A3D.toInt(), 0.5f * alphaMul),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 0.65f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, max(1f, coreR * 2.2f), paint)
        paint.shader = null
        canvas.restore()
    }

    // ---------- 冲击波 ----------

    private fun drawShock(canvas: Canvas, cx: Float, cy: Float, r: Float, shockT: Float) {
        if (shockT <= 0f || shockT >= 1f) return
        val radius = 5f + (r * 2.4f) * easeOutBack(shockT).coerceIn(0f, 1.3f)
        val alpha = (1f - shockT).coerceIn(0f, 1f)
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = max(1f, 3f * alpha)
        paint.color = HudPalette.withAlpha(HudPalette.GALAXY_MID, alpha * 0.9f)
        canvas.drawCircle(cx, cy, radius, paint)
    }

    // ---------- 全息面板（Camera 伪 3D） ----------

    private fun drawHologram(canvas: Canvas, cx: Float, cy: Float, r: Float, tt: Float, reveal: Float) {
        val panelW = r * 1.85f
        val panelH = r * 1.2f
        val floatOffset = 6f * sin(tt * (2 * PI.toFloat() / 4.2f)) // 轻微漂浮
        val panelCy = cy + floatOffset
        val scale = 0.6f + 0.4f * easeOutBack(reveal).coerceIn(0f, 1.15f)

        // 地面网格：78° 近乎贴地的透视
        drawTiltedPlane(canvas, cx, cy + panelH * 0.62f, 78f, 0f) { local ->
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            val gridColor = HudPalette.colorFor(mode())
            var gx = -panelW
            while (gx <= panelW) {
                val distFrac = 1f - (abs(gx) / panelW).coerceIn(0f, 1f)
                paint.color = HudPalette.withAlpha(gridColor, 0.28f * distFrac * reveal)
                local.drawLine(gx, -panelH, gx, panelH, paint)
                gx += panelW / 7f
            }
            var gy = -panelH
            while (gy <= panelH) {
                val distFrac = 1f - (abs(gy) / panelH).coerceIn(0f, 1f)
                paint.color = HudPalette.withAlpha(gridColor, 0.28f * distFrac * reveal)
                local.drawLine(-panelW, gy, panelW, gy, paint)
                gy += panelH / 5f
            }
        }

        // 面板本体：16°/-10° 倾斜
        drawTiltedPlane(canvas, cx, panelCy, 16f, -10f) { local ->
            val accent = HudPalette.colorFor(mode())
            val hw = panelW * scale / 2f
            val hh = panelH * scale / 2f

            paint.reset(); paint.isAntiAlias = true
            paint.shader = RadialGradient(
                0f, 0f, max(hw, hh) * 1.3f,
                intArrayOf(HudPalette.withAlpha(accent, 0.22f * reveal), HudPalette.withAlpha(HudPalette.GALAXY_OUTER, 0.08f * reveal)),
                null, Shader.TileMode.CLAMP
            )
            local.drawRoundRect(-hw, -hh, hw, hh, 14f, 14f, paint)
            paint.shader = null

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = HudPalette.withAlpha(accent, 0.8f * reveal)
            local.drawRoundRect(-hw, -hh, hw, hh, 14f, 14f, paint)

            // 扫描线
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1f
            var ly = -hh + 6f
            while (ly < hh) {
                paint.color = HudPalette.withAlpha(accent, 0.1f * reveal)
                local.drawLine(-hw, ly, hw, ly, paint)
                ly += 8f
            }

            if (reveal > 0.5f) drawPanelContent(local, mode(), accent, hw, hh, tt, reveal)
        }
    }

    /** 用 Camera 把一块本地坐标系（原点在中心）按给定角度"贴"到 (cx,cy)，回调里用变换后的 canvas 画本地内容。 */
    private inline fun drawTiltedPlane(
        canvas: Canvas, cx: Float, cy: Float,
        rotateXDeg: Float, rotateYDeg: Float, draw: (Canvas) -> Unit
    ) {
        camera.save()
        camera.rotateX(rotateXDeg)
        camera.rotateY(rotateYDeg)
        camera.getMatrix(matrix)
        camera.restore()
        // 本地内容都是围绕 (0,0) 这个"平面中心"画的，不用额外 pivot 调整，直接平移到屏幕位置。
        matrix.postTranslate(cx, cy)
        canvas.save()
        canvas.concat(matrix)
        draw(canvas)
        canvas.restore()
    }

    /**
     * 面板里的内容——用户反馈"聆听/思考/应答的 3D 效果太简陋"后重做：
     * 原版思考是 5 根横向滚动的虚线（纯 2D 效果，平面上几条线在跑），聆听/应答是经典
     * 条形声波图（同样是贴在平面上的 2D 柱子，谈不上立体）。两处都换了：
     * - 思考：换成一圈绕轴旋转的全息粒子环（参数化简易 3D——每颗点算出 x/y/深度 z，
     *   深度决定缩放和透明度、按深度从远到近排序绘制，近大远小、近亮远暗），看起来是真的
     *   在立体空间里转，而不是贴图滚动；中心留一个脉动核心点呼应"在运算"的感觉。
     * - 聆听/应答：声波条从纯描边线条改成带"顶面高光 + 柱体侧光 + 底部倒影"的伪 3D 柱体
     *   （经典等轴测柱状图画法：亮的顶帽 + 渐暗的柱身 + 对称淡化的地面反光），外加整排
     *   柱子沿一条浅拱形排布而不是死板的直线，叠加面板本身的 Camera 倾斜，看起来有
     *   明显进深，不再是一排平贴在纸面上的竖线。
     */
    private fun drawPanelContent(canvas: Canvas, state: String, accent: Int, hw: Float, hh: Float, tt: Float, reveal: Float) {
        when (state) {
            "thinking" -> drawThinkingOrb(canvas, accent, hw, hh, tt, reveal)
            else -> drawVoiceBars3D(canvas, state, accent, hw, hh, tt, reveal) // listening / speaking
        }
    }

    private data class OrbitDot(val px: Float, val py: Float, val scale: Float, val alpha: Float, val depth: Float)

    private fun drawThinkingOrb(canvas: Canvas, accent: Int, hw: Float, hh: Float, tt: Float, reveal: Float) {
        val ringR = min(hw, hh) * 0.62f
        val tiltRad = Math.toRadians(58.0).toFloat() // 环面相对视线的倾角，决定投影后的椭圆扁率
        val sinT = sin(tiltRad); val cosT = cos(tiltRad)
        val n = 26
        val spin = tt * 0.85f // 环自转速度
        val dots = ArrayList<OrbitDot>(n)
        for (i in 0 until n) {
            val ang = spin + (i / n.toFloat()) * 2 * PI.toFloat()
            val x3 = cos(ang) * ringR
            val z3 = sin(ang) * ringR // 未倾斜前的"深度"分量
            val y2 = z3 * sinT // 倾斜后，深度分量有一部分折进屏幕纵向偏移
            val depth = z3 * cosT // 倾斜后剩下的才是真正的"远近"，用来定缩放/透明度/画序
            val perspective = (1f + depth / (ringR * 2.6f)).coerceIn(0.45f, 1.55f)
            dots.add(OrbitDot(x3 * perspective, y2 * perspective * 0.9f, perspective, 0f, depth))
        }
        // 远的先画、近的后画——保证"近处挡住远处"这个基本的立体叠压关系。
        dots.sortBy { it.depth }
        val maxDepth = ringR; val minDepth = -ringR
        for (d in dots) {
            val depthFrac = ((d.depth - minDepth) / (maxDepth - minDepth)).coerceIn(0f, 1f)
            val alpha = (0.2f + 0.75f * depthFrac) * reveal
            val size = (2.6f * d.scale).coerceIn(0.9f, 4.2f)
            paint.reset(); paint.isAntiAlias = true
            paint.color = HudPalette.withAlpha(accent, alpha)
            canvas.drawCircle(d.px, d.py, size, paint)
            if (depthFrac > 0.55f) { // 只有靠近"镜头"的几颗才叠一层柔光，强化近大远小的体积感
                paint.color = HudPalette.withAlpha(Color.WHITE, (depthFrac - 0.55f) * 0.5f * reveal)
                canvas.drawCircle(d.px, d.py, size * 1.8f, paint)
            }
        }
        // 环下方的椭圆轨迹淡线，给这圈粒子一个"轨道"的参照，强化是绕着转而不是乱飘。
        paint.reset(); paint.isAntiAlias = true
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 1f
        paint.color = HudPalette.withAlpha(accent, 0.18f * reveal)
        canvas.save()
        canvas.scale(1f, sinT.coerceIn(0.25f, 1f))
        canvas.drawCircle(0f, 0f, ringR, paint)
        canvas.restore()
        // 中心脉动核心：呼应"正在运算"，呼吸频率比待机星系核心快一点，区分开两种状态。
        val pulse = 0.7f + 0.3f * abs(sin(tt * (2 * PI.toFloat() / 1.1f)))
        val coreR = min(hw, hh) * 0.09f * pulse
        paint.reset(); paint.isAntiAlias = true
        paint.shader = RadialGradient(
            0f, 0f, max(1f, coreR * 2.4f),
            intArrayOf(HudPalette.withAlpha(Color.WHITE, 0.9f * reveal), HudPalette.withAlpha(accent, 0.5f * reveal), Color.TRANSPARENT),
            floatArrayOf(0f, 0.4f, 1f), Shader.TileMode.CLAMP
        )
        canvas.drawCircle(0f, 0f, max(1f, coreR * 2.4f), paint)
        paint.shader = null
    }

    private fun drawVoiceBars3D(canvas: Canvas, state: String, accent: Int, hw: Float, hh: Float, tt: Float, reveal: Float) {
        val micL = if (state == "listening") micLevel() else 0f
        val n = 11
        val baseline = hh * 0.34f // 柱子从这条线往上长、往下倒影，不是原来居中上下对称画
        val barW = (hw * 1.5f) / n * 0.46f
        for (i in 0 until n) {
            val f = i / (n - 1f)
            val lx = -hw * 0.75f + (hw * 1.5f) * f
            // 浅拱形排布：中间的柱子底座略微抬高，边上的略微压低，叠加面板本身的倾斜，
            // 看起来像一排立在弧形舞台上的灯柱，而不是钉在一条直线上。
            val arcLift = -hh * 0.07f * (1f - (f - 0.5f) * (f - 0.5f) * 4f)
            val by = baseline + arcLift
            val base = 0.22f + 0.55f * abs(sin(tt * (3f + i % 4) + i * 0.6f))
            val energy = (if (state == "listening") (base * 0.5f + micL * 0.6f) else base).coerceIn(0.08f, 1f)
            val barH = hh * 0.92f * energy
            val depthShift = barW * 0.34f // 柱子"厚度"在水平方向上的偏移量，模拟侧面可见的立体柱体

            // 柱体侧面（偏暗）：从顶到底整条侧边，给柱子一个"有厚度"的轮廓
            paint.reset(); paint.isAntiAlias = true
            paint.style = Paint.Style.FILL
            val sidePath = android.graphics.Path().apply {
                moveTo(lx + barW / 2f, by)
                lineTo(lx + barW / 2f + depthShift, by - depthShift * 0.6f)
                lineTo(lx + barW / 2f + depthShift, by - barH - depthShift * 0.6f)
                lineTo(lx + barW / 2f, by - barH)
                close()
            }
            paint.color = HudPalette.withAlpha(accent, 0.28f * reveal)
            canvas.drawPath(sidePath, paint)

            // 柱体正面：垂直渐变（亮顶→暗底），比纯色描边更有"打光"的体积感
            paint.shader = android.graphics.LinearGradient(
                lx, by - barH, lx, by,
                HudPalette.withAlpha(Color.WHITE, (0.55f + 0.35f * energy) * reveal),
                HudPalette.withAlpha(accent, (0.35f + 0.3f * energy) * reveal),
                Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(lx - barW / 2f, by - barH, lx + barW / 2f, by, barW * 0.3f, barW * 0.3f, paint)
            paint.shader = null

            // 柱顶高光帽：一小段更亮的圆角矩形，模拟顶面受光
            paint.color = HudPalette.withAlpha(Color.WHITE, (0.4f + 0.3f * energy) * reveal)
            canvas.drawRoundRect(lx - barW / 2f, by - barH, lx + barW / 2f, by - barH + barW * 0.4f, barW * 0.3f, barW * 0.3f, paint)

            // 地面倒影：柱子在基线下方的淡化镜像，强化"立在平面上"的空间感
            paint.shader = android.graphics.LinearGradient(
                lx, by, lx, by + barH * 0.4f,
                HudPalette.withAlpha(accent, 0.22f * reveal), Color.TRANSPARENT, Shader.TileMode.CLAMP
            )
            canvas.drawRoundRect(lx - barW / 2f, by, lx + barW / 2f, by + barH * 0.4f, barW * 0.3f, barW * 0.3f, paint)
            paint.shader = null
        }
    }
}
