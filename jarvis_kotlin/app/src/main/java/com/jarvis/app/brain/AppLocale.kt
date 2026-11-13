package com.jarvis.app.brain

import android.content.Context
import android.content.res.Configuration
import java.util.Locale

/**
 * Turns "which language" into "which Context the strings come from".
 * 1:1 移植自 Andee 的 AppLocale，只改一处：Andee 从 VoiceConfig.uiLanguage
 * 取用户选的语言；Jarvis 没有语言设置项，所以直接取设备默认语言。
 * LocalBrain 只用它两处：AppLocale.str() 读 brain_* 文案、
 * AppLocale.localeOf() 给日期格式化定语言。
 */
object AppLocale {

    /** 设备默认语言（Jarvis 没有语言设置项）。 */
    fun localeOf(context: Context): Locale = Locale.getDefault()

    /** [context] with its locale overridden to the device default. */
    fun wrap(context: Context): Context {
        val locale = localeOf(context)
        val base = context.resources.configuration
        if (base.locales[0] == locale) return context
        val config = Configuration(base)
        config.setLocale(locale)
        return context.createConfigurationContext(config)
    }

    /** One string, in the user's language. For call sites with no Context to keep. */
    fun str(context: Context, id: Int): String = wrap(context).getString(id)

    /** [str] with `%s` / `%d` arguments substituted. */
    fun str(context: Context, id: Int, vararg args: Any): String =
        wrap(context).getString(id, *args)
}
