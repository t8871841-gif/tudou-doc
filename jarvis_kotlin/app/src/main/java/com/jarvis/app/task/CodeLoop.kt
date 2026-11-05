package com.jarvis.app.task

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.net.CustomClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 编码循环（阶段 0，2026-11-05）：写代码任务的独立循环，不塞进 TaskLoop。
 * TaskLoop 是“看屏点按”的循环，编码是“读文件改文件”的循环，动作空间、
 * 提示词、终止条件全不同；只共用 CustomClient（glm-4.5 聊天模型通道）
 * 和 AuditLog。执行（跑代码）是阶段 1 的事，这里只写文件、读文件，不执行。
 *
 * 工作区白名单：只允许读写应用私有目录下的 code-workspace/，其他路径
 * 一律拒绝（防误删、越界写）。文件工具抄 Aider/SWE-agent 的做法：
 * read_file 窗口化（一次约 100 行带行号），edit_file 用 SEARCH/REPLACE 块
 * （SEARCH 必须与文件逐字一致，匹配不到回显 did-you-mean）。
 */
object CodeLoop {
    private const val MAX_STEPS = 25
    private const val MAX_REPEATS = 3
    private const val READ_LINES = 100
    private const val ASK_TIMEOUT_MS = 60_000L
    private const val WORKSPACE_DIR = "code-workspace"

    private val runSeq = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "CodeLoop").apply { isDaemon = true }
    }

    @Volatile
    var running = false

    /** 写代码任务的关键词分流：命中即走编码模式，不走跨应用任务循环。 */
    private val CODE_KEYWORDS = listOf(
        "写代码", "写脚本", "写函数", "写个程序", "写一段代码", "写个脚本",
        "改代码", "修 bug", "修bug", "跑个程序", "帮我写个", "编个程序",
        "写 python", "写 Python", "用 python", "用 Python",
    )

    fun looksLikeCodeTask(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        return CODE_KEYWORDS.any { t.contains(it) }
    }

    /** 急停：自增序号，正在跑的循环下一步检查时自行中止（与 TaskLoop 同款）。 */
    fun cancelInFlight() {
        runSeq.incrementAndGet()
    }

    /**
     * 执行一个写代码任务。[isAlive] 由调用方给（Agent 的代次检查）；[onReply]
     * 只会被调用一次，内容是给用户的一句结果（完成说明或卡在哪一步的如实说明）。
     * 注意：onReply 必须放最后——调用方用尾随 lambda 传它，后面再加参数会
     * 让尾随 lambda 绑错（TaskLoop jk-v55 的教训）。
     */
    fun run(
        ctx: Context, taskText: String, isAlive: () -> Boolean,
        onReply: (String) -> Unit
    ) {
        val mySeq = runSeq.incrementAndGet()
        val appCtx = ctx.applicationContext
        executor.execute {
            runBlocking(appCtx, taskText, mySeq, isAlive, onReply)
        }
    }

    private fun runBlocking(
        ctx: Context, taskText: String, mySeq: Int,
        isAlive: () -> Boolean, onReply: (String) -> Unit
    ) {
        fun alive() = mySeq == runSeq.get() && isAlive()
        AuditLog.append(ctx, "编码循环", taskText, "开始（上限 $MAX_STEPS 步，只写文件不执行）")
        val history = mutableListOf<String>()
        var lastError = ""
        var lastSignature = ""
        var repeats = 0
        var finalReply: String? = null
        running = true
        try {
            var step = 0
            stepLoop@ while (step < MAX_STEPS) {
                if (!alive()) {
                    finalReply = null
                    return
                }
                step++
                // ---------- 观察：工作区文件列表 + 历史 ----------
                val workspace = workspaceDir(ctx)
                val fileList = listWorkspace(workspace)
                val userText = buildString {
                    append("任务：").append(taskText).append('\n')
                    append("工作区（code-workspace/，只能读写这里面的文件）：\n")
                    append(if (fileList.isEmpty()) "（空）" else fileList).append('\n')
                    if (history.isNotEmpty()) {
                        append("已执行步骤（旧→新）：\n")
                        history.forEach { append(it).append('\n') }
                    }
                    if (lastError.isNotEmpty()) append("上一步的问题：").append(lastError).append('\n')
                    append("现在只输出下一步的一个 JSON 动作。")
                }
                // ---------- 问模型（glm-4.5 聊天模型通道） ----------
                val reply = try {
                    askModel(ctx, CODE_SYSTEM_PROMPT, userText)
                } catch (e: Exception) {
                    lastError = "问模型失败：${e.message ?: e.javaClass.simpleName}"
                    history += "第 $step 步：$lastError"
                    AuditLog.append(ctx, "编码循环", "第 $step 步问模型失败", lastError)
                    continue@stepLoop
                }
                if (reply == null) {
                    lastError = "问模型超时（${ASK_TIMEOUT_MS / 1000} 秒没回）"
                    history += "第 $step 步：$lastError"
                    AuditLog.append(ctx, "编码循环", "第 $step 步问模型失败", lastError)
                    continue@stepLoop
                }
                val action = parseAction(reply)
                if (action == null) {
                    lastError = "模型输出不是合法的 JSON 动作"
                    history += "第 $step 步：输出无法解析 — ${reply.take(80)}"
                    AuditLog.append(ctx, "编码循环", "第 $step 步输出无法解析", reply.take(200))
                    continue@stepLoop
                }
                val kind = action.optString("action")
                if (kind == "done" || kind == "fail") {
                    val msg = if (kind == "done") {
                        action.optString("message").ifEmpty { "代码写好了" }
                    } else {
                        action.optString("reason").ifEmpty { "模型判断做不下去" }
                    }
                    history += "第 $step 步：$kind — $msg"
                    AuditLog.append(ctx, "编码循环", kind, msg)
                    finalReply = if (kind == "done") msg else "写代码没完成，卡在第 $step 步：$msg"
                    return
                }
                // ---------- 重复守卫：同一动作连着来 3 次即停 ----------
                val sig = signatureOf(action)
                if (sig == lastSignature) {
                    repeats++
                } else {
                    lastSignature = sig
                    repeats = 0
                }
                if (repeats >= MAX_REPEATS) {
                    finalReply = "写代码卡在第 $step 步：同一个动作连着重复多次，已停下。"
                    AuditLog.append(ctx, "编码循环", "重复守卫触发", "$kind 连续重复")
                    return
                }
                // ---------- 执行文件动作 ----------
                val result = executeFileAction(ctx, action)
                history += "第 $step 步：${describeAction(action)} → ${result.take(500)}"
                AuditLog.append(ctx, "编码循环", "$kind（第 $step 步）", result.take(500))
                lastError = if (result.startsWith("OK")) "" else result
            }
            finalReply = "写代码到第 $MAX_STEPS 步还没做完，已停下。"
            AuditLog.append(ctx, "编码循环", "步数上限", "到 $MAX_STEPS 步未完成")
        } finally {
            running = false
            if (finalReply != null && alive()) onReply(finalReply)
        }
    }

    // ---------- 问模型（阻塞式，等 CustomClient 回调） ----------

    private fun askModel(ctx: Context, systemPrompt: String, userText: String): String? {
        val latch = CountDownLatch(1)
        var result: String? = null
        var error: String? = null
        CustomClient.askOnce(
            ctx, systemPrompt, userText, null,
            onResult = { result = it; latch.countDown() },
            onError = { error = it; latch.countDown() },
        )
        latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        if (error != null) throw IllegalStateException(error)
        return result
    }

    private fun parseAction(reply: String): JSONObject? {
        val start = reply.indexOf('{')
        val end = reply.lastIndexOf('}')
        if (start < 0 || end <= start) return null
        return try {
            val obj = JSONObject(reply.substring(start, end + 1))
            if (obj.optString("action").isEmpty()) null else obj
        } catch (_: Exception) {
            null
        }
    }

    private fun signatureOf(action: JSONObject): String {
        val kind = action.optString("action")
        val path = action.optString("path")
        return "$kind:$path"
    }

    private fun describeAction(action: JSONObject): String {
        val kind = action.optString("action")
        val path = action.optString("path")
        return if (path.isNotEmpty()) "$kind($path)" else kind
    }

    // ---------- 文件工具（白名单：只读写 code-workspace/） ----------

    private fun workspaceDir(ctx: Context): File =
        File(ctx.filesDir, WORKSPACE_DIR).apply { mkdirs() }

    /** 路径解析 + 越界检查：只允许工作区内的路径，.. 逃逸一律拒绝。 */
    private fun resolvePath(ctx: Context, path: String): File? {
        val root = workspaceDir(ctx).canonicalFile
        val target = File(root, path).canonicalFile
        if (!target.path.startsWith(root.path + File.separator) && target.path != root.path) return null
        return target
    }

    private fun listWorkspace(root: File): String {
        val files = root.walkTopDown()
            .filter { it.isFile }
            .map { it.relativeTo(root).path }
            .sorted()
            .take(50)
            .toList()
        return files.joinToString("\n")
    }

    /**
     * 执行一个文件动作，返回给模型看的结果（"OK..." 或错误说明）。
     * 动作：write_file / read_file / list_dir / edit_file。
     */
    private fun executeFileAction(ctx: Context, action: JSONObject): String {
        return try {
            when (val kind = action.optString("action")) {
                "write_file" -> {
                    val path = action.optString("path")
                    val content = action.optString("content")
                    if (path.isBlank()) return "错误：write_file 缺 path"
                    val file = resolvePath(ctx, path) ?: return "错误：路径 $path 不在工作区内，拒绝写入"
                    file.parentFile?.mkdirs()
                    file.writeText(content)
                    "OK：已写入 $path（${content.length} 字）"
                }
                "read_file" -> {
                    val path = action.optString("path")
                    val start = action.optInt("start", 1).coerceAtLeast(1)
                    val lines = action.optInt("lines", READ_LINES).coerceIn(1, 200)
                    if (path.isBlank()) return "错误：read_file 缺 path"
                    val file = resolvePath(ctx, path) ?: return "错误：路径 $path 不在工作区内，拒绝读取"
                    if (!file.isFile) return "错误：$path 不存在或不是文件"
                    val all = file.readLines()
                    if (all.isEmpty()) return "OK：$path 是空文件"
                    val from = (start - 1).coerceIn(0, all.size)
                    val to = minOf(from + lines, all.size)
                    val out = StringBuilder()
                    for (i in from until to) out.append(i + 1).append("|").append(all[i]).append('\n')
                    if (to < all.size) out.append("（还有 ${all.size - to} 行，用 start=${to + 1} 再读）")
                    "OK：$path 第 $start–$to 行（共 ${all.size} 行）：\n$out"
                }
                "list_dir" -> {
                    val path = action.optString("path").ifBlank { "" }
                    val dir = if (path.isBlank()) workspaceDir(ctx) else resolvePath(ctx, path)
                        ?: return "错误：路径 $path 不在工作区内，拒绝列出"
                    if (!dir.isDirectory) return "错误：$path 不是目录"
                    val entries = dir.listFiles()?.sortedBy { it.name }
                        ?.joinToString("\n") { (if (it.isDirectory) "[目录] " else "[文件] ") + it.name }
                        ?: ""
                    "OK：$path 内容：\n${entries.ifEmpty { "（空）" }}"
                }
                "edit_file" -> {
                    val path = action.optString("path")
                    val search = action.optString("search")
                    val replace = action.optString("replace")
                    if (path.isBlank() || search.isEmpty()) return "错误：edit_file 缺 path 或 search"
                    val file = resolvePath(ctx, path) ?: return "错误：路径 $path 不在工作区内，拒绝修改"
                    if (!file.isFile) return "错误：$path 不存在或不是文件"
                    val text = file.readText()
                    val count = text.split(search).size - 1
                    if (count == 0) {
                        // 匹配不到：回显最相似的行（Aider 的 did-you-mean），别让模型瞎猜。
                        val hint = mostSimilarLine(text, search)
                        return "错误：SEARCH 在 $path 里找不到。最相似的行是：\n$hint\n" +
                            "请按文件里的实际内容（包括缩进、空行）重写 SEARCH，不要凭印象编。"
                    }
                    if (count > 1) {
                        return "错误：SEARCH 在 $path 里出现了 $count 次，无法确定改哪一处。" +
                            "请把 SEARCH 写长一点（多带几行上下文），让它只匹配一处。"
                    }
                    file.writeText(text.replace(search, replace))
                    "OK：已修改 $path（一处）"
                }
                else -> "错误：不认识的动作 $kind（可用：write_file / read_file / list_dir / edit_file / done / fail）"
            }
        } catch (e: Exception) {
            "错误：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 找文件里与 SEARCH 最相似的一行（按共同子串长度），供 did-you-mean 回显。 */
    private fun mostSimilarLine(text: String, search: String): String {
        val firstLine = search.lineSequence().firstOrNull()?.trim() ?: return "（无）"
        var best = ""
        var bestScore = -1
        for (line in text.lineSequence()) {
            val score = commonPrefixLength(line.trim(), firstLine)
            if (score > bestScore) {
                bestScore = score
                best = line
            }
        }
        return best.ifBlank { "（无相似行）" }
    }

    private fun commonPrefixLength(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    companion object {
        /**
         * 编码循环的 system prompt：动作格式 + 规则。模型每步只输出一个 JSON 动作。
         * edit_file 的 SEARCH 必须与文件逐字一致（含缩进、空行）；不确定时先 read_file 看。
         */
        private const val CODE_SYSTEM_PROMPT = "你是一个手机上的编程助手，正在帮用户写代码。" +
            "你每次只输出一个 JSON 动作，不要任何其他文字。可用动作：\n" +
            "1. {\"action\":\"write_file\",\"path\":\"相对路径\",\"content\":\"文件全文\"}：整文件写入（会覆盖旧内容）。\n" +
            "2. {\"action\":\"read_file\",\"path\":\"相对路径\",\"start\":起始行号,\"lines\":行数}：窗口化读文件（默认从第 1 行读 100 行，带行号）。\n" +
            "3. {\"action\":\"list_dir\",\"path\":\"相对路径或空\"}：列出目录内容（空表示工作区根目录）。\n" +
            "4. {\"action\":\"edit_file\",\"path\":\"相对路径\",\"search\":\"原文\",\"replace\":\"新文\"}：把文件里与 search 逐字一致的那一处换成 replace。" +
            "search 必须与文件内容逐字一致（包括缩进、空行），且只能匹配一处；不确定时先 read_file 看清楚再改。\n" +
            "5. {\"action\":\"done\",\"message\":\"完成说明\"}：任务做完了。\n" +
            "6. {\"action\":\"fail\",\"reason\":\"做不下去的原因\"}：任务做不下去。\n" +
            "规则：所有路径都是相对工作区 code-workspace/ 的相对路径，只能读写这里面的文件；" +
            "写代码前先 list_dir 看看工作区里有什么、再 read_file 看相关文件；" +
            "不要凭印象编造文件内容，没看过的文件先读；" +
            "一次只改一处，改完读回来确认；做完了输出 done，做不下去输出 fail。"
    }
}
