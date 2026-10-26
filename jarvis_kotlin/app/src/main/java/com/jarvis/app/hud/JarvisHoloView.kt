package com.jarvis.app.hud

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * 主界面全息背景：raymarching 三带（整换旧三叶结光带，旧文件已删，git 历史可找回）。
 *
 * 做法（用户定的 J.A.R.V.I.S. 全息方案，View 接线版——工程零 Compose，
 * 原稿的 @Composable 包装改成普通 View 接线）：
 * - 3 条扭曲扁环面丝带（青蓝 / 暖橙 / 浅蓝），raymarching 体积累加；
 * - 丝带表面：数据流条纹 + 全息网格 + 边缘高光；
 * - 扫描光幕随时间上下扫过，扫到处泛蓝光；
 * - 空间悬浮数据粒子（哈希点云 + 闪烁，青橙随机）；
 * - 暗角 + 微弱屏幕扫描线 + 色调映射；
 * - 半分辨率 FBO 渲染再放大到全屏（性能优化），30fps 限帧；
 * - 音量推辉光（u_glow，与旧光带同一公式 0.55 + 0.60*amp）；
 * - thinking 时动画 ×3（时间积分，切换时相位不跳；旧光带 t*speed 切换会跳相位）。
 *
 * 层级注意：GLSurfaceView 的 Surface 默认位于窗口底层（不调 setZOrderOnTop），
 * 所以它只能做最底层背景；MainActivity 里 ES 3.0 可用时用它，不可用时走
 * GradientLayerView（与旧光带同一分流）。设置页藏它（见 MainActivity）。
 */
class JarvisHoloView(context: Context) : GLSurfaceView(context) {

    /** 外部音量 0..1，渲染线程每帧读取（MainActivity 接 OfflineAsrSession.currentLevel） */
    @Volatile
    var amplitudeFn: () -> Float = { 0f }

    /** thinking 态时动画 ×3（MainActivity 接 JarvisAgent.currentState() == "thinking"） */
    @Volatile
    var thinkingFn: () -> Boolean = { false }

    private val renderer = HoloRenderer(this)

    init {
        // 不调用 setZOrderOnTop(true)：Surface 默认位于窗口底层，全部 UI 盖在它上面。
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0) // RGBA8888 + depth16
        setRenderer(renderer)
        renderMode = RENDERMODE_CONTINUOUSLY
    }

    override fun onPause() {
        super.onPause()
        renderer.paused = true
    }

    override fun onResume() {
        super.onResume()
        renderer.paused = false
    }

    companion object {
        /** 设备是否支持 OpenGL ES 3.0（清单里 required=false，靠这里运行时分流） */
        fun isSupported(ctx: Context): Boolean {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return false
            return am.deviceConfigurationInfo.reqGlEsVersion >= 0x30000
        }
    }
}

private class HoloRenderer(private val view: JarvisHoloView) : GLSurfaceView.Renderer {

    @Volatile var paused = false

    private var holoProgram = 0
    private var blitProgram = 0
    private var fbo = 0
    private var fboTexture = 0
    private var fboWidth = 0
    private var fboHeight = 0
    private var screenW = 0
    private var screenH = 0

    private var uTime = 0
    private var uResolution = 0
    private var uGlow = 0
    private var uBlitTexture = 0

    private var animTime = 0f
    private var lastTimeNanos = 0L
    private var lastFrameNanos = 0L
    private val targetFrameNanos = 1_000_000_000L / 30 // 30fps 限帧

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        holoProgram = createProgram(readRaw(R.raw.fullscreen_vertex), readRaw(R.raw.fluid_holo_fragment))
        blitProgram = createProgram(readRaw(R.raw.fullscreen_vertex), readRaw(R.raw.blit_fragment))
        if (holoProgram == 0) Log.e("JarvisHolo", "holo program failed")
        if (blitProgram == 0) Log.e("JarvisHolo", "blit program failed")

        uTime = GLES30.glGetUniformLocation(holoProgram, "u_time")
        uResolution = GLES30.glGetUniformLocation(holoProgram, "u_resolution")
        uGlow = GLES30.glGetUniformLocation(holoProgram, "u_glow")
        uBlitTexture = GLES30.glGetUniformLocation(blitProgram, "u_texture")
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        screenW = if (width > 0) width else 1
        screenH = if (height > 0) height else 1
        // 核心优化：半分辨率
        fboWidth = screenW / 2
        fboHeight = screenH / 2

        // 重建先删旧的，免得转屏/尺寸变漏纹理与 FBO（原稿没删）
        if (fbo != 0) {
            GLES30.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
            fbo = 0
        }
        if (fboTexture != 0) {
            GLES30.glDeleteTextures(1, intArrayOf(fboTexture), 0)
            fboTexture = 0
        }

        val texIds = IntArray(1)
        GLES30.glGenTextures(1, texIds, 0)
        fboTexture = texIds[0]
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, fboWidth, fboHeight, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)

        val fboIds = IntArray(1)
        GLES30.glGenFramebuffers(1, fboIds, 0)
        fbo = fboIds[0]
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, fboTexture, 0)
        if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            Log.e("JarvisHolo", "fbo incomplete")
        }
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (paused) return

        // 30fps 限帧：在渲染线程 sleep 补足 33ms，避免满帧耗电（与旧光带一致）。
        val now = System.nanoTime()
        if (lastFrameNanos != 0L) {
            val elapsed = now - lastFrameNanos
            if (elapsed < targetFrameNanos) {
                val sleepMs = (targetFrameNanos - elapsed) / 1_000_000L
                if (sleepMs > 0) {
                    try {
                        Thread.sleep(sleepMs)
                    } catch (e: InterruptedException) {
                        Thread.currentThread().interrupt()
                        return
                    }
                }
            }
        }
        lastFrameNanos = System.nanoTime()

        // 动画时间积分：thinking ×3，切换时相位不跳。
        val now2 = System.nanoTime()
        if (lastTimeNanos == 0L) lastTimeNanos = now2
        val dt = ((now2 - lastTimeNanos) / 1_000_000_000f).coerceIn(0f, 0.1f)
        lastTimeNanos = now2
        val speed = if (view.thinkingFn()) 3f else 1f
        animTime += dt * speed

        val amp = view.amplitudeFn().coerceIn(0f, 1f)
        val glow = 0.55f + 0.60f * amp // 与旧光带同一辉光公式

        // 1. 渲染到半分辨率 FBO
        if (holoProgram != 0 && fbo != 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo)
            GLES30.glViewport(0, 0, fboWidth, fboHeight)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(holoProgram)
            GLES30.glUniform1f(uTime, animTime)
            GLES30.glUniform2f(uResolution, fboWidth.toFloat(), fboHeight.toFloat())
            GLES30.glUniform1f(uGlow, glow)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3) // 全屏三角形，无需 VBO
        }

        // 2. 放大到全屏
        if (blitProgram != 0 && fboTexture != 0) {
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
            GLES30.glViewport(0, 0, screenW, screenH)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            GLES30.glUseProgram(blitProgram)
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fboTexture)
            GLES30.glUniform1i(uBlitTexture, 0) // 原稿没显式绑，默认虽是 0，这里写明
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
    }

    // ---------- Shader ----------

    private fun readRaw(resId: Int): String {
        return try {
            view.context.resources.openRawResource(resId).bufferedReader(Charsets.UTF_8).use { it.readText() }
        } catch (e: Exception) {
            Log.e("JarvisHolo", "readRaw failed: $resId", e)
            ""
        }
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        if (sh == 0) return 0
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("JarvisHolo", "shader compile failed: " + GLES30.glGetShaderInfoLog(sh))
            GLES30.glDeleteShader(sh)
            return 0
        }
        return sh
    }

    private fun createProgram(vsSrc: String, fsSrc: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vsSrc)
        if (vs == 0) return 0
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, fsSrc)
        if (fs == 0) {
            GLES30.glDeleteShader(vs)
            return 0
        }
        val p = GLES30.glCreateProgram()
        if (p == 0) {
            GLES30.glDeleteShader(vs)
            GLES30.glDeleteShader(fs)
            return 0
        }
        GLES30.glAttachShader(p, vs)
        GLES30.glAttachShader(p, fs)
        GLES30.glLinkProgram(p)
        GLES30.glDeleteShader(vs)
        GLES30.glDeleteShader(fs)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("JarvisHolo", "program link failed: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }
}
