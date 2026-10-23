package com.jarvis.app.local

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.jarvis.app.JarvisPrefs
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.Executors

/**
 * 批五：端侧小模型（MediaPipe LLM Inference）。逻辑移植自旧 Flutter 版已验证的
 * LocalLlmBridge（tasks-genai 0.10.24，只吃 .task）：单线程串行加载/生成、模型由用户
 * 经系统文件选择器导入 filesDir/models，App 不内置、不联网下载。
 * 与旧桥的一处差别：maxTokens 旧桥强制 ≥1024，用户那颗 gemma3-tudou.task 上下文上限就是
 * 1024、超了会加载失败，故本版改为设置里可调（256..1024，默认 1024，Prefs 层再钳一次）。
 */
object LocalLlm {

    private val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()

    private var llm: LlmInference? = null
    private var loadedPath: String? = null
    private var loadedMaxTokens: Int = -1

    fun modelFile(ctx: Context): File? {
        val path = JarvisPrefs.getLocalLlmPath(ctx)
        if (path.isBlank()) return null
        return File(path).takeIf { it.exists() }
    }

    fun isReady(ctx: Context): Boolean = modelFile(ctx) != null

    fun statusText(ctx: Context): String {
        val f = modelFile(ctx) ?: return "未导入模型"
        val mb = f.length() / 1024.0 / 1024.0
        return "当前模型：${f.name}（${"%.0f".format(mb)} MB）"
    }

    /** 把用户的话套进 Gemma 指令模型的对话模板（jk-v11 真机教训：直喂原话会胡言乱语、复读数字串）。 */
    private fun buildPrompt(userText: String): String =
        // 批九硬约束（与 AgnesClient 同一条线）：本地模型同样不许编造实时信息。
        "<start_of_turn>user\n你是 Jarvis，用户的个人助理。用中文简短回答，不要用列表、不要发表情。天气、气温、时间、新闻、价格这类实时信息你没有数据来源，查不到就明说查不到，绝对不要编造具体数值。\n\n$userText<end_of_turn>\n<start_of_turn>model\n"

    /** 清洗模型输出：砍模板残留、截断复读、数字垃圾直接判失败（返回 null 让上层回退云端）。 */
    private fun cleanOutput(raw: String): String? {
        var s = raw
        for (marker in listOf("<end_of_turn>", "<start_of_turn>", "<eos>")) {
            val idx = s.indexOf(marker)
            if (idx >= 0) s = s.substring(0, idx)
        }
        s = s.trim()
        if (s.isBlank()) return null
        val junkRatio = s.count { it.isDigit() || it == '-' || it == ' ' }.toDouble() / s.length
        if (junkRatio > 0.6) return null
        return cutRepetition(s).takeIf { it.isNotBlank() }
    }

    /** 同一短片段连续重复 6 次以上视为复读机，在重复处截断。 */
    private fun cutRepetition(s: String): String {
        if (s.length < 24) return s
        for (unitLen in 4..12) {
            var i = 0
            while (i + unitLen * 6 <= s.length) {
                val unit = s.substring(i, i + unitLen)
                var count = 1
                var j = i + unitLen
                while (j + unitLen <= s.length && s.substring(j, j + unitLen) == unit) {
                    count++
                    j += unitLen
                }
                if (count >= 6) return s.substring(0, i + unitLen).trim()
                i++
            }
        }
        return s
    }

    /** 确保已加载并生成；任何失败返回 null（上层据此回退云端），不在这里抛错或假装答了。 */
    suspend fun generate(ctx: Context, prompt: String): String? = withContext(dispatcher) {
        val path = JarvisPrefs.getLocalLlmPath(ctx)
        if (path.isBlank() || !File(path).exists()) return@withContext null
        val maxTokens = JarvisPrefs.getLocalMaxTokens(ctx)
        try {
            if (llm == null || loadedPath != path || loadedMaxTokens != maxTokens) {
                try { llm?.close() } catch (_: Throwable) {}
                llm = null
                val opts = LlmInference.LlmInferenceOptions.builder()
                    .setModelPath(path)
                    .setMaxTokens(maxTokens)
                    .build()
                llm = LlmInference.createFromOptions(ctx.applicationContext, opts)
                loadedPath = path
                loadedMaxTokens = maxTokens
            }
            llm!!.generateResponse(buildPrompt(prompt))?.let { cleanOutput(it) }
        } catch (_: Throwable) {
            try { llm?.close() } catch (_: Throwable) {}
            llm = null
            loadedPath = null
            null
        }
    }

    fun unload() {
        try { llm?.close() } catch (_: Throwable) {}
        llm = null
        loadedPath = null
        loadedMaxTokens = -1
    }

    /** 把文件选择器选中的模型复制进 filesDir/models（文件名清洗），成功返回绝对路径。 */
    suspend fun importPicked(ctx: Context, uri: Uri): String? = withContext(dispatcher) {
        try {
            var name = "local_model.task"
            ctx.contentResolver.query(uri, null, null, null, null)?.use { c ->
                val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (idx >= 0 && c.moveToFirst()) name = c.getString(idx) ?: name
            }
            val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
            val dir = File(ctx.filesDir, "models").apply { mkdirs() }
            val dest = File(dir, safe)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            } ?: throw IllegalStateException("无法读取所选文件")
            dest.absolutePath
        } catch (_: Throwable) {
            null
        }
    }

    /** 删除已导入的模型文件并清路径。 */
    fun deleteModel(ctx: Context): Boolean {
        unload()
        val f = modelFile(ctx)
        JarvisPrefs.setLocalLlmPath(ctx, "")
        return f?.delete() ?: true
    }
}
