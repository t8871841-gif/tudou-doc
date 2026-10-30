package com.jarvis.app.hud

import android.graphics.Color

/**
 * 星系全息版配色（设计稿确认版，替换掉原来蓝→紫→玫瑰那套"呼吸光球"配色）。
 * 待机态是等离子星系：橙核(CORE) → 紫(MID) → 靛蓝(OUTER) 的径向渐变；
 * 唤醒之后是全息面板：聆听玫瑰、思考紫、应答青三色，跟星系的紫/靛蓝是同一个色系家族，
 * 过渡时色相不会跳得很突兀。
 */
object HudPalette {
    // 星系径向渐变三色（核心→旋臂中段→外围尘埃）
    const val GALAXY_CORE = 0xFFFFB347.toInt()   // 橙核
    const val GALAXY_MID = 0xFF9B6BFF.toInt()    // 紫（旋臂中段，也是"思考"色）
    const val GALAXY_OUTER = 0xFF2B2FA8.toInt()  // 靛蓝（外围尘埃）
    // 第二梯队批九新增：最外圈尘埃带的青蓝色，参考图里旋臂外侧是发青发蓝的尘埃雾，
    // 不是一路暗下去的纯靛蓝。只在 GalaxyHoloStageView 的配色渐变里新增这一档，
    // 不改动 GALAXY_OUTER 本身的值——GALAXY_OUTER 还被冲击波环等别处引用，原样留着。
    const val GALAXY_TEAL = 0xFF35D6D6.toInt()   // 青蓝（旋臂最外侧尘埃带）

    const val BLUE2 = GALAXY_MID            // 待命态主题色沿用旋臂紫，跟星系视觉统一
    const val ROSE = 0xFFFF5F8F.toInt()     // 聆听：玫瑰
    const val PURPLE = GALAXY_MID           // 思考：跟星系旋臂同一个紫
    const val AQUA = 0xFF2FE6C9.toInt()     // 回答：青绿
    const val ICE = 0xFFF1F4FF.toInt()      // 近白文字

    const val BOOT_BG = 0xFF070611.toInt()
    const val BG_TOP = 0xFF070611.toInt()
    const val BG_MID = 0xFF120A22.toInt()
    const val BG_BOTTOM = 0xFF050508.toInt()

    /** state 字符串和 JarvisAgent 的状态机一致：idle/listening/thinking/speaking。 */
    fun colorFor(state: String): Int = when (state) {
        "thinking" -> PURPLE
        "speaking" -> AQUA
        "listening" -> ROSE
        else -> BLUE2 // idle / booting
    }

    fun labelsFor(state: String, wakeMode: Boolean): Pair<String, String> = when (state) {
        "listening" -> "LISTENING" to "正在聆听"
        "thinking" -> "PROCESSING" to "思考中"
        "speaking" -> "RESPONDING" to "回应中"
        else -> if (wakeMode) "WAKE WORD" to "等待唤醒" else "STANDBY" to "待命"
    }

    /**
     * 主色 → (辅色, 第三色)。待命/思考现在共用同一个"旋臂紫"主题色（星系设计本来就是这个
     * 意思——思考态只是待命态星系收拢成面板后的延续），所以 `BLUE2`/`PURPLE` 这两个常量
     * 现在值相同，下面只留一个分支，另一个会被判成重复分支，故意不重复写。
     */
    fun secondaryTertiary(primary: Int): Pair<Int, Int> = when (primary) {
        PURPLE -> GALAXY_CORE to ROSE // 同时覆盖 BLUE2（值相同）
        ROSE -> PURPLE to GALAXY_OUTER
        AQUA -> GALAXY_CORE to PURPLE
        else -> {
            val hsv = FloatArray(3)
            Color.colorToHSV(primary, hsv)
            val h1 = (hsv[0] + 40) % 360
            val h2 = (hsv[0] + 90) % 360
            val a = Color.HSVToColor(floatArrayOf(h1, hsv[1], hsv[2]))
            val b = Color.HSVToColor(floatArrayOf(h2, hsv[1], hsv[2]))
            a to b
        }
    }

    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (alpha.coerceIn(0f, 1f) * 255).toInt()
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun lerp(from: Int, to: Int, t: Float): Int {
        val tt = t.coerceIn(0f, 1f)
        val a = Color.alpha(from) + ((Color.alpha(to) - Color.alpha(from)) * tt).toInt()
        val r = Color.red(from) + ((Color.red(to) - Color.red(from)) * tt).toInt()
        val g = Color.green(from) + ((Color.green(to) - Color.green(from)) * tt).toInt()
        val b = Color.blue(from) + ((Color.blue(to) - Color.blue(from)) * tt).toInt()
        return Color.argb(a, r, g, b)
    }
}
