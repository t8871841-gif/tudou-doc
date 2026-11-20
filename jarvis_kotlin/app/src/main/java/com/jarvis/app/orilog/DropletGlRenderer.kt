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
        // 颤动（第 43 批重写，第 41 批的角向颤动是错的——用户 2026-11-20 15:09 指出
        // “每帧都是奇形怪状”：角向颤动让每一帧的轮廓都是坑坑洼洼的不规则形，
        // 但参考图是正圆，Henry 30fps 实测圆拟合残差只有 1.07px（半径 206px 的
        // 0.5%）。参考的 ±5% 颤动是半径随时间均匀呼吸（整圆一起胀缩），不是
        // 边缘按角度变形。改成只随时间变的均匀呼吸：形状永远是正圆，半径
        // 在时间上抖。
        // 第 48 批（物理想透彻了再改）：颤动换成值噪声，不再是固定正弦。
        // 水滴颤动是表面张力在拉、空气和震动在推、黏性在耗——推一下耗一下，
        // 永远不重复。Henry 实测：没有干净周期，相关时间 1–2 秒，标准差
        // 9.3px（半径 4.5%）。固定正弦频谱是死的，迟早重复，看着就机械。
        // 值噪声（hash + 平滑插值）两层叠加，相关时间约 2 秒和 0.8 秒，
        // 振幅调到标准差约 4.1%，贴近参考的 4.5%，且不重复。
        float hash1(float n) { return fract(sin(n) * 43758.5453123); }
        float vnoise(float t) {
            float i = floor(t);
            float f = fract(t);
            float u = f * f * (3.0 - 2.0 * f);
            return mix(hash1(i), hash1(i + 1.0), u) * 2.0 - 1.0;
        }
        float breatheFactor(float t) {
            return 1.0
                + 0.065 * vnoise(t * 0.5)
                + 0.030 * vnoise(t * 1.3 + 7.0);
        }
        void main() {
            vec2 fragPx = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
            vec2 c = uCenter + vec2(uDriftX, uDriftY);
            vec2 local = fragPx - c;
            float dist = length(local);
            float R = uRadius;
            // 均匀呼吸后的有效半径：只随时间变，形状永远是正圆。
            float R_eff = R * breatheFactor(uTime);
            // 水滴外：影子 / 外面聚光 / 全透明。GL View 现在是透明的（第 39 批，
            // 用户指出水滴周围被截成正方形、跟背景格格不入——之前 GL 自己画背景，
            // 渐变跟下面真正的背景对不上）。这里不再输出背景，只输出影子/聚光，
            // 其余全透明，让下面真正的背景露出来。
            // 第 40 批：分界从 1.18R 改成 R——1.18 是旧 AGSL 版的颤动余量
            // （TUNE_SHAPE_MARGIN，水滴边缘颤动峰值会探出 R），GL 版没有颤动，
            // 余量留着就成了一个圈：0.90R 处 edge 开始、1.18R 处渲染截止，
            // 两道边界叠出来就是用户说的“两圈”。
            // 第 42 批（Claude 审查点 12）：分界从 R_eff 放到 1.02*R_eff，
            // 给水滴边缘留一小段 alpha 渐变做抗锯齿，不然 R 处硬截止在真机上会看出锯齿。
            if (dist > R_eff * 1.02) {
                // 第 48 批（物理想透彻了再改）：影子和亮斑叠加，不再“影子优先返回”。
                // 之前是阴影 > 0.01 就直接返回，亮斑永远画不出来——散射的光
                // 应该透过阴影照出来，亮斑长在阴影里面。现在两处一起算：
                // 影子压暗、亮斑提亮，合成一个输出（白×亮斑 + 背景×(1−影子−亮斑)）。
                // 影子中心也挪到光的方向：光在 54°（右上），影子顺着光往左下偏，
                // 中心 (-0.59R, +0.81R)，方向 126°；之前是 (-0.18R, +1.0R)，
                // 几乎正下方，往左偏得不够，差了约 26°。
                vec2 sCenter = c + vec2(-R * 0.59, R * 0.81);
                float sDist = length(fragPx - sCenter);
                float shadow = (1.0 - smoothstep(0.0, R * 1.4, sDist)) * 0.70;
                // 外面聚光：半透明白，提亮下面真正的背景。
                vec2 ocCenter = c + vec2(-R * 1.5 * 0.7071, R * 1.5 * 0.7071);
                float ocDist = length(fragPx - ocCenter);
                float oc = (1.0 - smoothstep(0.0, R * 0.6, ocDist)) * 0.10;
                float a = clamp(shadow + oc, 0.0, 1.0);
                if (a > 0.01) {
                    // 颜色 = 白×亮斑占比（亮斑 0 时纯黑、影子 0 时纯白），
                    // alpha = 两者之和。SurfaceFlinger 再跟真背景合成。
                    oColor = vec4(vec3(oc / a), a);
                    return;
                }
                oColor = vec4(0.0);
                return;
            }
            vec2 n2 = local / R_eff;
            float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
            // 折射：沿法线弯折，采样真实背景纹理。n2 是在翻转后（Android）空间里算的，
            // 偏移量的 y 分量要翻回来（取负），再加到没翻转的 gl_FragCoord 上。
            float bend = 120.0;
            vec2 refractUv = gl_FragCoord.xy / uRes + vec2(n2.x, -n2.y) * bend * (1.0 - nz) / uRes;
            vec3 refracted = texture(uBgTex, refractUv).rgb;
            // 边缘（按呼吸后的有效半径算）。第 45 批（DeepSeek 对比点 1、4）：
            // 亮边带从 0.90R–R 收窄到 0.94R–R——之前那圈白边太粗、太实，
            // 像霓虹描边。参考的亮边是细细的一线，贴着轮廓。
            float edge = smoothstep(R_eff * 0.94, R_eff, dist);
            // 顶部暗带（~40° 宽）
            float cosTop = -n2.y;
            float topDarkMask = smoothstep(0.94, 0.98, cosTop);
            // 底部笑脸
            vec2 bottomDir = normalize(vec2(0.12, 0.99));
            float cosBottom = dot(normalize(n2), bottomDir);
            float smileMask = smoothstep(0.60, 0.85, cosBottom);
            float smileBright = edge * smileMask;
            // 暗带环。第 45 批：跟着亮边带一起收窄（0.94R–0.975R），别让暗带
            // 盖住太多里面。
            float darkBand = smoothstep(R_eff * 0.94, R_eff * 0.975, dist)
                * (1.0 - smoothstep(R_eff * 0.975, R_eff, dist));
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
            // 第 44 批（DeepSeek 对比）：高光指数从 1.5 放到 1.0，边缘过渡
            // 更柔和，别像 2D 软件里直接画上去的死白块。峰值仍是 1.0，
            // 中心照样顶白 255（参考的高光中心也是纯白），只是边缘化开。
            float highlight = exp(-dot(hlQ, hlQ) * 1.0);
            // 亮边（菲涅尔）：第 45 批（DeepSeek 对比点 1、4）——系数 0.28→0.18。
            // 参考的素边接近背景色（170-185），我们之前亮到 203，整圈白边
            // 太实、像塑料球描边。降下来，让亮边细、淡，只在朝光面显出来
            // （lightFace 已经在跟着光走了）。
            float lightFace = 0.55 + 0.45 * max(0.0, dot(normalize(n2), normalize(vec2(0.47, -0.65))));
            float rimBright = edge * (1.0 - topDarkMask) * (1.0 - smileMask * 0.6) * 0.18 * lightFace;
            // 暗边。第 45 批：0.40→0.25，别把边缘压得太黑，参考的边缘是
            // 通透的、不是一圈黑。
            float rimDark = smoothstep(R_eff * 0.97, R_eff, dist) * 0.25 * (1.0 - topDarkMask);
            // 小高光：左下（-0.45r/+0.42r），半径 0.14r，峰值 0.70（旧版第二个高光，
            // 第 41 批补上；位置跟聚光（225°/0.6R）很近，两处亮斑叠在一起是参考图
            // 左下那片亮）。
            vec2 shC = vec2(-0.45, 0.42);
            vec2 shD = n2 - shC;
            float smallHighlight = exp(-dot(shD, shD) / (0.14 * 0.14) * 1.5) * 0.70;
            // 聚光（左下 225°，0.6R 处）
            vec2 causticDir = vec2(-0.7071, 0.7071);
            float causticDist = length(n2 - causticDir * 0.6);
            float caustic = (1.0 - smoothstep(0.0, 0.55, causticDist)) * 0.30;
            // 合成：先把折射背景和原背景按 alpha 混合（中心透明、边缘 0.22，
            // 跟旧版一致）；光学特征（高光、笑脸、亮边、聚光、漫反射）是加性光，
            // 必须直接加在结果上——之前把它们放进 colorOut 再乘 alpha，
            // alpha 只有 0.01–0.22，特征全被压没了，水滴才成了一块平板。
            // 这是 v103 真机（2026-11-20 09:52 截图量测）找到的根因：
            // 中心 176 全平、右上高光没出现（315° 方向 170 平）、只有聚光
            // （直接加在结果上那项）亮到了 222。
            // 第 45 批（DeepSeek 对比点 1）：里面不透明、像塑料球——alpha 上限
            // 0.22→0.15，让里面更透、更接近背景。参考的里面几乎就是背景色，
            // 只有一层很淡的折射。中心 alpha 仍是 0.01（几乎全透）。
            float alpha = mix(0.01, 0.15, edge);
            vec3 bg = texture(uBgTex, gl_FragCoord.xy / uRes).rgb;
            vec3 result = refracted * 0.98 * alpha + bg * (1.0 - alpha);
            // 暗特征先乘（暗带、暗边只压暗水滴表面）。
            result *= (1.0 - darkBand * 0.35 * topDarkMask);
            result *= (1.0 - rimDark);
            // 亮特征直接加（不乘 alpha）。
            // 第 47 批（1:1 复制）：笑脸系数 0.25→0.20。v105 真机量过，
            // 笑脸在底部边缘（y≈685，x=420–500），峰值 248，顶白了，
            // 参考是 203。降下来，往 203 靠。
            result += vec3(1.0) * (smileBright * 0.20 + diffuse * 0.06 + rimBright + highlight + smallHighlight + caustic * 0.5);
            // 涟漪：第 47 批（1:1 复制，用户令）。三点轮流起波，每 45 秒一圈，
            // 间隔 18/15/12 秒。波从源点沿水滴表面扩散，随角度距离和时间衰减，
            // 平时没有。角度用屏幕坐标（atan2，+y 朝下：0°=右，90°=下）。
            // 三个源（用户给的角度是数学约定 0°=右、逆时针，换算到屏幕坐标）：
            // 左下 225° → 屏幕 135°（2.356 rad），触发 t%45=0；
            // 右下 315° → 屏幕 45°（0.785 rad），触发 t%45=18；
            // 左上 135° → 屏幕 -135°（-2.356 rad），触发 t%45=33。
            // 波沿表面走，振幅很小（0.05），只求一丝动感，别喧宾夺主。
            float ripple = 0.0;
            float surfAng = atan(n2.y, n2.x);
            {
                float dAng = abs(mod(surfAng - 2.356 + 3.14159, 6.28318) - 3.14159);
                float dt = mod(uTime - 0.0, 45.0);
                ripple += sin(dAng * 6.0 - dt * 2.5) * exp(-dAng * 1.2) * exp(-dt * 0.7);
            }
            {
                float dAng = abs(mod(surfAng - 0.785 + 3.14159, 6.28318) - 3.14159);
                float dt = mod(uTime - 18.0, 45.0);
                ripple += sin(dAng * 6.0 - dt * 2.5) * exp(-dAng * 1.2) * exp(-dt * 0.7);
            }
            {
                float dAng = abs(mod(surfAng - (-2.356) + 3.14159, 6.28318) - 3.14159);
                float dt = mod(uTime - 33.0, 45.0);
                ripple += sin(dAng * 6.0 - dt * 2.5) * exp(-dAng * 1.2) * exp(-dt * 0.7);
            }
            result += vec3(1.0) * ripple * 0.05;
            // 边缘抗锯齿（第 42 批）：R_eff 以内不透明，R_eff→1.02*R_eff 淡出到透明。
            // 这一环里 n2 长度略大于 1、nz=0，折射偏移最大，但只有 2% 宽且在淡出，
            // 看不出错色。
            float edgeAlpha = 1.0 - smoothstep(R_eff, R_eff * 1.02, dist);
            oColor = vec4(result, edgeAlpha);
        }
    """.trimIndent()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        // 第 39 批：透明 Surface，清成全透明（水滴外由着色器输出 alpha=0）。
        GLES30.glClearColor(0f, 0f, 0f, 0f)
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
        // 第 42 批（Claude 审查点 5）：FBO 背景跟真背景同相位——之前 FBO 用的是
        // 写死的固定三色，真背景会随时间做色相漂移（青↔蓝↔紫），时间一长两者
        // 色相差拉开，水滴内部折射采到的颜色跟外面真背景对不上，边缘会看出
        // 一条“内外不一致”的分界。改成直接调 OriloBackgroundView.phaseAt()
        // （跟真背景同一个相位公式、同一套绝对时间），再用静态 hueColor()
        // 把三色 + 两光斑算出来传进 uniform。光斑位置（ax/ay/bx/by）也用
        // phaseAt 的，不再自己抄一份公式。
        val phase = com.jarvis.app.OriloBackgroundView.phaseAt(android.os.SystemClock.uptimeMillis())
        setVec2(bgProgram, "uBlobA", phase.ax * viewW, phase.ay * viewH)
        setVec2(bgProgram, "uBlobB", phase.bx * viewW, phase.by * viewH)
        setColor3(bgProgram, "uGradTop", com.jarvis.app.OriloBackgroundView.hueColor(232f, 0.50f, 0.95f, phase.hueShift, phase.satScale))
        setColor3(bgProgram, "uGradMid", com.jarvis.app.OriloBackgroundView.hueColor(232f, 0.68f, 0.91f, phase.hueShift, phase.satScale))
        setColor3(bgProgram, "uGradBot", com.jarvis.app.OriloBackgroundView.hueColor(243f, 0.64f, 0.91f, phase.hueShift, phase.satScale))
        setColor3(bgProgram, "uBlobAColor", com.jarvis.app.OriloBackgroundView.hueColor(com.jarvis.app.OriloBackgroundView.TUNE_BLOB_A_HUE, com.jarvis.app.OriloBackgroundView.TUNE_BLOB_A_SAT, com.jarvis.app.OriloBackgroundView.TUNE_BLOB_A_VAL, phase.hueShift, phase.satScale))
        setColor3(bgProgram, "uBlobBColor", com.jarvis.app.OriloBackgroundView.hueColor(com.jarvis.app.OriloBackgroundView.TUNE_BLOB_B_HUE, com.jarvis.app.OriloBackgroundView.TUNE_BLOB_B_SAT, com.jarvis.app.OriloBackgroundView.TUNE_BLOB_B_VAL, phase.hueShift, phase.satScale))
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
        // 第 47 批（1:1 复制，用户令）：水滴半径改成参考值。之前是
        // 110dp×density×0.78，抄旧版抄来的，在用户手机上算出来 234px，
        // 参考 Henry 实测是 206px（屏宽 1080 的 19%）。改成屏宽的 19%，
        // 在这台手机上就是 206px，跟参考 1:1。不再用 dp 抄旧版。
        val r = viewW * 0.19f
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

    /** 第 42 批：把 hueColor() 算出来的 Int 颜色拆成 0..1 的 RGB 传给 uniform。 */
    private fun setColor3(prog: Int, name: String, color: Int) {
        GLES30.glUniform3f(
            loc(prog, name),
            android.graphics.Color.red(color) / 255f,
            android.graphics.Color.green(color) / 255f,
            android.graphics.Color.blue(color) / 255f,
        )
    }
}
