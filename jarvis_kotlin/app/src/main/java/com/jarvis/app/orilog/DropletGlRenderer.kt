package com.jarvis.app.orilog

import android.content.Context
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

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
        uniform float uAbsTime;
        uniform vec2 uBlobA;
        uniform vec2 uBlobB;
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
        // 第 55 批（补）：hsv2rgb（跟 OriloBackgroundView AGSL 同一套）。
        vec3 hsv2rgb(vec3 c) {
            vec4 K = vec4(1.0, 2.0 / 3.0, 1.0 / 3.0, 3.0);
            vec3 p = abs(fract(c.xxx + K.xyz) * 6.0 - K.www);
            return c.z * mix(K.xxx, clamp(p - K.xxx, 0.0, 1.0), c.y);
        }
        void main() {
            vec2 uv = gl_FragCoord.xy / uRes;
            // 第 62 批：warp 改用 uAbsTime——FBO 的 warp 之前用相对时间 uTime，
            // AGSL 真背景的 warp 用绝对时间 iTime，两者相位对不上，FBO 采到的
            // 色相场位置跟真背景错开，水滴内部颜色跟外面背景变化不同步。
            // 改成绝对时间后 FBO 跟真背景同相位，内外颜色一起变。
            float w = noise(uv * 2.8 + vec2(uAbsTime * 0.03, 0.0)) - 0.5;
            vec2 wuv = uv + vec2(w * 0.25, noise(uv * 2.8 + vec2(0.0, uAbsTime * 0.02)) * 0.25 - 0.125);
            // 第 55 批（补）：底色改成 HSV 直接算（跟 AGSL 同一套）。色相由
            // 噪声场直接定（青 190°↔蓝 232°↔紫 267°），饱和的青、蓝、紫同框
            // 互相流；饱和度/明度仍按纵向渐变；乳白场独立（另一路 noise），
            // 饱和度压到 0.08、明度抬到 0.97，褪成乳白。青斑和乳白斑位置不同、
            // 各自流动，青↔乳白互相渗。uGradTop/Mid/Bot 不再用。
            float t = clamp(dot(wuv, vec2(0.25, 1.0)) / 1.0625, 0.0, 1.0);
            float sat = mix(mix(0.50, 0.68, clamp(t / 0.55, 0.0, 1.0)),
                            mix(0.68, 0.64, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                            step(0.55, t));
            float val = mix(mix(0.95, 0.91, clamp(t / 0.55, 0.0, 1.0)),
                            mix(0.91, 0.91, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                            step(0.55, t));
            float hueN = noise(wuv * 1.6 + vec2(uAbsTime * 0.010, uAbsTime * 0.008));
            float hue = mix(190.0, 267.0, hueN);
            float milkN = noise(wuv * 1.1 + vec2(uAbsTime * 0.006, uAbsTime * 0.005) + 7.3);
            float milkAmt = smoothstep(0.6, 0.9, milkN);
            sat = mix(sat, 0.08, milkAmt * 0.85);
            val = mix(val, 0.97, milkAmt * 0.5);
            vec3 base = hsv2rgb(vec3(hue / 360.0, sat, val));
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
        // 0.5%）。第 43 批改成只随时间变的均匀呼吸：形状永远是正圆，半径
        // 在时间上抖。
        // 第 48 批（物理想透彻了再改）：颤动换成值噪声，不再是固定正弦。
        // 水滴颤动是表面张力在拉、空气和震动在推、黏性在耗——推一下耗一下，
        // 永远不重复。Henry 实测：没有干净周期，相关时间 1–2 秒，标准差
        // 9.3px（半径 4.5%）。固定正弦频谱是死的，迟早重复，看着就机械。
        // 值噪声（hash + 平滑插值）两层叠加，相关时间约 2 秒和 0.8 秒，
        // 振幅调到标准差约 4.1%，贴近参考的 4.5%，且不重复。
        // 第 53 批（纠正“整体呼吸”）：2026-11-22 用户问“人家是呼吸效果吗？”、
        // “水滴形变”，我重算了 Henry 994 帧逐方向半径——“均匀呼吸”不成立：
        // 对向半径负相关（0°↔180° -0.33、135°↔315° -0.59），各方向半径跟
        // 平均半径的回归 R² 不到 0.1。参考不是纯呼吸，是半径抖动 + 方向性
        // 形变混在一起：一轴拉长、垂直轴收缩（体积守恒的椭球模态 l=2）。
        // 但方向性分量很小——圆拟合残差中位数只有 0.69px、90% 分位 1.8px，
        // 所以振幅只取 1.5%（约 3px），形状看着还是圆，统计上才有对向负相关。
        // 振幅大了就回到 v104 的坑洼轮廓。拉伸轴用值噪声缓慢游走
        // （相关时间约 20 秒），不固定、不周期。
        float hash1(float n) { return fract(sin(n) * 43758.5453123); }
        float vnoise(float t) {
            float i = floor(t);
            float f = fract(t);
            float u = f * f * (3.0 - 2.0 * f);
            return mix(hash1(i), hash1(i + 1.0), u) * 2.0 - 1.0;
        }
        // 第 58 批：呼吸整个拿掉——用户纠正：水滴体积不变，
        // 无论怎么变都是同体积下的变化。均匀呼吸（半径一起胀缩）会改体积：
        // 半径 205→224，体积变成 (224/205)^3 ≈ 1.30 倍，物理上错的。
        // 我 60fps 量到的“平均半径 std 2.3%”不是真在变大变小，
        // 是椭球（一轴拉长、一轴收缩）+ 边缘检测噪声在 8 个方向采样上的
        // 残差；体积守恒的形变只有 l=2 椭球模态（面积一阶守恒）。
        // 所以 breatheFactor 恒为 1.0，形变只剩椭球（3%）+ 中心漂移。
        // 第 56/57 批的呼吸系数全部作废。
        float breatheFactor(float t) {
            return 1.0;
        }
        void main() {
            vec2 fragPx = vec2(gl_FragCoord.x, uRes.y - gl_FragCoord.y);
            vec2 c = uCenter + vec2(uDriftX, uDriftY);
            vec2 local = fragPx - c;
            float dist = length(local);
            float R = uRadius;
            // 均匀呼吸 × 椭球方向性（第 53 批加，第 54 批加强）：半径随时间抖
            // （值噪声，呼吸那一半）+ 随角度调制（cos2θ，l=2 椭球模态，
            // 一轴拉长、一轴收缩）。theta 是屏幕角（0°=右、90°=下），
            // 跟 ellipAngle 同一坐标系。
            // 第 54 批：振幅 0.015→0.03（3%，R=205px 时约 6px，长短轴差
            // 约 12px）——1.5% 在真机上看不出来，用户说“还是呼吸效果”。
            // 轴游走频率 0.05→0.3（相关时间约 20 秒→约 3 秒）——20 秒
            // 在 4 秒视频里轴基本不动，方向性形变看不出来；3 秒一圈，
            // 几秒内就能看到拉伸方向在转。l=2 是平滑椭圆不是坑洼，
            // 振幅翻倍不会回到 v104 那种坑洼轮廓。
            float theta = atan(local.y, local.x);
            // 第 61 批：修“被电击”——jk-v110 真机录像（319 帧 @60fps）量出来：
            // 尺寸稳住了（平均半径 208.8px、std 0.2px，体积守恒 ✓），椭球振幅
            // 也稳（0.030），但长轴每帧平均转 6.8°、最大一帧 53.2°——轴在疯转，
            // 水滴像触电一样抽搐。第 59 批把轴频率提到 3.0 是错的：参考的快抖
            // （0.1–0.2 秒）是半径在固定方向上的小幅快变，不是轴在转。
            // 改回来：轴频率 3.0→0.3（约 3 秒，慢慢换方向）；快抖改由振幅承担——
            // 椭球振幅 0.03×(1+0.6×vnoise(3.0t))，椭圆度快速脉动（约 0.33 秒），
            // 沿同一方向一伸一缩，逐帧半径变化约 1px，跟参考一致，不抽搐。
            float ellipAngle = 6.28318 * (vnoise(uTime * 0.3 + 13.0) * 0.5 + 0.5);
            float ellipAmp = 0.03 * (1.0 + 0.6 * vnoise(uTime * 3.0 + 5.0));
            float ellip = 1.0 + ellipAmp * cos(2.0 * (theta - ellipAngle));
            float R_eff = R * breatheFactor(uTime) * ellip;
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
                // 第 50 批：影子中心改直下方。第 48 批按“光 54°→影子 126°”
                // 挪到 (-0.59R,+0.81R) 是错的——4454 参考图背景相对法量过，
                // 影子质心 (-0.07R,+1.31R)，基本正下方；主界面视频两帧也是
                // (+0.15~0.21R,+1.27~1.59R)，也是正下方。光主要是正面打过来
                // （高光在右上是镜面反射角，影子投在背景上基本不偏），
                // 所以影子就放正下方 1.3R。半径 1.4R、强度 0.70 不动。
                vec2 sCenter = c + vec2(0.0, R * 1.3);
                float sDist = length(fragPx - sCenter);
                float shadow = (1.0 - smoothstep(0.0, R * 1.4, sDist)) * 0.70;
                // 外面聚光：半透明白，提亮下面真正的背景。
                // 第 50 批：位置 1.5R→1.0R（4454 量到最亮点 (-0.75R,+0.72R)，
                // 离中心 1.04R；主界面视频 (-0.67R,+0.77R)，1.02R），
                // 强度 0.10→0.70（0.10 在阴影里只能提亮约 9/255，根本看不见；
                // 参考外面亮斑比背景亮 30–48，强度 0.70 手算约提亮 28–36）。
                vec2 ocCenter = c + vec2(-R * 1.0 * 0.7071, R * 1.0 * 0.7071);
                float ocDist = length(fragPx - ocCenter);
                float oc = (1.0 - smoothstep(0.0, R * 0.6, ocDist)) * 0.70;
                float a = clamp(shadow + oc, 0.0, 1.0);
                if (a > 0.01) {
                    // 第 53 批：预乘 alpha 写法。jk-v106 真机证实：外面聚光爆成
                    // 纯白 255（比当地背景高 111，参考只高 30–48）——Android
                    // 的 SurfaceFlinger 用预乘 alpha 合成透明 GLSurfaceView，
                    // 直 alpha 写法 vec3(oc/a) 在 a 中等处被放大、截断到纯白。
                    // 预乘写法 vec3(oc)：输出的 RGB 就是已乘过 alpha 的颜色，
                    // 合成 = oc + 背景×(1−a)，亮斑强度回到参考水平。
                    // 影子不受影响（黑在两种模式下一样），所以 v106 影子是对的。
                    oColor = vec4(vec3(oc), a);
                    return;
                }
                oColor = vec4(0.0);
                return;
            }
            vec2 n2 = local / R_eff;
            float nz = sqrt(max(0.0, 1.0 - dot(n2, n2)));
            // 折射：沿法线弯折，采样真实背景纹理。n2 是在翻转后（Android）空间里算的，
            // 偏移量的 y 分量要翻回来（取负），再加到没翻转的 gl_FragCoord 上。
            // 第 50 批：bend 120→180。用户说折射“还是淡的”——里面比背景暗、
            // 渐变错位那层意思有，但背景透过水滴的错位太轻，看着像平玻璃。
            // 加到 180，错位更明显；磨砂水滴，180 还不会变成透明弹珠的强倒像。
            float bend = 180.0;
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
            // 第 49 批：高光独立游走。参考里高光自己还在 ±25px（0.12R）范围里
            // 游走，不只是跟着水滴中心漂——表面微小起伏改变局部法线，反射点
            // 就跟着动。用值噪声（跟颤动同一套函数，不同种子、更慢的速度，
            // 相关时间约 4 秒），缓慢漂移、不抖动。
            vec2 hlWander = vec2(0.12 * vnoise(uTime * 0.25 + 11.0),
                0.12 * vnoise(uTime * 0.22 + 23.0));
            vec2 hlC = vec2(0.47, -0.65) + hlWander;
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
            // 第 66 批：去白边——用户拿 4454 参考图问“你看这个有白边吗”。
            // 没有。参考的水滴边缘没有一圈连续的白线：边缘是靠折射（背景透
            // 过水滴变形）和右上高光、顶部细月牙、左右小亮点、底部笑脸来
            // 界定的，本身是透明的。我的 rimBright 用 lightFace 下限 0.55，
            // 背光面也有 0.18×0.55≈0.10 的亮边，整圈连起来就是一圈淡淡的
            // 白线，像气球的轮廓。改成下限 0.10：背光面亮边 0.018（看不见），
            // 朝光面 0.18（保留），亮边只在受光那侧，不再连成一圈。
            float lightFace = 0.10 + 0.90 * max(0.0, dot(normalize(n2), normalize(vec2(0.47, -0.65))));
            // 第 51 批：笑脸方向的亮边再压下去。Claude 重算指出笑脸峰值
            // 系统性亮约 60 点，根子是 rimBright 在笑脸方向贡献太多，
            // 光调笑脸系数压不下来（底子不跟着系数变）。把笑脸方向的
            // 亮边压制从 0.6 提到 0.85（笑脸中心处亮边只剩 15%，原来 40%），
            // 配合笑脸系数减半，峰值约 194–205，落进目标区间。
            float rimBright = edge * (1.0 - topDarkMask) * (1.0 - smileMask * 0.85) * 0.18 * lightFace;
            // 暗边。第 45 批：0.40→0.25，别把边缘压得太黑，参考的边缘是
            // 通透的、不是一圈黑。
            float rimDark = smoothstep(R_eff * 0.97, R_eff, dist) * 0.25 * (1.0 - topDarkMask);
            // 小高光：第 52 批移位。旧值（-0.45r/+0.42r）是第 41 批从旧版
            // 抄来的，从没实测过。2026-11-21 另一位审稿人把 210 帧全量完，
            // 指出小高光比代码写得更靠左、更不靠下（-0.6/0.25），但自己
            // 没把握（小高光暗、跟边缘反光界限不清）。我用窄窗口复核了
            // 清醒态 174 帧（屏幕角 135–200°、半径 0.45–0.85R，只取最亮点）：
            // dx/R = -0.65（-0.70..-0.62）、dy/R = +0.12（0.07..0.17），
            // 标准差只有 0.02，亮度 235（221–255），是稳定亮斑，不是噪声。
            // 位置约 170°（屏幕角，近 9 点方向），半径 0.66R。
            // 4454 参考图也说左边缘约 9 点钟有小亮点，对得上。
            // 所以从（-0.45, +0.42）移到（-0.65, +0.12）。半径 0.14、峰值 0.70 不变。
            vec2 shC = vec2(-0.65, 0.12);
            vec2 shD = n2 - shC;
            float smallHighlight = exp(-dot(shD, shD) / (0.14 * 0.14) * 1.5) * 0.70;
            // 右侧小亮点：第 68 批。参考 4454 左右边缘各有一个小亮点，
            // 左边的已有（-0.65, +0.12），右边的缺了。补一个镜像点
            // (+0.65, +0.12)，同半径、同峰值。
            vec2 shRC = vec2(0.65, 0.12);
            vec2 shRD = n2 - shRC;
            float smallHighlightR = exp(-dot(shRD, shRD) / (0.14 * 0.14) * 1.5) * 0.70;
            // 顶部细月牙：第 68 批。参考 4454 顶部有一道细月牙——一道细细的
            // 亮弧，贴着水滴上缘内侧弯着，从左上到右上。不是大高光（那是右上
            // 实心白块），是独立的细弧。实现：顶部方向（0,-1）的点积定角度
            // 范围（顶部 ±41° 内），半径 0.90R 处高斯细带，薄、亮、两端渐隐。
            vec2 topDir = vec2(0.0, -1.0);
            float topDot = dot(normalize(n2), topDir);
            float cresR = length(n2);
            float crescent = smoothstep(0.75, 0.95, topDot) * exp(-pow((cresR - 0.90) / 0.035, 2.0)) * 0.60;
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
            // 第 49 批：笑脸亮度起伏。主界面 30fps 实测笑脸 155–227、均值 174，
            // 亮度是活的，跟着呼吸走——水滴胀缩时底部曲率和反射角变化，菲涅尔
            // 反射亮度跟着变。
            // 第 51 批：系数 0.20→0.10。Claude 按实际公式重算：第 49 批的
            // “158–238”是线性缩放，错的——笑脸是加在背景+亮边+漫反射的底子上，
            // 不是从 0 开始正比。实际是系数 0.16→220、0.20→231、0.24→241，
            // 比目标（155–227）系统性亮约 60 点。光调系数压不下来，
            // 因为底子（背景 170 + 亮边）不跟着系数变。系数减半到 0.10，
            // 再配合亮边在笑脸方向多压一点（见 rimBright），峰值约 194–205，
            // 落进目标区间（4454 目标 203）。
            // 第 50 批：漫反射 0.06→0.12。里面太平、太暗（v105 中心 ~151，
            // 参考 166–216 平滑渐变）——漫反射只给 0.06，里面拉不出渐变。
            // 翻倍，朝光面亮、背光面暗，里面有了明暗，折射那层“淡”也跟着好起来。
            float smileCoeff = 0.10 * (1.0 + 0.20 * vnoise(uTime * 0.5 + 31.0));
            // 第 67 批：球和背景太分明——用户指出水滴像个独立的球，跟背景
            // 是分开的。参考 4454 里水滴是透明的：背景透过水滴，带着渐变
            // 和折射，里面比背景略暗，水滴融在背景里。我的里面太均匀、太亮，
            // 像个实心蓝球——根子是漫反射 diffuse 0.12 全幅提亮，把折射进来
            // 的背景渐变洗平了。降到 0.06：里面不再均匀发亮，背景渐变透出来，
            // 水滴跟背景融在一起。折射 bend 180 不动（用户之前说折射淡才加到
            // 180 的）。
            result += vec3(1.0) * (smileBright * smileCoeff + diffuse * 0.06 + rimBright + highlight + smallHighlight + smallHighlightR + crescent + caustic * 0.5);
            // 涟漪：第 47 批（1:1 复制，用户令）。三点轮流起波，每 45 秒一圈，
            // 间隔 18/15/12 秒。波从源点沿水滴表面扩散，随距离和时间衰减，
            // 平时没有。三个源点在边缘，屏幕角：
            // 左下 135°（2.356 rad），触发 t%15=0；
            // 右下 45°（0.785 rad），触发 t%15=6；
            // 左上 225°（-2.356 rad），触发 t%15=11。
            // 第 51 批：纠正第 50 批的错误“修正”，源点角度用屏幕角（n2.y
            // 为正 = 屏幕向下），第 47 批的原值（2.356/0.785/-2.356）是对的。
            // 第 60 批：振幅 0.05→0.15（真机上看不到）。第 62 批：15 秒一圈、
            // 振幅 0.30（45 秒一圈 5 秒录像容易错过）。
            // 第 65 批：用户指出涟漪做成了中心放射线——错了。旧写法用 dAng
            // （到源点的角度差）当距离，相位只跟角度有关、跟半径无关，
            // 同一角度上所有半径亮度一样，就成了从中心发散的射线。
            // 涟漪是三个点受力产生的点源波：波前是以源点为圆心的圆，
            // 向外扩。改成到源点的实际表面距离 d = length(n2 - src)，
            // 相位 sin(d*6 - dt*2.5)，波前是绕源点的圆环，越扩越淡。
            float ripple = 0.0;
            {
                vec2 src = vec2(cos(2.356), sin(2.356));
                float d = length(n2 - src);
                float dt = mod(uTime - 0.0, 15.0);
                ripple += sin(d * 6.0 - dt * 2.5) * exp(-d * 1.2) * exp(-dt * 0.7);
            }
            {
                vec2 src = vec2(cos(0.785), sin(0.785));
                float d = length(n2 - src);
                float dt = mod(uTime - 6.0, 15.0);
                ripple += sin(d * 6.0 - dt * 2.5) * exp(-d * 1.2) * exp(-dt * 0.7);
            }
            {
                vec2 src = vec2(cos(-2.356), sin(-2.356));
                float d = length(n2 - src);
                float dt = mod(uTime - 11.0, 15.0);
                ripple += sin(d * 6.0 - dt * 2.5) * exp(-d * 1.2) * exp(-dt * 0.7);
            }
            result += vec3(1.0) * ripple * 0.30;
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
        // 第 55 批：空间色相场用绝对时间（跟 AGSL 的 iTime 同一个挂钟），
        // 保证 FBO 跟真背景同相位。uTime 是相对 startMs 的（水滴颤动用），
        // 不能拿来算背景色相场，否则 FBO 跟真背景的紫蓝边界对不上。
        setFloat(bgProgram, "uAbsTime", android.os.SystemClock.uptimeMillis() / 1000f)
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
        // 第 55 批（补）：底色色相改由着色器内噪声场直接定（hsv2rgb），
        // uGradTop/Mid/Bot 不再用，这里不再传。光斑颜色仍由 CPU 算。
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
        // 第 50 批：19% 必须按屏幕宽算，不能按 viewW 算。面板有 24dp 左右
        // 内边距，dropWrap 宽只有 949px（屏宽 1080 − 48dp×2.73），
        // viewW×0.19 只有 180px，比参考 206px 小了 25px。
        // 用 displayMetrics.widthPixels（屏幕宽 1080）算，R=205px。
        val screenW = context.resources.displayMetrics.widthPixels.toFloat()
        val r = screenW * 0.19f
        val cx = viewW / 2f
        val cy = viewH * 0.45f
        setVec2(dropProgram, "uCenter", cx, cy)
        setFloat(dropProgram, "uRadius", r)
        // 第 54 批：中心漂移从正弦改成值噪声。之前的 sin/cos 是周期函数，
        // 中心按固定轨迹平滑地转圈，看着机械。改成值噪声（跟呼吸同一套
        // 思路，不同种子、相关时间约 2 秒和 0.8 秒），中心不规则地漂，
        // 不重复、不周期。vnoise/hash1 在下面，跟 GLSL 里同一套公式。
        val driftX = (vnoise(tSec * 0.5f) * 0.7f + vnoise(tSec * 1.3f + 7f) * 0.3f) * r * driftFrac
        val driftY = (vnoise(tSec * 0.45f + 11f) * 0.7f + vnoise(tSec * 1.1f + 23f) * 0.3f) * r * driftFrac
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

    /** 第 54 批：值噪声（跟 GLSL 里同一套公式），中心漂移用。不规则、不周期。 */
    private fun hash1(n: Float): Float {
        val x = kotlin.math.sin(n.toDouble()) * 43758.5453123
        return (x - kotlin.math.floor(x)).toFloat()
    }

    private fun vnoise(t: Float): Float {
        val i = kotlin.math.floor(t.toDouble()).toFloat()
        val f = t - i
        val u = f * f * (3f - 2f * f)
        val a = hash1(i)
        val b = hash1(i + 1f)
        return (a * (1f - u) + b * u) * 2f - 1f
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
