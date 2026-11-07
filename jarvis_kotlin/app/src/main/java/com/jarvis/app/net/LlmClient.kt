package com.jarvis.app.net

import android.content.Context
import com.jarvis.app.JarvisPrefs

/**
 * 第十五批·LLM 注册表（DroidRun llama-index LLM 抽象 + provider 注册表的
 * TaskLoop 版，只借思路、自己重写，不抄 DroidRun 代码）。
 *
 * 先读了三个调用点到底需要 client 的哪种能力，再定的接口（见下），不是凭空
 * 抽象出来的两层皮：
 *
 * 1. 短问答 askOnce——一次返回全文：TaskLoop 的三个调用点（任务主循环
 *    askModel、Reflector 核验 askReflect、Manager 规划 askManager）全都
 *    只用这一种能力。两个 client 都有它，都是回调式（回调在后台线程）、都
 *    接受 isAlive 急停守卫、读超时都是 180 秒（TaskLoop 外层闩锁 200 秒 /
 *    短问答 60 秒不变）。唯一的差别是参数顺序：CustomClient.askOnce 是
 *    (…, onResult, onError, isAlive)，AutoGLMClient.askOnce 是
 *    (…, isAlive, onResult, onError)——适配器把顺序统一成接口的 canonical
 *    顺序 (…, isAlive, onResult, onError)，调用方不用再记两套顺序。
 * 2. 流式对话 askStream——逐句返回：只有 CustomClient 有它（JarvisAgent
 *    的聊天流式路径用，固定 SYSTEM_PROMPT + 滚动历史 + 场景，不带
 *    systemPrompt / 图片 / isAlive）；AutoGLMClient 没有流式 API，而且它
 *    的输出是转好的 JSON 动作串、不是聊天文本，拿它冒充流式聊天是错的。
 *    所以流式是独立的子接口 StreamingLlmClient，只有 Custom 适配器实现；
 *    不给 AutoGLM 编一个“一次回全文当一段 token”的假流式——TaskLoop 也不
 *    用流式，假实现只会藏错。
 *
 * 注册表只做接线，不改任何一个 client 内部的请求构造、解析、重试、限流
 * 逻辑；不改模型选择（GLM / 自定义开关）本身；不新增模型、不改默认模型。
 * 适配器是薄透传：只调换参数顺序、原样转发回调，不包逻辑、不改超时。
 */
interface LlmClient {

    /** 注册表里的名字（"custom" / "autoglm"，见 LlmRegistry）。 */
    val name: String

    /**
     * 单轮短问答，不进任何对话历史（CustomClient.askOnce / AutoGLMClient.askOnce
     * 同款语义）。[jpegBase64] 非空时按多模态格式附图；[isAlive] 返回 false
     * 即已急停/已换任务，本次调用直接中止、不回调；[onResult]/[onError]
     * 在后台线程回调，只会回调其中一个。
     */
    fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        isAlive: (() -> Boolean)? = null,
        onResult: (String) -> Unit, onError: (String) -> Unit
    )
}

/**
 * 会流式对话的模型 client（LlmClient 的子接口，只是一种能力的标记）。
 * 目前只有 CustomClient 的适配器实现（CustomClient.askStream 同款语义：
 * 固定对话 system prompt + 滚动历史，不带自定义 systemPrompt / 图片 /
 * isAlive；[onToken] 每收到一段增量就回调，[onDone] 在流结束时回完整全文，
 * 回调都在 OkHttp dispatcher 线程上）。
 */
interface StreamingLlmClient : LlmClient {

    fun askStream(
        ctx: Context, userText: String, memoryContext: String = "",
        onToken: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit
    )
}

/** CustomClient 的适配器：短问答 + 流式都原样透传，只统一参数顺序。 */
private object CustomLlmClient : StreamingLlmClient {
    override val name: String = LlmRegistry.NAME_CUSTOM

    override fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        isAlive: (() -> Boolean)?,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        CustomClient.askOnce(
            ctx, systemPrompt, userText, jpegBase64,
            onResult = onResult, onError = onError, isAlive = isAlive
        )
    }

    override fun askStream(
        ctx: Context, userText: String, memoryContext: String,
        onToken: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit
    ) {
        CustomClient.askStream(ctx, userText, memoryContext, onToken, onDone, onError)
    }
}

/** AutoGLMClient 的适配器：只有短问答（它没有流式 API，不实现 StreamingLlmClient）。 */
private object AutoGlmLlmClient : LlmClient {
    override val name: String = LlmRegistry.NAME_AUTOGLM

    override fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        isAlive: (() -> Boolean)?,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        AutoGLMClient.askOnce(
            ctx, systemPrompt, userText, jpegBase64,
            isAlive = isAlive, onResult = onResult, onError = onError
        )
    }
}

/**
 * 模型 client 注册表：按名字 / 模式登记，TaskLoop 只认接口、不再硬编码
 * 用哪个 client。行为与改前一字不变（见各函数的“改前对应”）：
 *
 * - 任务大脑 taskClient：改前 askModel 的 if/else 原样搬过来——模型开关
 *   为 "custom" 走 Custom 适配（用户填的 OpenAI 兼容接口、带图问），其余
 *   （默认 GLM，含开关值异常时）走 AutoGLM 适配（autoglm-phone，do(...)
 *   由 AutoGLMClient 转成 JSON 动作串，计划/笔记块回传也在它内部）。
 * - 聊天 client chatClient：改前 askReflect / askManager 统一走
 *   CustomClient.askOnce——核验要 A/B/C 自由文本、规划要计划块自由文本，
 *   GLM 模式的任务 client 只回 JSON 动作串、问不出来。聊天 client 与模式
 *   开关无关（CustomClient 内部按开关解析聊天模型：GLM 模式 glm-4.5 /
 *   自定义模式用户填的模型），所以 chatClient 不取 ctx。
 * - 按名取 client(name)：未登记的名字直接抛 IllegalArgumentException，
 *   不静默回落到默认 client——静默回落会把“配错了名”藏成“走了默认模型”，
 *   任务做到一半才发现不对。
 */
object LlmRegistry {

    /** 聊天模型 client（CustomClient 适配）：自由文本短问答 + 流式对话。 */
    const val NAME_CUSTOM = "custom"

    /** 任务专用模型 client（AutoGLMClient 适配）：只回 JSON 动作串的短问答。 */
    const val NAME_AUTOGLM = "autoglm"

    /** 任务大脑：按模型开关取，与改前 askModel 的 if/else 一一对应。 */
    fun taskClient(ctx: Context): LlmClient =
        if (JarvisPrefs.getModelMode(ctx) == "custom") CustomLlmClient else AutoGlmLlmClient

    /** 聊天 client（Reflector 核验 / Manager 规划用）：恒为 Custom 适配，与模式开关无关。 */
    fun chatClient(): StreamingLlmClient = CustomLlmClient

    /** 按名取 client：未登记的名字抛错，不静默回落。 */
    fun client(name: String): LlmClient = when (name) {
        NAME_CUSTOM -> CustomLlmClient
        NAME_AUTOGLM -> AutoGlmLlmClient
        else -> throw IllegalArgumentException(
            "未登记的模型：${name}（已登记：${NAME_CUSTOM}、${NAME_AUTOGLM}）"
        )
    }

    /** 已登记的名字集合（新模型接进来时在这里加名、加分支即可，TaskLoop 不用改）。 */
    fun registeredNames(): Set<String> = setOf(NAME_CUSTOM, NAME_AUTOGLM)
}
