package com.jarvis.app.screen

/**
 * 批九移植说明：Andee 的 TapMarkerUi 是在屏幕上画「上次手势落点圆环」的悬浮
 * 标记层，供模型在手势后的截图里核对落点。它的**绘制部分本批未移植**——纯视觉
 * 反馈，不参与任何动作的执行与成败判定；而本工程的悬浮球（OrbController）自带
 * 状态气泡，接管边框（TakeoverBorder）也会在任务期间亮起，落点反馈不至于全无。
 *
 * 这里保留与原版同名的接口与落点状态记录，原因有二：
 *  1. ScreenController 在 tap/longPress 后调 mark()、截图时调
 *     revealNow()/lastMarkPx()/lastMarkAgeMs()，调用点全部原样保留，移植
 *     绘制层时只需替换本类实现，不必再动 ScreenController；
 *  2. GroundingLog 的 shot 记录要读 lastMarkPx/lastMarkAgeMs，状态是真的：
 *     mark() 如实记录坐标与时间戳，只是没有把圆环画出来。
 *
 * revealNow() 因此是空操作——原版语义是「手势通道把标记藏起来了，此刻让它
 * 重新可见」，没有绘制层就没有可显示的东西。
 */
class TapMarkerUi {

    enum class Kind { TAP, HOLD }

    @Volatile
    private var lastPx: IntArray? = null

    @Volatile
    private var lastAtMs = 0L

    fun mark(x: Int, y: Int, kind: Kind) {
        lastPx = intArrayOf(x, y)
        lastAtMs = System.currentTimeMillis()
    }

    /** 绘制层未移植：无可显示之物，空操作（状态记录不受影响）。 */
    fun revealNow() {
    }

    fun lastMarkPx(): IntArray? = lastPx

    fun lastMarkAgeMs(): Long? =
        if (lastAtMs == 0L) null else System.currentTimeMillis() - lastAtMs
}
