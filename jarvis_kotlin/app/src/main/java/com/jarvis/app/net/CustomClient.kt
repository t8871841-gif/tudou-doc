package com.jarvis.app.net

import android.content.Context
import com.jarvis.app.JarvisPrefs
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/**
 * 对话大脑：GLM / 自定义大模型（OpenAI 兼容的 /chat/completions 接口）。
 * 按模型开关（JarvisPrefs.getModelMode）取配置：GLM 模式用内置智谱（聊天 glm-4.5），
 * 自定义模式用设置里用户填的地址/模型；Key 两种模式共用同一个 API Key 栏位。
 * 未配置时直接报错，不发请求。
 *
 * 维护一份进程内滚动对话历史（不落盘——持久化记忆是需求第 9 条，未排期），
 * 超过 MAX_TURNS 轮后从最早的一轮开始丢弃，避免单次请求体无限增长。
 * 流式 askStream（边想边说）与单轮 askOnce 共用同一套配置。
 */
object CustomClient {

    private const val MAX_TURNS = 12
    private const val SYSTEM_PROMPT =
        "你是 Jarvis，用户的个人智能助理。自称 Jarvis，称呼用户\"先生\"。默认使用中文回答，" +
            "语气克制、干练、不啰嗦；你的回答会直接被朗读出来，所以不要使用markdown、列表符号、" +
            "表情符号，只用适合语音播报的自然口语短句。遇到做不到或失败的事情直说，不要编造。" +
            // 批九硬约束（jk-v14 真机：问天气被编出"晴朗，60 度"）：实时信息没来源就不许编。
            "特别注意：天气、气温、时间日期、新闻、价格、汇率、股价、路况这类实时信息，" +
            "你没有任何数据来源，不知道就是不知道，必须明说\"这个我查不到\"，绝对禁止编造具体数值、" +
            "预报或看似确切的说法。记不清的事实同样不要装作确定。"

    private val client by lazy {
        OkHttpClient.Builder()
            // Andee 对齐：LlmClient 连接 15 秒、读 180 秒（思考模型按分钟回答）。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val history = mutableListOf<Pair<String, String>>() // role to content

    @Synchronized
    fun resetHistory() {
        history.clear()
    }

    /** 当前任务是否已被取消（急停用）——同一个任务序号被标记取消后，回调不再生效。 */
    @Volatile
    private var cancelSeq = 0

    fun cancelInFlight() {
        cancelSeq++
    }

    /**
     * 单轮调用，不进任何对话历史（任务循环 task/TaskLoop 与记忆提取用）。多模态格式：
     * jpegBase64 非空时按 image_url + data URL 附图（自定义模型走 OpenAI 兼容接口，带图问）。
     * 回调在后台线程。
     */
    fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        val base = JarvisPrefs.getChatBaseUrl(ctx).trim().trimEnd('/')
        val model = JarvisPrefs.getChatModel(ctx)
        val key = JarvisPrefs.getChatKey(ctx)
        if (base.isBlank() || model.isBlank()) {
            onError("未配置自定义大模型接口地址或模型名，请先去设置里填写")
            return
        }
        if (key.isBlank()) {
            onError("未配置自定义大模型 API Key，请先去设置里填写")
            return
        }
        val userContent: Any = if (jpegBase64.isNullOrBlank()) {
            userText
        } else {
            JSONArray()
                .put(JSONObject().put("type", "text").put("text", userText))
                .put(
                    JSONObject().put("type", "image_url").put(
                        "image_url",
                        JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                    )
                )
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userContent))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.2)
        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onError("自定义大模型接口返回错误：HTTP ${resp.code}")
                        return
                    }
                    try {
                        val text = resp.body?.string().orEmpty()
                        val json = JSONObject(text)
                        val reply = json.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content").trim()
                        onResult(reply)
                    } catch (e: Exception) {
                        onError("解析自定义大模型返回内容失败：${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 单轮多模态问答（看屏用）——带一张图片，不进滚动对话历史（看屏问答与日常聊天分开计数）。
     * 按模型开关取配置：GLM 模式用内置 glm-4v-plus + 智谱地址，自定义模式用视觉三项
     * （留空则回退到自定义大模型）；模型是否真支持图片输入取决于后端，
     * 这里按 OpenAI 多模态消息格式（image_url + data URL）发送。
     */
    fun askVision(
        ctx: Context, prompt: String, jpegBase64: String,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        val base = JarvisPrefs.getVisionBaseUrl(ctx)
        val model = JarvisPrefs.getVisionModel(ctx)
        val key = JarvisPrefs.getVisionApiKey(ctx)
        if (key.isBlank()) {
            onError("未配置 API Key，请先去设置里填写")
            return
        }
        if (base.isBlank()) {
            onError("未配置视觉模型接口地址，请先去设置里填写")
            return
        }
        val content = JSONArray()
            .put(JSONObject().put("type", "text").put("text", prompt))
            .put(
                JSONObject().put("type", "image_url").put(
                    "image_url",
                    JSONObject().put("url", "data:image/jpeg;base64,$jpegBase64")
                )
            )
        val messages = JSONArray()
            .put(JSONObject().put("role", "user").put("content", content))
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.1)
        val req = Request.Builder()
            .url("${base.trimEnd('/')}/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onError("视觉模型返回错误：HTTP ${resp.code}")
                        return
                    }
                    try {
                        val text = resp.body?.string().orEmpty()
                        val json = JSONObject(text)
                        val reply = json.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content").trim()
                        onResult(reply)
                    } catch (e: Exception) {
                        onError("解析视觉模型返回内容失败：${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 发起一次对话（非流式）。onResult 在后台线程回调；onError 携带可读错误信息（网络失败 / 未配置 Key / HTTP 错误码）。
     */
    fun ask(ctx: Context, userText: String, memoryContext: String = "", onResult: (String) -> Unit, onError: (String) -> Unit) {
        val base = JarvisPrefs.getChatBaseUrl(ctx).trim().trimEnd('/')
        val model = JarvisPrefs.getChatModel(ctx)
        val key = JarvisPrefs.getChatKey(ctx)
        if (base.isBlank() || model.isBlank()) {
            onError("未配置自定义大模型接口地址或模型名，请先去设置里填写")
            return
        }
        if (key.isBlank()) {
            onError("未配置自定义大模型 API Key，请先去设置里填写")
            return
        }
        val mySeq = ++cancelSeq
        synchronized(this) {
            history.add("user" to userText)
            while (history.size > MAX_TURNS * 2) history.removeAt(0)
        }

        // 批三：长期记忆拼在系统提示词后面（memoryContext 为空时与原来完全一致）
        val fullSystemPrompt = if (memoryContext.isNotBlank()) SYSTEM_PROMPT + memoryContext else SYSTEM_PROMPT
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", fullSystemPrompt))
        synchronized(this) {
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.6)

        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃结果
                    if (!resp.isSuccessful) {
                        onError("自定义大模型接口返回错误：HTTP ${resp.code}")
                        return
                    }
                    try {
                        val text = resp.body?.string().orEmpty()
                        val json = JSONObject(text)
                        val reply = json.getJSONArray("choices")
                            .getJSONObject(0)
                            .getJSONObject("message")
                            .getString("content")
                            .trim()
                        synchronized(this@CustomClient) {
                            history.add("assistant" to reply)
                        }
                        onResult(reply)
                    } catch (e: Exception) {
                        onError("解析自定义大模型返回内容失败：${e.message}")
                    }
                }
            }
        })
    }

    /**
     * 流式对话（边想边说）：与 ask 相同的请求体与历史，
     * 但 stream=true，逐个 SSE 分片回吐，供 JarvisAgent 按句播报，不用等整段回完。
     * onToken 每收到一段增量就回调；onDone 在流结束时回完整全文；
     * onError 只在请求/解析失败时回调。取消守卫与 ask 同款（cancelSeq）。
     * 注意：onToken/onDone 都跑在 OkHttp 的 dispatcher 线程上，调用方自己切回主线程。
     */
    fun askStream(
        ctx: Context, userText: String, memoryContext: String = "",
        onToken: (String) -> Unit, onDone: (String) -> Unit, onError: (String) -> Unit
    ) {
        val base = JarvisPrefs.getChatBaseUrl(ctx).trim().trimEnd('/')
        val model = JarvisPrefs.getChatModel(ctx)
        val key = JarvisPrefs.getChatKey(ctx)
        if (base.isBlank() || model.isBlank()) {
            onError("未配置自定义大模型接口地址或模型名，请先去设置里填写")
            return
        }
        if (key.isBlank()) {
            onError("未配置自定义大模型 API Key，请先去设置里填写")
            return
        }
        val mySeq = ++cancelSeq
        synchronized(this) {
            history.add("user" to userText)
            while (history.size > MAX_TURNS * 2) history.removeAt(0)
        }
        val fullSystemPrompt = if (memoryContext.isNotBlank()) SYSTEM_PROMPT + memoryContext else SYSTEM_PROMPT
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", fullSystemPrompt))
        synchronized(this) {
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        val body = JSONObject()
            .put("model", model)
            .put("messages", messages)
            .put("stream", true)
            .put("temperature", 0.6)
        val req = Request.Builder()
            .url("$base/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃
                onError("网络请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (mySeq != cancelSeq) return
                    if (!resp.isSuccessful) {
                        onError("自定义大模型接口返回错误：HTTP ${resp.code}")
                        return
                    }
                    val full = StringBuilder()
                    try {
                        val source = resp.body?.source() ?: run {
                            onError("自定义大模型流式返回为空")
                            return
                        }
                        while (true) {
                            if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃
                            val line = source.readUtf8Line() ?: break
                            val data = line.trim()
                            if (data.isEmpty()) continue
                            if (!data.startsWith("data:")) continue
                            val payload = data.removePrefix("data:").trim()
                            if (payload == "[DONE]") break
                            try {
                                val json = JSONObject(payload)
                                val delta = json.optJSONArray("choices")
                                    ?.optJSONObject(0)
                                    ?.optJSONObject("delta")
                                    ?.optString("content").orEmpty()
                                if (delta.isNotEmpty()) {
                                    full.append(delta)
                                    onToken(delta)
                                }
                            } catch (_: Exception) {
                                // 单个分片解析失败就跳过，不中断整条流
                            }
                        }
                        val reply = full.toString().trim()
                        synchronized(this@CustomClient) {
                            history.add("assistant" to reply)
                        }
                        onDone(reply)
                    } catch (e: Exception) {
                        if (mySeq != cancelSeq) return
                        onError("解析自定义大模型流式返回失败：${e.message}")
                    }
                }
            }
        })
    }
}
