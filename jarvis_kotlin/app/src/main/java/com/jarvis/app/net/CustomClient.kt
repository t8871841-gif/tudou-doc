package com.jarvis.app.net

import android.content.Context
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.config.ScenePresets
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
 * 双预算裁剪（M21，Andee LocalBrain 同款：60 条消息且 60_000 字符，超了从最旧丢；
 * 完整的 HARVEST 复盘不在此做，只做双预算）。
 * 流式 askStream（边想边说）与单轮 askOnce 共用同一套配置。
 */
object CustomClient {

    /** M21：历史预算——60 条消息（Andee MAX_HISTORY 同款），不是 60 轮。 */
    private const val MAX_TURNS = 60
    /** M21：历史预算另一半——总字符数（Andee MAX_HISTORY_CHARS 同款）。 */
    private const val MAX_HISTORY_CHARS = 60_000
    private const val SYSTEM_PROMPT =
        "你是 Jarvis，用户的个人智能助理。自称 Jarvis，称呼用户\"先生\"。默认使用中文回答，" +
            "语气克制、干练、不啰嗦；你的回答会直接被朗读出来，所以不要使用markdown、列表符号、" +
            "表情符号，只用适合语音播报的自然口语短句。遇到做不到或失败的事情直说，不要编造。" +
            // 批九硬约束（jk-v14 真机：问天气被编出"晴朗，60 度"）：实时信息没来源就不许编。
            "特别注意：天气、气温、时间日期、新闻、价格、汇率、股价、路况这类实时信息，" +
            "你没有任何数据来源，不知道就是不知道，必须明说\"这个我查不到\"，绝对禁止编造具体数值、" +
            "预报或看似确切的说法。记不清的事实同样不要装作确定。" +
            // 脑移植 SpeechMood：模型可在回复开头带一个心情标记（如 [happy]、[calm]、[curious]），
            // 表示语气；标记不朗读、不显示。没把握时不带标记也行。
            "你可以在回复开头带一个心情标记（如 [happy]、[calm]、[curious]、[tense]、[concerned]）表示语气，标记不会被朗读出来；不确定时不用带。"

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

    /** C7：飞行中的 OkHttp Call（Andee LlmClient.current 同款），cancelInFlight() 连同网络一起取消。 */
    @Volatile
    private var inFlightCall: Call? = null

    /** C7：急停——序号作废 + 断开网络（正在读流的 readUtf8Line 会因此立即抛 IOException 打断，不用等满 180 秒读超时）。 */
    fun cancelInFlight() {
        cancelSeq++
        runCatching { inFlightCall?.cancel() }
    }

    private fun clearInFlight(call: Call) {
        if (call === inFlightCall) inFlightCall = null
    }

    /** M21：双预算裁剪——消息数或总字符数任一超标都从最旧丢，至少保留最后一条。调用方须已持 history 锁（本函数可重入）。 */
    private fun trimHistory() {
        synchronized(this) {
            while (history.size > 1 &&
                (history.size > MAX_TURNS || history.sumOf { it.second.length } > MAX_HISTORY_CHARS)
            ) {
                history.removeAt(0)
            }
        }
    }

    /** JarvisAgent 路约定：把一段助手回复追加进 history（并按双预算裁剪）。空文本不追加。 */
    fun addAssistantTurn(text: String) {
        val reply = text.trim()
        if (reply.isEmpty()) return
        synchronized(this) {
            history.add("assistant" to reply)
            trimHistory()
        }
    }

    /** 从服务端错误体里取 detail（Andee LlmHttpException.detail 同款）。 */
    private fun errorDetail(body: String): String {
        val detail = runCatching {
            val err = JSONObject(body).opt("error")
            when (err) {
                is JSONObject -> err.optString("message")
                is String -> err
                else -> ""
            }
        }.getOrDefault("").ifBlank { body }.trim()
        return detail
    }

    /** M27：按 Andee explainFailure 分码给可操作文案，调用方原样报给用户即可。 */
    private fun httpError(prefix: String, code: Int, body: String): String = when (code) {
        402 -> "大模型账户余额不足（HTTP 402），请先充值后再试"
        401, 403 -> "大模型鉴权失败（HTTP $code），请检查 API Key 是否正确、是否过期"
        400 -> "大模型请求被拒（HTTP 400）：${errorDetail(body).take(160).ifBlank { "HTTP 400" }}"
        404 -> "大模型接口或模型不存在（HTTP 404），请检查接口地址和模型名是否填对"
        408, 429 -> "大模型请求太频繁或超时（HTTP $code），请稍等一会儿再试"
        in 500..599 -> "大模型服务暂时不可用（HTTP $code），请稍后再试"
        else -> "$prefix：HTTP $code"
    }

    /** M27：超时 / 断网各一句（Andee explainFailure 同款）。 */
    private fun networkError(e: IOException): String = when (e) {
        is java.net.SocketTimeoutException -> "大模型请求超时，请检查网络后重试"
        is java.net.UnknownHostException, is java.net.ConnectException -> "网络未连接或连不上大模型服务，请检查网络后再试"
        else -> "网络请求失败：${e.message}"
    }

    /**
     * 单轮调用，不进任何对话历史（任务循环 task/TaskLoop 与记忆提取用）。多模态格式：
     * jpegBase64 非空时按 image_url + data URL 附图（自定义模型走 OpenAI 兼容接口，带图问）。
     * 回调在后台线程。
     * M11：与 ask/askStream 共用 cancelSeq——cancelInFlight() 能丢弃本次结果；
     * [isAlive] 由调用方（任务循环）提供，发请求前与回调时检查，返回 false 即已急停/已换任务，
     * 本次调用直接中止，不回调 onResult/onError（调用方靠自己的超时/短轮询返回 null）。
     */
    fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        onResult: (String) -> Unit, onError: (String) -> Unit,
        isAlive: (() -> Boolean)? = null
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
        inFlightCall?.cancel() // 新一轮取代旧一轮：旧请求序号已作废，网络也一并断开，不叠加跑
        if (isAlive?.invoke() == false) return // 发请求前检查：已急停就不发
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
        val call = client.newCall(req)
        inFlightCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                clearInFlight(call)
                if (mySeq != cancelSeq || isAlive?.invoke() == false) return // 已被急停/下一轮覆盖，丢弃
                onError(networkError(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    response.use { resp ->
                        if (mySeq != cancelSeq || isAlive?.invoke() == false) return // 已被急停/下一轮覆盖，丢弃结果
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            onError(httpError("自定义大模型接口返回错误", resp.code, errBody))
                            return
                        }
                        try {
                            val text = resp.body?.string().orEmpty()
                            val json = JSONObject(text)
                            val reply = json.getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").getString("content").trim()
                            if (mySeq != cancelSeq || isAlive?.invoke() == false) return // 回调时再查一次
                            onResult(reply)
                        } catch (e: Exception) {
                            onError("解析自定义大模型返回内容失败：${e.message}")
                        }
                    }
                } finally {
                    clearInFlight(call)
                }
            }
        })
    }

    /**
     * 单轮多模态问答（看屏用）——带一张图片，不进滚动对话历史（看屏问答与日常聊天分开计数）。
     * 按模型开关取配置：GLM 模式用内置 glm-4v-plus + 智谱地址，自定义模式用视觉三项
     * （留空则回退到自定义大模型）；模型是否真支持图片输入取决于后端，
     * 这里按 OpenAI 多模态消息格式（image_url + data URL）发送。
     * 自动档下按 GLM 走（任务循环不调这里——任务模型是直接带图问的，路由在第 1 批
     * 已跟好；这里只供语音看屏幕/点目标、技能步骤与设置页的视觉测试用）。
     * L3：与 ask / askStream 同款取消守卫（mySeq + isAlive），取消后回调不再执行。
     */
    fun askVision(
        ctx: Context, prompt: String, jpegBase64: String,
        onResult: (String) -> Unit, onError: (String) -> Unit,
        isAlive: (() -> Boolean)? = null
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
        val mySeq = ++cancelSeq
        inFlightCall?.cancel() // 新一轮取代旧一轮：旧请求序号已作废，网络也一并断开，不叠加跑
        if (isAlive?.invoke() == false) return // 发请求前检查：已急停就不发
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
        val call = client.newCall(req)
        inFlightCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                clearInFlight(call)
                if (mySeq != cancelSeq || isAlive?.invoke() == false) return // 已被急停/下一轮覆盖，丢弃
                onError(networkError(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    response.use { resp ->
                        if (mySeq != cancelSeq || isAlive?.invoke() == false) return // 已被急停/下一轮覆盖，丢弃结果
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            onError(httpError("视觉模型返回错误", resp.code, errBody))
                            return
                        }
                        try {
                            val text = resp.body?.string().orEmpty()
                            val json = JSONObject(text)
                            val reply = json.getJSONArray("choices")
                                .getJSONObject(0).getJSONObject("message").getString("content").trim()
                            if (mySeq != cancelSeq || isAlive?.invoke() == false) return // 回调时再查一次
                            onResult(reply)
                        } catch (e: Exception) {
                            onError("解析视觉模型返回内容失败：${e.message}")
                        }
                    }
                } finally {
                    clearInFlight(call)
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
        inFlightCall?.cancel() // 新一轮取代旧一轮：旧请求序号已作废，网络也一并断开，不叠加跑

        // L5：记忆不拼进 system prompt（system prompt 保持常量，保住前缀缓存，Andee 同款），
        // 改拼到本轮用户消息上；memoryContext 本身以 "\n\n以下是关于用户的一些长期记忆…" 开头，直接追加即可。
        val userContent = if (memoryContext.isBlank()) userText else userText + memoryContext
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        synchronized(this) {
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        // L6：本轮 user 先不进 history，只放进本次请求的 messages；拿到回复后再与 assistant 成对写入，
        // 失败/被取消时不留孤儿 user 消息。
        messages.put(JSONObject().put("role", "user").put("content", userContent))
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

        val call = client.newCall(req)
        inFlightCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                clearInFlight(call)
                if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃
                onError(networkError(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    response.use { resp ->
                        if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃结果
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            onError(httpError("自定义大模型接口返回错误", resp.code, errBody))
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
                                // L6：user 与 assistant 成对写入（发请求前没写 history，失败/取消时不留孤儿 user）
                                // history 里只存用户原话，不存拼进去的记忆文本。
                                history.add("user" to userText)
                                history.add("assistant" to reply)
                                trimHistory()
                            }
                            onResult(reply)
                        } catch (e: Exception) {
                            onError("解析自定义大模型返回内容失败：${e.message}")
                        }
                    }
                } finally {
                    clearInFlight(call)
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
        inFlightCall?.cancel() // 新一轮取代旧一轮：旧请求序号已作废，网络也一并断开，不叠加跑
        // L5：记忆不拼进 system prompt（system prompt 保持常量，保住前缀缓存，Andee 同款），
        // 改拼到本轮用户消息上；history 里只存用户原话。
        val userContent = if (memoryContext.isBlank()) userText else userText + memoryContext
        // 场景移植 ScenePresets：活动场景的规则 + 场景列表拼进 system prompt，模型按场景行事；
        // 无场景时 system prompt 保持常量（L5 前缀缓存不受影响）。
        val activeSceneName = ScenePresets.activeScene(ctx)
        val activeScenePrompt = activeSceneName?.let { ScenePresets.scene(ctx, it)?.prompt }.orEmpty()
        val sceneList = ScenePresets.scenesForPrompt(ctx)
        val systemContent = buildString {
            append(SYSTEM_PROMPT)
            if (activeScenePrompt.isNotBlank()) append("\n\n当前场景「$activeSceneName」的规则：$activeScenePrompt")
            if (sceneList.isNotBlank()) append("\n\n可用场景：\n$sceneList")
        }
        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", systemContent))
        synchronized(this) {
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        // L6：本轮 user 先不进 history，只放进本次请求的 messages；流结束后再与 assistant 成对写入，
        // 失败/被取消时不留孤儿 user 消息。
        messages.put(JSONObject().put("role", "user").put("content", userContent))
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
        val call = client.newCall(req)
        inFlightCall = call
        call.enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                clearInFlight(call)
                if (mySeq != cancelSeq) return // 已被急停/下一轮覆盖，丢弃
                onError(networkError(e))
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                try {
                    response.use { resp ->
                        if (mySeq != cancelSeq) return
                        if (!resp.isSuccessful) {
                            val errBody = resp.body?.string().orEmpty()
                            onError(httpError("自定义大模型接口返回错误", resp.code, errBody))
                            return
                        }
                        val full = StringBuilder()
                        // H9：流结束（成功 / 取消 / 中途失败）时，把已完整收到/念出的助手文本写进 history，
                        // 哪怕只有一部分；只存一次，空文本不留孤儿 user 消息。
                        var historySaved = false
                        fun savePartialHistory() {
                            if (historySaved) return
                            historySaved = true
                            val reply = full.toString().trim()
                            if (reply.isEmpty()) return
                            synchronized(this@CustomClient) {
                                history.add("user" to userText)
                                history.add("assistant" to reply)
                                trimHistory()
                            }
                        }
                        try {
                            val source = resp.body?.source() ?: run {
                                onError("自定义大模型流式返回为空")
                                return
                            }
                            while (true) {
                                if (mySeq != cancelSeq) {
                                    savePartialHistory()
                                    return // 已被急停/下一轮覆盖，丢弃
                                }
                                // C7：cancelInFlight() 会调 call.cancel() 关掉连接，readUtf8Line 立即抛
                                // IOException 走下面的 catch，不用等满 180 秒读超时。
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
                            savePartialHistory()
                            onDone(full.toString().trim())
                        } catch (e: Exception) {
                            // 取消打断读循环也会走到这里：先把已收到的部分存进历史，再静默丢弃。
                            savePartialHistory()
                            if (mySeq != cancelSeq) return
                            onError("解析自定义大模型流式返回失败：${e.message}")
                        }
                    }
                } finally {
                    clearInFlight(call)
                }
            }
        })
    }
}
