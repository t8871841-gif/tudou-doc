package com.jarvis.native.net

import android.content.Context
import com.jarvis.native.JarvisPrefs
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
 * 对话大脑：Agnes（OpenAI 兼容的 /chat/completions 接口）。
 * Base URL 固定为 https://apihub.agnes-ai.com/v1，模型固定 agnes-3.0-flash（需求文档指定）。
 * Key 从设置读取，不写死；未配置 Key 时直接报错，不发请求。
 *
 * 维护一份进程内滚动对话历史（不落盘——持久化记忆是需求第 9 条，未排期），
 * 超过 MAX_TURNS 轮后从最早的一轮开始丢弃，避免单次请求体无限增长。
 */
object AgnesClient {
    private const val BASE_URL = "https://apihub.agnes-ai.com/v1"
    private const val MODEL = "agnes-3.0-flash"
    private const val MAX_TURNS = 12
    private const val SYSTEM_PROMPT =
        "你是 Jarvis，用户的个人智能助理。自称 Jarvis，称呼用户\"先生\"。默认使用中文回答，" +
            "语气克制、干练、不啰嗦；你的回答会直接被朗读出来，所以不要使用markdown、列表符号、" +
            "表情符号，只用适合语音播报的自然口语短句。遇到做不到或失败的事情直说，不要编造。"

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    private val history = mutableListOf<Pair<String, String>>() // role to content

    /**
     * 三期新增：单轮多模态问答（看屏用）——带一张图片，不进滚动对话历史（看屏问答与日常聊天分开计数）。
     * 复用对话用的同一个 Key/模型配置（需求文档允许"复用设置里已有的模型配置"），
     * 模型是否真支持图片输入取决于 Agnes 后端，这里按 OpenAI 多模态消息格式（image_url + data URL）发送；
     * 如果 agnes-3.0-flash 不支持图片，这里会从服务端收到错误，上层按 onError 兜底提示，不会假装成功。
     */
    fun askVision(
        ctx: Context, prompt: String, jpegBase64: String,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        val key = JarvisPrefs.getAgnesKey(ctx)
        if (key.isBlank()) {
            onError("未配置 Agnes API Key，请先去设置里填写")
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
            .put("model", MODEL)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.1)
        val req = Request.Builder()
            .url("$BASE_URL/chat/completions")
            .addHeader("Authorization", "Bearer $key")
            .addHeader("Content-Type", "application/json")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(req).enqueue(object : Callback {
            override fun onFailure(call: Call, e: IOException) {
                onError("视觉模型请求失败：${e.message}")
            }

            override fun onResponse(call: Call, response: okhttp3.Response) {
                response.use { resp ->
                    if (!resp.isSuccessful) {
                        onError("视觉模型返回错误：HTTP ${resp.code}（如果 agnes-3.0-flash 不支持图片输入，会一直报错，需要换一个支持多模态的模型）")
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
     * 发起一次对话。onResult 在后台线程回调；onError 携带可读错误信息（网络失败 / 未配置 Key / HTTP 错误码）。
     */
    fun ask(ctx: Context, userText: String, onResult: (String) -> Unit, onError: (String) -> Unit) {
        val key = JarvisPrefs.getAgnesKey(ctx)
        if (key.isBlank()) {
            onError("未配置 Agnes API Key，请先去设置里填写")
            return
        }
        val mySeq = ++cancelSeq
        synchronized(this) {
            history.add("user" to userText)
            while (history.size > MAX_TURNS * 2) history.removeAt(0)
        }

        val messages = JSONArray()
        messages.put(JSONObject().put("role", "system").put("content", SYSTEM_PROMPT))
        synchronized(this) {
            for ((role, content) in history) {
                messages.put(JSONObject().put("role", role).put("content", content))
            }
        }
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.6)

        val req = Request.Builder()
            .url("$BASE_URL/chat/completions")
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
                        onError("Agnes 接口返回错误：HTTP ${resp.code}")
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
                        synchronized(this@AgnesClient) {
                            history.add("assistant" to reply)
                        }
                        onResult(reply)
                    } catch (e: Exception) {
                        onError("解析 Agnes 返回内容失败：${e.message}")
                    }
                }
            }
        })
    }
}
