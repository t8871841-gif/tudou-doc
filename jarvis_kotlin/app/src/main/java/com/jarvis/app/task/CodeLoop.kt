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
    /** 阶段 2：共享反思预算（Aider 数）——编辑/lint/测试错共用 3 次，不是每类各 3 次。 */
    private const val MAX_REFLECTIONS = 3
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
        // 阶段 2：共享反思预算——编辑错、lint 错、测试错（执行返回“错误”）共用 3 次，
        // 不是每类各 3 次；同一错误连着出现 3 次也停（卡住检测）。
        var consecutiveFailures = 0
        var lastFailure = ""
        var sameErrorCount = 0
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
                    // 失败收尾抄 mini-swe-agent：先把当前工作区状态交出来再退出，
                    // 绝不空手而归（成功时不用附，done 消息本身就是结果）。
                    finalReply = if (kind == "done") msg else
                        "写代码没完成，卡在第 $step 步：$msg\n${workspaceSummary(ctx)}"
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
                    finalReply = "写代码卡在第 $step 步：同一个动作连着重复多次，已停下。\n${workspaceSummary(ctx)}"
                    AuditLog.append(ctx, "编码循环", "重复守卫触发", "$kind 连续重复")
                    return
                }
                // ---------- 执行文件动作 ----------
                val result = executeFileAction(ctx, action)
                history += "第 $step 步：${describeAction(action)} → ${result.take(500)}"
                AuditLog.append(ctx, "编码循环", "$kind（第 $step 步）", result.take(500))
                lastError = if (result.startsWith("OK")) "" else result
                // ---------- 阶段 2：共享反思预算 + 卡住检测 ----------
                // 执行返回“错误”才计入预算（问模型失败、输出解析失败不算，
                // 那些是另外的路数，不占用反思次数）。
                if (result.startsWith("错误")) {
                    consecutiveFailures++
                    if (result == lastFailure) sameErrorCount++ else sameErrorCount = 1
                    lastFailure = result
                    if (consecutiveFailures >= MAX_REFLECTIONS) {
                        finalReply = "写代码连续 $MAX_REFLECTIONS 次出错，已停下，留给人处理。\n${workspaceSummary(ctx)}"
                        AuditLog.append(ctx, "编码循环", "反思预算用完", "连续 $MAX_REFLECTIONS 次出错")
                        return
                    }
                    if (sameErrorCount >= MAX_REFLECTIONS) {
                        finalReply = "写代码同一个错误连着出现 $MAX_REFLECTIONS 次，已停下，留给人处理。\n${workspaceSummary(ctx)}"
                        AuditLog.append(ctx, "编码循环", "卡住检测", "同一错误 $MAX_REFLECTIONS 次")
                        return
                    }
                } else {
                    consecutiveFailures = 0
                    sameErrorCount = 0
                    lastFailure = ""
                }
            }
            finalReply = "写代码到第 $MAX_STEPS 步还没做完，已停下。\n${workspaceSummary(ctx)}"
            AuditLog.append(ctx, "编码循环", "步数上限", "到 $MAX_STEPS 步未完成")
        } finally {
            running = false
            if (finalReply != null && alive()) onReply(finalReply)
        }
    }

    // ---------- 问模型（阻塞式，等 CustomClient 回调） ----------

    /**
     * 问模型一次。429 限流时指数退避重试（2 秒起、翻倍、30 秒封顶，最多 3 次），
     * 与任务的反思预算分层——429 不占用反思次数；非 429 错误直接抛。
     */
    private fun askModel(ctx: Context, systemPrompt: String, userText: String): String? {
        var backoffMs = 0L
        var lastError: String? = null
        repeat(3) { attempt ->
            if (backoffMs > 0) {
                AuditLog.append(ctx, "编码循环", "问模型 429 限流", "第 ${attempt + 1} 次，等 ${backoffMs / 1000} 秒再试")
                try {
                    Thread.sleep(backoffMs)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw IllegalStateException("问模型被打断")
                }
            }
            val latch = CountDownLatch(1)
            var result: String? = null
            var error: String? = null
            CustomClient.askOnce(
                ctx, systemPrompt, userText, null,
                onResult = { result = it; latch.countDown() },
                onError = { error = it; latch.countDown() },
            )
            latch.await(ASK_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            if (error == null) return result
            lastError = error
            if (!error.contains("429")) throw IllegalStateException(error)
            backoffMs = if (backoffMs == 0L) 2000 else minOf(backoffMs * 2, 30000)
        }
        throw IllegalStateException(lastError ?: "问模型失败")
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

    /** 失败收尾用的工作区快照：让用户看到停下时写了哪些文件（抄 mini-swe-agent 交半成品）。 */
    private fun workspaceSummary(ctx: Context): String {
        val files = listWorkspace(workspaceDir(ctx))
        return if (files.isEmpty()) "工作区还是空的，没写出文件。" else "工作区现有文件：\n$files"
    }

    /**
     * 阶段 2 改前校验（轻量版，抄 SWE-agent lint 守门）：写盘前检查括号/引号配对，
     * 有问题不落盘、把原因返回给模型再试。跳过 # 注释与单/双引号字符串里的内容，
     * 避免误判；三引号字符串、缩进错误这类查不到的，靠执行时报错再修。
     * 返回错误说明，没问题返回 null。
     */
    private fun validateCode(content: String): String? {
        val stack = ArrayDeque<Char>()
        val pairs = mapOf(')' to '(', ']' to '[', '}' to '{')
        var inSingle = false
        var inDouble = false
        var escaped = false
        var line = 1
        var i = 0
        while (i < content.length) {
            val ch = content[i]
            if (ch == '\n') {
                line++
                i++
                continue
            }
            if (escaped) {
                escaped = false
                i++
                continue
            }
            if (ch == '\\') {
                escaped = true
                i++
                continue
            }
            if (inSingle) {
                if (ch == '\'') inSingle = false
                i++
                continue
            }
            if (inDouble) {
                if (ch == '"') inDouble = false
                i++
                continue
            }
            // 行注释：# 到行尾的内容不参与校验。
            if (ch == '#') {
                while (i < content.length && content[i] != '\n') i++
                continue
            }
            when (ch) {
                '\'' -> inSingle = true
                '"' -> inDouble = true
                '(', '[', '{' -> stack.addLast(ch)
                ')', ']', '}' -> {
                    val open = pairs[ch]!!
                    if (stack.isEmpty() || stack.removeLast() != open) {
                        return "括号不匹配：第 $line 行 '$ch' 没有对应的 '$open'，没写盘"
                    }
                }
            }
            i++
        }
        if (inSingle) return "单引号未闭合，没写盘"
        if (inDouble) return "双引号未闭合，没写盘"
        if (stack.isNotEmpty()) return "括号未闭合：多了 '${stack.last()}'，没写盘"
        return null
    }

    /**
     * 执行一个动作，返回给模型看的结果（"OK..." 或错误说明）。
     * 文件动作：write_file / read_file / list_dir / edit_file。
     * 执行动作（阶段 1）：run_shell（Shizuku 跑 shell 命令）/
     * run_python（Termux 跑 Python 代码，heredoc 传进去）/
     * run_termux（Termux 跑任意 bash 脚本）。
     */
    private fun executeFileAction(ctx: Context, action: JSONObject): String {
        return try {
            when (val kind = action.optString("action")) {
                "run_shell" -> {
                    val command = action.optString("command")
                    if (command.isBlank()) return "错误：run_shell 缺 command"
                    blockedReason(command)?.let { return "错误：命令被安全规则拦下：$it" }
                    if (!ShizukuHelper.isShizukuAvailable(ctx)) {
                        return "错误：Shizuku 不可用（没装或服务没启动），run_shell 跑不了"
                    }
                    if (!ShizukuHelper.hasPermission()) {
                        return "错误：没有 Shizuku 授权，请先在 Shizuku 里给 Jarvis 授权"
                    }
                    // Shizuku 的 runShell 内部把超时钳到 10 秒，shell 命令够用了。
                    val r = ShizukuHelper.runShell(arrayOf("sh", "-c", command))
                    formatExecResult(ctx, "run_shell", command, r.stdout, "", r.exitCode)
                }
                "run_python" -> {
                    val code = action.optString("code")
                    if (code.isBlank()) return "错误：run_python 缺 code"
                    if (code.length > TermuxExecutor.MAX_SCRIPT_CHARS) {
                        return "错误：代码超过 ${TermuxExecutor.MAX_SCRIPT_CHARS} 字，请拆小一点"
                    }
                    runTermuxPython(ctx, code)
                }
                "run_termux" -> {
                    val script = action.optString("script")
                    if (script.isBlank()) return "错误：run_termux 缺 script"
                    blockedReason(script)?.let { return "错误：命令被安全规则拦下：$it" }
                    runTermuxScript(ctx, script)
                }
                "write_file" -> {
                    val path = action.optString("path")
                    val content = action.optString("content")
                    if (path.isBlank()) return "错误：write_file 缺 path"
                    // 阶段 2 改前校验：括号/引号不对不落盘，原因喂回模型再试。
                    validateCode(content)?.let { return "错误：$it" }
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
                    val newText = text.replace(search, replace)
                    // 阶段 2 改前校验：改完的全文括号/引号不对不落盘。
                    validateCode(newText)?.let { return "错误：$it" }
                    file.writeText(newText)
                    "OK：已修改 $path（一处）"
                }
                else -> "错误：不认识的动作 $kind（可用：write_file / read_file / list_dir / edit_file / run_shell / run_python / run_termux / done / fail）"
            }
        } catch (e: Exception) {
            "错误：${e.message ?: e.javaClass.simpleName}"
        }
    }

    // ---------- 执行（阶段 1）：Shizuku shell + Termux Python ----------

    /**
     * 命令安全检查：拦下最危险的几类。返回拦下的原因，没拦下返回 null。
     * 这是底线防护，不是万能——工作区白名单 + 审计日志是另外两道线。
     */
    private fun blockedReason(script: String): String? {
        val s = script.lowercase()
        // 删根目录 / 家目录
        if (Regex("rm\\s+(-[a-z]*r[a-z]*f|-[a-z]*f[a-z]*r)\\s+(/|/\\*|~|\\$home)").containsMatchIn(s)) {
            return "rm -rf 根目录/家目录"
        }
        // 管道进 shell（curl|sh、wget|bash 这类远程代码执行）
        if (Regex("\\|\\s*(sh|bash|zsh|dash)\\b").containsMatchIn(s)) return "管道进 shell（| sh / | bash）"
        // 格式化、清盘
        if (s.contains("mkfs.") || Regex("dd\\s+.*if=/dev/zero").containsMatchIn(s)) return "格式化/清盘命令"
        // fork 炸弹
        if (s.contains(":(){") && s.contains(":|:&")) return "fork 炸弹"
        return null
    }

    /** Termux 跑 Python 代码：heredoc 传进去，不用先写文件（省掉共享存储中转）。 */
    private fun runTermuxPython(ctx: Context, code: String): String {
        // heredoc 标记要足够特别，避免跟代码内容撞上。
        val script = "python3 <<'PYEOF_JARVIS'\n$code\nPYEOF_JARVIS"
        return runTermuxScript(ctx, script)
    }

    private fun runTermuxScript(ctx: Context, script: String): String {
        if (!TermuxExecutor.isInstalled(ctx)) {
            return "错误：Termux 没有安装，run_termux 跑不了"
        }
        if (!TermuxExecutor.hasPermission(ctx)) {
            return "错误：没有 Termux RUN_COMMAND 权限（去 Termux 里开 allow-external-apps 并授权）"
        }
        return try {
            val r = TermuxExecutor.runScript(ctx, script)
            formatExecResult(ctx, "run_termux", script, r.stdout, r.stderr, r.exitCode, r.errorMessage)
        } catch (e: Exception) {
            "错误：Termux 执行失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /**
     * 执行结果格式化（抄 OpenCode）：stdout/stderr/exitCode 三件套一次给全；
     * 默认截断到 2000 行 / 50KB 给模型，全量输出落盘到工作区 runs/<时间戳>.log，
     * 模型看到截断版 + 文件路径，可再读文件。成功但无输出时明确说出来，
     * 别让模型误判为卡住。
     */
    private fun formatExecResult(
        ctx: Context, kind: String, command: String,
        stdout: String, stderr: String, exitCode: Int, errorMessage: String = "",
    ): String {
        val full = buildString {
            append("命令：").append(command.take(500)).append('\n')
            append("exitCode：").append(exitCode).append('\n')
            if (errorMessage.isNotBlank()) append("Termux 错误：").append(errorMessage).append('\n')
            append("--- stdout ---\n").append(stdout).append('\n')
            if (stderr.isNotBlank()) append("--- stderr ---\n").append(stderr).append('\n')
        }
        // 全量落盘（工作区 runs/，模型可再读）。
        val logPath = try {
            val runsDir = File(workspaceDir(ctx), "runs").apply { mkdirs() }
            val logFile = File(runsDir, "${System.currentTimeMillis()}.log")
            logFile.writeText(full)
            "runs/${logFile.name}"
        } catch (_: Exception) {
            ""
        }
        val truncated = truncateOutput(full)
        val head = if (exitCode == 0) "OK：命令成功（exitCode 0）" else "命令退出码 $exitCode（非 0，失败）"
        val emptyNote = if (stdout.isBlank() && stderr.isBlank()) "（成功且无输出）" else ""
        val logNote = if (logPath.isNotEmpty()) "\n（完整输出已存 $logPath，可用 read_file 再读）" else ""
        return "$head$emptyNote\n$truncated$logNote"
    }

    private fun truncateOutput(text: String): String {
        val maxChars = 50_000
        val maxLines = 2000
        val lines = text.lines()
        var out = if (lines.size > maxLines) {
            lines.take(maxLines).joinToString("\n") + "\n（输出太长，已截断到前 $maxLines 行）"
        } else {
            text
        }
        if (out.length > maxChars) {
            out = out.take(maxChars) + "\n（输出太长，已截断到前 50KB）"
        }
        return out
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
            "5. {\"action\":\"run_shell\",\"command\":\"shell 命令\"}：经 Shizuku 跑一条 shell 命令（如 ls、cat、sh 脚本），回 stdout + 退出码。\n" +
            "6. {\"action\":\"run_python\",\"code\":\"Python 代码\"}：经 Termux 跑一段 Python 代码，回 stdout/stderr + 退出码。\n" +
            "7. {\"action\":\"run_termux\",\"script\":\"bash 脚本\"}：经 Termux 跑任意 bash 脚本（如 pip install、node）。\n" +
            "8. {\"action\":\"done\",\"message\":\"完成说明\"}：任务做完了。\n" +
            "9. {\"action\":\"fail\",\"reason\":\"做不下去的原因\"}：任务做不下去。\n" +
            "规则：所有路径都是相对工作区 code-workspace/ 的相对路径，只能读写这里面的文件；" +
            "写代码前先 list_dir 看看工作区里有什么、再 read_file 看相关文件；" +
            "不要凭印象编造文件内容，没看过的文件先读；" +
            "一次只改一处，改完读回来确认；" +
            "写完代码要跑起来验证：先 run_python/run_termux 跑一遍，看输出和退出码，" +
            "报错就按报错改、再跑，直到跑通或确认做不下去；" +
            "做完了输出 done，做不下去输出 fail。"
    }
}
