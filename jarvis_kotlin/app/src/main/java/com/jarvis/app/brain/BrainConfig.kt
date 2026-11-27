package com.jarvis.app.brain

import android.content.Context
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.task.TaskRouter

/** Andee 的大脑模式常量（VoiceConfig.BRAIN_* 的 Jarvis 版；Jarvis 只用 local）。 */
const val BRAIN_HUB = "hub"
const val BRAIN_LOCAL = "local"
const val BRAIN_CODEFLYING = "codeflying"

/**
 * 大脑的后端配置。1:1 对应 Andee VoiceConfig.BrainConfig
 * （mode / apiKey / baseUrl / model / thinking / reasoningEffort），
 * LocalBrain 直接拿它建 LlmClient。
 */
data class BrainConfig(
    /** [BRAIN_HUB], [BRAIN_LOCAL] or [BRAIN_CODEFLYING]。Jarvis 只用 local。 */
    val mode: String,
    val apiKey: String,        // empty = on-device brain cannot run
    val baseUrl: String,
    val model: String,
    val thinking: Boolean,
    val reasoningEffort: String,
    /** NPU 档：本地 LiteRT-LM 后端，没有 Key/地址；model 只当审计里的名字。 */
    val isNpu: Boolean = false,
) {
    /** The agent loop runs on this device (local DeepSeek or CodeFlying). */
    val isLocal: Boolean get() = mode == BRAIN_LOCAL || mode == BRAIN_CODEFLYING

    /**
     * Same backend, possibly asked differently. [mode], [apiKey] and
     * [baseUrl] say *who* answers; [model], [thinking] and
     * [reasoningEffort] only say how they are asked, and a running brain
     * can take those on the next request.
     */
    fun sameBackend(other: BrainConfig): Boolean =
        mode == other.mode && apiKey == other.apiKey && baseUrl == other.baseUrl && isNpu == other.isNpu
}

/**
 * Jarvis 的大脑配置来源：模型开关（JarvisPrefs）+ 任务路由（TaskRouter）。
 * 手动四档（GLM/自定义/DeepSeek/NPU）直接取对应配置；自动档按 TaskRouter.route()
 * 的结果选 GLM 还是 DeepSeek（拿不准默认 DeepSeek，与原来自动档一致）；
 * NPU 档按 TaskRouter.resolve() 的结果选 NPU 还是云端（简单任务 NPU，
 * 复杂多步/打电话走云端）。
 *
 * 注意 GLM/自定义档 thinking=false：LlmClient 会往请求里放
 * `"thinking": {"type": "disabled"}`（不带 reasoning_effort）。Andee 自己
 * 支持 BYO GLM 走同一个客户端，智谱端大概率直接忽略这个字段；换脑后第一
 * 次跑 GLM 任务时看一眼审计/报错，400 的话在 LlmClient 里按 baseUrl 摘掉
 * 这个字段（3 行的事）。
 */
object JarvisBrain {

    /** 本轮任务最多几步（Andee 默认 25；与原来 TaskLoop 的 MAX_STEPS 一致）。 */
    const val MAX_STEPS = 25

    fun configForMode(ctx: Context, mode: String): BrainConfig = when (mode) {
        "deepseek" -> BrainConfig(
            mode = BRAIN_LOCAL,
            apiKey = JarvisPrefs.getDeepSeekKey(ctx),
            baseUrl = JarvisPrefs.DEEPSEEK_BASE_URL,
            model = JarvisPrefs.DEEPSEEK_MODEL,
            thinking = true,
            reasoningEffort = JarvisPrefs.DEEPSEEK_REASONING_EFFORT,
        )
        "custom" -> BrainConfig(
            mode = BRAIN_LOCAL,
            apiKey = JarvisPrefs.getCustomKey(ctx),
            baseUrl = JarvisPrefs.getCustomBaseUrl(ctx),
            model = JarvisPrefs.getCustomModel(ctx),
            thinking = false,
            reasoningEffort = "",
        )
        // NPU 档：没有 Key/地址，model 只当审计里的名字；LocalBrain 按
        // isNpu 建 NpuLlmClient，不走 HTTP。BrainRunner 与 LocalBrain
        // 的“没 Key”拦截都要认 isNpu（见那两处）。
        "npu" -> BrainConfig(
            mode = BRAIN_LOCAL,
            apiKey = "",
            baseUrl = "",
            model = "gemma-4-E2B-it-sm8550",
            thinking = false,
            reasoningEffort = "",
            isNpu = true,
        )
        else -> BrainConfig( // glm
            mode = BRAIN_LOCAL,
            apiKey = JarvisPrefs.getCustomKey(ctx),
            baseUrl = JarvisPrefs.GLM_BASE_URL,
            model = JarvisPrefs.GLM_TASK_MODEL,
            thinking = false,
            reasoningEffort = "",
        )
    }

    /** 给这句任务配大脑：路由一次定模式（手动档原样、自动档按文本、NPU 档简单走 NPU 复杂走云端）。 */
    fun forTask(ctx: Context, taskText: String): BrainConfig {
        val mode = TaskRouter.resolve(ctx, taskText).mode
        return configForMode(ctx, mode)
    }

    /**
     * 当前生效的场景（LocalBrain 每步重读 persona 用）。Jarvis 的场景系统
     * （ScenePresets/SceneCommands）还没接到大脑的 persona 上，先返回 null
     * （只有基础 persona 生效）；接上是另外一件事，不堵换脑。
     */
    fun activeScene(ctx: Context): Notebook.Scene? = null
}
