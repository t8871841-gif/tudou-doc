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
 * 批十二新增：DeepSeek（OpenAI 兼容的 /chat/completions 接口），只给跨应用任务
 * 循环（task/TaskLoop）当可选大脑用。Base URL 固定为 https://api.deepseek.com/v1，
 * 模型固定 deepseek-chat。Key 从设置读取，不写死；未配置 Key 时直接报错，不发请求。
 *
 * deepseek-chat 是纯文本模型：本类只发文本消息，[askOnce] 的 jpegBase64 参数仅为
 * 与 [AgnesClient.askOnce] 保持同一签名（调用方按设置切换时不用改调用形状），
 * 传入的图片会被忽略——任务循环靠无障碍界面树文字推进（Andee 同款路线）。
 */
object DeepSeekClient {
    private const val BASE_URL = "https://api.deepseek.com/v1"
    private const val MODEL = "deepseek-chat"

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
     * [AgnesClient.askOnce] 完全一致；[jpegBase64] 忽略（纯文本模型）。
     * 回调在后台线程。
     */
    fun askOnce(
        ctx: Context, systemPrompt: String, userText: String, jpegBase64: String?,
        onResult: (String) -> Unit, onError: (String) -> Unit
    ) {
        val key = JarvisPrefs.getDeepSeekKey(ctx)
        if (key.isBlank()) {
            onError("未配置 DeepSeek API Key，请先去设置里填写")
            return
        }
        val messages = JSONArray()
            .put(JSONObject().put("role", "system").put("content", systemPrompt))
            .put(JSONObject().put("role", "user").put("content", userText))
        val body = JSONObject()
            .put("model", MODEL)
            .put("messages", messages)
            .put("stream", false)
            .put("temperature", 0.2)
        val req = Request.Builder()
            .url("$BASE_URL/chat/completions")
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
                        onError("DeepSeek 接口返回错误：HTTP ${resp.code}")
                        return
                    }
                    try {
                        val text = resp.body?.string().orEmpty()
                        val json = JSONObject(text)
                        val reply = json.getJSONArray("choices")
                            .getJSONObject(0).getJSONObject("message").getString("content").trim()
                        onResult(reply)
                    } catch (e: Exception) {
                        onError("解析 DeepSeek 返回内容失败：${e.message}")
                    }
                }
            }
        })
    }
}
