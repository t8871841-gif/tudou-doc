package com.jarvis.app.ui

import android.content.Context
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable

/**
 * 共用液态玻璃背景（第 1 批，2026-11-12；用户定：对话框、底部按钮、设置行、HUD 图标全套都用它）。
 *
 * 材料四件套，与 OrbController.drawGlassBall 同款视觉语言（径向渐变 + 左上高光 + 底部亮边）：
 *  1. 半透明渐变底：调用方给的两个底色（TL→BR 对角渐变），本组件不改色，只套上去。
 *  2. 镜面高光边：1.5dp 渐变描边，左上最亮、右下最暗——实现办法是两层套叠：
 *     下层是一张全尺寸对角渐变（亮→暗），上层是内缩一个描边宽的底色层，露出的 1.5dp 细圈就是高光边。
 *  3. 顶部柔光：上沿一道白光由强渐弱，约在上 1/3 处淡出。
 *  4. 底部内阴影 + 外层柔和阴影：内阴影是下沿一道黑由弱渐强；外投影是整张底下一层向下淡出的黑影
 *     （纯黑底上看不见，是为以后按钮/设置行这些非黑底面留的，本批照样做进去，不特殊处理）。
 *
 * 诚实说明：原生 View 没有“模糊背后内容”的现成办法（RenderEffect 只模糊自身内容，
 * 且要 API 31+；本组件只用 minSdk 24 就有的 GradientDrawable/LayerDrawable，不做真模糊）。
 * 对话区底色是纯黑，毛玻璃的“透”在纯黑上看不出来——本批在对话框上的效果主要是高光边 +
 * 微透底色 + 柔和阴影，不是真的折射。HUD 里第 4 批的 CSS backdrop-filter 才是真模糊。
 *
 * 材料数值表（都在下面当具名常量，不散落各处）：
 *  描边宽        RIM_WIDTH_DP = 1.5dp
 *  高光边渐变    左上白 60% → 中部白 22% → 右下白 8%
 *  顶部柔光      上沿白 20% → 中部白 4% → 透明（约上 1/3 淡出）
 *  底部内阴影    透明 → 下沿黑 18%（只在下半部分）
 *  外投影        上沿黑 30% → 透明，向下伸出 3dp、两侧各伸出 1dp
 */
object LiquidGlass {

    /** 镜面高光边的描边宽。 */
    const val RIM_WIDTH_DP = 1.5f

    /** 高光边：左上亮（白 60%）。 */
    private const val RIM_TOP = 0x99FFFFFF.toInt()
    /** 高光边：中部（白 22%）。 */
    private const val RIM_MID = 0x38FFFFFF.toInt()
    /** 高光边：右下暗（白 8%）。 */
    private const val RIM_BOTTOM = 0x14FFFFFF.toInt()

    /** 顶部柔光：上沿白 20%。 */
    private const val TOP_LIGHT_TOP = 0x33FFFFFF.toInt()
    /** 顶部柔光：中部白 4%（到这一步已基本看不见，柔光约在上 1/3 淡出）。 */
    private const val TOP_LIGHT_MID = 0x0AFFFFFF.toInt()

    /** 底部内阴影：下沿黑 18%。 */
    private const val BOTTOM_SHADE = 0x2E000000.toInt()

    /** 外投影：顶部黑 30%，向下淡出到透明。 */
    private const val SHADOW_TOP = 0x4D000000.toInt()

    /** 外投影向下伸出的距离。 */
    private const val SHADOW_DOWN_DP = 3f
    /** 外投影向两侧各伸出的距离。 */
    private const val SHADOW_SIDE_DP = 1f
    /** 外投影顶部离本体的距离（投影从本体下 2dp 处开始，不盖顶边）。 */
    private const val SHADOW_TOP_DP = 2f

    private fun dp(context: Context, v: Float): Int =
        (v * context.resources.displayMetrics.density + 0.5f).toInt()

    /**
     * 通用入口：统一圆角的液态玻璃背景（底部按钮、设置行等后续批次用）。
     * [radiusDp] 是四个角共用的圆角半径。
     */
    fun rounded(context: Context, fillTop: Int, fillBottom: Int, radiusDp: Float): Drawable {
        val r = dp(context, radiusDp).toFloat()
        return build(context, fillTop, fillBottom, floatArrayOf(r, r, r, r, r, r, r, r))
    }

    /**
     * 对话框气泡入口：每角圆角可不同（与旧气泡的 cornerRadii 一字不差，调用方原样传入）。
     * [cornerRadii] 是 8 个值：左上 x/y、右上 x/y、右下 x/y、左下 x/y（单位 px）。
     */
    fun bubble(context: Context, fillTop: Int, fillBottom: Int, cornerRadii: FloatArray): Drawable =
        build(context, fillTop, fillBottom, cornerRadii)

    /**
     * 五层套叠（下→上）：外投影 → 高光边渐变（全尺寸）→ 半透明渐变底 → 顶部柔光 → 底部内阴影。
     * 底、柔光、内阴影三层都内缩一个描边宽，露出的细圈就是高光边。
     */
    private fun build(context: Context, fillTop: Int, fillBottom: Int, radii: FloatArray): Drawable {
        val rim = dp(context, RIM_WIDTH_DP)
        // 0. 外投影：向下淡出的黑影（LayerDrawable 允许负 inset，层会伸出边界——XML layer-list
        // 里同款写法的老办法；在纯黑底上不可见，留给以后非黑底的面）。
        val shadow = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(SHADOW_TOP, 0x00000000)).apply {
            cornerRadii = radii
        }
        // 1. 高光边：全尺寸对角渐变，亮→暗（左上最亮、右下最暗）。
        val rimLayer = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(RIM_TOP, RIM_MID, RIM_BOTTOM)
        ).apply {
            cornerRadii = radii
        }
        // 2. 半透明渐变底：调用方给的颜色，原样套用。
        val fill = GradientDrawable(
            GradientDrawable.Orientation.TL_BR, intArrayOf(fillTop, fillBottom)
        ).apply {
            cornerRadii = radii
        }
        // 3. 顶部柔光：上沿白光渐弱。
        val topLight = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(TOP_LIGHT_TOP, TOP_LIGHT_MID, 0x00000000)
        ).apply {
            cornerRadii = radii
        }
        // 4. 底部内阴影：只在下半部分出现的下沿暗角。
        val bottomShade = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(0x00000000, 0x00000000, BOTTOM_SHADE)
        ).apply {
            cornerRadii = radii
        }
        return LayerDrawable(arrayOf<Drawable>(shadow, rimLayer, fill, topLight, bottomShade)).apply {
            setLayerInset(0, -dp(context, SHADOW_SIDE_DP), dp(context, SHADOW_TOP_DP), -dp(context, SHADOW_SIDE_DP), -dp(context, SHADOW_DOWN_DP))
            setLayerInset(1, 0, 0, 0, 0)
            setLayerInset(2, rim, rim, rim, rim)
            setLayerInset(3, rim, rim, rim, rim)
            setLayerInset(4, rim, rim, rim, rim)
        }
    }
}
