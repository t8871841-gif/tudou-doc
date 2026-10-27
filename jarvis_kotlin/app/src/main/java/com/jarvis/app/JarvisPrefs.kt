package com.jarvis.app

import android.content.Context
import android.content.SharedPreferences

/**
 * 二期新增：统一的设置读写（Agnes 对话 Key、唤醒词开关与灵敏度、打断说话开关）。
 * Key 只存本机 SharedPreferences，不写死在代码里，不上传。
 */
object JarvisPrefs {
    private const val FILE = "jarvis_settings"
    private const val KEY_AGNES_KEY = "agnes_api_key"
    private const val KEY_DEEPSEEK_KEY = "deepseek_api_key"
    private const val KEY_TASK_BRAIN = "task_brain" // 跨应用任务循环用哪个模型：agnes（默认）/ deepseek
    private const val KEY_WAKE_ENABLED = "wake_enabled"
    private const val KEY_BARGE_IN_ENABLED = "barge_in_enabled"

    private fun p(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun getAgnesKey(ctx: Context): String = p(ctx).getString(KEY_AGNES_KEY, "") ?: ""
    fun setAgnesKey(ctx: Context, key: String) {
        p(ctx).edit().putString(KEY_AGNES_KEY, key.trim()).apply()
    }

    // ---------- 批十二：DeepSeek Key + 跨应用任务模型选择 ----------

    fun getDeepSeekKey(ctx: Context): String = p(ctx).getString(KEY_DEEPSEEK_KEY, "") ?: ""
    fun setDeepSeekKey(ctx: Context, key: String) {
        p(ctx).edit().putString(KEY_DEEPSEEK_KEY, key.trim()).apply()
    }

    fun getTaskBrain(ctx: Context): String = p(ctx).getString(KEY_TASK_BRAIN, "agnes") ?: "agnes"
    fun setTaskBrain(ctx: Context, v: String) {
        p(ctx).edit().putString(KEY_TASK_BRAIN, v.trim()).apply()
    }

    fun isWakeEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_WAKE_ENABLED, false)
    fun setWakeEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_WAKE_ENABLED, on).apply()
    }

    // 2026-10-24 用户令「麦克风不要一直启动，点击再启用」：打断监听默认关——它会在每次播报时
    // 开麦，且 jk-v14 真机出现过播报被自己回声误判掐断（只见气泡没声）。要打断功能自己在设置里开。
    fun isBargeInEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_BARGE_IN_ENABLED, false)
    fun setBargeInEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_BARGE_IN_ENABLED, on).apply()
    }

    // ---------- 批九：GroundingLog（移植自 Andee）开关，默认关，设置页暂无入口 ----------

    private const val KEY_GROUNDING_ENABLED = "grounding_enabled"

    fun isGroundingEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_GROUNDING_ENABLED, false)

    // ---------- 批三：长期记忆总开关（记忆本体存独立 Room 库，与 Key 解耦） ----------

    private const val KEY_MEMORY_ENABLED = "memory_enabled"

    fun isMemoryEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_MEMORY_ENABLED, true)
    fun setMemoryEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_MEMORY_ENABLED, on).apply()
    }

    // ---------- 批四：技能固化总开关（技能本体存独立 Room 库 jarvis_skills.db） ----------

    private const val KEY_SKILL_ENABLED = "skill_enabled"

    fun isSkillEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_SKILL_ENABLED, true)
    fun setSkillEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_SKILL_ENABLED, on).apply()
    }

    // ---------- 批五：端侧小模型（开关/模型路径/maxTokens/优先本地） ----------

    private const val KEY_LOCAL_ENABLED = "local_llm_enabled"
    private const val KEY_LOCAL_PATH = "local_llm_path"
    private const val KEY_LOCAL_MAX_TOKENS = "local_llm_max_tokens"
    private const val KEY_LOCAL_PREFER = "local_llm_prefer"

    fun isLocalLlmEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_LOCAL_ENABLED, false)
    fun setLocalLlmEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_LOCAL_ENABLED, on).apply()
    }

    fun getLocalLlmPath(ctx: Context): String = p(ctx).getString(KEY_LOCAL_PATH, "") ?: ""
    fun setLocalLlmPath(ctx: Context, v: String) {
        p(ctx).edit().putString(KEY_LOCAL_PATH, v.trim()).apply()
    }

    /** 上限 4096：Gemma 3n 的 .task 上下文是 4096；旧 gemma3-tudou.task 上限 1024，超了加载失败（旧桥踩过），默认仍 1024。 */
    fun getLocalMaxTokens(ctx: Context): Int = p(ctx).getInt(KEY_LOCAL_MAX_TOKENS, 1024)
    fun setLocalMaxTokens(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_LOCAL_MAX_TOKENS, v.coerceIn(256, 4096)).apply()
    }

    fun isLocalLlmPrefer(ctx: Context): Boolean = p(ctx).getBoolean(KEY_LOCAL_PREFER, false)
    fun setLocalLlmPrefer(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_LOCAL_PREFER, on).apply()
    }

    // ---------- 批六：主动性（播报开关/勿扰/出发提醒；晨报时间在 ReminderScheduler 自己的存储里） ----------

    private const val KEY_ANNOUNCE_ENABLED = "announce_enabled"
    private const val KEY_ANNOUNCE_CALLS = "announce_calls"
    private const val KEY_ANNOUNCE_SMS = "announce_sms"
    private const val KEY_ANNOUNCE_APPS = "announce_apps_raw"
    private const val KEY_DND_ENABLED = "dnd_enabled"
    private const val KEY_DND_START = "dnd_start_min"
    private const val KEY_DND_END = "dnd_end_min"
    private const val KEY_DEPARTURE_ENABLED = "departure_enabled"
    private const val KEY_DEPARTURE_LEAD = "departure_lead_min"

    fun isAnnounceEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ANNOUNCE_ENABLED, false)
    fun setAnnounceEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ANNOUNCE_ENABLED, on).apply()
    }

    fun isAnnounceCalls(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ANNOUNCE_CALLS, true)
    fun setAnnounceCalls(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ANNOUNCE_CALLS, on).apply()
    }

    fun isAnnounceSms(ctx: Context): Boolean = p(ctx).getBoolean(KEY_ANNOUNCE_SMS, true)
    fun setAnnounceSms(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_ANNOUNCE_SMS, on).apply()
    }

    fun getAnnounceAppsRaw(ctx: Context): String = p(ctx).getString(KEY_ANNOUNCE_APPS, "") ?: ""
    fun setAnnounceAppsRaw(ctx: Context, v: String) {
        p(ctx).edit().putString(KEY_ANNOUNCE_APPS, v).apply()
    }
    fun announceAppsSet(ctx: Context): Set<String> =
        getAnnounceAppsRaw(ctx).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    fun isDndEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_DND_ENABLED, true)
    fun setDndEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_DND_ENABLED, on).apply()
    }

    fun getDndStartMin(ctx: Context): Int = p(ctx).getInt(KEY_DND_START, 22 * 60)
    fun setDndStartMin(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_DND_START, v.coerceIn(0, 1439)).apply()
    }

    fun getDndEndMin(ctx: Context): Int = p(ctx).getInt(KEY_DND_END, 8 * 60)
    fun setDndEndMin(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_DND_END, v.coerceIn(0, 1439)).apply()
    }

    /** 当前是否处于勿扰时段（支持跨夜，如 22:00–08:00）。 */
    fun isDndActive(ctx: Context): Boolean {
        if (!isDndEnabled(ctx)) return false
        val s = getDndStartMin(ctx)
        val e = getDndEndMin(ctx)
        if (s == e) return false
        val cal = java.util.Calendar.getInstance()
        val now = cal.get(java.util.Calendar.HOUR_OF_DAY) * 60 + cal.get(java.util.Calendar.MINUTE)
        return if (s < e) now in s until e else now >= s || now < e
    }

    fun isDepartureEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_DEPARTURE_ENABLED, false)
    fun setDepartureEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_DEPARTURE_ENABLED, on).apply()
    }

    fun getDepartureLeadMin(ctx: Context): Int = p(ctx).getInt(KEY_DEPARTURE_LEAD, 30)
    fun setDepartureLeadMin(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_DEPARTURE_LEAD, v.coerceIn(1, 720)).apply()
    }

    private const val KEY_MUTED = "tts_muted"

    // 批八：语音引擎三档——auto（云扬优先，失败重试一次再落系统）/ edge（只云扬）/ system（只系统）。
    // jk-v12 真机反馈两个声来回跳，先给用户一个能锁死的档位。
    private const val KEY_TTS_ENGINE = "tts_engine"
    fun getTtsEngine(ctx: Context): String = p(ctx).getString(KEY_TTS_ENGINE, "auto") ?: "auto"
    fun setTtsEngine(ctx: Context, v: String) {
        p(ctx).edit().putString(KEY_TTS_ENGINE, v).apply()
    }
    fun ttsEngineLabel(v: String): String = when (v) {
        "edge" -> "只用云扬"
        "system" -> "只用系统"
        else -> "自动"
    }

    // 批十：天气默认城市（问天气不带城市名时用；用户当前在新乡）
    private const val KEY_WEATHER_CITY = "weather_city"
    fun getWeatherCity(ctx: Context): String = p(ctx).getString(KEY_WEATHER_CITY, "新乡") ?: "新乡"
    fun setWeatherCity(ctx: Context, v: String) {
        p(ctx).edit().putString(KEY_WEATHER_CITY, v.ifBlank { "新乡" }).apply()
    }

    /** 批一新增：HUD 主页的静音按钮——开了就只显示文字回复，不过 EdgeTts。 */
    fun isMuted(ctx: Context): Boolean = p(ctx).getBoolean(KEY_MUTED, false)
    fun setMuted(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_MUTED, on).apply()
    }

    // ---------- 批二：语音音色/语速音调 ----------

    private const val KEY_VOICE = "tts_voice"
    private const val KEY_RATE = "tts_rate_pct" // -50..100
    private const val KEY_PITCH = "tts_pitch_hz" // -50..50，Edge 语音接受的大致区间

    fun getVoice(ctx: Context): String = p(ctx).getString(KEY_VOICE, "") ?: ""
    fun setVoice(ctx: Context, voiceId: String) {
        p(ctx).edit().putString(KEY_VOICE, voiceId).apply()
    }

    fun getRatePct(ctx: Context): Int = p(ctx).getInt(KEY_RATE, -2)
    fun setRatePct(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_RATE, v.coerceIn(-50, 100)).apply()
    }

    fun getPitchHz(ctx: Context): Int = p(ctx).getInt(KEY_PITCH, 0)
    fun setPitchHz(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_PITCH, v.coerceIn(-50, 50)).apply()
    }

    // ---------- 批二：唤醒严格度（替代原来的 0..100 滑杆） ----------

    private const val KEY_WAKE_STRICTNESS = "wake_strictness" // 0=严格 1=标准 2=宽松

    /** 0=严格（"贾维斯"须在句首且整句短）/ 1=标准（默认，句首附近）/ 2=宽松（句中任意位置）。 */
    fun getWakeStrictness(ctx: Context): Int = p(ctx).getInt(KEY_WAKE_STRICTNESS, 1)
    fun setWakeStrictness(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_WAKE_STRICTNESS, v.coerceIn(0, 2)).apply()
    }

    // ---------- 批二：视觉看屏配置 ----------

    private const val KEY_VISION_ENABLED = "vision_enabled"
    private const val KEY_VISION_MODEL = "vision_model" // 留空 = 复用 Agnes 对话模型
    private const val KEY_VISION_BASE = "vision_base_url" // 留空 = 复用 Agnes 接口地址
    private const val KEY_VISION_KEY = "vision_api_key" // 留空 = 复用 Agnes Key
    private const val KEY_VISION_WHITELIST = "vision_whitelist" // 每行一个包名

    fun isVisionEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_VISION_ENABLED, true)
    fun setVisionEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_VISION_ENABLED, on).apply()
    }

    fun getVisionModel(ctx: Context): String = p(ctx).getString(KEY_VISION_MODEL, "") ?: ""
    fun setVisionModel(ctx: Context, v: String) { p(ctx).edit().putString(KEY_VISION_MODEL, v.trim()).apply() }

    fun getVisionBaseUrl(ctx: Context): String = p(ctx).getString(KEY_VISION_BASE, "") ?: ""
    fun setVisionBaseUrl(ctx: Context, v: String) { p(ctx).edit().putString(KEY_VISION_BASE, v.trim()).apply() }

    fun getVisionApiKey(ctx: Context): String = p(ctx).getString(KEY_VISION_KEY, "") ?: ""
    fun setVisionApiKey(ctx: Context, v: String) { p(ctx).edit().putString(KEY_VISION_KEY, v.trim()).apply() }

    fun getVisionWhitelistRaw(ctx: Context): String = p(ctx).getString(KEY_VISION_WHITELIST, "") ?: ""
    fun setVisionWhitelistRaw(ctx: Context, v: String) { p(ctx).edit().putString(KEY_VISION_WHITELIST, v).apply() }
    fun visionWhitelistSet(ctx: Context): Set<String> =
        getVisionWhitelistRaw(ctx).lines().map { it.trim() }.filter { it.isNotEmpty() }.toSet()

    // ---------- 批二：常驻与入口 ----------

    private const val KEY_KEEP_ALIVE = "keep_alive_enabled"
    private const val KEY_CONTINUOUS_CHAT = "continuous_chat"

    /** 开了就记住"要保持后台常驻"，下次打开 App 会自动尝试启动前台服务（不代表现在就在跑）。 */
    fun isKeepAliveEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_KEEP_ALIVE, false)
    fun setKeepAliveEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_KEEP_ALIVE, on).apply()
    }

    fun isContinuousChat(ctx: Context): Boolean = p(ctx).getBoolean(KEY_CONTINUOUS_CHAT, false)
    fun setContinuousChat(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_CONTINUOUS_CHAT, on).apply()
    }
}
