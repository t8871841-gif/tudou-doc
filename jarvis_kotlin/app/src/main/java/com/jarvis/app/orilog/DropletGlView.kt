package com.jarvis.app.orilog

import android.content.Context
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.util.AttributeSet
import android.view.TextureView

/**
 * Orilo 路线重做的水滴 View（第 36 批起）。
 *
 * 跟旧的 OriloDropletView（Android View + AGSL RuntimeShader）完全分开，
 * 放在 orilog/ 文件夹里，不混在一起。
 *
 * 架构（照 Orilo 原版路线，不是抄它的代码）：
 * - 原生 OpenGL ES + 自定义 GLSL。
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
 *
 * 第 80 批（用户令，方案一，要惊艳效果）：改回不透明 Surface。GL 自己全屏画
 * 背景（贴 FBO 纹理），OriloBackgroundView 不再上屏——屏幕上的背景和水滴采的
 * 背景是同一份纹理，颜色同步是结构保证的。第 39 批那个“正方形截断”不会再
 * 出现，因为这次背景是全屏贴的，不是只画在水滴 View 的小框里。
 *
 * 第 81 批：GLSurfaceView → TextureView。jk-v117 真机：气泡和底部按钮的
 * 液态玻璃全黑——LiquidGlassView 靠把身后层级光栅化成位图再做折射，
 * GLSurfaceView 的像素在独立 Surface 上，光栅化时是黑洞，玻璃采到黑色。
 * TextureView 的内容画在 View 层级里，光栅化能看到，玻璃恢复正常。
 * 单套渲染不变（同一份 FBO 纹理做背景 + 折射采样），同步仍是结构保证。
 * EGL/渲染线程自己管：SurfaceTexture 可用时建 EGL 上下文 + 起渲染线程，
 * 销毁时停线程、拆 EGL；Activity 切后台走 onPause/onResume 停/起线程
 * （第 36 批的规矩不变：后台渲染线程不能一直跑）。
 * ScreenController.isRenderSurface 本来就把 TextureView 和 GLSurfaceView
 * 同等对待（都是渲染面），自动化那边不用改。
 */
class DropletGlView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : TextureView(context, attrs), TextureView.SurfaceTextureListener {

    private val renderer = DropletGlRenderer(context)

    @Volatile
    private var renderThread: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var surfaceTex: SurfaceTexture? = null
    @Volatile
    private var pendingW = 0
    @Volatile
    private var pendingH = 0

    init {
        // 不透明：GL 自己全屏贴背景，下面没有东西要透出来，省一次混合。
        isOpaque = true
        surfaceTextureListener = this
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

    /** 第 36 批的规矩：跟 Activity 生命周期走，切后台停渲染线程。 */
    fun onPause() {
        stopRenderThread()
    }

    fun onResume() {
        val st = surfaceTex
        if (st != null) startRenderThread(st)
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        surfaceTex = surface
        pendingW = width
        pendingH = height
        startRenderThread(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
        // 尺寸变化不直接调 GL（GL 调用只能在渲染线程）——先记下来，
        // 渲染线程下一帧开头应用。
        pendingW = width
        pendingH = height
    }

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        surfaceTex = null
        stopRenderThread()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
        // 不用。
    }

    private fun startRenderThread(surface: SurfaceTexture) {
        if (running) return
        running = true
        renderThread = Thread({
            var eglDisplay: EGLDisplay = EGL14.EGL_NO_DISPLAY
            var eglContext: EGLContext = EGL14.EGL_NO_CONTEXT
            var eglSurface: EGLSurface = EGL14.EGL_NO_SURFACE
            try {
                eglDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
                val eglMajor = IntArray(1)
                val eglMinor = IntArray(1)
                EGL14.eglInitialize(eglDisplay, eglMajor, 0, eglMinor, 0)
                val configs = arrayOfNulls<EGLConfig>(1)
                val numConfigs = IntArray(1)
                // ES3（着色器是 #version 300 es）+ RGBA8888 + 16 位深度。
                // 注意：EGL14 里常量叫 EGL_OPENGL_ES3_BIT，没有 _KHR 后缀
                // （_KHR 只在 EGLExt 里）；eglInitialize 是五参版
                // (display, major, majorOffset, minor, minorOffset)。
                val attribs = intArrayOf(
                    EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES3_BIT,
                    EGL14.EGL_RED_SIZE, 8,
                    EGL14.EGL_GREEN_SIZE, 8,
                    EGL14.EGL_BLUE_SIZE, 8,
                    EGL14.EGL_ALPHA_SIZE, 8,
                    EGL14.EGL_DEPTH_SIZE, 16,
                    EGL14.EGL_NONE,
                )
                EGL14.eglChooseConfig(eglDisplay, attribs, 0, configs, 0, 1, numConfigs, 0)
                val ctxAttribs = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 3, EGL14.EGL_NONE)
                eglContext = EGL14.eglCreateContext(
                    eglDisplay, configs[0], EGL14.EGL_NO_CONTEXT, ctxAttribs, 0,
                )
                eglSurface = EGL14.eglCreateWindowSurface(
                    eglDisplay, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0,
                )
                EGL14.eglMakeCurrent(eglDisplay, eglSurface, eglSurface, eglContext)
                renderer.onSurfaceCreated()
                var lastW = 0
                var lastH = 0
                while (running) {
                    val w = pendingW
                    val h = pendingH
                    if (w > 0 && h > 0 && (w != lastW || h != lastH)) {
                        renderer.onSurfaceChanged(w, h)
                        lastW = w
                        lastH = h
                    }
                    if (lastW > 0 && lastH > 0) {
                        renderer.onDrawFrame()
                        // 默认 swap interval 1，卡在 vsync 上，不用自己算帧率。
                        EGL14.eglSwapBuffers(eglDisplay, eglSurface)
                    }
                    try {
                        Thread.sleep(8)
                    } catch (e: InterruptedException) {
                        break
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DropletGl", "render thread failed", e)
            } finally {
                if (eglDisplay !== EGL14.EGL_NO_DISPLAY) {
                    EGL14.eglMakeCurrent(
                        eglDisplay,
                        EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT,
                    )
                    if (eglSurface !== EGL14.EGL_NO_SURFACE) {
                        EGL14.eglDestroySurface(eglDisplay, eglSurface)
                    }
                    if (eglContext !== EGL14.EGL_NO_CONTEXT) {
                        EGL14.eglDestroyContext(eglDisplay, eglContext)
                    }
                    EGL14.eglTerminate(eglDisplay)
                }
            }
        }, "DropletGl").apply { start() }
    }

    private fun stopRenderThread() {
        if (!running) return
        running = false
        try {
            renderThread?.join(500)
        } catch (e: InterruptedException) {
            // 停不下来就算了，线程自己会在下一帧退出。
        }
        renderThread = null
    }
}
