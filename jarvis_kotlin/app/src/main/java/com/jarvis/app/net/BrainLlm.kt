package com.jarvis.app.net

import org.json.JSONArray
import org.json.JSONObject

/**
 * LocalBrain 用的模型 client 接口（DeepSeekLlmClient 的实例方法子集）。
 *
 * 本来 LocalBrain 直接抱着 DeepSeekLlmClient（Andee 1:1 移植）构造；
 * NPU 档要换另一个后端（LiteRT-LM），而 DeepSeekLlmClient 是不许改逻辑的
 * 移植件、方法又不是 open——所以实例方法走这个接口，构造时按
 * BrainConfig.isNpu 二选一。伴生对象里的静态能力（toolSpecs、textBlock、
 * imageBlock、ToolCall、Reply）仍在 DeepSeekLlmClient 上，LocalBrain
 * 继续从那里拿，不走接口。
 */
interface BrainLlmClient {
    fun complete(messages: List<JSONObject>, tools: JSONArray): DeepSeekLlmClient.Reply
    fun cancel()
    fun retune(model: String, thinking: Boolean, reasoningEffort: String)
}

/** HTTP 后端（DeepSeek/GLM/自定义）的薄包装：只转发，不包逻辑。 */
class HttpBrainLlmClient(private val inner: DeepSeekLlmClient) : BrainLlmClient {
    override fun complete(messages: List<JSONObject>, tools: JSONArray): DeepSeekLlmClient.Reply =
        inner.complete(messages, tools)

    override fun cancel() = inner.cancel()

    override fun retune(model: String, thinking: Boolean, reasoningEffort: String) =
        inner.retune(model, thinking, reasoningEffort)
}
