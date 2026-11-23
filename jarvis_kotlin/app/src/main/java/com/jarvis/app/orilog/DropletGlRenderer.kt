package com.jarvis.app.orilog

import android.content.Context
import android.opengl.GLES30
import android.os.SystemClock

/**
 * Orilo 路线的水滴渲染器（第 36 批起）。
 *
 * 架构（照 Orilo 原版路线，自己写的 GLSL，不抄它的代码）：
 * 1. 背景 pass：把程序化背景（渐变 + 光斑 + 流场）渲染到 FBO 纹理。
 * 2. 贴图 pass（第 80 批）：把 FBO 纹理全屏贴到屏幕上——屏幕上看到的背景
 *    就是这份 FBO 纹理，不再是另一套 AGSL 渲染。只此一份背景，水滴采样它、
 *    眼睛看它，颜色不可能不同步。
 * 3. 水滴 pass：全屏大三角，片元着色器里逐像素算水滴光学——
 *    采样背景纹理做折射（真实背景采样，不是重算假背景），
 *    再加高光、笑脸、暗带、亮边、聚光、影子。
 * 4. 场景参数（中心漂移、颤动相位）每帧 CPU 算好、经 uniform 传。
 *
 * 跟旧 AGSL 版的关键差别：背景是真实渲染出来的纹理，水滴折射采的是它，
 * 不是在片元里重算一个平滑函数——折射在平滑渐变上看不见的问题从根上解决。
 *
 * 第 80 批（用户令，方案一，要惊艳效果）：单套渲染。之前是两套背景各渲各的——
 * 屏幕上的背景是 OriloBackgroundView（AGSL），水滴里透的是 FBO（GLSL），
 * 时间、光斑、噪声精度稍有出入，颜色就对不上。现在 GLSurfaceView 自己全屏画
 * 背景（贴 FBO 纹理），OriloBackgroundView 不再上屏。水滴采样和眼睛看到的是
 * 同一份纹理，同步是结构保证的，不靠参数对齐。
 *
 * 第 81 批：GLSurfaceView → TextureView。jk-v117 真机：气泡/按钮的液态玻璃
 * 全黑——LiquidGlassView 靠把身后层级光栅化成位图再折射，GLSurfaceView 的像素
 * 在独立 Surface 上，光栅化时是黑洞，玻璃采到黑色。TextureView 的内容画在
 * View 层级里，光栅化能看到，玻璃恢复正常。单套渲染不变（同一份 FBO 纹理），
 * 同步仍是结构保证。EGL/渲染线程由 DropletGlView 管，渲染器只保留
 * onSurfaceCreated/onSurfaceChanged/onDrawFrame 三个方法，不再实现
 * GLSurfaceView.Renderer。
 */
class DropletGlRenderer(private val context: Context) {

    /** 中心漂移幅度（半径分数），默认 0.05。 */
    @Volatile
    var driftFrac: Float = 0.05f

    // ---- 悬浮球模式（第 83 批，用户令：悬浮球也换成水滴，主水滴的 2/3）----
    // 主界面是整屏不透明：贴图 pass 把 FBO 背景全屏贴出来，水滴采同一份纹理。
    // 悬浮球浮在别的 App 上面，窗口是半透明的：水滴外必须全透明（只透出水滴
    // 自己 + 它投在下面 App 上的影子），不能贴背景。FBO 背景照常渲染——
    // 只留给水滴内部折射采样用，不再显示。着色器本身全按 R 的相对量算
    // （高光、亮点、月牙、笑脸、影子、涟漪、折射 bend 都是 R 的倍数或
    // UV 归一化量），小尺寸下特征 1:1 缩放，不用改参数。
    /** true = 悬浮球模式：不贴背景、帧缓冲先清透明、水滴 pass 用预乘混合。 */
    @Volatile
    var orbMode: Boolean = false
    /** 半径系数：主界面 1.0（屏宽 19%），悬浮球 2/3。 */
    @Volatile
    var radiusScale: Float = 1f
    /** 水滴中心 Y 占 viewH 的比例：主界面 0.225；悬浮球 0.40——窗口要装下
     *  水滴 + 正下方影子（影子底约中心 +1.5R），中心不能放太低。 */
    @Volatile
    var centerYFrac: Float = 0.225f

    private var bgProgram = 0
    private var dropProgram = 0
    private var blitProgram = 0
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
        uniform float uOrbMode;
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
            // 第 82 批：流动再快一点——warp 与色相场的时间系数 ×1.5。
            // 第 84 批（用户令：背景颜色变化不自然、不流畅）：Henry 视频 30fps
            // 量出来，Orilo 色相在固定点上 2–5°/秒稳步单向走，我们只有 0.2–1°/秒。
            // 时间系数再 ×4（累计 ×6）：warp 0.045→0.18、0.03→0.12，
            // 色相场 0.015→0.06、0.012→0.048，目标 2–5°/秒。
            float w = noise(uv * 2.8 + vec2(uAbsTime * 0.18, 0.0)) - 0.5;
            vec2 wuv = uv + vec2(w * 0.25, noise(uv * 2.8 + vec2(0.0, uAbsTime * 0.12)) * 0.25 - 0.125);
            // 第 55 批（补）：底色改成 HSV 直接算（跟 AGSL 同一套）。色相由
            // 噪声场直接定，饱和的青、蓝、紫同框互相流；饱和度/明度仍按纵向渐变。
            // 第 82 批（用户令：背景只保留图片上颜色）：4454_1_3jar.png 实测背景
            // 只有 230°–267°（左上 261°、右下 230°），色相场 210°↔285° 两头
            // 都超了，收进 230°↔267°；乳白场（另一路 noise 把饱和度压到 0.08、
            // 明度抬到 0.97）整段删掉——图里没有乳白色，乳白斑是画蛇添足。
            // uGradTop/Mid/Bot 不再用。
            float t = clamp(dot(wuv, vec2(0.25, 1.0)) / 1.0625, 0.0, 1.0);
            float sat = mix(mix(0.50, 0.68, clamp(t / 0.55, 0.0, 1.0)),
                            mix(0.68, 0.64, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                            step(0.55, t));
            float val = mix(mix(0.95, 0.91, clamp(t / 0.55, 0.0, 1.0)),
                            mix(0.91, 0.91, clamp((t - 0.55) / 0.45, 0.0, 1.0)),
                            step(0.55, t));
            float hueN = noise(wuv * 1.6 + vec2(uAbsTime * 0.06, uAbsTime * 0.048));
            float hue = mix(230.0, 267.0, hueN);
            vec3 base = hsv2rgb(vec3(hue / 360.0, sat, val));
            float fa = smoothstep(0.0, 1.0, clamp(1.0 - length(wuv * uRes - uBlobA) / (uRes.x * 0.45), 0.0, 1.0));
            base = mix(base, uBlobAColor, fa * 0.55);
            float fb = smoothstep(0.0, 1.0, clamp(1.0 - length(wuv * uRes - uBlobB) / (uRes.x * 0.40), 0.0, 1.0));
            base = mix(base, uBlobBColor, fb * 0.45);
            // 第 84 批（用户令：悬浮球紫色不好看，要淡紫+淡蓝互相流）：
            // 球在别的 App 上面，看不见底下真实背景，折射采到的还是 App 自己
            // 那套深紫背景（饱和 50–68%），球像颗实心紫球。悬浮球模式把折射源
            // 往白里褪 30%（第 86 批：84 批的 55% 加白太多、球发白发浅）：
            // 色相不动（230° 蓝↔267° 紫还在），饱和压到约 35–45%、明度抬到
            // 约 94–99%，球里就是淡紫淡蓝互相流动。主界面不动
            // （uOrbMode=0 时原样，水滴跟背景配得上）。
            if (uOrbMode > 0.5) { base = mix(base, vec3(1.0), 0.30); }
            oColor = vec4(base, 1.0);
        }
    """.trimIndent()

    // ---- 贴图片元着色器（第 80 批）：把 FBO 背景纹理原样贴满屏幕 ----
    // 屏幕上看到的背景就是这份纹理，水滴采的也是它——同一份，同步是结构保证的。
    // 注意 GL 纹理坐标原点在左下、gl_FragCoord 原点也在左下，直接除以分辨率即可，
    // 不用翻转（FBO 渲染时 gl_FragCoord 也是左下原点，两边一致）。
    private val blitFragSrc = """
        #version 300 es
        precision highp float;
        uniform vec2 uRes;
        uniform sampler2D uTex;
        out vec4 oColor;
        void main() {
            oColor = vec4(texture(uTex, gl_FragCoord.xy / uRes).rgb, 1.0);
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
            // 第 75 批（电影原理）：用户指出“细微的变化连在一起播放就是动态，
            // 电影同样道理”“每一帧肯定都是静态图片”。30fps 逐帧差分实测：
            // 相邻帧平均像素差 2–6，起伏周期约 0.3 秒，高光在 ±15px 里跳着晃。
            // 参考的颤动是像素级、快而乱的闪，静帧上看不出椭圆；我的 3% 椭圆
            // 在真机上看得见一个椭圆在转，太几何、太犟硬。改：振幅 0.03→0.01
            // （1%，静帧上看不出椭圆，约 2px）；再叠一层极小的高频角向抖动
            // （0.005×vnoise(8t+3θ)），打破完美椭圆的干净感，让表面有细碎的活气。
            float ellipAngle = 6.28318 * (vnoise(uTime * 0.3 + 13.0) * 0.5 + 0.5);
            // 第 84 批：用户说抖动幅度没有 Orilo 大——30fps 量横向半径，Orilo
            // 206–214px（std 2.35px）、我们卡在 193–194px。振幅 0.01→0.02
            // （约 2%，Orilo 约 1.1–2.3%），轴仍慢的不规则走（61 批修法不动），
            // 不回 3% 那种看得见的转。
            float ellipAmp = 0.02 * (1.0 + 0.6 * vnoise(uTime * 3.0 + 5.0));
            float ellip = 1.0 + ellipAmp * cos(2.0 * (theta - ellipAngle))
                + 0.005 * vnoise(uTime * 8.0 + theta * 3.0);
            // 蛋形（第 74 批）：水滴不是绝对正圆。受表面张力/透视影响，视觉上
            // 极微的“上宽下窄”，底部接触面略扁。静态形态微调：egg = 1 +
            // 0.015×(-sin(theta))，顶部 +1.5%、底部 -1.5%，平滑、无断点。
            // 不是颤动（颤动是椭球 3% 随时间变），是底形本身就带一点蛋形。
            float egg = 1.0 + 0.015 * (-sin(theta));
            float R_eff = R * breatheFactor(uTime) * ellip * egg;
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
                // 所以影子就放正下方。第 76 批：中心 1.3R→1.5R，减少跟水滴
                // 下缘的重叠（Claude 对比：60°–100° 异常暗是影子叠在边缘上）。
                // 半径 1.1R、强度 0.55 不动。
                // 第 81 批：圆盘改成椭圆高斯——中心贴着接触线 (0, 1.05R)，横向
                // sigma 0.75R、纵向 sigma 0.35R，峰值 0.55。圆盘盘内几乎一样暗：
                // jk-v117 真机量到正下方 1.05R:54、1.2R:64、1.35R:57、1.5R:54，
                // 一团平的暗斑，1.5R 处还有第二个暗核；参考 4454 是 44→73→92，
                // 贴着接触线最暗、向外单调变亮。高斯以接触线为峰向外衰减，
                // 形状一次对上参考。接触阴影（下面那行）不动，仍是最暗那条线。
                vec2 sOff = fragPx - c;
                float shadow = 0.55 * exp(-pow(sOff.x / (R * 0.75), 2.0)
                    - pow((sOff.y - R * 1.05) / (R * 0.35), 2.0));
                // 接触阴影：第 74 批。水滴与平面接触的那条线（约 1.0R、底部）
                // 有一道极暗、极窄的接触阴影（最暗 <50），向外迅速变宽、变浅、
                // 羽化。这是环境光遮蔽（AO），不是单纯挡光，极大增强“水滴附着
                // 在平面上”的真实感。实现：底部 90° 附近、r=1.0R 处的窄高斯暗带，
                // 峰值 0.5，径向宽 0.05R、角向宽 30°，叠加到影子上。
                // 第 76 批（Claude 对比 4953 vs 4454）：影子形状不对——球体边缘到
                // 影子是硬切（167→44 只隔 10px），切完影子在 0.76R–1.43R 几乎
                // 是平的（81–97），不是“中心暗、向外平滑变淡”。根子是接触阴影
                // 太窄太狠（径向宽 0.05R、峰值 0.35），在边缘切出一道黑线。
                // 改：径向宽 0.05R→0.10R，峰值 0.35→0.25，让接触阴影变宽变软，
                // 跟漫射影子平滑衔接。漫射影子中心也从 1.3R 下移到 1.5R，
                // 减少跟水滴下缘的重叠（同批修 60°–100° 异常暗区）。
                float contactAng = abs(atan(fragPx.y - c.y, fragPx.x - c.x) - 1.5708);
                contactAng = min(contactAng, 6.28318 - contactAng);
                float contactR = length(fragPx - c) / R;
                float contact = exp(-pow(contactAng / 0.52, 2.0)) * exp(-pow((contactR - 1.0) / 0.10, 2.0)) * 0.25;
                shadow = clamp(shadow + contact, 0.0, 1.0);
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
            // 第 79 批：bend 180→120。180 在边缘处 UV 偏移太大（边缘 1.0-nz
            // 接近 1，偏移能到 140px），采到远处的颜色，一圈忽明忽暗，
            // 圆周处理过猛。120 是第 50 批之前的值；现在 alpha 已提到
            // 0.85–1.0，折射显出来了，120 够用，采得近、颜色跟得上局部背景。
            float bend = 120.0;
            vec2 refractUv = gl_FragCoord.xy / uRes + vec2(n2.x, -n2.y) * bend * (1.0 - nz) / uRes;
            vec3 refracted = texture(uBgTex, refractUv).rgb;
            // 边缘（按呼吸后的有效半径算）。第 45 批（DeepSeek 对比点 1、4）：
            // 亮边带从 0.90R–R 收窄到 0.94R–R——之前那圈白边太粗、太实，
            // 像霓虹描边。参考的亮边是细细的一线，贴着轮廓。
            float edge = smoothstep(R_eff * 0.94, R_eff, dist);
            // 顶部暗带（~40° 宽）。第 81 批（补）：cosTop 原来是 -n2.y（没归一化），
            // 在顶部射线上等于 r/R——暗带只在 r>0.94R 才出现，成了一道细环。
            // 4454 的顶部暗区从 0.85R 就开始了。改成方向余弦（只跟角度有关），
            // 径向范围由 darkBand 的缓坡管。
            float cosTop = -n2.y / max(length(n2), 0.001);
            float topDarkMask = smoothstep(0.94, 0.98, cosTop);
            // 底部笑脸
            vec2 bottomDir = normalize(vec2(-0.42, 0.91));
            float cosBottom = dot(normalize(n2), bottomDir);
            float smileMask = smoothstep(0.60, 0.85, cosBottom);
            float smileBright = edge * smileMask;
            // 顶部暗带。第 45 批曾把它收窄成 0.94R–0.975R 的细环、强度 0.53——
            // 错了：jk-v117 真机量到顶部边缘 54，比里面暗一半，成了一处黑斑。
            // 第 81 批（补）：再看 4454——Orilo 顶部没有黑斑也没有黑线，顶部是
            // 从 0.85R（144）到边缘外 1.03R（113）平滑变暗，约 20%，摊在 0.2R
            // 的宽度上。改成宽缓坡：从 0.70R 起渐暗，到边缘 0.20。
            float darkBand = smoothstep(R_eff * 0.70, R_eff, dist);
            // 第 78 批：漫反射计算整段删掉（上面已从合成里拿掉，留着是死代码）。
            // 大高光：右上，窄核心（旧 Canvas 高光的 GLSL 版：中心 0.47r/-0.65r，
            // 半径 0.25r，椭圆 1×0.55，转 -40°。Claude 第 36 批审查指出旧代码里
            // 压根没有这颗高光，整圈亮边顶到 220/255，笑脸显不出来。）
            // 第 49 批：高光独立游走。参考里高光自己还在 ±25px（0.12R）范围里
            // 游走，不只是跟着水滴中心漂——表面微小起伏改变局部法线，反射点
            // 就跟着动。用值噪声（跟颤动同一套函数，不同种子、更慢的速度，
            // 相关时间约 4 秒），缓慢漂移、不抖动。
            // 第 84 批：用户说抖动幅度没有 Orilo 大——高光游走实测 Orilo ±25px，
            // 我们只有 ±14px。0.07→0.12，回到 49 批注释里写的参考值。
            vec2 hlWander = vec2(0.12 * vnoise(uTime * 2.0 + 11.0),
                0.12 * vnoise(uTime * 1.7 + 23.0));
            vec2 hlC = vec2(0.47, -0.65) + hlWander;
            vec2 hlD = n2 - hlC;
            float hlAng = -0.698;
            float hlCa = cos(hlAng), hlSa = sin(hlAng);
            vec2 hlR = vec2(hlD.x * hlCa - hlD.y * hlSa, hlD.x * hlSa + hlD.y * hlCa);
            // 第 73 批：高光太胖——用户令按光的原理全量核对。4454 高光 L≥240 区域
            // 实测：长轴标准差 0.114R、短轴 0.042R（比 7.24），长轴指向屏幕
            // 40°（右下）；换算成高斯 sigma 是长 0.33R、短 0.12R（比 2.75）。
            // 我的高光是 0.25R × 0.1375R（比 1.82），太圆、太胖，像个白点不像
            // 一道斜拉的光斑。改成 0.33 × 0.12。位置 (0.47,-0.65) 与参考中心
            // (0.50,-0.66) 已对上，不动；转角 -40° 也不动。
            // 第 79 批：高光长轴 0.45→0.33（参考值）。0.45 太长，沿长轴方向
            // 高光中心离边缘只有 0.20R，边缘处还有峰值 82%，漫出水滴。
            // 参考的长轴 sigma 就是 0.33R。
            vec2 hlQ = vec2(hlR.x / 0.33, hlR.y / 0.08);
            // 第 44 批（DeepSeek 对比）：高光指数从 1.5 放到 1.0，边缘过渡
            // 更柔和，别像 2D 软件里直接画上去的死白块。峰值仍是 1.0，
            // 中心照样顶白 255（参考的高光中心也是纯白），只是边缘化开。
            // 第 75 批：jk-v114 真机量到高光是 60×60px 的圆斑，不是长条——
            // 峰值 1.0 饱和区太大，把长条撑成了圆。峰值 1.0→0.85，拉长
            // 0.33×0.12→0.45×0.08，让斜拉的光斑形状显出来。
            // 第 79 批：高光收边。镜面反射只能发生在水滴表面上，没有表面就
            // 没有光——高光绝不能探出圆周。0.85R 内全亮，到 0.98R 收到零，
            // 游走到哪都漫不出去。
            float hlEdgeMask = 1.0 - smoothstep(0.85, 0.98, length(n2));
            float highlight = exp(-dot(hlQ, hlQ) * 1.0) * 0.85 * hlEdgeMask;
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
            // 第 70 批：白边还在——用户看 jk-v113 截图说“还有白边”。第 66 批只把
            // lightFace 下限 0.55→0.10，背光面是暗了，但朝光面（顶部、左上）
            // 仍有 0.18 的亮边：实测 4942 顶部 r=0.98R 亮度 182–200，参考
            // 4454 同位置只有 129–150。亮边本身就是问题——参考的边缘没有
            // 加上去的白线，靠折射、月牙、亮点、笑脸界定。rimBright 系数
            // 0.18→0.05：朝光面只剩 0.05（细、淡），背光面 0.005（看不见），
            // 边缘由折射和其它特征界定，不再有一圈白线。
            // 第 78 批（用户物理课）：白边整条删。rimBright 0.05 也是错的——
            // 水滴边缘没有一圈白线，亮只亮在有光的地方（高光、亮点、笑脸、
            // 聚光），边缘由折射 + 暗环（rimDark，全内反射）界定。归零。
            float rimBright = edge * (1.0 - topDarkMask) * (1.0 - smileMask * 0.85) * 0.0 * lightFace;
            // 暗边。第 45 批：0.40→0.25，别把边缘压得太黑，参考的边缘是
            // 通透的、不是一圈黑。
            // 第 79 批：rimDark 0.25→0.15。圆周处理过猛——暗环太重，边缘一圈
            // 忽明忽暗。参考的边缘很平静（111–175），暗环只是全内反射的细线。
            // 第 81 批：rimDark 0.15→0.12 本想稍压，但再看 4454 才明白问题不在
            // 深浅、在宽窄——参考的边缘暗下去约 25 点，但摊在 0.2R（约 40px）
            // 上，是平滑过渡；我的 0.97R–R 只有 2–3px 宽，再淡也是一条黑线。
            // 改成宽缓坡（0.85R–R）+ 0.06：留一点全内反射的暗边，但看不出线。
            float rimDark = smoothstep(R_eff * 0.85, R_eff, dist) * 0.06 * (1.0 - topDarkMask);
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
            float smallHighlight = exp(-dot(shD, shD) / (0.14 * 0.14) * 1.5) * 0.60;
            // 右侧小亮点：第 68 批。参考 4454 左右边缘各有一个小亮点，
            // 左边的已有（-0.65, +0.12），右边的缺了。补一个镜像点
            // (+0.65, +0.12)，同半径、同峰值。
            vec2 shRC = vec2(0.65, 0.12);
            vec2 shRD = n2 - shRC;
            // 第 78 批：右亮点 0.55→0.20。jk-v115 右亮点 237，参考 147——
            // 参考的右亮点极淡，几乎看不见，我的太亮抢眼，压回去。
            float smallHighlightR = exp(-dot(shRD, shRD) / (0.14 * 0.14) * 1.5) * 0.20;
            // 顶部细月牙：第 68 批。参考 4454 顶部有一道细月牙——一道细细的
            // 亮弧，贴着水滴上缘内侧弯着，从左上到右上。不是大高光（那是右上
            // 实心白块），是独立的细弧。实现：顶部方向（0,-1）的点积定角度
            // 范围（顶部 ±41° 内），半径 0.90R 处高斯细带，薄、亮、两端渐隐。
            // 第 69 批：全量核对 4454 vs 4940，漏项一次补齐（用户令“再检查检查别的”）。
            // 实测五处亮度不匹配：
            // 1）月牙太亮：参考月牙只比背景亮 +20（187 vs 168），我的峰值 0.60
            //    会顶到 +153。降到 0.08（+20），跟参考一致。
            // 2）右亮点太亮：参考右点 226、左点 255，右边本来就暗一档。我的
            //    右点跟左点同峰值 0.70 会顶到 255。降到 0.50。
            // 3）笑脸太亮：参考 203，我的 222（+19）。smileCoeff 0.10→0.07。
            // 4）顶部暗带太亮：参考均值 128，我的 175（+47）。暗带系数
            //    0.35→0.53（乘子 0.65→0.47，175×0.73≈128）。
            // 5）外聚光太亮：参考 +61，我的 +94（+33）。聚光 0.30→0.20，
            //    叠加笑脸减弱（-19）合计 -32，回到 +62≈+61。
            // 大高光（255/294°/0.85R vs 255/300°/0.75R）、左亮点（255）、
            // 影子（50 vs 44）、尺寸（208 vs 205）均已对上，不动。
            // 第 71 批：放大看光线细节（用户令）。4454 三倍放大实测两处错位：
            // 1）月牙位置错了：参考的细月牙不在 0.90R（贴边），在顶部约 0.65R、
            //    向两侧弯到 0.85R——是一道贴着上半部内侧的弧，不是贴边的圈。
            //    我的 0.90R 高斯带贴在边上，看着像又一圈亮边。改到 0.70R。
            // 2）笑脸方向偏了：参考笑脸峰值在左下 125°（映的是左下聚光面），
            //    我的 bottomDir (0.12,0.99)=83° 正下方。改到 (-0.42,0.91)=115°，
            //    笑脸中心移到左下，跟参考对上。
            // 其余放大确认无误：大高光 255（294–322°/0.76–0.91R，中心 (0.51,-0.65)，
            // 我的是 (0.47,-0.65)）、左亮点 241 偏粉白、右亮点偏蓝紫（都是背景色
            // 透出来的，不用改色）、边缘是平滑过渡+外侧暗环、没有亮线（70 批
            // 亮边 0.05 已是上限）。
            vec2 topDir = vec2(0.0, -1.0);
            float topDot = dot(normalize(n2), topDir);
            float cresR = length(n2);
            // 第 78 批：月牙 0.15→0.25。jk-v115 月牙 122（比背景暗，看不见），
            // 参考 156（亮的细弧）。底色变了，按新底色重算，提亮到显出来。
            // 第 81 批：月牙 0.25→0.28。jk-v117 真机量到顶部 0.70R 处 142–149，
            // 参考 156，还差一档，小提。
            float crescent = smoothstep(0.75, 0.95, topDot) * exp(-pow((cresR - 0.70) / 0.02, 2.0)) * 0.28;
            // 光路散射：第 72 批。用户问：光在水滴里折射+散射，到左下是不是沿着
            // 水滴比其他部位稍微亮一点。实测 4454：是。沿高光→聚光这条线，
            // 亮度从 148 渐亮到 188；左下象限（181–185）比右上/右下象限
            // （156–161）亮约 20–25；光路对角线比垂直对角线亮 +5–+7。光从右上
            // 进来、穿过水滴、会聚到左下，沿途散射让这条线微微发亮，越靠近
            // 聚光点越亮。我的漫反射（光从左上）不产生这条线，聚光只是个点，
            // 缺的就是这条光路。实现：到高光→聚光线段的距离高斯带，宽 0.35R，
            // 亮度沿线渐强（0.3→1.0），峰值 0.06（约 +15），柔和、不抢戏。
            vec2 beamA = vec2(0.47, -0.65);
            vec2 beamB = vec2(-0.42, 0.42);
            vec2 beamD = beamB - beamA;
            float beamT = clamp(dot(n2 - beamA, beamD) / dot(beamD, beamD), 0.0, 1.0);
            vec2 beamClosest = beamA + beamT * beamD;
            float beamDist = length(n2 - beamClosest);
            float beam = exp(-pow(beamDist / 0.35, 2.0)) * (0.3 + 0.7 * beamT) * 0.06;
            vec2 causticDir = vec2(-0.7071, 0.7071);
            float causticDist = length(n2 - causticDir * 0.6);
            float caustic = (1.0 - smoothstep(0.0, 0.55, causticDist)) * 0.12;
            // 第 74 批（用户列的细节清单，一次补齐）：
            // a）次级高光：顶部细月牙与内表面反射之间（约 1–2 点钟方向），
            //    还有一条极微弱、弥散的反射带，与细月牙共同构成顶部内表面
            //    的复杂反射。实现：(0.45,-0.45) 处宽高斯（0.25R），峰值 0.04。
            vec2 secC = vec2(0.45, -0.45);
            float secondary = exp(-dot(n2 - secC, n2 - secC) / (0.25 * 0.25)) * 0.04;
            // b）色散：9 点钟左亮点边缘有极微弱的粉/紫色包边（不同波长折射率
            //    不同）。实现：左亮点外一圈稍大的粉晕 (1.0,0.7,1.0)，峰值 0.03。
            float fringe = exp(-dot(shD, shD) / (0.20 * 0.20) * 1.5) * 0.03;
            // c）焦散光桥：内聚光（0.6R）与外聚光（1.04R）之间有一条视觉相连
            //    的光桥。光穿透水滴底部最薄处，在边缘强烈折射压缩，左下边缘
            //    （1.0R 附近）透出背景被扭曲后的高浓度紫蓝。实现：135° 方向的
            //    窄高斯带（角宽 0.14 rad），径向以 1.0R 为峰、宽 0.25R，峰值 0.08。
            float bridgeAng = abs(atan(n2.y, n2.x) - 2.356);
            bridgeAng = min(bridgeAng, 6.28318 - bridgeAng);
            float bridgeR = length(n2);
            float bridge = exp(-pow(bridgeAng / 0.14, 2.0)) * exp(-pow((bridgeR - 1.0) / 0.25, 2.0)) * 0.08;
            // d）反弹光：左下聚光照亮下方平面，平面把紫光微弱反射回水滴右下
            //    边缘，右下边缘不是死黑，带一层极薄的暗紫灰色轮廓。实现：45°
            //    方向、0.97R 处的窄高斯，暗紫灰 (0.35,0.28,0.55)，峰值 0.04。
            vec2 bounceC = vec2(0.68, 0.68);
            float bounce = exp(-dot(n2 - bounceC, n2 - bounceC) / (0.10 * 0.10)) * 0.04;
            // 合成：先把折射背景和原背景按 alpha 混合（中心透明、边缘 0.22，
            // 跟旧版一致）；光学特征（高光、笑脸、亮边、聚光、漫反射）是加性光，
            // 必须直接加在结果上——之前把它们放进 colorOut 再乘 alpha，
            // alpha 只有 0.01–0.22，特征全被压没了，水滴才成了一块平板。
            // 这是 v103 真机（2026-11-20 09:52 截图量测）找到的根因：
            // 中心 176 全平、右上高光没出现（315° 方向 170 平）、只有聚光
            // （直接加在结果上那项）亮到了 222。
            // 第 78 批（用户物理课）：水滴无色透明，颜色全是背景透过来的。
            // alpha 0.01–0.15 等于只让 1%–15% 的折射背景透进来，剩下全是
            // 直接背景，水滴几乎没起透镜作用，才像贴上去的紫色圆片。
            // 提上去：里面基本全是折射进来的背景（透镜），时刻跟背景走。
            float alpha = mix(0.85, 1.0, edge);
            vec3 bg = texture(uBgTex, gl_FragCoord.xy / uRes).rgb;
            vec3 result = refracted * 0.98 * alpha + bg * (1.0 - alpha);
            // 暗特征先乘（暗带、暗边只压暗水滴表面）。
            result *= (1.0 - darkBand * 0.20 * topDarkMask);
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
            // 第 78 批：笑脸 0.12→0.20。jk-v115 笑脸 131（比背景暗，看不见），
            // 参考 181（底部宽而柔的光弧）。底色变了，按新底色重算，提亮。
            // 第 81 批：笑脸 0.20→0.24。jk-v117 真机量到笑脸峰值 152（115°）
            // ～179（135°，含光桥叠加），参考 181–203，还差一档。方向不动
            // （115°；135° 的亮是光桥/光路叠的，不是笑脸偏了）。
            float smileCoeff = 0.24 * (1.0 + 0.20 * vnoise(uTime * 0.5 + 31.0));
            // 第 67 批：球和背景太分明——用户指出水滴像个独立的球，跟背景
            // 是分开的。参考 4454 里水滴是透明的：背景透过水滴，带着渐变
            // 和折射，里面比背景略暗，水滴融在背景里。我的里面太均匀、太亮，
            // 像个实心蓝球——根子是漫反射 diffuse 0.12 全幅提亮，把折射进来
            // 的背景渐变洗平了。降到 0.06：里面不再均匀发亮，背景渐变透出来，
            // 水滴跟背景融在一起。折射 bend 180 不动（用户之前说折射淡才加到
            // 180 的）。
            // 第 78 批（用户物理课）：漫反射 diffuse 删掉。diffuse 是实心球的
            // 明暗（朝光面亮、背光面暗），水滴没有这个——水滴只有折射、镜面
            // 高光、聚光。留着它，里面就永远像个塑料球。只留加白光的镜面项。
            result += vec3(1.0) * (smileBright * smileCoeff + rimBright + highlight + smallHighlight + smallHighlightR + crescent + beam + secondary + bridge + caustic * 0.5);
            result += vec3(1.0, 0.7, 1.0) * fringe;
            result += vec3(0.35, 0.28, 0.55) * bounce;
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
            // 第 82 批（用户：涟漪不像水波，太宽、太深）：波长 2π/6≈1.05R，
            // 整颗水滴只有两圈宽环，像同心圆靶子；振幅 0.30 是 30% 亮度起伏，
            // 太抢。改窄改浅——空间频率 6→14（波长约 0.45R，约四圈半细环），
            // 时间系数跟着按比例提（2.5→6.0，波前扩散速度不变），振幅 0.30→0.12。
            // 第 65 批：用户指出涟漪做成了中心放射线——错了。旧写法用 dAng
            // （到源点的角度差）当距离，相位只跟角度有关、跟半径无关，
            // 同一角度上所有半径亮度一样，就成了从中心发散的射线。
            // 涟漪是三个点受力产生的点源波：波前是以源点为圆心的圆，
            // 向外扩。改成到源点的实际表面距离 d = length(n2 - src)，
            // 相位 sin(d*14 - dt*6)，波前是绕源点的圆环，越扩越淡。
            float ripple = 0.0;
            {
                vec2 src = vec2(cos(2.356), sin(2.356));
                float d = length(n2 - src);
                float dt = mod(uTime - 0.0, 15.0);
                ripple += sin(d * 14.0 - dt * 6.0) * exp(-d * 1.2) * exp(-dt * 0.7);
            }
            {
                vec2 src = vec2(cos(0.785), sin(0.785));
                float d = length(n2 - src);
                float dt = mod(uTime - 6.0, 15.0);
                ripple += sin(d * 14.0 - dt * 6.0) * exp(-d * 1.2) * exp(-dt * 0.7);
            }
            {
                vec2 src = vec2(cos(-2.356), sin(-2.356));
                float d = length(n2 - src);
                float dt = mod(uTime - 11.0, 15.0);
                ripple += sin(d * 14.0 - dt * 6.0) * exp(-d * 1.2) * exp(-dt * 0.7);
            }
            result += vec3(1.0) * ripple * 0.12;
            // 边缘抗锯齿（第 42 批）：R_eff 以内不透明，R_eff→1.02*R_eff 淡出到透明。
            // 这一环里 n2 长度略大于 1、nz=0，折射偏移最大，但只有 2% 宽且在淡出，
            // 看不出错色。
            float edgeAlpha = 1.0 - smoothstep(R_eff, R_eff * 1.02, dist);
            oColor = vec4(result, edgeAlpha);
        }
    """.trimIndent()

    fun onSurfaceCreated() {
        // 第 39 批：透明 Surface，清成全透明（水滴外由着色器输出 alpha=0）。
        GLES30.glClearColor(0f, 0f, 0f, 0f)
        // EGL 上下文重建时本方法会再跑一次，先删旧对象（Claude 第 36 批审查建议，防御性）。
        if (bgProgram != 0) GLES30.glDeleteProgram(bgProgram)
        if (dropProgram != 0) GLES30.glDeleteProgram(dropProgram)
        if (blitProgram != 0) GLES30.glDeleteProgram(blitProgram)
        if (fboId != 0) GLES30.glDeleteFramebuffers(1, intArrayOf(fboId), 0)
        if (bgTexId != 0) GLES30.glDeleteTextures(1, intArrayOf(bgTexId), 0)
        bgProgram = buildProgram(vertSrc, bgFragSrc)
        dropProgram = buildProgram(vertSrc, dropFragSrc)
        blitProgram = buildProgram(vertSrc, blitFragSrc)
        startMs = SystemClock.uptimeMillis()
        val fbo = IntArray(1)
        GLES30.glGenFramebuffers(1, fbo, 0)
        fboId = fbo[0]
        val tex = IntArray(1)
        GLES30.glGenTextures(1, tex, 0)
        bgTexId = tex[0]
    }

    fun onSurfaceChanged(width: Int, height: Int) {
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

    fun onDrawFrame() {
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
        // 第 84 批：悬浮球模式把折射源褪淡（淡紫淡蓝），主界面原样。
        setFloat(bgProgram, "uOrbMode", if (orbMode) 1f else 0f)
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
        // ---- 贴图 pass（第 80 批）：把 FBO 背景纹理全屏贴到屏幕上 ----
        // 屏幕上看到的背景就是这份纹理，水滴采的也是它——同一份，同步是结构保证的。
        // 不透明贴图，关混合。
        // 悬浮球模式跳过这步：下面是别的 App，水滴外要全透明；帧缓冲先清透明——
        // 没有贴图盖住全屏，水滴 pass 的混合（SRC_ALPHA）写不掉上一帧的影子残影。
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, viewW, viewH)
        if (orbMode) {
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
        } else {
            GLES30.glDisable(GLES30.GL_BLEND)
            GLES30.glUseProgram(blitProgram)
            setVec2(blitProgram, "uRes", viewW.toFloat(), viewH.toFloat())
            GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, bgTexId)
            setInt(blitProgram, "uTex", 0)
            GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        }
        // ---- 水滴 pass：渲染到屏幕，采样背景纹理 ----
        // 水滴边缘要抗锯齿淡出，开混合（SRC_ALPHA/ONE_MINUS_SRC_ALPHA），
        // 边缘 alpha 淡出处透出下面刚贴好的背景。
        // 悬浮球模式用预乘混合（ONE/ONE_MINUS_SRC_ALPHA）：水滴外那支输出
        // 是预乘写法 vec4(vec3(oc), a)（第 53 批：影子是黑、亮斑是白，
        // RGB 已乘过 alpha）。SRC_ALPHA 会再乘一次 alpha，影子 alpha 通道
        // 变成 a²——0.55 的影子只压暗 30%，比参考的 55% 淡一半。预乘混合
        // 直接输出 oc，SurfaceFlinger 再按 (1−a) 透出下面 App，影子强度
        // 跟主界面一致。水滴内那支输出 vec4(result, edgeAlpha)，result
        // 本身已是最终颜色，ONE 混合不改它，边缘淡出照样平滑。
        GLES30.glEnable(GLES30.GL_BLEND)
        if (orbMode) {
            GLES30.glBlendFunc(GLES30.GL_ONE, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        } else {
            GLES30.glBlendFunc(GLES30.GL_SRC_ALPHA, GLES30.GL_ONE_MINUS_SRC_ALPHA)
        }
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
        // 悬浮球模式：半径 ×2/3（第 83 批，用户令：主水滴的 2/3）。
        val r = screenW * 0.19f * radiusScale
        val cx = viewW / 2f
        // 第 80 批：View 现在是全屏（之前只占上半屏 dropWrap），水滴中心 Y
        // 从 0.45 改成 0.225——原来是上半屏高度的 45%，上半屏是整屏一半，
        // 所以是整屏的 22.5%，屏上位置不变。
        // 悬浮球模式：centerYFrac = 0.40（窗口要装下水滴 + 正下方影子）。
        val cy = viewH * centerYFrac
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
