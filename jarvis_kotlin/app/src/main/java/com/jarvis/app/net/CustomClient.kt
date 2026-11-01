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
 * 自定义大模型（OpenAI 兼容的 /chat/completions 接口），只给跨应用任务
 * 循环（task/TaskLoop）当可选大脑用。接口地址、模型名、Key 都从设置读取，
 * 不写死；地址或 Key 为空时直接报错，不发请求。
 *
 * 与 AgnesClient.askOnce 同一签名、同一多模态格式：jpegBase64 非空时按
 * image_url + data URL 附图（自定义模型与 Agnes 一样走 OpenAI 兼容接口，
 * 带图问）。回调在后台线程。
 */
object CustomClient {

    private val client by lazy {
        OkHttpClient.Builder()
            // Andee 对齐：LlmClient 连接 15 秒、读 180 秒。
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(180, TimeUnit.SECONDS)
            .writeTimeout(10, TimeUnit.SECONDS)
            .build()
    }

    /**
     * 单轮调用，不进任何对话历史（任务循环 task/TaskLoop 用）。签名与
     * [AgnesClient.askOnce] 完全一致。
     * 回调在后台线程。
     */
    fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        val base = JarvisPrefs.getCustomBaseUrl(ctx).trim().trimEnd('/')
        val model = JarvisPrefs.getCustomModel(ctx)
        val key = JarvisPrefs.getCustomKey(ctx)
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
}
