package com.jarvis.app.hud

import android.graphics.Color

/**
 * 移植自 Flutter 版 `hud_painters.dart` 的配色（蓝→紫→玫瑰，参考 Gemini），
 * 数值原样照抄，只是从 Dart Color 换成 Android ARGB Int。
 */
object HudPalette {
    const val BLUE2 = 0xFF8AB4F8.toInt()   // 待命：天蓝
    const val ROSE = 0xFFE8788A.toInt()    // 聆听：玫瑰
    const val PURPLE = 0xFFA98BE0.toInt()  // 思考：薰衣草紫
    const val AQUA = 0xFF7FD6E6.toInt()    // 回答：浅青
    const val ICE = 0xFFF1F4FF.toInt()     // 近白文字

    const val BOOT_BG = 0xFF050811.toInt()
    const val BG_TOP = 0xFF050811.toInt()
    const val BG_MID = 0xFF0B132B.toInt()
    const val BG_BOTTOM = 0xFF050811.toInt()

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

    /** 主色 → (辅色, 第三色)，和 Dart hudPaletteFor 的几个命中分支对齐。 */
    fun secondaryTertiary(primary: Int): Pair<Int, Int> = when (primary) {
        BLUE2 -> PURPLE to ROSE
        ROSE -> PURPLE to BLUE2
        PURPLE -> BLUE2 to ROSE
        AQUA -> BLUE2 to PURPLE
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
