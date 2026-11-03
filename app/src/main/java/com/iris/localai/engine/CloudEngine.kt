package com.iris.localai.engine

import android.content.Context
import com.iris.localai.data.GenerationStats
import com.iris.localai.data.LocalModel
import com.iris.localai.data.SettingsRepository
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.atomic.AtomicBoolean

/**
 * AZJ: the cloud model engine (Agnes, OpenAI-compatible API). No local weights: [load] only
 * validates the cloud settings, [generate] POSTs to `{baseUrl}/chat/completions` with
 * `stream: true` and turns the SSE deltas into [GenEvent.Piece]s. The API key lives in the
 * app settings (entered by the user) and is never bundled with the app.
 */
class CloudEngine(context: Context) : InferenceEngine {
    companion object {
        /** Engine id [EngineManager.engineFor] returns for a cloud model. */
        const val ENGINE_ID = "cloud"
    }

    private val appContext = context.applicationContext

    @Volatile
    private var connection: HttpURLConnection? = null
    private val cancelled = AtomicBoolean(false)

    /** Fresh settings on every call: the user may edit them while this engine is loaded. */
    private fun cloudSettings() = SettingsRepository(appContext).current

    override suspend fun load(model: LocalModel, config: LoadConfig, onProgress: (Float) -> Unit): ModelDetails {
        val s = cloudSettings()
        val base = s.cloudBaseUrl.trim().trimEnd('/')
        val name = s.cloudModel.trim()
        val key = s.cloudApiKey.trim()
        when {
            base.isBlank() -> throw EngineException("云端模型未配置地址：请到 设置 › API 服务 › 云端模型 填写接口地址。")
            name.isBlank() -> throw EngineException("云端模型未配置模型名：请到 设置 › API 服务 › 云端模型 填写模型名。")
            key.isBlank() -> throw EngineException("云端模型未填写 API Key：请到 设置 › API 服务 › 云端模型 填写 Key。")
        }
        onProgress(1f)
        return ModelDetails(
            description = "云端 · $name",
            parameterCount = 0,
            contextLength = 128000,
            trainContextLength = 128000,
            supportsThinking = false,
            supportsVision = false,
            hasChatTemplate = true,
            backend = "云端 · Agnes",
            activeBackend = Backend.CPU,
        )
    }

    override fun generate(history: List<PromptMessage>, params: SamplingConfig): Flow<GenEvent> = flow {
        val s = cloudSettings()
        val base = s.cloudBaseUrl.trim().trimEnd('/')
        val name = s.cloudModel.trim()
        val key = s.cloudApiKey.trim()
        if (base.isBlank() || name.isBlank() || key.isBlank()) {
            emit(GenEvent.Done(StopReason.ERROR, null, "云端模型未配置：请到 设置 › API 服务 › 云端模型 填写地址、模型名和 Key。"))
            return@flow
        }
        cancelled.set(false)
        val t0 = System.currentTimeMillis()
        val body = JSONObject().apply {
            put("model", name)
            put("messages", JSONArray().apply {
                for (m in history) put(JSONObject().put("role", m.role).put("content", m.content))
            })
            put("temperature", params.temperature.toDouble())
            put("top_p", params.topP.toDouble())
            if (params.maxNewTokens > 0) put("max_tokens", params.maxNewTokens)
            put("stream", true)
            put("stream_options", JSONObject().put("include_usage", true))
        }
        var conn: HttpURLConnection? = null
        try {
            conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 120000
                requestMethod = "POST"
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer $key")
            }
            connection = conn
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                val err = runCatching {
                    conn.errorStream?.bufferedReader()?.readText().orEmpty()
                }.getOrDefault("")
                val msg = runCatching {
                    JSONObject(err).optJSONObject("error")?.optString("message")
                }.getOrNull().takeUnless { it.isNullOrBlank() } ?: err.ifBlank { "HTTP $code" }
                emit(GenEvent.Done(StopReason.ERROR, null, "云端模型请求失败（HTTP $code）：$msg"))
                return@flow
            }
            val generatedText = StringBuilder()
            var promptTokens = 0
            var completionTokens = 0
            var sawUsage = false
            BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    currentCoroutineContext().ensureActive()
                    if (cancelled.get()) break
                    val l = line!!.trim()
                    if (!l.startsWith("data:")) continue
                    val data = l.removePrefix("data:").trim()
                    if (data.isEmpty() || data == "[DONE]") continue
                    val obj = runCatching { JSONObject(data) }.getOrNull() ?: continue
                    // Usage (sent with the last chunk when stream_options.include_usage is on).
                    obj.optJSONObject("usage")?.let { u ->
                        promptTokens = u.optInt("prompt_tokens", promptTokens)
                        completionTokens = u.optInt("completion_tokens", completionTokens)
                        sawUsage = true
                    }
                    val choices = obj.optJSONArray("choices") ?: continue
                    if (choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val content = delta.optString("content", "")
                    if (content.isNotEmpty()) {
                        generatedText.append(content)
                        emit(GenEvent.Piece(content))
                    }
                }
            }
            if (cancelled.get()) {
                emit(GenEvent.Done(StopReason.CANCELLED, null, null))
                return@flow
            }
            val ms = System.currentTimeMillis() - t0
            val genTokens = if (sawUsage) completionTokens else (generatedText.length / 4).coerceAtLeast(0)
            emit(
                GenEvent.Done(
                    StopReason.END,
                    GenerationStats(
                        promptTokens = promptTokens,
                        generatedTokens = genTokens,
                        prefillMs = 0,
                        generateMs = ms,
                    ),
                ),
            )
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            emit(GenEvent.Done(StopReason.ERROR, null, "云端模型请求失败：${t.message ?: t.javaClass.simpleName}"))
        } finally {
            runCatching { conn?.disconnect() }
            if (connection === conn) connection = null
        }
    }

    override fun cancel() {
        cancelled.set(true)
        runCatching { connection?.disconnect() }
    }

    /** A cloud model has no local state to retune: the next reply just reads fresh settings. */
    override fun liveApplyMode(): LiveApply = LiveApply.NEXT_REPLY

    override suspend fun resetContext() {
        // Stateless: every request sends the full history.
    }

    override suspend fun unload() {
        cancel()
    }
}
