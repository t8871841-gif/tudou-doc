package com.jarvis.app.orilog

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/**
 * Orilo 路线的水滴渲染器（第 36 批起）。
 *
 * 架构（照 Orilo 原版路线，自己写的 GLSL，不抄它的代码）：
 * 1. 背景 pass：把程序化背景（渐变 + 光斑 + 流场）渲染到 FBO 纹理。
 * 2. 水滴 pass：全屏大三角，片元着色器里逐像素算水滴光学——
 *    采样背景纹理做折射（真实背景采样，不是重算假背景），
 *    再加高光、笑脸、暗带、亮边、聚光、影子。
 * 3. 场景参数（中心漂移、颤动相位）每帧 CPU 算好、经 uniform 传。
 *
 * 跟旧 AGSL 版的关键差别：背景是真实渲染出来的纹理，水滴折射采的是它，
 * 不是在片元里重算一个平滑函数——折射在平滑渐变上看不见的问题从根上解决。
 */
class DropletGlRenderer(private val context: Context) : GLSurfaceView.Renderer {

    /** 中心漂移幅度（半径分数），默认 0.05。 */
    @Volatile
    var driftFrac: Float = 0.05f

    private var bgProgram = 0
    private var dropProgram = 0
    private var fboId = 0
    private var bgTexId = 0
    private var viewW = 0
    private var viewH = 0
    private var startMs = 0L

    // ---- 顶点着色器：全屏大三角，无 VBO（照 Orilo 路线） ----
    private val vertSrc = """
        #version 300 es
        void main() {
            int vid = gl_VertexID;
            vec2 p = vec2(float((vid << 1) & 2), float(vid & 2));
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
    """.trimIndent()

    // ---- 背景片元着色器：程序化背景（渐变 + 双光斑 + 流场），渲染到 FBO ----
    private val bgFragSrc = """
        #version 300 es
        precision highp float;
        uniform vec2 uRes;
        uniform float uTime;
        uniform vec2 uBlobA;
        uniform vec2 uBlobB;
        uniform vec3 uGradTop;
        uniform vec3 uGradMid;
        uniform vec3 uGradBot;
        uniform vec3 uBlobAColor;
        uniform vec3 uBlobBColor;
        out vec4 oColor;
        float hash(vec2 p) { return fract(sin(dot(p, vec2(127.1, 311.7))) * 43758.5453); }
        float noise(vec2 p) {
            vec2 i = floor(p), f = fract(p);
            vec2 u = f * f * (3.0 - 2.0 * f);
            return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), u.x),
                       mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), u.x), u.y);
        }
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            float w = noise(uv * 2.8 + vec2(uTime * 0.03, 0.0)) - 0.5;
            vec2 wuv = uv + vec2(w * 0.25, noise(uv * 2.8 + vec2(0.0, uTime * 0.02)) * 0.25 - 0.125);
            float t = clamp(dot(wuv, vec2(0.25, 1.0)) / 1.0625, 0.0, 1.0);
            vec3 base = mix(mix(uGradTop, uGradMid, clamp(t / 0.55, 0.0, 1.0)),
                            mix(uGradMid, uGradBot, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                            step(0.55, t));
            float fa = smoothstep(0.0, 1.0, clamp(1.0 - length(wuv * uRes - uBlobA) / (uRes.x * 0.45), 0.0, 1.0));
            base = mix(base, uBlobAColor, fa * 0.55);
            float fb = smoothstep(0.0, 1.0, clamp(1.0 - length(wuv * uRes - uBlobB) / (uRes.x * 0.40), 0.0, 1.0));
            base = mix(base, uBlobBColor, fb * 0.45);
            oColor = vec4(base, 1.0);
        }
    """.trimIndent()

    // ---- 水滴片元着色器：一束光模型，采样真实背景纹理做折射 ----
    private val dropFragSrc = """
        #version 300 es
        precision highp float;
        uniform vec2 uRes;
        uniform float uTime;
        uniform sampler2D uBgTex;
        uniform vec2 uCenter;
        uniform float uRadius;
        uniform float uDriftX;
        uniform float uDriftY;
        out vec4 oColor;
        void main() {
            vec2 fragPx = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
            vec2 c = uCenter + vec2(uDriftX, uDriftY);
            vec2 local = fragPx - c;
            float dist = length(local);
            float R = uRadius;
            // 水滴外：直接输出背景（纹理采样永远用没翻转的 gl_FragCoord.xy——
            // 背景纹理写入时用的是 GL 原始坐标（y 向上），用翻转后的 fragPx 采样
            // 等于又多翻一次，上下镜像颠倒。Claude 第 36 批审查指出，实锤。）
            if (dist > R * 1.18) {
                vec3 bgOut = texture(uBgTex, gl_FragCoord.xy / uRes).rgb;
                // 影子：水滴下方 1.3R 处、半径 1.8R 的软影（在背景上乘暗）
                vec2 sCenter = c + vec2(-R * 0.18, R * 1.3);
                float sDist = length(fragPx - sCenter);
                float shadow = (1.0 - smoothstep(0.0, R * 1.8, sDist)) * 0.75;
                bgOut *= (1.0 - shadow);
                // 外面聚光：水滴左下 1.5R 处、半径 0.6R 的亮斑（光透过水珠照亮一片区域；
                // 原来在背景 View 里画（第 23 批），GL View 不透明把它盖住了，挪到这里）。
                vec2 ocCenter = c + vec2(-R * 1.5 * 0.7071, R * 1.5 * 0.7071);
                float ocDist = length(fragPx - ocCenter);
                float oc = (1.0 - smoothstep(0.0, R * 0.6, ocDist)) * 0.10;
                bgOut += vec3(1.0) * oc;
                oColor = vec4(bgOut, 1.0);
                return;
            }
            vec2 n2 = local / R;
            float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
            // 折射：沿法线弯折，采样真实背景纹理。n2 是在翻转后（Android）空间里算的，
            // 偏移量的 y 分量要翻回来（取负），再加到没翻转的 gl_FragCoord 上。
            float bend = 120.0;
            vec2 refractUv = gl_FragCoord.xy / uRes + vec2(n2.x, -n2.y) * bend * (1.0 - nz) / uRes;
            vec3 refracted = texture(uBgTex, refractUv).rgb;
            // 边缘
            float edge = smoothstep(R * 0.90, R, dist);
            // 顶部暗带（~40° 宽）
            float cosTop = -n2.y;
            float topDarkMask = smoothstep(0.94, 0.98, cosTop);
            // 底部笑脸
            vec2 bottomDir = normalize(vec2(0.12, 0.99));
            float cosBottom = dot(normalize(n2), bottomDir);
            float smileMask = smoothstep(0.60, 0.85, cosBottom);
            float smileBright = edge * smileMask;
            // 暗带环
            float darkBand = smoothstep(R * 0.90, R * 0.975, dist)
                * (1.0 - smoothstep(R * 0.975, R, dist));
            // 漫反射（左下亮、右上暗的平滑渐变）
            vec3 normal = vec3(n2, nz);
            vec3 lightDir = normalize(vec3(-0.45, -0.65, 0.6));
            float diffuse = clamp(dot(normal, lightDir), 0.0, 1.0);
            // 大高光：右上，窄核心（旧 Canvas 高光的 GLSL 版：中心 0.47r/-0.65r，
            // 半径 0.25r，椭圆 1×0.55，转 -40°。Claude 第 36 批审查指出旧代码里
            // 压根没有这颗高光，整圈亮边顶到 220/255，笑脸显不出来。）
            vec2 hlC = vec2(0.47, -0.65);
            vec2 hlD = n2 - hlC;
            float hlAng = -0.698;
            float hlCa = cos(hlAng), hlSa = sin(hlAng);
            vec2 hlR = vec2(hlD.x * hlCa - hlD.y * hlSa, hlD.x * hlSa + hlD.y * hlCa);
            vec2 hlQ = vec2(hlR.x / 0.25, hlR.y / (0.25 * 0.55));
            float highlight = exp(-dot(hlQ, hlQ) * 1.5);
            // 亮边（菲涅尔）：降到 0.5，别跟笑脸/高光抢（之前 1.0 整圈顶满）。
            float rimBright = edge * (1.0 - topDarkMask) * 0.5;
            // 暗边
            float rimDark = smoothstep(R * 0.97, R, dist) * 0.40 * (1.0 - topDarkMask);
            // 聚光（左下 225°，0.6R 处）
            vec2 causticDir = vec2(-0.7071, 0.7071);
            float causticDist = length(n2 - causticDir * 0.6);
            float caustic = (1.0 - smoothstep(0.0, 0.55, causticDist)) * 0.30;
            // 合成：折射打底（只留 2% 色调，主体是背景本身），再加各光学项
            vec3 colorOut = refracted * 0.98
                + vec3(1.0) * smileBright * 1.0
                + vec3(1.0) * diffuse * 0.06
                + vec3(1.0) * rimBright
                + vec3(1.0) * highlight;
            colorOut *= (1.0 - darkBand * 0.35 * topDarkMask);
            colorOut *= (1.0 - rimDark);
            // alpha：中心 0.01、边缘 0.22（跟旧版一致，中心保持透明）
            float alpha = mix(0.01, 0.22, edge);
            // 手动合成到背景上（最后输出 alpha=1.0 不透明像素，不走 Android 合成层，
            // 没有预乘透明度歧义），聚光只加一次。
            vec3 bg = texture(uBgTex, gl_FragCoord.xy / uRes).rgb;
            vec3 result = colorOut * alpha + bg * (1.0 - alpha) + vec3(1.0) * caustic * 0.5;
            oColor = vec4(result, 1.0);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES30.glClearColor(0f, 0f, 0f, 1f)
        // EGL 上下文重建时本方法会再跑一次，先删旧对象（Claude 第 36 批审查建议，防御性）。
        if (bgProgram != 0) GLES30.glDeleteProgram(bgProgram)
        if (dropProgram != 0) GLES30.glDeleteProgram(dropProgram)
        if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        if (bgTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(bgTexId), 0)
        bgProgram = buildProgram(vertSrc, bgFragSrc)
        dropProgram = buildProgram(vertSrc, dropFragSrc)
        startMs = SystemClock.uptimeMillis()
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        bgTexId = tex[0]
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewW = width
        viewH = height
        GLES30.glViewport(0, 0, width, height)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTexId)
        GLES30.glTexImage2D(GLES30.GL_TEXTURE_2D, 0, GLES30.GL_RGBA8, width, height, 0, GLES30.GL_RGBA, GLES30.GL_UNSIGNED_BYTE, null)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    override fun onDrawFrame(gl: GL10?) {
        val tSec = (SystemClock.uptimeMillis() - startMs) / 1000f
        // ---- 背景 pass：渲染到 FBO ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fboId)
        GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, bgTexId, 0)
        // FBO 完整性检查（Claude 第 36 批审查建议：AGSL 那边吃过静默失败的亏）。
        val fboStatus = GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER)
        if (fboStatus != GLES30.GL_FRAMEBUFFER_COMPLETE) {
            android.util.Log.e("DropletGl", "FBO incomplete: $fboStatus")
        }
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glUseProgram(bgProgram)
        setVec2(bgProgram, "uRes", viewW.toFloat(), viewH.toFloat())
        setFloat(bgProgram, "uTime", tSec)
        val phase = huePhase(tSec)
        setVec2(bgProgram, "uBlobA", phase.ax * viewW, phase.ay * viewH)
        setVec2(bgProgram, "uBlobB", phase.bx * viewW, phase.by * viewH)
        setVec3(bgProgram, "uGradTop", 0.55f, 0.65f, 0.95f)
        setVec3(bgProgram, "uGradMid", 0.45f, 0.55f, 0.91f)
        setVec3(bgProgram, "uGradBot", 0.50f, 0.58f, 0.91f)
        setVec3(bgProgram, "uBlobAColor", 0.45f, 0.75f, 0.95f)
        setVec3(bgProgram, "uBlobBColor", 0.65f, 0.55f, 0.95f)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // ---- 水滴 pass：渲染到屏幕，采样背景纹理 ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        GLES30.glUseProgram(dropProgram)
        setVec2(dropProgram, "uRes", viewW.toFloat(), viewH.toFloat())
        setFloat(dropProgram, "uTime", tSec)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTexId)
        setInt(dropProgram, "uBgTex", 0)
        // 水滴固定 220dp 直径（跟旧版一致），不跟 View 大小走——View 是整片上半屏，
        // 给影子留地方，水滴本身还是 220dp。
        val density = context.resources.displayMetrics.density
        val r = 110f * density * 0.78f
        val cx = viewW / 2f
        val cy = viewH * 0.45f
        setVec2(dropProgram, "uCenter", cx, cy)
        setFloat(dropProgram, "uRadius", r)
        val driftX = (sin(tSec * 0.90) * 0.7 + sin(tSec * 0.52 + 1.7) * 0.3).toFloat() * r * driftFrac
        val driftY = (cos(tSec * 0.73 + 0.9) * 0.7 + cos(tSec * 1.10 + 2.4) * 0.3).toFloat() * r * driftFrac
        setFloat(dropProgram, "uDriftX", driftX)
        setFloat(dropProgram, "uDriftY", driftY)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, 0)
    }

    // 背景光斑相位（跟旧版 OriloBackgroundView 的 13s 流场对齐的简化版）。
    private data class Phase(val ax: Float, val ay: Float, val bx: Float, val by: Float)
    private fun huePhase(tSec: Float): Phase {
        val p = (tSec % 13f) / 13f
        val ax = 0.30f + 0.18f * sin(p * 6.2831f)
        val ay = 0.26f + 0.14f * cos(p * 6.2831f * 0.7f)
        val bx = 0.62f + 0.16f * cos(p * 6.2831f * 0.9f + 1.0f)
        val by = 0.55f + 0.15f * sin(p * 6.2831f * 0.8f + 2.0f)
        return Phase(ax, ay, bx, by)
    }

    private fun buildProgram(vert: String, frag: String): Int {
        val vs = compileShader(GLES30.GL_VERTEX_SHADER, vert)
        val fs = compileShader(GLES30.GL_FRAGMENT_SHADER, frag)
        val prog = GLES30.glCreateProgram()
        GLES30.glAttachShader(prog, vs)
        GLES30.glAttachShader(prog, fs)
        GLES30.glLinkProgram(prog)
        val link = IntArray(1)
        GLES30.glGetProgramiv(prog, GLES30.GL_LINK_STATUS, link, 0)
        if (link[0] == 0) {
            val log = GLES30.glGetProgramInfoLog(prog)
            GLES30.glDeleteProgram(prog)
            throw RuntimeException("GL program link failed: $log")
        }
        return prog
    }

    private fun compileShader(type: Int, src: String): Int {
        val sh = GLES30.glCreateShader(type)
        GLES30.glShaderSource(sh, src)
        GLES30.glCompileShader(sh)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(sh, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            val log = GLES30.glGetShaderInfoLog(sh)
            GLES30.glDeleteShader(sh)
            throw RuntimeException("GL shader compile failed: $log")
        }
        return sh
    }

    private fun loc(prog: Int, name: String): Int = GLES30.glGetUniformLocation(prog, name)
    private fun setFloat(prog: Int, name: String, v: Float) {
        GLES30.glUniform1f(loc(prog, name), v)
    }
    private fun setInt(prog: Int, name: String, v: Int) {
        GLES30.glUniform1i(loc(prog, name), v)
    }
    private fun setVec2(prog: Int, name: String, x: Float, y: Float) {
        GLES30.glUniform2f(loc(prog, name), x, y)
    }
    private fun setVec3(prog: Int, name: String, x: Float, y: Float, z: Float) {
        GLES30.glUniform3f(loc(prog, name), x, y, z)
    }
}
