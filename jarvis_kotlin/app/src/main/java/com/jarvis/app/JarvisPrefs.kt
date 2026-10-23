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
    private const val KEY_WAKE_ENABLED = "wake_enabled"
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

    fun isBargeInEnabled(ctx: Context): Boolean = p(ctx).getBoolean(KEY_BARGE_IN_ENABLED, true)
    fun setBargeInEnabled(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_BARGE_IN_ENABLED, on).apply()
    }

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

    /** 上限 1024：用户那颗 gemma3-tudou.task 的上下文上限就是 1024，超了模型加载失败（旧桥踩过）。 */
    fun getLocalMaxTokens(ctx: Context): Int = p(ctx).getInt(KEY_LOCAL_MAX_TOKENS, 1024)
    fun setLocalMaxTokens(ctx: Context, v: Int) {
        p(ctx).edit().putInt(KEY_LOCAL_MAX_TOKENS, v.coerceIn(256, 1024)).apply()
    }

    fun isLocalLlmPrefer(ctx: Context): Boolean = p(ctx).getBoolean(KEY_LOCAL_PREFER, false)
    fun setLocalLlmPrefer(ctx: Context, on: Boolean) {
        p(ctx).edit().putBoolean(KEY_LOCAL_PREFER, on).apply()
    }

    private const val KEY_MUTED = "tts_muted"

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
