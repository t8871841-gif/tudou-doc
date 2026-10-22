package com.jarvis.native

import android.content.Context
import android.content.SharedPreferences

/**
 * 二期新增：统一的设置读写（Agnes 对话 Key、唤醒词开关与灵敏度、打断说话开关）。
 * Key 只存本机 SharedPreferences，不写死在代码里，不上传。
 */
object JarvisPrefs {
    private const val FILE = "jarvis_settings"
    private const val KEY_AGNES_KEY = "agnes_api_key"
    private const val KEY_WAKE_ENABLED = "wake_enabled"
    private const val KEY_WAKE_SENSITIVITY = "wake_sensitivity" // 0..100，越大越容易被唤醒（误唤醒也越多）
    private const val KEY_BARGE_IN_ENABLED = "barge_in_enabled"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAgnesKey(ctx: Context): String = p(ctx).getString(KEY_AGNES_KEY, "") ?: ""
    fun setAgnesKey(ctx: Context, key: String) {
        p(ctx).edit().putString(KEY_AGNES_KEY, key.trim()).apply()
    }

    fun isWakeEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_WAKE_ENABLED, false)
    fun setWakeEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_WAKE_ENABLED, on).apply()
    }

    /** 0..100，默认 50。数值越大，判定阈值越松（越容易唤醒，也越容易误唤醒）。 */
    fun getWakeSensitivity(ctx: Context): Int = p(ctx).getInt(KEY_WAKE_SENSITIVITY, 50)
    fun setWakeSensitivity(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_WAKE_SENSITIVITY, v.coerceIn(0, 100)).apply()
    }

    fun isBargeInEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_BARGE_IN_ENABLED, true)
    fun setBargeInEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_BARGE_IN_ENABLED, on).apply()
    }
}
