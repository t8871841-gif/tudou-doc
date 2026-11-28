package com.jarvis.app.local

import android.content.Context
import android.net.Uri
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * NPU 档（2026-11-27）：端侧 NPU 模型（LiteRT-LM + 高通 QNN），结构照
 * LocalLlm（MediaPipe .task）与 OfflineAsr（模型下载）两处现成模式拼：
 *
 * - 模型不进 APK：filesDir/npu/ 下一个 .litertlm，首次使用前导入或下载；
 * - 导入（文件选择器）与下载（HF 直链）都验 sha256，过了才写 marker；
 * - 引擎单例：路径没变就复用，initialize 最长约 10 秒，跑在调用线程上
 *   （LocalBrain 的单循环线程；与云端 complete 阻塞调用线程同款）。
 *
 * 模型：Fertroll10 的 Gemma 4 E2B 社区编译版，只认骁龙 8 Gen 2
 * （SM8550），纯文本输入（看不了图），上下文 4096。不是 Google/高通官方
 * 发布件。LiteRT-LM 0.19.1 + QAIRT 2.52 实测组合（见模型页）。
 */
object NpuModel {

    const val MODEL_FILE_NAME = "gemma-4-E2B-it-v3_Qualcomm_SM8550.litertlm"
    const val MODEL_SHA256 =
        "ff365e873a91700875746589ddf324bb546c293c7e3a4743754e4f74ddf2c932"
    private const val MODEL_DOWNLOAD_URL =
        "https://huggingface.co/Fertroll10/gemma-4-E2B-it-litert-lm-npu/resolve/2687ca17/gemma-4-E2B-it-v3_Qualcomm_SM8550.litertlm"
    private const val MODEL_MARKER = ".ready"
    private const val MODEL_DIR = "npu"

    private val io = Executors.newSingleThreadExecutor { r ->
        Thread(r, "NpuModel").apply { isDaemon = true }
    }
    private val dispatcher = io.asCoroutineDispatcher()

    /** 正在下载模型（statusText 用，给设置页看“下载中”）。 */
    @Volatile
    private var downloading = false

    /** 最近一次 ping / 聊天失败的原因（给设置页展示、聊天里展示，排查用；
     * 成功时清掉。2026-11-28 教训：试答失败只吐一句“加载或生成失败”，
     * 引擎加载失败还是生成失败、底层报什么全看不见，只能盲猜；
     * 以后报错必须留原文。 */
    @Volatile
    var lastError: String? = null
        private set

    @Volatile
    private var engine: Engine? = null
    private var loadedPath: String? = null
    private val engineLock = Any()

    fun modelDir(ctx: Context): File = File(ctx.filesDir, MODEL_DIR)

    fun modelFile(ctx: Context): File? {
        val f = File(modelDir(ctx), MODEL_FILE_NAME)
        return f.takeIf { it.exists() }
    }

    /**
     * marker 内容 "sha256:length"。文件长度对上即就绪——不每次重算 3GB 的
     * hash；hash 只在导入/下载/校验那一次算。
     */
    fun isReady(ctx: Context): Boolean {
        val f = modelFile(ctx) ?: return false
        val marker = File(modelDir(ctx), MODEL_MARKER)
        if (!marker.exists()) return false
        val parts = runCatching { marker.readText().trim().split(":") }.getOrNull()
            ?: return false
        if (parts.size != 2) return false
        if (parts[0] != MODEL_SHA256) return false
        return parts[1].toLongOrNull() == f.length()
    }

    fun statusText(ctx: Context): String {
        if (downloading) return "模型下载中…"
        val f = modelFile(ctx)
        if (f == null) return "未导入模型（需下载 3.16GB 或导入 .litertlm）"
        if (isReady(ctx)) {
            val gb = f.length() / 1024.0 / 1024.0 / 1024.0
            return "NPU 模型已就绪：Gemma 4 E2B（SM8550，%.2f GB）".format(gb)
        }
        return "模型文件已在（${f.length() / 1024 / 1024} MB），未校验——点“下载 / 校验模型”验一次"
    }

    fun isDownloading(): Boolean = downloading

    private fun sha256Of(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(1 shl 20)
            var n: Int
            while (input.read(buf).also { n = it } > 0) digest.update(buf, 0, n)
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun writeMarker(ctx: Context, file: File) {
        File(modelDir(ctx), MODEL_MARKER).writeText("$MODEL_SHA256:${file.length()}")
    }

    /**
     * 把文件选择器选中的模型拷进 filesDir/npu（固定文件名），验 sha256，
     * 过了才写 marker。错文件（如下成 SM8650 的）hash 对不上，直接删掉、
     * 返回 false——sha 就是防下错文件的关。
     */
    suspend fun importPicked(ctx: Context, uri: Uri): Boolean = withContext(dispatcher) {
        try {
            val dir = modelDir(ctx).apply { mkdirs() }
            val dest = File(dir, MODEL_FILE_NAME)
            ctx.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { out -> input.copyTo(out, 1 shl 20) }
            } ?: return@withContext false
            if (sha256Of(dest) != MODEL_SHA256) {
                dest.delete()
                File(dir, MODEL_MARKER).delete()
                return@withContext false
            }
            writeMarker(ctx, dest)
            unload()
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 下载或校验：已就绪直接 true；文件在但没 marker 就验 hash（对了写
     * marker，不对删了重下）；否则下载。进度回调跑在后台线程，调用方自己
     * post 回主线程再碰 UI。
     */
    suspend fun ensureReady(ctx: Context, onProgress: (downloaded: Long, total: Long) -> Unit): Boolean =
        withContext(dispatcher) {
            if (isReady(ctx)) return@withContext true
            val existing = modelFile(ctx)
            if (existing != null) {
                val ok = runCatching { sha256Of(existing) == MODEL_SHA256 }.getOrDefault(false)
                if (ok) {
                    writeMarker(ctx, existing)
                    return@withContext true
                }
                existing.delete()
                File(modelDir(ctx), MODEL_MARKER).delete()
            }
            downloading = true
            try {
                downloadModelFile(ctx, onProgress)
                isReady(ctx)
            } catch (_: Throwable) {
                false
            } finally {
                downloading = false
            }
        }

    /**
     * 从 HF 直链下一个模型文件：先下到 .tmp，sha256 校验通过才改名到位。
     * 下载失败/校验不对都删掉临时文件和目标文件并抛错，不留残缺文件。
     * （OfflineAsr.downloadModelFile 同款，校验改按 sha256——3.16GB 的
     * 文件没有现成的期望字节数可抄。）
     */
    private fun downloadModelFile(ctx: Context, onProgress: (Long, Long) -> Unit) {
        val dir = modelDir(ctx).apply { mkdirs() }
        val target = File(dir, MODEL_FILE_NAME)
        val tmp = File(dir, "$MODEL_FILE_NAME.tmp")
        try {
            val conn = URL(MODEL_DOWNLOAD_URL).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 120_000
            conn.instanceFollowRedirects = true
            conn.connect()
            if (conn.responseCode !in 200..299) throw IOException("HTTP ${conn.responseCode}")
            val total = conn.contentLengthLong // HF CDN 会给，-1 表示未知
            var done = 0L
            conn.inputStream.use { input ->
                FileOutputStream(tmp).use { output ->
                    val buf = ByteArray(1 shl 20)
                    var n: Int
                    while (input.read(buf).also { n = it } > 0) {
                        output.write(buf, 0, n)
                        done += n
                        onProgress(done, total)
                    }
                }
            }
            if (sha256Of(tmp) != MODEL_SHA256) {
                throw IOException("校验没通过（sha256 对不上，可能是下错了文件）")
            }
            target.delete()
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            writeMarker(ctx, target)
        } catch (e: Exception) {
            tmp.delete()
            target.delete()
            throw IOException("下载 NPU 模型失败：${e.message}", e)
        }
    }

    /**
     * 确保引擎已加载（路径没变就复用）。失败抛 IllegalStateException
     * （中文，调用方直接当报错回给用户）。跑在调用线程上；
     * initialize 最长约 10 秒，阻塞调用线程是预期的。
     */
    fun ensureEngine(ctx: Context): Engine {
        synchronized(engineLock) {
            val path = modelFile(ctx)?.absolutePath
                ?: throw IllegalStateException("NPU 模型还没就绪，请先在设置 → NPU 模型里导入或下载模型（3.16GB）")
            if (!isReady(ctx)) {
                throw IllegalStateException("NPU 模型文件还没校验通过，请先在设置 → NPU 模型里点“下载 / 校验模型”")
            }
            engine?.let { if (loadedPath == path) return it }
            try { engine?.close() } catch (_: Throwable) {}
            engine = null
            val config = EngineConfig(
                modelPath = path,
                // QNN 运行时随 APK 打包进 nativeLibraryDir（见 build.gradle
                // 的 useLegacyPackaging 说明）；不传这个，NPU 初始化找不到库。
                backend = Backend.NPU(nativeLibraryDir = ctx.applicationInfo.nativeLibraryDir),
            )
            val created = Engine(config)
            try {
                created.initialize()
            } catch (t: Throwable) {
                runCatching { created.close() }
                throw IllegalStateException(
                    "NPU 引擎加载失败：${t.message ?: t.javaClass.simpleName}（可能是 QNN 版本不对或机型不匹配）"
                )
            }
            engine = created
            loadedPath = path
            return created
        }
    }

    fun unload() {
        synchronized(engineLock) {
            try { engine?.close() } catch (_: Throwable) {}
            engine = null
            loadedPath = null
        }
    }

    fun deleteModel(ctx: Context): Boolean {
        unload()
        File(modelDir(ctx), MODEL_MARKER).delete()
        return modelFile(ctx)?.delete() ?: true
    }

    /** 聊天用系统提示（与 LocalLlm.buildPrompt 同一条线：短中文回答、不编实时信息）。 */
    private const val CHAT_SYSTEM_PROMPT =
        "你是 Jarvis，用户的个人助理。用中文简短回答，不要用列表、不要发表情。" +
            "天气、气温、时间、新闻、价格这类实时信息你没有数据来源，查不到就明说查不到，绝对不要编造具体数值。"

    /** 记忆片段字符上限（与 LocalLlm.MEMORY_CONTEXT_MAX_CHARS 同值）：调用方按
     * topN 精简过的记忆，单条本身可能很长，这里再兜底截一刀，别把 4096 上下文吃掉。 */
    private const val MEMORY_CONTEXT_MAX_CHARS = 150

    /** 把异常拼成一句可读的原因（类名 + 消息 + 两层原因链，截 400 字）。
     * 引擎加载失败会包一层中文，底层原因在 cause 里。 */
    private fun formatError(t: Throwable): String {
        val sb = StringBuilder("${t.javaClass.simpleName}: ${t.message ?: ""}")
        var cause = t.cause
        var depth = 0
        while (cause != null && depth < 2) {
            sb.append(" / 原因：${cause.javaClass.simpleName}: ${cause.message ?: ""}")
            cause = cause.cause
            depth++
        }
        return sb.toString().take(400)
    }

    /** 清洗模型输出（与 LocalLlm.cleanOutput 同一套阈值）：数字垃圾判失败、
     * 同一片段连续重复 6 次以上在重复处截断。 */
    private fun cleanOutput(raw: String): String? {
        var s = raw.trim()
        if (s.isBlank()) return null
        val junkRatio = s.count { it.isDigit() || it == '-' || it == ' ' }.toDouble() / s.length
        if (junkRatio > 0.6) return null
        return cutRepetition(s).takeIf { it.isNotBlank() }
    }

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

    /**
     * 聊天：单句生成（用户 2026-11-28 定：NPU 档日常聊天、回答问题走 NPU）。
     * 调用方已把精简记忆（Top-2）传进来；每句新建会话（带系统提示），与
     * 任务脑的 NpuLlmClient 各走各的会话，互不干扰。失败返回 null，
     * 原因记 lastError。
     */
    suspend fun generate(ctx: Context, prompt: String, memoryContext: String = ""): String? =
        withContext(dispatcher) {
            try {
                val eng = ensureEngine(ctx)
                val userText = if (memoryContext.isBlank()) prompt
                else "【相关记忆】\n${memoryContext.trim().take(MEMORY_CONTEXT_MAX_CHARS)}\n\n$prompt"
                val out = eng.createConversation(
                    ConversationConfig(systemInstruction = Contents.of(CHAT_SYSTEM_PROMPT)),
                ).use { conv ->
                    conv.sendMessage(userText).contents.contents
                        .filterIsInstance<Content.Text>()
                        .joinToString("") { it.text }.trim()
                        .let { cleanOutput(it) }
                }
                lastError = null
                out
            } catch (t: Throwable) {
                lastError = formatError(t)
                null
            }
        }

    /** 设置页试答：建个临时会话问一句。失败返回 null，并把原因记到
     * lastError（设置页状态行展示，排查用）。
     * Message 没有 text 属性，正文在 contents 的 Content.Text 块里。 */
    suspend fun ping(ctx: Context, text: String): String? = withContext(dispatcher) {
        try {
            val eng = ensureEngine(ctx)
            val out = eng.createConversation().use { conv ->
                conv.sendMessage(text).contents.contents
                    .filterIsInstance<Content.Text>()
                    .joinToString("") { it.text }.trim()
                    .takeIf { it.isNotBlank() }
            }
            lastError = null
            out
        } catch (t: Throwable) {
            lastError = formatError(t)
            null
        }
    }
}
