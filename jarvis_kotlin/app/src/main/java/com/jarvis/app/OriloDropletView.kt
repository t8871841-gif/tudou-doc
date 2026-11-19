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
import android.os.Build
import android.os.SystemClock
import android.util.AttributeSet
import android.view.View
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
 * 动态（第 19 批改了）：水滴颜色固定玻璃色、不跟 JarvisAgent 状态走；大小
 * 永远满尺寸（不再有待机缩小、Z 休息提示取消了，水滴永远是醒着的、呆萌萌的）；
 * 平时叠一层慢呼吸（6 秒一圈）+ 轻微晃形；原地动，只缩放不平移，中心不动
 * （2026-11-15 用户纠正过的行为，保留）。
 * 公开方法名（setAwake）、MainActivity 调用方式不变（setAwake 现在是空方法）。
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
 *
 * 2026-11-17 第 17 批（用户转发 DeepSeek 对第 16 批的审稿）：方向对（用更强
 * 折射撑体积感），但提醒两个极端——内部要么像"贴了一张扭曲的图"很脏，要么
 * 背景太素、折射了也看不出来。改动：大高光重画（位置稍往外、转 -40°、椭圆
 * 0.3→0.4 别压太扁、半径 0.3r→0.25r、中间停 0x40→0x80、核心 15%→20%），
 * 笑脸 0.20→0.35 且外余弦 0.40→0.55（光散得更开、免得亮线太硬），暗带
 * 0.25→0.35，边缘透明度 0.30→0.35（让边缘稍微"肉"一点），整圈白描边
 * alpha 25→50。折射保持 120（还没上真机验，不急着加到 150）；背景的
 * WARP/FBM 没动（DeepSeek 说"如果依然觉得平"才加，等真机结果）。
 *
 * 2026-11-17 第 18 批（用户看 jk-v91 两张截图："看不出区别，为什么周围一圈
 * 黑"，并贴了新参考图 4454）：折射 120 还是看不出区别（背景太素，采样到的
 * 颜色跟周围差不多）；水滴周围有一圈黑边。黑边的根因有两个：一是暗带
 * （0.35）太强，二是染色强度 0.08 把折射颜色整体压暗、边缘 alpha 又高，
 * 合出来就是黑边。新参考图（4454）的边缘是亮的细边、不是黑的，内部能看
 * 出背景被放大/扭曲。改动：暗带 0.35→0.10（只留一点点意思），染色 0.08→
 * 0.25（让折射颜色亮回来、边缘不再发黑），整圈白描边 alpha 50→100（亮边
 * 要能读出来），背景扭曲 0.16→0.25、纹理密度 2.2→2.8（让水滴内部有"扭曲
 * 的流动感"可看）。折射保持 120。都是估计值，真机说了算。
 *
 * 2026-11-17 第 20 批（用户转发 DeepSeek 对第 19 批的审稿）：第 19 批的
 * 功能逻辑没问题（跳变、取消待机都对），但渲染核心还有 3 个视觉炸弹：
 * ① 顶部暗带用 dot(n2, topDir) 算会在顶部两侧各留一个黑斑（"熊猫眼"，
 * v91 实测 30° 和 300° 各一个 -60 暗斑、顶部反而中性）——改成直接用
 * Y 轴坐标：topDarkMask = 1.0 - smoothstep(-0.8, 0.2, n2.y)，顶部是一
 * 整条柔和暗带；uTopDarkInnerCos/uTopDarkOuterCos 和 TUNE_TOP_DARK_* 两
 * 个常量随之删掉。② 整圈白描边 alpha 100→30：100 的白线太生硬、像
 * 塑料边框，真玻璃的边缘亮是折射出来的光、不该有实线边框。③ 折射边缘
 * 收窄：TUNE_EDGE_SOFT_START 0.80→0.85（过渡带 0.20r→0.15r），折射
 * 120 把远处颜色"扯"进来形成的横线/断层不那么显眼。另：大高光中间停
 * 0x80→0xE6、位置 0.2→0.08（前 8% 极亮、之后光速褪去，核心别那么死板）。
 *
 * 2026-11-17 第 21 批（用户转发 DeepSeek 对 jk-v92 真机的三审）：熊猫眼
 * 没了、高光自然了，但跟 Henry 原图比还有两个距离——"内部像磨砂、缺乏
 * 清澈感"和"底部笑脸消失"。改动：边缘透明度 0.35→0.22（消肥皂泡感，玻璃
 * 边缘是很薄的），边缘起始 0.85→0.90（只有最外面 10% 才有明显不透明感），
 * 笑脸 0.35→0.50（边缘透明度砍了、笑脸会被稀释，提到 0.50 像一束柔和的
 * 聚光；真机上太亮像口红就降到 0.20、太淡就升到 0.45），漫反射 0.01→
 * 0.05（背景太平、折射扭不出花样，直接给内部加一点明暗补体积感），大高
 * 光中间停 0xE6→纯白、位置 0.08→0.05（中心像针尖一样亮、再锐利一点）。
 * DeepSeek 还建议把水滴拉成上窄下宽的水滴形（buildWobblePath 里 y 乘
 * 1.05），没做——那是改形状，不是调参，等用户点头再说。
 *
 * 2026-11-17 第 22 批（用户发 Morning Coffee 参考视频，20 帧/秒量过）：
 * 呼吸加速放大——幅度总量 2.6%→约 4.7%（0.012/0.008/0.006→0.020/
 * 0.015/0.012），速度 0.23/0.15/0.31→4.2/2.9/5.6（周期约 1.5/2.2/1.1
 * 秒，参考实测约 1.5 秒一圈）；涟漪在背景着色器里做（见 OriloBackgroundView），
 * 不是这里。TUNE_SHAPE_MARGIN 1.18 不用动（1+0.047=1.047 < 1.18）。
 *
 * 2026-11-17 第 19 批（用户令）：背景颜色变换不要固定时间——色相不再按
 * 120 秒慢慢转圈，改成跳变：平时色相不动（只有光斑位置/呼吸/饱和度在
 * 13 秒轴上慢慢变幻），隔 20–60 秒（随机）色相一瞬间跳到一个新值（跟现
 * 在至少差 15°，方向随机，0..50 循环、青蓝到紫、不经过红）。Z 休息提示
 * 取消、不要待机：水滴永远是醒着的、呆萌萌的——setAwake 改成空方法，不
 * 缩（初始就是满尺寸）、颤动不再冻结、不画 Z。
 *
 * 2026-11-17 第 23 批（用户给的一束光模型，终于找到感觉了）：水珠的光暗全由
 * 一束从右上打进来的光决定——折射 + 散射。光打在右上表面：一部分反射（右上大
 * 高光），一部分折进水里、往法线偏；水珠是凸透镜，光线会聚到对面左下（里面聚
 * 光亮、穿出去照亮外面左下一片）；顶部暗带是菲涅尔反射（映上面暗处，连续的一
 * 条带），底部笑脸同理（映下面亮的面）。熊猫眼是旧 dot(n2, topDir) 公式造的
 * 假象，物理上不存在。这批按这个模型补：
 * 1. **里面左下聚光**：着色器里加柔和亮斑（225°、0.6r 处），是"光"（加色项，
 *    不走 alpha——水滴内部 alpha 只有 0.01，走 alpha 会被吃掉看不见），笑脸不动。
 * 2. **涟漪挪到水滴表面**：v93 把涟漪做在背景上是错的——涟漪是沿着水珠自己的
 *    表面、水珠外面区域没有。改法：背景上的涟漪删掉；涟漪做到水滴着色器里，从
 *    左下源点出发、沿着水滴表面往里扩（往右上走）、随距离衰减；还是波包——
 *    每 15 秒发一波，胀起来再衰减到零，平时没有（也是加色项，不走 alpha）。
 * 3. **染色/漫反射砍掉**：TINT 0.25→0.02、DIFFUSE 0.05→0.01——水滴是清水
 *    玻璃、跟背景同色，不自己带颜色和明暗；真实感来自光（聚光、高光、笑脸、
 *    暗带、影子），不是人工染色。
 * 4. **影子跟光走**：光从右上进，影子推到左下（中心 +0.18r→-0.18r，椭圆跟
 *    着镜像），最深处贴水滴下缘、向外褪净。顶部暗带不动（那是边缘折射，不是影子）。
 * 5. **高光跟着颤动游走**：大高光中心按颤动相位小幅游走（±0.05r，约 ±10px，
 *    参考实测 ±11px），不再是固定点。外面左下的聚光亮斑在背景着色器里
 *    （见 OriloBackgroundView 第 23 批）。
 *
 * 2026-11-17 第 24 批（用户发 jk-v94 截图 4534 两次纠正）：①"水珠表面阴影
 * 不合理，不符合物理"——量过：边缘（0.95r）比外面背景暗 -12 到 -20，横跨左
 * 和上（165° 到 300°），最暗在左上（225°，-20.4），而不是只在顶部；最外缘
 * （0.99r）整圈都暗（142–159），最暗在左（180°，142.1）。根因两个：顶部暗带
 * 用的 n2.y 掩码太宽（盖整个上半圈 146°），第 23 批把染色/漫反射砍到近零、
 * 水滴自己的颜色变暗、边缘 alpha 又高（0.22），暗颜色盖住了亮背景。②"水滴
 * 表面的阴影超出水滴外围——阴影可以在水滴表面扩散，不能超出水滴表面"——量过：
 * 底部最暗点在 1.02r（145.9），比边缘本身（0.99r，147.8）还暗，暗区从水滴
 * 里面一直连到外面 1.3r。根因：下面那片影子（cast shadow）的椭圆顶在
 * cy+0.7r，伸进水滴里面 0.3r，跟水滴下半的暗边连成一片。改法三处：
 * 1. **暗带改回余弦掩码**：cosTop = -n2.y，内 cos 0.98（11.5° 内全暗）、
 *    外 cos 0.94（20° 外全亮），总宽 ~40°，跟原图实测一致。第 20 批删的是
 *    dot(n2, topDir) 那个"帽"（会留熊猫眼），余弦掩码跟笑脸一样、按夹角
 *    smoothstep，不会出熊猫眼。
 * 2. **加亮边（菲涅尔）**：边缘映的是亮背景，应该是亮的、不是暗的。只在边缘
 *    （edge）起作用、顶部除外（topDarkMask 那里是暗带），强度 0.80（估计值，
 *    真机上太亮就降、太淡就升），把边缘亮回背景亮度（新参考图 4454 的边缘
 *    就是亮的细边、不是黑的）。
 * 3. **影子椭圆顶挪到水滴底边**：从 cy+0.7r 挪到 cy+1.0r，不伸进水滴里面，
 *    影子只留在水滴下面（椭圆顶贴水滴底边、相切）。影子中心 cy+1.08r 不动、
 *    位置还是左下。
 *
 * 2026-11-19 第 25 批（用户发 jk-v95 截图、指出白圈 Bug）：水滴外面悬浮着一圈
 * "实体光环"——真实物理世界里不可能有。罪魁祸首是 drawHighlightsAndRim 末尾
 * 的 `canvas.drawCircle(cx, cy, r - 1f, rimSoftPaint)`：水滴本体边缘是动态
 * 颤动的（wobblePath，半径 ±4.7% 波动），但这圈是绝对完美的正圆、半径死死
 * 固定在 r-1f；水滴缩进去（波谷）时，白圈就原形毕露、悬浮在水滴外面。alpha 30
 * 的半透明白描边在深色背景下像发光的塑料壳。原图没有这圈线，真玻璃的边缘靠
 * 折射暗带和内部亮弧塑造。改法（方案一）：drawCircle 和 rimSoftPaint 变量
 * 一起删掉，亮边由着色器里的 uRimStrength（菲涅尔，跟着颤动路径走）负责。
 * 用户还顺带提了高光裁剪（clipPath 限制高光在水滴内部、别溢出边缘），这次
 * 没做——先只删白圈，等用户点头再动高光。
 *
 * 2026-11-19 第 26 批（用户令，参考视频 24fps×5s 实测）：水滴的颤动还不自然。
 * 实测 5 秒 120 帧、8 个角度：①水滴不是圆的，是水滴形——底部 225px、顶部
 * 196px，差 15%，下面鼓、上面收。②颤动不是一起呼吸，是左右晃——右边鼓出去
 * 时左边正好缩进来（0° 与 180° 相关性 -0.79），像水在左右晃。③颤动不规律、
 * 很碎——5 秒里方向反转 21–54 次，纯正弦波 5 秒只反转 2、3 次；慢晃上面还
 * 叠了很多小而快的抖动。改法：①加静态水滴形 TUNE_SHAPE_TEARDROP=0.07
 * （cos(theta-π/2)，底部 +1、顶部 -1）。②K=2 的 SPEED_1 4.2→3.0，主周期
 * 对到实测的约 2.1 秒。③加第 4 组高频小幅（K=7、AMP=0.005、SPEED=13.0、
 * 约 0.48 秒一圈）补碎抖。TUNE_SHAPE_MARGIN 1.18 不用动（1+0.07+0.052=
 * 1.122 < 1.18）。注意：透明度问题（用户 09:48 说参考图水滴很透、
 * 我们这个不透）这次没动，等用户点头再改 ALPHA/TINT/DIFFUSE。
 *
 * 2026-11-19 第 27 批（Claude 审查第 26 批，用脚本把 wobbleFactor 的公式原样
 * 模拟了一遍 5 秒 24fps，而不是只看代码）：第 26 批"K=2 是左右晃主力"这条站不住——
 * 偶数倍角 cos(2θ+φ) 在 θ 和 θ+π 这两个正对的点上数学上永远同值，不可能产生
 * 对边反相；模拟算出来 0°/180° 相关系数是 -0.02，实测是 -0.79，差一个数量级还
 * 不说，符号那头"基本不相关"也对不上"强反相关"。只把 `TUNE_WOBBLE_K_1` 从 2.0
 * 改成 1.0（SPEED_1 等都不动，周期还是对的 ~2.1s）——奇数倍角才会反相
 * （cos(K(θ+π)+φ)=(-1)^K·cos(Kθ+φ)），改完模拟出来是 -1.0，方向和量级终于对上了
 * （比 -0.79 略强，没有再细调，优先级不如把方向搞对）。碎抖反转次数模拟下来是
 * 10-19 次，实测 21-54 次，还是偏少，但这个测量范围本身跨度两倍多、噪声大，
 * 没有为了凑这个数字再加项。水滴形方向（底部+顶部-）、主周期（2π/3.0≈2.1s）、
 * TUNE_SHAPE_MARGIN 安全余量这三条第 26 批做对了，原样没动。
 *
 * 2026-11-19 第 28 批（用户令，按参考图 4454 加强反差）：v96 真机截图量过，
 * 水滴中心 174–188、背景 168–180，里面和外面几乎一样，边缘 1.0r 也就
 * 165–185，没有亮边也没有暗带——像个空肥皂泡，撑不起体积。参考图中心 176、
 * 背景 177，也是透明的，但它看着像实心玻璃球：上面暗带、下面笑脸亮弧、
 * 高光纯白、下面影子漆黑，四边的反差把球框出来了，中心再透也不空。
 * 所以不加中心白（ALPHA_MIN 保持 0.01f），只加强四边的反差：①笑脸
 * 0.50→0.75、OUTER_COS 0.55→0.60（底部亮一大块；若真机太硬变实线，把 OUTER_COS
 * 往下调到 0.50 让它褪得更慢——第 29 批纠正：0.65 更接近 0.85、过渡更窄只会更硬，
 * 0.65 的方向是反的）。②暗带 0.10→0.35（顶部压出一层看得见的暗；只在
 * 顶部 ~40° 宽的余弦掩码里起作用，不是整圈，第 18 批的黑边不会回来）。
 * ③影子加深：核心 0x18→0x38、中间 0x08→0x14，渐变停点保持 (0f, 0.35f, 1f)
 * （核心虽暗、35% 半径处就开始快速消散，不糊成一大团黑）。④大高光改胖改软：
 * scale (1, 0.4)→(1, 0.55)，渐变停点 [0, 0.05, 1]→[0, 0.35, 1]
 * （现在是针尖、5% 白核就没了；参考图是新月，35% 白核再慢慢淡出）。
 *
 * 2026-11-19 第 30 批（用户令"这批全部改完再构建"，Henry 视频 30fps×994 帧实测）：
 * 用户两个模型纠正 + 四条实测改进，一次全做：①去静态水滴形——水滴本质是圆，
 * 所谓水滴形是某个时刻的形变；TUNE_SHAPE_TEARDROP 0.07→0.0，瞬时水滴形由 K=1
 * 左右晃产生（第 26 批的静态项是错的）。②涟漪改多点——原来只有一个源点（左下
 * 225°），现在 3 个源点（225°左下、315°右下、135°左上），各错开 5 秒轮流起波，
 * 每 5 秒有一波从不同点出发；单点周期还是 15 秒（第 30 批补：用户纠正 5 秒
 * 太密，周期改 45 秒、错开 12 秒和 27 秒，三波间隔 18、15、12 秒，都在
 * 10–20 秒里，时间不均匀）。③背景色相跳变改连续漂移——
 * 994 帧量出来根本没有跳变，色相是连续缓慢漂移（2–5°/秒，20–25 秒走完蓝↔紫
 * 一圈）；跳变定时器和 jumpHue() 全删（见 OriloBackgroundView），色相改成两组
 * 不成整数比的正弦叠加（31 秒和 47 秒），漂移不重复。④饱和度跟色相耦合——
 * 变蓝时浓、变紫时淡（实测蓝 0.68–0.70、紫 0.45），satScale 1.0→0.65，13 秒
 * 余弦饱和度作废。⑤水滴中心漂移——中心在框中心附近 ±7px 不规则漂移，两组
 * 慢正弦叠加（约 7 秒和 12 秒），幅度 0.05r；cx/cy 一次漂移到位，颤动 Path、
 * 着色器 lensCenter、影子、高光全用同一个 cx/cy。⑥高光游走加大 + 位置外推——
 * 游走 0.05r→0.11r（实测 ±25px），位置 (0.43, -0.46)→(0.41, -0.57)，距圆心
 * 0.63r→0.70r（实测核心在 0.80r；再往外推外缘就伸出水滴了）。⑦第 5 组高频
 * 小幅（K=9、AMP=0.004、SPEED=20.0、约 0.31 秒一圈）补碎抖——4 组模拟的 5 秒
 * 反转数只有 10–19 次，实测 21–54 次。五组总量 0.056，最大半径倍数 1.056 <
 * TUNE_SHAPE_MARGIN 1.18，安全。
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
    // 第 25 批（用户令）：rimSoftPaint 删掉——它是画一个固定正圆的描边
    // （drawCircle(cx, cy, r-1f)），半径死死固定在 r-1f，不跟水滴的颤动
    // 路径（wobblePath，半径 ±4.7% 波动）走。水滴缩进去（波谷）时，这圈白边
    // 就悬浮在水滴外面，像一圈"实体光环"（v95 实测底部 1.02–1.05r 亮 220–235、
    // 背景才 193）。原图没有这圈线，真玻璃的边缘靠折射暗带和内部亮弧塑造。
    // 亮边现在由着色器里的 uRimStrength（菲涅尔，跟着颤动路径走）负责。

    // ---------- 第 19 批（用户令）：Z 休息提示取消了，不再有待机/睡觉态——
    // 水滴永远是醒着的、呆萌萌的，不缩、不冻、不画 Z。setAwake 还留着
    // （MainActivity 还在调它），但现在是个空方法，什么也不做。 ----------

    // ---------- 动态水滴：永远满尺寸、原地动 ----------
    private var currentScale = AWAKE_SCALE
    private var motionAnimator: ValueAnimator? = null

    // ---------- 第 9 批新增：按角度颤动的 Path（替换原来的 scaleX/Y 挤压） ----------
    // 第 19 批：不再冻结——水滴永远醒着，颤动相位一直用挂钟时间走。
    private val wobblePath = Path()
    private val wobblePts = FloatArray(WOBBLE_POINTS * 2)

    init {
        if (useShader) {
            runtimeShader = RuntimeShader(AGSL_SOURCE)
            shaderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = runtimeShader }
        }
        applyMotion()
    }

    /** 第 19 批：空方法。水滴不再有待机/睡觉态——永远满尺寸、永远颤动、
     *  不画 Z。留着这个方法是让 MainActivity 的调用不用改。 */
    fun setAwake(@Suppress("UNUSED_PARAMETER") awake: Boolean) {
        // 故意什么都不做。
    }

    private fun applyMotion() {
        // 第 9 批：这里不再做"挤压成椭圆"——量测报告说原版是边缘局部此起彼伏、整体
        // 大小基本不变，不是整个水滴一起变椭圆。真正的颤动形状改到 onDraw() 里按
        // 角度现算一个 Path（见 buildWobblePath()）；这里只管"待机缩小/干活胀回"的
        // 整体大小，中心不动、不平移。
        scaleX = currentScale
        scaleY = currentScale
    }

    /** 第 9 批新增：按角度算这一刻（或冻结时刻）水滴边缘该鼓出/缩进多少，5 组不成
     *  整数比的频率叠加在角度 θ 和时间 t 上——θ 上必须是整数倍频率（不然 θ=0 和
     *  θ=2π 接不上，边缘会有裂缝），t 上则要连续（不能用会循环重置的值，道理跟
     *  第 8 批一样）。返回的是"半径倍数"，1.0 就是不鼓不缩。
     *  第 30 批：静态水滴形项归零（TUNE_SHAPE_TEARDROP=0.0，用户纠正——水滴
     *  本质是圆，水滴形是某个时刻的形变），瞬时水滴形由 K=1 左右晃产生。 */
    private fun wobbleFactor(theta: Double, tSec: Double): Double {
        return 1.0 +
            // 第 26 批（参考视频 24fps×5s 实测）：水滴形——下面鼓、上面收，
            // 底部半径比顶部大 ~15%（实测底部 225px、顶部 196px）。cos(theta-π/2)
            // 在底部（θ=90°，屏幕坐标 y 向下）为 +1、顶部（θ=270°）为 -1。
            // 第 30 批：这一项已归零（TUNE_SHAPE_TEARDROP=0.0）——用户纠正，
            // 水滴形是某个时刻的形变、不是静态形状，留着这一行是让结构还在、
            // 以后想调回来只改常数就行。
            TUNE_SHAPE_TEARDROP * cos(theta - Math.PI / 2) +
            TUNE_WOBBLE_AMP_1 * cos(TUNE_WOBBLE_K_1 * theta + TUNE_WOBBLE_SPEED_1 * tSec) +
            TUNE_WOBBLE_AMP_2 * cos(TUNE_WOBBLE_K_2 * theta + TUNE_WOBBLE_SPEED_2 * tSec) +
            TUNE_WOBBLE_AMP_3 * cos(TUNE_WOBBLE_K_3 * theta + TUNE_WOBBLE_SPEED_3 * tSec) +
            // 第 26 批：碎抖——实测 5 秒里方向反转 21–54 次，纯正弦波 5 秒只反转
            // 2、3 次；慢晃上面还叠了很多小而快的抖动。第 4 组高频小幅（K=7、
            // 0.5%、约 0.48 秒一圈）补这个碎抖。
            TUNE_WOBBLE_AMP_4 * cos(TUNE_WOBBLE_K_4 * theta + TUNE_WOBBLE_SPEED_4 * tSec) +
            // 第 30 批：第 5 组更高频小幅（K=9、0.4%、约 0.31 秒一圈）——4 组
            // 模拟的 5 秒反转数只有 10–19 次，实测 21–54 次，再补一组往上靠。
            TUNE_WOBBLE_AMP_5 * cos(TUNE_WOBBLE_K_5 * theta + TUNE_WOBBLE_SPEED_5 * tSec)
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
        // 第 19 批：不再有待机缩小，IDLE_SCALE 删了；水滴永远满尺寸。
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
        /** 边缘增厚的起始位置，0..1（相对半径）：越小，变厚/变实的范围越宽，水滴显得越"肉"。
         *  第 20 批（DeepSeek 审）0.80→0.85：折射 120 会把远处的颜色"扯"到中间，
         *  在过渡区形成一道突兀的横线/断层；把边缘收窄一点（过渡带从 0.20r 收到
         *  0.15r），让那道缝不那么显眼。第 21 批（DeepSeek 审）0.85→0.90：
         *  只有最外面那 10% 的圈才有明显的不透明感，中间更透明、消"肥皂泡"感。 */
        const val TUNE_EDGE_SOFT_START = 0.90f
        /** 水滴中心的不透明度：第 9 批按量测报告（中心跟背景亮度只差 0~15/255）大幅调低，
         *  越小中心越透，越接近"看不见水滴只看见一圈边"。第 10 批再调低一档（0.08→0.05），
         *  真机反馈内部还是偏奶白、背景光斑透不出来。第 14 批（DeepSeek 审）再压到 0.01：
         *  内部要极度透明，玻璃感来自边缘和折射、不是中心那点白。 */
        const val TUNE_ALPHA_MIN = 0.01f
        /** 水滴边缘的不透明度：第 9 批也调低了一点（配合"中心更透"的整体方向），
         *  越大边缘越实。第 14 批（DeepSeek 审）0.50→0.25：边缘太实了，看着像实心球。
         *  第 16 批 0.25→0.30：0.25 时内部太空、没"体"，稍微回一点、让折射和
         *  亮弧暗带有地方落。第 17 批（DeepSeek 审）0.30→0.35：让边缘稍微"肉"
         *  一点，太薄了就像气泡。第 21 批（DeepSeek 审）0.35→0.22：边缘太厚、
         *  糊成实心白、像肥皂泡；真正的玻璃边缘是很薄的。 */
        const val TUNE_ALPHA_MAX = 0.22f
        /** 背景颜色透进水滴的浓度（这是"边缘"位置的浓度上限，中心会按
         *  `tintEdgeFactor` 再打折，见 main() 里的说明）：越大水滴边缘背景色越明显，
         *  越小越像一颗纯白/无色的玻璃珠。第 9 批调低了默认值——量测报告说中心
         *  几乎就是背景本身，之前的 0.6 让中心颜色太实。第 13 批再调低一档（0.3→0.2）：
         *  用户反馈"用力过猛"，内部要再透一点。第 14 批（DeepSeek 审）再压到 0.08。
         *  第 18 批 0.08→0.25：0.08 有个副作用——它把折射颜色整体压暗，边缘
         *  alpha 又高，合出来就是一圈黑边（用户问"为什么周围一圈黑"）。提到
         *  0.25，让折射颜色亮回来、边缘不再发黑，内部也能多带一点折射的色彩。
         *  第 23 批（用户令"水滴跟背景同色"）0.25→0.02：水滴是清水玻璃，不自己
         *  带颜色——染色砍到近零，内部就是折射过来的背景本身；真实感来自光
         *  （聚光、高光、笑脸、暗带、影子），不是人工染色。 */
        const val TUNE_TINT_STRENGTH = 0.02f
        /** 中心相对边缘的折射强度折扣：中心是这个值 × TUNE_TINT_STRENGTH，边缘是
         *  完整的 TUNE_TINT_STRENGTH——越小中心越透明，环境色反射越集中在边缘。 */
        const val TUNE_TINT_CENTER_FACTOR = 0.35f
        /** 第 13 批新增：底部"笑脸"亮弧的强度——一圈柔和的漫射亮斑（不是线），
         *  目标是最亮处比背景亮约 +40/255（Henry 原图实测峰值）。第 11 批删掉的
         *  rimPaint 弧是硬线，别恢复它；第 9 批的顶部亮弧方向反了，已整个删掉。
         *  第 14 批（DeepSeek 审）0.30→0.12：0.30 在图上看着像一根亮线，太实了；
         *  内外余弦也放宽一点（0.87→0.85、0.50→0.40），让亮带褪得更平滑。
         *  第 15 批：0.12 太淡了（0.12 × 边缘透明度 0.25 ≈ +8，远低于原图的
         *  +40），回调到 0.20（≈ +13），亮带还是柔光、不是线。第 17 批（DeepSeek
         *  审）0.20→0.35：让它有明显的聚光感；外余弦 0.40→0.55，让光散得更开、
         *  更柔和，免得亮线太硬。第 21 批（DeepSeek 审）0.35→0.50：边缘透明度
         *  被砍到 0.22，笑脸会被稀释；提到 0.50、让它像一束柔和的聚光（真机上
         *  若太亮像口红就降到 0.20、太淡就升到 0.45，纯审美微调）。
         *  第 28 批（用户令，按参考图 4454 加强反差）：0.50→0.75——v96 实测
         *  底部边缘只亮 184、背景 168–180，笑脸太弱、撑不起体积；参考图笑脸
         *  203。注意：ALPHA_MAX 只有 0.22，笑脸提太高可能亮得发白、变实线；
         *  若真机看着太硬，把 OUTER_COS 往下调（0.60→0.50）让过渡更宽、褪得更慢；往上拉到 0.65 只会更硬（第 29 批 Claude 审查纠正）。 */
        const val TUNE_SMILE_STRENGTH = 0.75f
        /** 第 13 批新增：底部"笑脸"亮弧的半角，夹角余弦——cos(30°)≈0.87，数字越大
         *  （越接近 1）这段全亮的弧越窄；配合 `TUNE_SMILE_OUTER_COS` 控制往外的
         *  淡出范围，两个一起大致框出"4 点半到 7 点半"（约 60° 宽）这段弧。 */
        const val TUNE_SMILE_INNER_COS = 0.85f
        /** 第 13 批新增：底部"笑脸"亮弧彻底消失的夹角，cos(60°)≈0.50——比这个夹角更大
         *  （更偏向水滴两侧/上方）就完全没有底部亮弧了。
         *  第 28 批：0.55→0.60——笑脸强度提到 0.75 后，让光散得更开、褪得更慢，
         *  免得底部亮成一条实线；若真机还嫌硬，往下调到 0.50（不是往上拉到 0.65——0.65 离内 cos 0.85 更近、过渡更窄只会更硬，第 29 批 Claude 审查纠正）。 */
        const val TUNE_SMILE_OUTER_COS = 0.60f
        /** 第 24 批：顶部暗带改回余弦掩码（跟笑脸同套做法）——第 20 批的 n2.y 掩码
         *  太宽（n2.y < 0.2 就起作用，盖住整个上半圈 146°），真机上暗带横跨左和上、
         *  最暗在左上（225°，-20），不是原图那种只在顶部 ~40° 宽的带。余弦掩码只在
         *  顶部起作用：cosTop = -n2.y，顶部是 1、离开顶部 20° 是 0.94。内 cos 0.98
         *  （11.5° 内全暗）、外 cos 0.94（20° 外全亮），暗带总宽 ~40°，跟原图
         *  实测一致。第 20 批删的是 dot(n2, topDir) 那个"帽"（会在顶部两侧留熊猫眼），
         *  不是余弦掩码本身——余弦掩码是按夹角 smoothstep，跟笑脸一样，不会出熊猫眼。 */
        const val TUNE_TOP_DARK_INNER_COS = 0.98f
        const val TUNE_TOP_DARK_OUTER_COS = 0.94f
        /** 第 4 批新增、第 7 批调弱、第 10 批再调弱：球面漫反射强度。采样目标截图发现球体中央其实相当平，
         *  不是明显的明暗渐变，所以这个只给一丝若有若无的立体感，不是主要观感来源，
         *  默认值比第 4 批小了一大截。调到 0 就是完全没有这一丝渐变。第 21 批
         *  （DeepSeek 审）0.01→0.05：背景太平、折射扭不出花样，给内部直接加
         *  一点明暗——左上角稍亮、右下角稍暗，补一点体积感。第 23 批（用户令）
         *  0.05→0.01：水滴是清水玻璃，不自己带明暗——漫反射砍到近零，内部的
         *  明暗由那束光决定（右上入口亮、左下聚光亮），不是人工 shading。 */
        const val TUNE_DIFFUSE_STRENGTH = 0.01f
        /** 第 7 批新增：贴着最外缘的窄暗带强度——采样目标截图发现边缘有一圈比球体内部和
         *  背景都暗的细边（玻璃全反射那种"黑边"感），这是让边缘看着"是玻璃"而不是
         *  "一张半透明贴纸"的关键细节。第 13 批起只在顶部出现（topDarkMask），
         *  底部被"笑脸"亮弧盖住的位置不再变暗。第 14 批（DeepSeek 审）0.4→0.15：
         *  左侧和顶部太黑了，暗带要更淡。第 15 批：0.15 太淡了（0.15 × 边缘透明度
         *  0.25 ≈ 只暗 4%，原图是 -46），回调到 0.25（≈ 暗 6%），还是比原图淡，
         *  但至少看得见。第 17 批（DeepSeek 审）0.25→0.35：暗带能让水滴有一种
         *  被"压住"的稳重感。第 18 批 0.35→0.10：真机上它读出来是一圈黑边，
         *  用户问"为什么周围一圈黑"；新参考图（4454）的边缘是亮的、不是黑的，
         *  暗带只能留一点点意思。
         *  第 28 批（用户令，按参考图 4454 加强反差）：0.10→0.35——v96 实测
         *  顶部边缘 176、和背景一样，暗带看不见、球的上沿没被压住；参考图顶部
         *  有一层看得见的暗。注意第 18 批的教训（0.35 时真机读成一圈黑边）：
         *  这次暗带只在顶部 ~40° 宽的余弦掩码里起作用（第 24 批的 topDarkMask），
         *  不是整圈，黑边不会回来。 */
        const val TUNE_DARK_BAND_STRENGTH = 0.35f
        /** 第 24 批新增：亮边（菲涅尔）强度——边缘映的是亮背景，应该是亮的，不是暗的。
         *  第 23 批把染色/漫反射砍到近零后，水滴自己的颜色太暗，边缘 alpha 又高
         *  （0.22），暗颜色盖住亮背景，整圈都发暗（v94 实测最外缘 142–159）。
         *  亮边只在边缘（edge）起作用、顶部除外（topDarkMask 那里是暗带），把边缘
         *  亮回背景亮度（新参考图 4454 的边缘就是亮的细边、不是黑的）。0.80 是估计值，
         *  真机上太亮就降、太淡就升。 */
        const val TUNE_RIM_STRENGTH = 0.80f
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
        /** 第 9 批新增：颤动花瓣数——鼓缩的瓣数，必须是整数（θ=0 和 θ=2π 才接得上）。
         *  第 27 批（2026-11-19 Claude 审查第 26 批时发现）：这里原来是 2.0，但偶数倍角
         *  天然是"同相"的——cos(2θ+φ) 在 θ 和 θ+π 这两个正对的点上永远同值
         *  （cos(2θ+2π+φ)=cos(2θ+φ)），不可能产生测量报告里"右边鼓、左边缩"的反相
         *  摆动；只有奇数倍角才会在正对的两点上反相（cos(K(θ+π)+φ)=(-1)^K·cos(Kθ+φ)）。
         *  照实测公式直接模拟 5 秒 24fps 验证过：K=2 时 0°/180° 相关系数算出来是 -0.02
         *  （基本不相关），跟实测的 -0.79 对不上；改成 K=1 后模拟出来是 -1.0，方向和量级
         *  才对得上（比 -0.79 略强，因为改完这一项权重最大，这个暂时没细调）。 */
        const val TUNE_WOBBLE_K_1 = 1.0
        /** 第 9 批新增：花瓣 1 的幅度——0.012 就是这一层单独最多让半径多鼓/缩 1.2%。
         *  第 10 批（2026-11-16 真机反馈“奇形怪状”）从 0.035 砍下来：原版每一帧都是
         *  干净正圆，颤动只在动的时候才感觉得到，7.8% 总量太大、每帧都看着像疙瘩。
         *  第 22 批（2026-11-17 参考视频实测：原版呼吸约 5% 幅度、约 1.5 秒一圈）
         *  0.012→0.020，三组总量约 4.7%。 */
        const val TUNE_WOBBLE_AMP_1 = 0.020
        /** 第 9 批新增：花瓣 1 转动的快慢（弧度/秒）。
         *  第 22 批（参考视频实测约 1.5 秒一圈，周期 = 2π/SPEED）0.23→4.2，
         *  约 1.5 秒一圈；三组速度不成整数比，波形不重复。
         *  第 26 批（参考视频 24fps×5s 实测：主周期约 2.1 秒）4.2→3.0，
         *  约 2.1 秒一圈，周期要跟实测主周期对上。
         *  第 27 批：K 从 2 改成 1 后（见 TUNE_WOBBLE_K_1 的说明），这一层才真的是
         *  左右晃的主力（对边反相关，模拟验证 -1.0，实测 -0.79）——周期本身没变，
         *  只是现在真的对应测出来的那个"主周期"现象，不只是数字对上了。 */
        const val TUNE_WOBBLE_SPEED_1 = 3.0
        /** 第 9 批新增：颤动花瓣数 2（鼓缩 3 瓣，更碎一点的起伏）。 */
        const val TUNE_WOBBLE_K_2 = 3.0
        /** 第 22 批：0.008→0.015。 */
        const val TUNE_WOBBLE_AMP_2 = 0.015
        /** 第 22 批：0.15→2.9，约 2.2 秒一圈。 */
        const val TUNE_WOBBLE_SPEED_2 = 2.9
        /** 第 9 批新增：颤动花瓣数 3（鼓缩 5 瓣，最碎的那一层，贡献"局部此起彼伏"的细节）。 */
        const val TUNE_WOBBLE_K_3 = 5.0
        /** 第 22 批：0.006→0.012。 */
        const val TUNE_WOBBLE_AMP_3 = 0.012
        /** 第 22 批：0.31→5.6，约 1.1 秒一圈。 */
        const val TUNE_WOBBLE_SPEED_3 = 5.6
        /** 第 26 批新增：水滴形的静态幅度——底部半径比顶部大多少的一半。
         *  参考视频 24fps×5s 实测：底部 225px、顶部 196px，差 ~15%，
         *  所以一半是 0.07；cos(theta-π/2) 在底部为 +1、顶部为 -1。
         *  第 30 批（用户纠正）：水滴本质是圆形，所谓水滴形是某个时刻的形变，
         *  不是静态形状——把瞬时形变焊死成永久形状是错的。静态项归零（0.0），
         *  瞬时水滴形改由 K=1 左右晃产生（某一刻一侧鼓、一侧缩，就是水滴形）。 */
        const val TUNE_SHAPE_TEARDROP = 0.0f
        /** 第 26 批新增：颤动花瓣数 4（鼓缩 7 瓣，最碎的抖动层）。 */
        const val TUNE_WOBBLE_K_4 = 7.0
        /** 第 26 批新增：花瓣 4 的幅度——0.005 就是这一层单独最多让半径多鼓/缩 0.5%，
         *  只补"碎抖"的手感，不抢主颤动的戏。 */
        const val TUNE_WOBBLE_AMP_4 = 0.005
        /** 第 26 批新增：花瓣 4 转动的快慢（弧度/秒）——13.0 约 0.48 秒一圈，
         *  5 秒里反转约 20 次，加上前三组的慢晃，合起来接近实测的 21–54 次反转。 */
        const val TUNE_WOBBLE_SPEED_4 = 13.0
        /** 第 30 批新增：颤动花瓣数 5（鼓缩 9 瓣，最碎的抖动层）——Henry 视频
         *  30fps 实测：半径颤动没有干净周期，是 1–2 秒相关时间的快抖；4 组
         *  正弦模拟的 5 秒反转数只有 10–19 次，实测 21–54 次。再加一组高频
         *  小幅往上补。K=9 是奇数（对边反相，跟 K=1、3、5、7 同套道理）。 */
        const val TUNE_WOBBLE_K_5 = 9.0
        /** 第 30 批新增：花瓣 5 的幅度——0.004 就是这一层单独最多让半径多鼓/缩
         *  0.4%，只补"碎抖"的手感，不抢主颤动的戏。五组总量 0.020+0.015+0.012
         *  +0.005+0.004=0.056，最大半径倍数 1.056 < TUNE_SHAPE_MARGIN 1.18，安全。 */
        const val TUNE_WOBBLE_AMP_5 = 0.004
        /** 第 30 批新增：花瓣 5 转动的快慢（弧度/秒）——20.0 约 0.31 秒一圈，
         *  5 秒里单这一组就反转约 32 次，合起来往实测的 21–54 次靠。 */
        const val TUNE_WOBBLE_SPEED_5 = 20.0
        /** 第 9 批新增：构成颤动轮廓 Path 用多少个点——点数越多边缘越顺滑，但每帧要多算
         *  几个三角函数；48 在普通手机上绰绰有余，没有卡顿的必要不建议调太高。 */
        const val WOBBLE_POINTS = 48

        /** 第 9 批新增：着色器自己那套"圆形裁切"的半径放宽倍数——颤动会让水滴边缘鼓出去
         *  最多 `TUNE_WOBBLE_AMP_1+2+3+4+5` 这么多（第 30 批起约 5.6%），如果着色器自己还按原来的
         *  半径硬裁切，鼓出去的部分会被着色器判成"圆外"直接透明，等于白鼓了。这个值要
         *  略大于 1+幅度总和，留点安全余量；改了上面几个 AMP 的话记得这个也要跟着调大。
         *  第 30 批：五组总量 0.056，最大半径倍数 1.056 < 1.18，安全，不用动。 */
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

        // ====== 第 23 批：一束光模型（右上进、左下聚）+ 表面涟漪波包 ======
        // 光从右上打进来，折进水里会聚到对面左下——里面左下亮（聚光），穿出去还
        // 照亮外面左下一片（外面聚光在背景着色器里，见 OriloBackgroundView）。
        // 聚光和涟漪都是"光"（加色项），不走 alpha——水滴内部 alpha 只有 0.01，
        // 走 alpha 会被吃掉看不见；它们在 main() 的 return 里直接加到颜色上。
        /** 里面左下聚光的峰值亮度（0..1）：参考视频实测左下里面比背景亮 +33/255
         *  （≈0.13），这里取 0.15、留一点余量让它在真机上读得出来。太亮像灯泡
         *  就往 0.10 降，太淡看不见就往 0.20 升。 */
        const val TUNE_CAUSTIC_STRENGTH = 0.15f
        /** 里面左下聚光中心离水滴圆心的距离（相对半径）：0.6r，225° 方向（左下）。
         *  着色器里按 n2 坐标算，中心在 (-0.7071, +0.7071) * 0.6。 */
        const val TUNE_CAUSTIC_CENTER_FRAC = 0.6f
        /** 里面左下聚光的半径（相对半径）：亮斑从中心往外褪到 0 的距离，0.55r。
         *  中心 0.6r + 半径 0.55r = 1.15r，刚好到水滴边缘、被颤动 Path 裁掉，
         *  不会漏到水滴外面。 */
        const val TUNE_CAUSTIC_RADIUS_FRAC = 0.55f
        /** 涟漪亮度调制幅度（0..1）：0.03 就是亮度上下只动 3%，跟 v93 背景涟漪
         *  同一个强度——v93 真机上这个强度读着正好（用户说"这次好多了"）。
         *  涟漪现在在水滴表面上（不是背景上），强度不变。 */
        const val TUNE_RIPPLE_STRENGTH = 0.03f
        /** 涟漪空间频率（弧度/像素）：波长 = 2π/0.10 ≈ 63px，跟 v93 背景涟漪
         *  同一个值（参考圆弧间距约 50–70px）。水滴直径约 430px，一波跨过去
         *  约 7 个波长。 */
        const val TUNE_RIPPLE_FREQ = 0.10f
        /** 涟漪时间速度（弧度/秒）：波峰外扩速度 = 6.0/0.10 = 60px/秒，跟 v93
         *  同一个值。波包前 3–4 秒能跑约 180–240px（约半个水滴），再往后包络
         *  衰减、看不见了。 */
        const val TUNE_RIPPLE_SPEED = 6.0f
        /** 涟漪随距离衰减（1/像素）：300px 处剩 e^(-1.2)≈0.30，500px 处剩 0.14，
         *  跟 v93 同一个值。 */
        const val TUNE_RIPPLE_DECAY = 0.004f
        /** 涟漪波包的周期（秒）：一轮 45 秒（3 个源点各起一波）。包络 =
         *  smoothstep(0, 0.5, cycle) * exp(-cycle / 3.0)，cycle = iTime 对 45
         *  取模——0.5 秒内胀起来、3 秒时间常数衰减，到 45 秒时剩 e^(-15)≈0，
         *  下一波开始时包络是 0，不会"砰"一下跳出来。
         *  第 30 批：3 个源点各错开 5 秒（周期/3）轮流起波，等于每 5 秒有一波
         *  从不同点出发；单点周期还是 15 秒。
         *  第 30 批补（用户纠正：5 秒一波太密、不自然）：周期 15→45 秒，3 个
         *  源点错开 12 秒和 27 秒（不再是均匀的周期/3），一轮里三波的间隔是
         *  18 秒、15 秒、12 秒——都在 10–20 秒里，时间不均匀、看着随机。 */
        const val TUNE_RIPPLE_PERIOD = 45.0f
        /** 第 30 批补新增：涟漪源点 2（右下 315°）相对源点 1（左下 225°）的
         *  错开时间（秒）——12 秒。一轮 45 秒里三波的起波时刻是 0 秒、18 秒、
         *  33 秒，间隔 18、15、12 秒（都在 10–20 秒里，不均匀）。 */
        const val TUNE_RIPPLE_OFFSET_2 = 12.0f
        /** 第 30 批补新增：涟漪源点 3（左上 135°）相对源点 1 的错开时间（秒）
         *  ——27 秒。注意着色器里是 mod(iTime + offset, period)，offset 27
         *  对应起波时刻 45-27=18 秒（不是 27 秒）。 */
        const val TUNE_RIPPLE_OFFSET_3 = 27.0f
        /** 大高光跟着颤动游走的幅度（相对半径）：0.05r，在用户手机上约 ±10px
         *  （参考实测高光在 ±11px 内游走）。游走由颤动速度（4.2/2.9/5.6）驱动，
         *  跟水滴颤动同步、不是另外一套时钟。
         *  第 30 批（Henry 视频 30fps 实测）：高光游走其实是 ±25px，约 ±0.11r，
         *  0.05r 太小。提到 0.11r。 */
        const val TUNE_HIGHLIGHT_WANDER_FRAC = 0.11f
        /** 第 30 批新增：水滴中心漂移的幅度（相对半径）——Henry 视频 30fps 实测，
         *  中心在 (538, 716) 附近 ±7px 不规则漂移（标准差，范围 ±28px），没有
         *  固定周期。0.05r 在用户手机上约 ±12px，两组不成整数比的慢正弦叠加后
         *  标准差约 0.027r ≈ 6–7px。中心漂移后，颤动 Path、着色器 lensCenter、
         *  影子、高光都用同一个漂移后的 cx/cy，不会错位。 */
        const val TUNE_CENTER_DRIFT_FRAC = 0.05f

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
            uniform float  uDiffuseStrength;
            uniform float  uAmbientBoost;
            uniform float  uDarkBandStrength;
            uniform float  uTopDarkInnerCos;
            uniform float  uTopDarkOuterCos;
            uniform float  uRimStrength;
            uniform float  uShapeMargin;
            uniform float  uRingStrength;
            uniform float  uRingRadiusX;
            uniform float  uRingRadiusY;
            uniform float  uRingWidth;
            uniform float2 uRingCenter;
            uniform float3 blobAColor;
            uniform float3 blobBColor;
            uniform float  uCausticStrength;
            uniform float  uCausticCenterFrac;
            uniform float  uCausticRadiusFrac;
            uniform float  uRippleStrength;
            uniform float  uRippleFreq;
            uniform float  uRippleSpeed;
            uniform float  uRippleDecay;
            uniform float  uRipplePeriod;
            uniform float  uRippleOffset2;
            uniform float  uRippleOffset3;

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
                // （+40，不是线），第 13 批以柔光的方式加回来。
                // 第 24 批：顶部暗带改回余弦掩码（跟笑脸同套做法）——第 20 批的 n2.y
                // 掩码太宽（n2.y < 0.2 就起作用，盖住整个上半圈 146°），真机上暗带
                // 横跨左和上、最暗在左上（225°，-20），不是原图那种只在顶部 ~40° 宽
                // 的带。cosTop = -n2.y，顶部是 1、离开顶部 20° 是 0.94；内 cos 0.98
                // （11.5° 内全暗）、外 cos 0.94（20° 外全亮），总宽 ~40°。第 20 批
                // 删的是 dot(n2, topDir) 那个"帽"（会在顶部两侧留熊猫眼），余弦掩码
                // 是按夹角 smoothstep，跟笑脸一样，不会出熊猫眼。
                float cosTop = -n2.y;
                float topDarkMask = smoothstep(uTopDarkOuterCos, uTopDarkInnerCos, cosTop);
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
                // 第 24 批：亮边（菲涅尔）——边缘映的是亮背景，应该是亮的，不是暗的。
                // 只在边缘（edge）起作用，顶部除外（topDarkMask 那里是暗带、映上面
                // 暗处）。第 23 批染色/漫反射砍到近零后，水滴自己的颜色太暗、边缘
                // alpha 又高，整圈都发暗（v94 实测最外缘 142–159）；亮边把边缘亮回
                // 背景亮度（新参考图 4454 的边缘就是亮的细边、不是黑的）。加色项，
                // 直接加到颜色上；暗带乘法在它之后、只在顶部起作用，不会把它压暗。
                float rimBright = edge * (1.0 - topDarkMask) * uRimStrength;
                colorOut += float3(1.0, 1.0, 1.0) * rimBright;
                colorOut *= (1.0 - darkBand * uDarkBandStrength * topDarkMask);

                // 第 23 批：里面左下聚光——光从右上打进来、折进水里会聚到对面左下。
                // 中心在 225°（左下）、0.6r 处，柔和亮斑、往外褪到 0。参考实测左下
                // 里面比背景亮 +33/255（≈0.13），这里取 0.15。注意用 1.0 - smoothstep
                // 的写法，不用 edge0 > edge1 的反向 smoothstep（未定义行为，第 20 批
                // 讲过这个坑）。
                float2 causticDir = float2(-0.7071, 0.7071);
                float causticDist = length(n2 - causticDir * uCausticCenterFrac);
                float caustic = (1.0 - smoothstep(0.0, uCausticRadiusFrac, causticDist)) * uCausticStrength;

                // 第 23 批：涟漪在水滴自己的表面上（不是背景上）——从源点（rim 上）
                // 出发、沿着水滴表面往里扩、随距离衰减。波包：每 15 秒发一波，0.5 秒
                // 内胀起来、3 秒时间常数衰减到零，平时没有。源点在水滴本地坐标里
                // （不用 MainActivity 传），波峰随时间向外跑（波峰速度 =
                // uRippleSpeed / uRippleFreq），包络在周期边界是 0，相位跳变看不见。
                // 第 30 批（用户令）：涟漪改多点——原来只有一个源点（左下 225°），
                // 现在 3 个源点（225°左下、315°右下、135°左上），轮流起波；同一
                // 时刻最多一波是主峰，旧波已衰到 ~2%（间隔 12 秒以上，exp(-12/3)
                // ≈0.018），三波叠加不会冲到 3 倍。多个点、不同时刻的变化。
                // 第 30 批补（用户纠正：5 秒一波太密、不自然）：周期 15→45 秒，
                // 错开改成 12 秒和 27 秒（uRippleOffset2/3），一轮里三波的间隔是
                // 18 秒、15 秒、12 秒——都在 10–20 秒里，时间不均匀、看着随机。
                float ripple = 0.0;
                float2 rippleSrc1 = float2(-0.7071, 0.7071) * lensRadius;
                float rippleCycle1 = mod(iTime, uRipplePeriod);
                float rippleEnv1 = smoothstep(0.0, 0.5, rippleCycle1) * exp(-rippleCycle1 / 3.0);
                float rippleDist1 = length(local - rippleSrc1);
                ripple += sin(rippleDist1 * uRippleFreq - uRippleSpeed * rippleCycle1)
                    * exp(-rippleDist1 * uRippleDecay) * uRippleStrength * rippleEnv1;
                float2 rippleSrc2 = float2(0.7071, 0.7071) * lensRadius;
                float rippleCycle2 = mod(iTime + uRippleOffset2, uRipplePeriod);
                float rippleEnv2 = smoothstep(0.0, 0.5, rippleCycle2) * exp(-rippleCycle2 / 3.0);
                float rippleDist2 = length(local - rippleSrc2);
                ripple += sin(rippleDist2 * uRippleFreq - uRippleSpeed * rippleCycle2)
                    * exp(-rippleDist2 * uRippleDecay) * uRippleStrength * rippleEnv2;
                float2 rippleSrc3 = float2(-0.7071, -0.7071) * lensRadius;
                float rippleCycle3 = mod(iTime + uRippleOffset3, uRipplePeriod);
                float rippleEnv3 = smoothstep(0.0, 0.5, rippleCycle3) * exp(-rippleCycle3 / 3.0);
                float rippleDist3 = length(local - rippleSrc3);
                ripple += sin(rippleDist3 * uRippleFreq - uRippleSpeed * rippleCycle3)
                    * exp(-rippleDist3 * uRippleDecay) * uRippleStrength * rippleEnv3;

                // 聚光和涟漪都是"光"（加色项），直接加到颜色上、不走 alpha——水滴
                // 内部 alpha 只有 0.01，走 alpha 会被吃掉看不见。alpha 通道还是
                // 原来的值（水滴本身几乎透明，背景透过它显示；光是叠加上去的）。
                return half4(float4(colorOut * alpha + float3(1.0) * (caustic + ripple), alpha));
            }
        """
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        // 布局给 220dp 的框，水滴本身画 0.78 倍、圆心略靠上，给右下方的软影留地方。
        val r = min(width, height) / 2f * 0.78f
        // 第 30 批（Henry 视频 30fps 实测）：水滴中心不钉死——在框中心附近
        // ±7px 不规则漂移。两组不成整数比的慢正弦叠加（约 7 秒和 12 秒），
        // 幅度 0.05r，标准差约 0.027r。cx/cy 在这里一次漂移到位，后面颤动
        // Path、着色器 lensCenter、影子、高光全用这一个 cx/cy，不会错位。
        val tSec = SystemClock.uptimeMillis() / 1000.0
        val driftX = (sin(tSec * 0.90) * 0.7 + sin(tSec * 0.52 + 1.7) * 0.3).toFloat() * r * TUNE_CENTER_DRIFT_FRAC
        val driftY = (cos(tSec * 0.73 + 0.9) * 0.7 + cos(tSec * 1.10 + 2.4) * 0.3).toFloat() * r * TUNE_CENTER_DRIFT_FRAC
        val cx = width / 2f + driftX
        val cy = height * 0.45f + driftY
        if (r <= 0f) return

        // 影子：右下方一团软影（两条路径共用）。第 10 批按原版实测重调：影子中心最暗、
        // 偏右下，向外平滑变淡——之前是均匀的一团淡影，中心不够实。核心 alpha 0x26→0x38，
        // 中心再往右下挪一点（0.10r→0.18r，1.02r→1.08r），中间加一档过渡、影子铺得更开。
        // 第 14 批（DeepSeek 审）：影子再放淡、褪得更快——核心 0x30→0x18，中间
        // 0x14→0x08，中间点 0.45→0.35；铺得更开（渐变半径 0.9r→1.0r，椭圆左右
        // 各放宽 0.2r）。原图影子核心暗、但极快地向外消散。
        // 第 23 批（用户令"影子跟光走"）：光从右上打进来，影子推到左下——中心
        // +0.18r→-0.18r，椭圆跟着镜像（左宽右窄，原来是右宽左窄）；最深处还是
        // 贴水滴下缘（cy + 1.08r 不动）、向外褪净。顶部暗带不动（那是边缘折射，
        // 不是影子）。
        // 第 24 批（用户令"阴影不能超出水滴表面"）：影子椭圆顶从 cy+0.7r 挪到
        // cy+1.0r（水滴底边）——原来顶伸进水滴里面 0.3r，跟水滴下半的暗边连成
        // 一片，底部最暗点落在水滴外面（1.02r），看着像表面阴影漏出去了。现在
        // 影子只留在水滴下面（椭圆顶贴水滴底边、相切，不伸进水滴），表面阴影
        // （暗边/暗带）只在水滴里面。影子中心 cy+1.08r 不动、位置还是左下。
        // 第 28 批（用户令，按参考图 4454 加强反差）：核心 0x18→0x38、中间
        // 0x08→0x14——v96 实测影子最暗 164、比背景亮不了多少，球飘着落不了地；
        // 参考图影子最暗 34。渐变停点保持 (0f, 0.35f, 1f)：核心虽暗、但 35%
        // 半径处就开始快速消散，不糊成一大团黑。
        // 第 29 批（Claude 审查：纯算术验证，不是模型近似）：黑色阴影压在亮度
        // 177/255 的背景上，要压到参考图实测的 34/255，需要的核心不透明度是
        // 1 - 34/177 ≈ 81%，0x38（22%）只做到了约四分之一的路——按 22% 算，
        // composite = 177×(1-0.22) ≈ 138，离 34 还差一大截，不是"看着浅"的
        // 主观问题，是这个数值本身不够。这次提到 0xC8（约 78%），中间停点同比例
        // 跟着提（0x14→0x58），没有直接冲到数学上的 81%——怕一步到位太突兀、
        // 真机看着像个黑洞，留一点余地，真机看完大概率还要再往上推一点。 */
        shadowPaint.shader = RadialGradient(
            cx - r * 0.18f, cy + r * 1.08f, r * 1.0f,
            intArrayOf(0xC8000000.toInt(), 0x58000000, 0x00000000),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawOval(
            RectF(cx - r * 1.2f, cy + r * 1.0f, cx + r * 1.0f, cy + r * 1.6f),
            shadowPaint,
        )

        // 第 9 批：本体改成画"颤动 Path"而不是正圆——冻结时刻（待机）用 setAwake()
        // 里记下的时间，没冻结（干活）就用这一帧的挂钟时间，相位连续、没有接缝
        // 第 19 批：颤动相位一直用挂钟时间走，不再冻结。
        // 第 30 批：tSec 已在 onDraw 开头算过（中心漂移要用），这里不再重复算。
        val path = buildWobblePath(cx, cy, r, tSec)

        if (useShader) drawBodyWithShader(canvas, path, cx, cy, r) else drawBodyLegacy(canvas, path, cx, cy, r)
        drawHighlightsAndRim(canvas, cx, cy, r, tSec)
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
        s.setFloatUniform("uDiffuseStrength", TUNE_DIFFUSE_STRENGTH)
        s.setFloatUniform("uAmbientBoost", TUNE_AMBIENT_BOOST)
        s.setFloatUniform("uDarkBandStrength", TUNE_DARK_BAND_STRENGTH)
        // 第 24 批：顶部暗带余弦掩码（~40° 宽）+ 亮边（菲涅尔）。
        s.setFloatUniform("uTopDarkInnerCos", TUNE_TOP_DARK_INNER_COS)
        s.setFloatUniform("uTopDarkOuterCos", TUNE_TOP_DARK_OUTER_COS)
        s.setFloatUniform("uRimStrength", TUNE_RIM_STRENGTH)
        s.setFloatUniform("uShapeMargin", TUNE_SHAPE_MARGIN)
        s.setFloatUniform("uRingStrength", TUNE_RING_STRENGTH)
        s.setFloatUniform("uRingRadiusX", TUNE_RING_RADIUS_X)
        s.setFloatUniform("uRingRadiusY", TUNE_RING_RADIUS_Y)
        s.setFloatUniform("uRingWidth", TUNE_RING_WIDTH)
        // 内部折射环的中心跟着背景光斑 A 的位置缓慢漂移（量测报告：环的位置跟着背景
        // 流场移形）；ax/ay 在 0.12~0.48 一带晃，这里重新映射到水滴本地 -1..1 坐标系
        // 里一个比较居中、留得出椭圆范围的小范围漂移。
        s.setFloatUniform("uRingCenter", (phase.ax - 0.30f) * 2.0f, (phase.ay - 0.26f) * 2.2f)
        // 第 23 批：一束光模型——里面左下聚光 + 水滴表面涟漪波包。
        s.setFloatUniform("uCausticStrength", TUNE_CAUSTIC_STRENGTH)
        s.setFloatUniform("uCausticCenterFrac", TUNE_CAUSTIC_CENTER_FRAC)
        s.setFloatUniform("uCausticRadiusFrac", TUNE_CAUSTIC_RADIUS_FRAC)
        s.setFloatUniform("uRippleStrength", TUNE_RIPPLE_STRENGTH)
        s.setFloatUniform("uRippleFreq", TUNE_RIPPLE_FREQ)
        s.setFloatUniform("uRippleSpeed", TUNE_RIPPLE_SPEED)
        s.setFloatUniform("uRippleDecay", TUNE_RIPPLE_DECAY)
        s.setFloatUniform("uRipplePeriod", TUNE_RIPPLE_PERIOD)
        s.setFloatUniform("uRippleOffset2", TUNE_RIPPLE_OFFSET_2)
        s.setFloatUniform("uRippleOffset3", TUNE_RIPPLE_OFFSET_3)
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

    private fun drawHighlightsAndRim(canvas: Canvas, cx: Float, cy: Float, r: Float, tSec: Double) {
        // 大高光：右上一道斜的亮斑。第 17 批（DeepSeek 审）：原来压太扁（0.3）
        // 加 RadialGradient 柔光，容易画成两头尖尖的细长橄榄球、像一道划痕；
        // 原图高光是锐利的"新月形"/斜长条。改成：位置稍往外挪（0.43/0.46，
        // 让它有被边缘"切割"的感觉）、转得更斜（-40°）、别压那么扁（0.4）、
        // 半径收一点（0.25r）、核心纯白 + 中间 0x80 柔光 + 迅速褪去。
        // 第 20 批（DeepSeek 审）：中间停改成 0xE6、位置拉到 8%——前 8% 是极亮
        // 的、之后光速褪去，白色核心别那么死板，更像新月。
        // 第 21 批（DeepSeek 审）：中间停改成纯白、位置收到 5%——高光中心像
        // 针尖一样亮，再锐利一点点。
        // 第 23 批：大高光跟着颤动游走——参考实测高光在 ±11px 内游走。游走由颤动
        // 速度（4.2/2.9/5.6）驱动，跟水滴颤动同步；两组不同速度/相位的正弦叠加，
        // 走得不规则、不是画圈。幅度 ±0.05r（用户手机上约 ±10px）。
        // 第 28 批（用户令，按参考图 4454 加强反差）：高光改胖改软——scale
        // (1, 0.4)→(1, 0.55)，渐变停点 [0, 0.05, 1]→[0, 0.35, 1]。现在高光
        // 是个针尖（5% 白核就没了）、像道划痕；参考图是个新月，有 35% 的白核
        // 再慢慢淡出。
        val wanderX = (sin(TUNE_WOBBLE_SPEED_1 * tSec) * 0.7 + sin(TUNE_WOBBLE_SPEED_2 * tSec + 1.3) * 0.3).toFloat() * r * TUNE_HIGHLIGHT_WANDER_FRAC
        val wanderY = (cos(TUNE_WOBBLE_SPEED_3 * tSec + 0.7) * 0.7 + cos(TUNE_WOBBLE_SPEED_1 * tSec + 2.1) * 0.3).toFloat() * r * TUNE_HIGHLIGHT_WANDER_FRAC
        canvas.save()
        // 第 30 批（Henry 视频 30fps 实测）：高光核心在 0.80r 处（距圆心），
        // 我们 0.63r 太靠里。位置往外推到 (0.41, -0.57)，距圆心 0.70r、方向
        // 约 54°（右上）；外缘 0.70+0.25=0.95r，还在水滴里面，不会成贴片。
        // 再往外推到 0.80r 的话外缘 1.05r 就伸出水滴了，别推过头。
        canvas.translate(cx + r * 0.41f + wanderX, cy - r * 0.57f + wanderY)
        canvas.rotate(-40f)
        canvas.scale(1f, 0.55f)
        highlightPaint.shader = RadialGradient(
            0f, 0f, r * 0.25f,
            intArrayOf(0xFFFFFFFF.toInt(), 0xFFFFFFFF.toInt(), 0x00FFFFFF),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP,
        )
        canvas.drawCircle(0f, 0f, r * 0.25f, highlightPaint)
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
        // 第 25 批（用户令）：亮边那圈 drawCircle 删掉——它是固定正圆（半径 r-1f），
        // 不跟水滴的颤动路径走；水滴缩进去时白圈就悬浮在外面，像"实体光环"。
        // 亮边由着色器里的 uRimStrength（菲涅尔，跟着颤动路径走）负责，不再画圈。
        // 第 11 批删掉的下半圈硬线亮弧别恢复——底部"笑脸"亮弧在着色器里
        // （smileMask，柔光）；顶部亮边已去掉（原图顶部最暗）。
    }
}
