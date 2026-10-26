package com.jarvis.app.hud

import android.app.ActivityManager
import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.util.Log
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/**
 * 主界面静止效果：交织光带 GL 背景（OpenGL ES 3.0）。
 *
 * 做法（对照用户给的参考图：立体交织的蓝橙光带 + 星空）：
 * - 两条三叶结（2,3）环带：一条冷蓝、一条暖橙，第二条沿法线推出 0.38，
 *   与第一条真实打结缠绕，交叉处靠深度测试形成压上/穿下的遮挡；
 * - 全屏背景三角画深蓝黑底 + 哈希星点 + 暗角；
 * - 慢速自转（待机 30 秒/圈，thinking 时 ×3）、2 秒呼吸缩放；
 * - amplitude（聆听音量 0..1）推高辉光与整体缩放。
 *
 * 层级注意：GLSurfaceView 的 Surface 默认位于窗口底层（不调 setZOrderOnTop），
 * 所以它只能做最底层背景；MainActivity 里 ES 3.0 可用时用它替换原来的
 * GradientLayerView + HudBackgroundView，不可用时走原来那套（见 MainActivity）。
 */
class JarvisRibbonView(context: Context) : GLSurfaceView(context) {

    /** 外部音量 0..1，渲染线程每帧读取（MainActivity 接 OfflineAsrSession.currentLevel） */
    @Volatile
    var amplitudeFn: () -> Float = { 0f }

    /** thinking 态时自转 ×3（MainActivity 接 JarvisAgent.currentState() == "thinking"） */
    @Volatile
    var thinkingFn: () -> Boolean = { false }

    private val renderer = RibbonRenderer(this)

    init {
        // 不调用 setZOrderOnTop(true)：Surface 默认位于窗口底层，全部 UI 盖在它上面。
        setEGLContextClientVersion(3)
        setEGLConfigChooser(8, 8, 8, 8, 16, 0) // RGBA8888 + depth16（交叉遮挡要深度）
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

private class RibbonRenderer(private val view: JarvisRibbonView) : GLSurfaceView.Renderer {

    @Volatile var paused = false

    private var programBg = 0
    private var programRibbon = 0
    private var uBgResolution = 0
    private var uBgTime = 0
    private var uMvp = 0
    private var uGlow = 0

    private var vbo = 0
    private var ibo = 0
    private var indexCount = 0

    private var viewW = 1
    private var viewH = 1
    private var aspect = 1f

    private val startNanos = System.nanoTime()
    private var lastFrameNanos = 0L
    private val targetFrameNanos = 1_000_000_000L / 30 // 30fps 限帧

    // 三叶结网格只算一次（CPU 数据常驻，GL 缓冲每次 onSurfaceCreated 重传，
    // 因为 EGL Context 在暂停/恢复时可能重建）。
    private val mesh: MeshData by lazy { buildKnotMesh() }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        programBg = createProgram(BG_VERTEX_SHADER, BG_FRAGMENT_SHADER)
        programRibbon = createProgram(RIBBON_VERTEX_SHADER, RIBBON_FRAGMENT_SHADER)
        if (programBg == 0) Log.e("JarvisRibbon", "bg program failed")
        if (programRibbon == 0) Log.e("JarvisRibbon", "ribbon program failed")

        uBgResolution = GLES30.glGetUniformLocation(programBg, "uResolution")
        uBgTime = GLES30.glGetUniformLocation(programBg, "uTime")
        uMvp = GLES30.glGetUniformLocation(programRibbon, "uMVP")
        uGlow = GLES30.glGetUniformLocation(programRibbon, "uGlow")

        // 上传网格（顶点：pos(3) + color(3) + across(1)，共 7 float 交错）
        val vb = ByteBuffer.allocateDirect(mesh.verts.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        vb.put(mesh.verts).position(0)
        val ib = ByteBuffer.allocateDirect(mesh.indices.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer()
        ib.put(mesh.indices).position(0)
        val bufs = IntArray(2)
        GLES30.glGenBuffers(2, bufs, 0)
        vbo = bufs[0]
        ibo = bufs[1]
        GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
        GLES30.glBufferData(GLES30.GL_ARRAY_BUFFER, mesh.verts.size * 4, vb, GLES30.GL_STATIC_DRAW)
        GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
        GLES30.glBufferData(GLES30.GL_ELEMENT_ARRAY_BUFFER, mesh.indices.size * 2, ib, GLES30.GL_STATIC_DRAW)
        indexCount = mesh.indices.size

        GLES30.glDisable(GLES30.GL_CULL_FACE) // 双面都画，缠绕方向错了也不会整条消失
        GLES30.glClearColor(0.02f, 0.027f, 0.055f, 1f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = if (width > 0) width else 1
        viewH = if (height > 0) height else 1
        aspect = viewW.toFloat() / viewH.toFloat()
        GLES30.glViewport(0, 0, viewW, viewH)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (paused) return

        // 30fps 限帧：在渲染线程 sleep 补足 33ms，避免满帧耗电。
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

        val t = (System.nanoTime() - startNanos) / 1_000_000_000f
        GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT or GLES30.GL_DEPTH_BUFFER_BIT)

        // 1. 全屏背景：深蓝黑 + 星点 + 暗角（不测深度、不写深度）
        if (programBg != 0) {
            GLES30.glDisable(GLES30.GL_DEPTH_TEST)
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(programBg)
            GLES30.glUniform2f(uBgResolution, viewW.toFloat(), viewH.toFloat())
            GLES30.glUniform1f(uBgTime, t)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }

        // 2. 交织光带：深度测试开（交叉遮挡）、加色混合（辉光叠加）
        if (programRibbon != 0 && vbo != 0 && ibo != 0) {
            GLES30.glEnable(GLES30.GL_DEPTH_TEST)
            GLES30.glDepthFunc(GLES30.GL_LEQUAL)
            GLES30.glEnable(GLES30.GL_BLEND)
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE)
            GLES30.glUseProgram(programRibbon)

            val thinking = view.thinkingFn()
            val amp = view.amplitudeFn().coerceIn(0f, 1f)
            val spinSpeed = if (thinking) 3f else 1f
            val spin = t * (2f * PI.toFloat() / 30f) * spinSpeed // 待机 30 秒/圈
            val breath = 1f + 0.02f * sin(PI.toFloat() * t) // 2 秒呼吸 0.98~1.02
            val scale = breath * (1f + 0.04f * amp)
            val glow = 0.85f + 0.75f * amp

            val mvp = buildMvp(spin, scale)
            GLES30.glUniformMatrix4fv(uMvp, 1, false, mvp, 0)
            GLES30.glUniform1f(uGlow, glow)

            GLES30.glBindBuffer(GLES30.GL_ARRAY_BUFFER, vbo)
            GLES30.glEnableVertexAttribArray(0)
            GLES30.glVertexAttribPointer(0, 3, GLES30.GL_FLOAT, false, 7 * 4, 0)
            GLES30.glEnableVertexAttribArray(1)
            GLES30.glVertexAttribPointer(1, 3, GLES30.GL_FLOAT, false, 7 * 4, 3 * 4)
            GLES30.glEnableVertexAttribArray(2)
            GLES30.glVertexAttribPointer(2, 1, GLES30.GL_FLOAT, false, 7 * 4, 6 * 4)
            GLES30.glBindBuffer(GLES30.GL_ELEMENT_ARRAY_BUFFER, ibo)
            GLES30.glDrawElements(GLES30.GL_TRIANGLES, indexCount, GLES30.GL_UNSIGNED_SHORT, 0)
            GLES30.glDisableVertexAttribArray(0)
            GLES30.glDisableVertexAttribArray(1)
            GLES30.glDisableVertexAttribArray(2)
        }
    }

    // ---------- Shader ----------

    private companion object {
        // 全屏三角：gl_VertexID 生成，不需要顶点数据。
        const val BG_VERTEX_SHADER = """#version 300 es
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
"""

        const val BG_FRAGMENT_SHADER = """#version 300 es
precision highp float;
uniform vec2 uResolution;
uniform float uTime;
out vec4 fragColor;

float hash21(vec2 p) {
    p = fract(p * vec2(123.34, 456.21));
    p += dot(p, p + 45.32);
    return fract(p.x * p.y);
}

void main() {
    vec2 uv = (gl_FragCoord.xy - 0.5 * uResolution) / uResolution.y;
    float r = length(uv);
    vec3 col = vec3(0x05, 0x07, 0x0E) / 255.0;
    // 左上淡淡的蓝紫雾，免得底色死平
    col += vec3(0.05, 0.08, 0.18) * exp(-length(uv - vec2(-0.35, 0.35)) * 2.2) * 0.5;
    // 星点：90x90 网格，约 1.5% 有星，独立相位闪烁
    vec2 starUv = gl_FragCoord.xy / uResolution.y;
    vec2 g = floor(starUv * 90.0);
    float rnd = hash21(g);
    float star = step(0.985, rnd);
    float tw = 0.5 + 0.5 * sin(uTime * 1.7 + rnd * 100.0);
    col += vec3(0.6, 0.75, 1.0) * star * tw * 0.12;
    // 少数亮星
    float rnd2 = hash21(g + 7.31);
    float star2 = step(0.997, rnd2);
    float tw2 = 0.5 + 0.5 * sin(uTime * 2.3 + rnd2 * 80.0);
    col += vec3(0.8, 0.9, 1.0) * star2 * tw2 * 0.35;
    // 暗角，视线聚到中间
    col *= 1.0 - 0.45 * smoothstep(0.35, 1.2, r);
    fragColor = vec4(col, 1.0);
}
"""

        // 光带顶点：layout 位置固定 0=pos 1=color 2=across，免去查 attrib 位置。
        const val RIBBON_VERTEX_SHADER = """#version 300 es
uniform mat4 uMVP;
layout(location = 0) in vec3 aPos;
layout(location = 1) in vec3 aColor;
layout(location = 2) in float aAcross;
out vec3 vColor;
out float vAcross;
void main() {
    vColor = aColor;
    vAcross = aAcross;
    gl_Position = uMVP * vec4(aPos, 1.0);
}
"""

        const val RIBBON_FRAGMENT_SHADER = """#version 300 es
precision highp float;
uniform float uGlow;
in vec3 vColor;
in float vAcross;
out vec4 fragColor;
void main() {
    float a = abs(vAcross);
    // 带边柔和衰减 + 中心更亮，加色混合后自然成辉光
    float edge = 1.0 - smoothstep(0.55, 1.0, a);
    float core = 1.0 - smoothstep(0.0, 0.4, a);
    vec3 col = vColor * (edge * 0.85 + core * 0.7) * uGlow;
    fragColor = vec4(col, 1.0);
}
"""
    }

    // ---------- Shader 编译 ----------

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        if (sh == 0) return 0
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e("JarvisRibbon", "shader compile failed: " + GLES30.glGetShaderInfoLog(sh))
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
            Log.e("JarvisRibbon", "program link failed: " + GLES30.glGetProgramInfoLog(p))
            GLES30.glDeleteProgram(p)
            return 0
        }
        return p
    }

    // ---------- 三叶结网格 ----------

    private class MeshData(val verts: FloatArray, val indices: ShortArray)

    // 网格常量（类里只能有一个 companion，写成普通成员字段）
    private val SEGMENTS = 360
    private val KNOT_SCALE = 0.72f // 结半径 3 -> 2.16，相机 z=7、fov45 时竖向刚好，横向裁边（与参考图一致）
    private val HALF_W = 0.16f // 单条带半宽
    private val PUSH = 0.38f // 第二条带沿法线推出距离（0.38 - 0.16 - 0.16 = 0.06 缝隙，缠绕感）
    private val BLUE = floatArrayOf(0.30f, 0.62f, 1.0f)
    private val ORANGE = floatArrayOf(1.0f, 0.50f, 0.16f)

    /** 三叶结 (2,3)：P(t) = ((2+cos3t)cos2t, (2+cos3t)sin2t, sin3t)，t in [0, 2π) */
    private fun knotPoint(t: Double): DoubleArray {
        val c3 = cos(3 * t)
        val c2 = cos(2 * t)
        val s2 = sin(2 * t)
        val s3 = sin(3 * t)
        return doubleArrayOf((2 + c3) * c2, (2 + c3) * s2, s3)
    }

    private fun buildKnotMesh(): MeshData {
        val n = SEGMENTS
        // 切线（中心差分）与平行移动法线：法线沿曲线连续搬运，不会像固定 up 向量那样在极点翻转。
        val points = Array(n) { i -> knotPoint(2 * PI * i / n) }
        val tangents = Array(n) { i ->
            val e = 1e-3
            val t = 2 * PI * i / n
            val a = knotPoint(t - e)
            val b = knotPoint(t + e)
            norm(doubleArrayOf(b[0] - a[0], b[1] - a[1], b[2] - a[2]))
        }
        val normals = Array(n) { DoubleArray(3) }
        run {
            val t0 = tangents[0]
            val ref = if (kotlin.math.abs(t0[1]) < 0.9) doubleArrayOf(0.0, 1.0, 0.0) else doubleArrayOf(1.0, 0.0, 0.0)
            normals[0] = norm(sub(ref, mul(t0, dot(ref, t0))))
            for (i in 1 until n) {
                val t = tangents[i]
                normals[i] = norm(sub(normals[i - 1], mul(t, dot(normals[i - 1], t))))
            }
            // 闭环修正：平行移动绕一圈后法线一般回不到起点（holonomy），
            // 把总偏差角均匀摊到整圈上，带边在接缝处才不会拧一下。
            val nClose = norm(sub(normals[n - 1], mul(tangents[0], dot(normals[n - 1], tangents[0]))))
            val cr = cross(normals[0], nClose)
            val angle = kotlin.math.atan2(dot(cr, tangents[0]), dot(normals[0], nClose))
            for (i in 0 until n) {
                normals[i] = rotateAroundAxis(normals[i], tangents[i], -angle * i / n)
            }
        }

        // 顶点：两条带，每段 2 点（across -1/+1），每点 7 float：pos(3) + color(3) + across(1)
        val verts = FloatArray(2 * n * 2 * 7)
        fun putVert(base: Int, idx: Int, cx: Double, cy: Double, cz: Double, across: Float, color: FloatArray) {
            var o = base + idx * 7
            verts[o++] = (cx * KNOT_SCALE).toFloat()
            verts[o++] = (cy * KNOT_SCALE).toFloat()
            verts[o++] = (cz * KNOT_SCALE).toFloat()
            verts[o++] = color[0]
            verts[o++] = color[1]
            verts[o++] = color[2]
            verts[o] = across
        }
        for (i in 0 until n) {
            val p = points[i]
            val nn = normals[i]
            // 带 A：冷蓝，本体
            putVert(0, 2 * i, p[0] - nn[0] * HALF_W, p[1] - nn[1] * HALF_W, p[2] - nn[2] * HALF_W, -1f, BLUE)
            putVert(0, 2 * i + 1, p[0] + nn[0] * HALF_W, p[1] + nn[1] * HALF_W, p[2] + nn[2] * HALF_W, 1f, BLUE)
            // 带 B：暖橙，沿法线推出 PUSH，与 A 打结缠绕
            val qx = p[0] + nn[0] * PUSH
            val qy = p[1] + nn[1] * PUSH
            val qz = p[2] + nn[2] * PUSH
            val baseB = 2 * n * 7
            putVert(baseB, 2 * i, qx - nn[0] * HALF_W, qy - nn[1] * HALF_W, qz - nn[2] * HALF_W, -1f, ORANGE)
            putVert(baseB, 2 * i + 1, qx + nn[0] * HALF_W, qy + nn[1] * HALF_W, qz + nn[2] * HALF_W, 1f, ORANGE)
        }

        // 索引：每条带每段 2 三角，闭环 wraparound
        val idx = ShortArray(2 * n * 6)
        var o = 0
        for (ribbon in 0 until 2) {
            val base = ribbon * 2 * n
            for (i in 0 until n) {
                val j = (i + 1) % n
                val a = base + 2 * i
                val b = base + 2 * i + 1
                val c = base + 2 * j
                val d = base + 2 * j + 1
                idx[o++] = a.toShort()
                idx[o++] = b.toShort()
                idx[o++] = c.toShort()
                idx[o++] = b.toShort()
                idx[o++] = d.toShort()
                idx[o++] = c.toShort()
            }
        }
        return MeshData(verts, idx)
    }

    private fun dot(a: DoubleArray, b: DoubleArray) = a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
    private fun sub(a: DoubleArray, b: DoubleArray) = doubleArrayOf(a[0] - b[0], a[1] - b[1], a[2] - b[2])
    private fun mul(a: DoubleArray, s: Double) = doubleArrayOf(a[0] * s, a[1] * s, a[2] * s)
    private fun cross(a: DoubleArray, b: DoubleArray) = doubleArrayOf(
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0]
    )

    /** Rodrigues 公式绕 axis 转 angle 弧度（axis 需为单位向量） */
    private fun rotateAroundAxis(v: DoubleArray, axis: DoubleArray, angle: Double): DoubleArray {
        val c = cos(angle)
        val s = sin(angle)
        val t1 = mul(v, c)
        val t2 = mul(cross(axis, v), s)
        val t3 = mul(axis, dot(axis, v) * (1 - c))
        return doubleArrayOf(t1[0] + t2[0] + t3[0], t1[1] + t2[1] + t3[1], t1[2] + t2[2] + t3[2])
    }
    private fun norm(a: DoubleArray): DoubleArray {
        val l = sqrt(dot(a, a))
        return if (l < 1e-9) doubleArrayOf(0.0, 0.0, 0.0) else mul(a, 1.0 / l)
    }

    // ---------- 矩阵（列主序 FloatArray(16)，与 glUniformMatrix4fv transpose=false 对应） ----------

    private fun buildMvp(spinY: Float, scale: Float): FloatArray {
        // M = Ry(spinY) * Rx(0.45) * S(scale)，V = 平移 z=-7，P = 透视 45°
        val rx = rotX(0.45f)
        val ry = rotY(spinY)
        val s = scaleMat(scale)
        val m = multiply(ry, multiply(rx, s))
        val v = FloatArray(16)
        v[0] = 1f; v[5] = 1f; v[10] = 1f; v[15] = 1f
        v[14] = -7f // 相机在 z=7 看向原点
        val vm = multiply(v, m)
        val p = perspective(45f, aspect, 0.1f, 100f)
        return multiply(p, vm)
    }

    private fun perspective(fovDeg: Float, aspect: Float, near: Float, far: Float): FloatArray {
        val f = (1f / tan(Math.toRadians((fovDeg / 2).toDouble()).toFloat()))
        val m = FloatArray(16)
        m[0] = f / aspect
        m[5] = f
        m[10] = (far + near) / (near - far)
        m[11] = -1f
        m[14] = 2f * far * near / (near - far)
        return m
    }

    private fun rotX(a: Float): FloatArray {
        val c = cos(a)
        val s = sin(a)
        val m = FloatArray(16)
        m[0] = 1f; m[5] = c; m[6] = s; m[9] = -s; m[10] = c; m[15] = 1f
        return m
    }

    private fun rotY(a: Float): FloatArray {
        val c = cos(a)
        val s = sin(a)
        val m = FloatArray(16)
        m[0] = c; m[2] = -s; m[5] = 1f; m[8] = s; m[10] = c; m[15] = 1f
        return m
    }

    private fun scaleMat(s: Float): FloatArray {
        val m = FloatArray(16)
        m[0] = s; m[5] = s; m[10] = s; m[15] = 1f
        return m
    }

    /** C = A * B，列主序：C[col*4+row] = Σ A[k*4+row] * B[col*4+k] */
    private fun multiply(a: FloatArray, b: FloatArray): FloatArray {
        val c = FloatArray(16)
        for (col in 0 until 4) {
            for (row in 0 until 4) {
                var sum = 0f
                for (k in 0 until 4) sum += a[k * 4 + row] * b[col * 4 + k]
                c[col * 4 + row] = sum
            }
        }
        return c
    }
}
