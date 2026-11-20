package com.jarvis.app.orilog

import android.content.Context
import android.graphics.PixelFormat
import android.opengl.GLSurfaceView
import android.util.AttributeSet

/**
 * Orilo 路线重做的水滴 View（第 36 批起）。
 *
 * 跟旧的 OriloDropletView（Android View + AGSL RuntimeShader）完全分开，
 * 放在 orilog/ 文件夹里，不混在一起。
 *
 * 架构（照 Orilo 原版路线，不是抄它的代码）：
 * - 原生 OpenGL ES，GLSurfaceView + 自定义 GLSL。
 * - 全屏大三角（无 VBO），整颗水滴在片元着色器里逐像素算。
 * - 背景先渲染到 FBO 纹理，水滴着色器采样真实背景纹理做折射。
 * - 场景参数每帧 CPU 算好、经 uniform 传进去。
 *
 * WebGL 不用（用户明确）。
 *
 * 第 39 批：改成透明 Surface（EGL 带 alpha + TRANSLUCENT + ZOrderOnTop），
 * 水滴外全透明，让下面真正的背景（OriloBackgroundView）露出来——
 * 之前 GL 自己画背景，渐变跟下面对不上，水滴周围被截成一个正方形。
 * FBO 背景只留给水滴内部折射采样用，不再显示。
 */
class DropletGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : GLSurfaceView(context, attrs) {

    private val renderer = DropletGlRenderer(context)

    init {
        // 透明 Surface：EGL 要带 alpha 通道，holder 设成半透明，GL 层置顶。
        setEGLConfigChooser(8, 8, 8, 8, 16, 0)
        holder.setFormat(PixelFormat.TRANSLUCENT)
        setZOrderOnTop(true)
        setEGLContextClientVersion(3)
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    /** 设置水滴中心漂移幅度（0.05 = 5% 半径），跟旧版 TUNE_CENTER_DRIFT_FRAC 对应。 */
    fun setDriftFrac(frac: Float) {
        renderer.driftFrac = frac
    }

    /** 空方法，跟旧 OriloDropletView 一样：水滴不再有待机/睡觉态——永远满尺寸、
     *  永远颤动、不画 Z。留着这个方法是让 MainActivity 的调用不用改。 */
    fun setAwake(@Suppress("UNUSED_PARAMETER") awake: Boolean) {
        // 故意什么都不做。
    }
}
