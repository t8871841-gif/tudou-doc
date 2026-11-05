package com.jarvis.app.task

import android.content.Context
import com.jarvis.app.AuditLog
import com.jarvis.app.net.CustomClient
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * 行程规划循环（阶段 0，2026-11-05）：独立循环，不塞进 TaskLoop。
 * TaskLoop 是“看屏点按”，规划是“问清信息、出按天排的行程”，动作空间、
 * 提示词、终止条件全不同；只共用 CustomClient（glm-4.5 聊天模型通道）
 * 和 AuditLog。阶段 0 只出文字行程、存文件，不碰高德 App（那是阶段 1）。
 *
 * 行程文件存在应用私有目录 trips/ 下：<时间>-<目的地>.md；进行中的
 * 待问问题存在 trips/pending.txt（原任务 + 上一个问题），用户下一句
 * 回答就接着往下排，不用从头再说一遍。
 */
object TripLoop {
    private const val MAX_STEPS = 6
    private const val MAX_REPEATS = 3
    private const val ASK_TIMEOUT_MS = 60_000L
    /** 阶段 1：一次高德核实（搜地点 / 查路线）最多等 5 分钟，超时就按没核实到处理。 */
    private const val VERIFY_TIMEOUT_MS = 5 * 60 * 1000L
    private const val TRIP_DIR = "trips"
    private const val PENDING_FILE = "pending.txt"

    private val runSeq = AtomicInteger(0)
    private val executor = Executors.newSingleThreadExecutor { r ->
        Thread(r, "TripLoop").apply { isDaemon = true }
    }

    @Volatile
    var running = false

    /** 行程规划的关键词分流：命中即走规划模式，不走跨应用任务循环。 */
    private val TRIP_KEYWORDS = listOf(
        "规划行程", "行程规划", "帮我规划", "旅游攻略", "行程安排",
        "去玩", "出游", "自驾游", "去哪玩", "玩几天", "旅游", "旅行",
        "周边游", "自由行",
    )

    fun looksLikeTripTask(text: String): Boolean {
        val t = text.trim()
        if (t.length < 4) return false
        return TRIP_KEYWORDS.any { t.contains(it) }
    }

    /**
     * 阶段 2：改行程的句式——必须带行程锚点（“行程”或“第X天”）+ 改/换动词，
     * 才算改行程。光说“换成”不算（“把壁纸换成那张图”不是改行程）。
     * 注意：“改行程”只有 3 个字，所以长度门槛是 3，不是规划句式的 4。
     */
    fun looksLikeTripRevision(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        val hasTripAnchor = t.contains("行程") ||
            Regex("第[一二三四五六七八九十0-9]+天").containsMatchIn(t)
        val hasEditVerb = t.contains("改") || t.contains("换")
        return hasTripAnchor && hasEditVerb
    }

    /** 阶段 2：看行程的句式（读最新存档，不进规划循环）。 */
    private val TRIP_VIEW_KEYWORDS = listOf(
        "看行程", "打开行程", "上次的行程", "我的行程", "行程呢", "看看行程",
    )

    fun looksLikeTripView(text: String): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        return TRIP_VIEW_KEYWORDS.any { t.contains(it) }
    }

    /** 是否存过行程（trips/ 下有没有 .md 文件）。 */
    fun hasSavedTrip(ctx: Context): Boolean {
        return try {
            tripDir(ctx).listFiles()?.any { it.isFile && it.name.endsWith(".md") } == true
        } catch (_: Exception) {
            false
        }
    }

    /** 最新的行程文件（按修改时间），没有返回 null。 */
    fun latestTripFile(ctx: Context): File? {
        return try {
            tripDir(ctx).listFiles()
                ?.filter { it.isFile && it.name.endsWith(".md") }
                ?.maxByOrNull { it.lastModified() }
        } catch (_: Exception) {
            null
        }
    }

    /** 急停：自增序号，正在跑的循环下一步检查时自行中止（与 CodeLoop 同款）。 */
    fun cancelInFlight() {
        runSeq.incrementAndGet()
    }

    // ---------- 待问问题（pending） ----------

    private fun tripDir(ctx: Context): File =
        File(ctx.filesDir, TRIP_DIR).apply { mkdirs() }

    private fun pendingFile(ctx: Context): File = File(tripDir(ctx), PENDING_FILE)

    /** 是否有个待回答的行程问题（用户上一句被问住了，下一句就是答案）。 */
    fun hasPendingQuestion(ctx: Context): Boolean {
        return try {
            pendingFile(ctx).isFile
        } catch (_: Exception) {
            false
        }
    }

    private fun loadPending(ctx: Context): String? {
        return try {
            val f = pendingFile(ctx)
            if (!f.isFile) null else f.readText().ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    private fun savePending(ctx: Context, task: String, question: String) {
        try {
            pendingFile(ctx).writeText("$task\n---\n$question")
        } catch (_: Exception) {
        }
    }

    private fun clearPending(ctx: Context) {
        try {
            pendingFile(ctx).delete()
        } catch (_: Exception) {
        }
    }

    /**
     * 执行一个行程规划任务。[isAlive] 由调用方给（Agent 的代次检查）；[onReply]
     * 只会被调用一次，内容是给用户的一句结果（行程摘要、追问、或卡在哪的说明）。
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
        // 新的行程句式 = 从头排，旧的待问问题作废；否则是回答上一轮的追问，
        // 把原任务 + 这一句拼起来接着排。改行程 / 看行程优先于待问回答：
        // “第二天改去华山”不是在回答“去哪、玩几天”，而是改已存的行程。
        val fresh = looksLikeTripTask(taskText)
        val isRevision = !fresh && looksLikeTripRevision(taskText)
        val isView = !fresh && !isRevision && looksLikeTripView(taskText)
        val pending = if (fresh || isRevision || isView) null else loadPending(ctx)
        val effectiveTask = if (pending != null) {
            val orig = pending.substringBefore("\n---\n").ifBlank { pending }
            "$orig\n用户补充回答：$taskText"
        } else {
            taskText
        }
        clearPending(ctx)
        AuditLog.append(ctx, "行程规划", effectiveTask, "开始（上限 $MAX_STEPS 步）")
        val history = mutableListOf<String>()
        var lastError = ""
        var lastSignature = ""
        var repeats = 0
        var finalReply: String? = null
        running = true
        try {
            // ---------- 阶段 2：改行程 / 看行程——单次处理，不进规划循环 ----------
            if (isRevision) {
                if (!alive()) {
                    finalReply = null
                    return
                }
                val file = latestTripFile(ctx)
                if (file == null) {
                    finalReply = "还没存过行程，先说一句「帮我规划去哪玩几天」，排好再改。"
                    AuditLog.append(ctx, "行程规划", "改行程", "没有已存行程")
                    return
                }
                val original = try { file.readText() } catch (_: Exception) { "" }
                if (original.isBlank()) {
                    finalReply = "行程文件是空的，改不了。你再说一遍想去哪、玩几天，我重新排。"
                    return
                }
                AuditLog.append(ctx, "行程规划", "改行程", "${file.name}：$taskText")
                val editText = "原行程：\n$original\n\n用户要求改：$taskText\n\n现在只输出下一步的一个 JSON 动作。"
                val editReply = try {
                    askModel(ctx, TRIP_EDIT_PROMPT, editText)
                } catch (e: Exception) {
                    finalReply = "改行程问模型失败：${e.message ?: ""}，原行程没动。"
                    AuditLog.append(ctx, "行程规划", "改行程问模型失败", e.message ?: "")
                    return
                }
                if (editReply == null) {
                    finalReply = "改行程问模型超时，原行程没动。"
                    return
                }
                val editAction = parseAction(editReply)
                if (editAction == null || editAction.optString("action") != "done") {
                    val reason = editAction?.optString("reason")?.ifEmpty { "改不了" } ?: "改不了"
                    finalReply = "行程没改成：$reason。原行程没动。"
                    return
                }
                val newItinerary = editAction.optString("itinerary")
                val changeSummary = editAction.optString("summary")
                if (newItinerary.isBlank()) {
                    finalReply = "模型没给改好的行程，原行程没动。"
                    return
                }
                try {
                    file.writeText(newItinerary)
                } catch (e: Exception) {
                    finalReply = "改好的行程存文件失败：${e.message}，原行程没动。"
                    return
                }
                AuditLog.append(ctx, "行程规划", "改行程完成", file.name)
                finalReply = if (changeSummary.isNotBlank()) {
                    "$changeSummary\n已改好，存在原文件（${file.name}）里。改的部分这次没拿高德核实，要核实再说一声。"
                } else {
                    "已改好，存在原文件（${file.name}）里。改的部分这次没拿高德核实，要核实再说一声。"
                }
                return
            }
            if (isView) {
                val file = latestTripFile(ctx)
                if (file == null) {
                    finalReply = "还没存过行程，先说一句「帮我规划去哪玩几天」，排好再看。"
                    return
                }
                val content = try { file.readText() } catch (_: Exception) { "" }
                if (content.isBlank()) {
                    finalReply = "行程文件是空的。"
                    return
                }
                AuditLog.append(ctx, "行程规划", "看行程", file.name)
                finalReply = "最近的行程是 ${file.name}：\n${tripHeadlines(content)}"
                return
            }
            var step = 0
            stepLoop@ while (step < MAX_STEPS) {
                if (!alive()) {
                    finalReply = null
                    return
                }
                step++
                // ---------- 观察：任务 + 今天日期 + 历史 ----------
                val userText = buildString {
                    append("用户想规划行程：").append(effectiveTask).append('\n')
                    append("今天是 ").append(todayString()).append("。\n")
                    if (history.isNotEmpty()) {
                        append("已执行步骤（旧→新）：\n")
                        history.forEach { append(it).append('\n') }
                    }
                    if (lastError.isNotEmpty()) append("上一步的问题：").append(lastError).append('\n')
                    append("现在只输出下一步的一个 JSON 动作。")
                }
                // ---------- 问模型（glm-4.5 聊天模型通道） ----------
                val reply = try {
                    askModel(ctx, TRIP_SYSTEM_PROMPT, userText)
                } catch (e: Exception) {
                    lastError = "问模型失败：${e.message ?: e.javaClass.simpleName}"
                    history += "第 $step 步：$lastError"
                    AuditLog.append(ctx, "行程规划", "第 $step 步问模型失败", lastError)
                    continue@stepLoop
                }
                if (reply == null) {
                    lastError = "问模型超时（${ASK_TIMEOUT_MS / 1000} 秒没回）"
                    history += "第 $step 步：$lastError"
                    AuditLog.append(ctx, "行程规划", "第 $step 步问模型失败", lastError)
                    continue@stepLoop
                }
                val action = parseAction(reply)
                if (action == null) {
                    lastError = "模型输出不是合法的 JSON 动作"
                    history += "第 $step 步：输出无法解析 — ${reply.take(80)}"
                    AuditLog.append(ctx, "行程规划", "第 $step 步输出无法解析", reply.take(200))
                    continue@stepLoop
                }
                val kind = action.optString("action")
                // ---------- 追问：关键信息缺了，问一句就停，等用户下一句回答 ----------
                if (kind == "ask") {
                    val question = action.optString("question").ifBlank { "还想问一句：去哪、玩几天？" }
                    history += "第 $step 步：ask — $question"
                    AuditLog.append(ctx, "行程规划", "追问", question)
                    savePending(ctx, effectiveTask, question)
                    finalReply = question
                    return
                }
                // ---------- 完成 / 做不下去 ----------
                if (kind == "done" || kind == "fail") {
                    if (kind == "done") {
                        val dest = action.optString("destination").ifBlank { "行程" }
                        val summary = action.optString("summary")
                        val itinerary = action.optString("itinerary").ifBlank { summary }
                        val places = extractStringList(action.optJSONArray("places"))
                        val legs = extractStringList(action.optJSONArray("legs"))
                        // 阶段 1：拿高德核实地点和路线，再按核实结果改行程；核实不到就原样存。
                        val revised = verifyAndRevise(ctx, dest, summary, itinerary, places, legs) { alive() }
                        val finalSummary = revised?.first ?: summary
                        val finalItinerary = revised?.second ?: itinerary
                        val saved = saveItinerary(ctx, dest, finalItinerary)
                        history += "第 $step 步：done — 已存 $saved"
                        AuditLog.append(ctx, "行程规划", "done", saved)
                        val verifyNote = if (revised == null && (places.isNotEmpty() || legs.isNotEmpty())) {
                            "（这次高德没核实到，行程是按模型知识排的）"
                        } else ""
                        finalReply = if (finalSummary.isNotBlank()) {
                            "$finalSummary$verifyNote\n行程全文已存好（$saved），要看全文、改哪天，随时说。"
                        } else {
                            "行程排好了，全文已存好（$saved），要看全文、改哪天，随时说。"
                        }
                    } else {
                        val reason = action.optString("reason").ifEmpty { "排不出来" }
                        history += "第 $step 步：fail — $reason"
                        AuditLog.append(ctx, "行程规划", "fail", reason)
                        finalReply = "行程没排出来：$reason"
                    }
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
                    finalReply = "行程规划卡住了，同一个动作连着重复多次，已停下。你再说一遍想去哪、玩几天，我重新排。"
                    AuditLog.append(ctx, "行程规划", "重复守卫触发", "$kind 连续重复")
                    return
                }
                lastError = "模型输出了不认识的动作 $kind"
                history += "第 $step 步：$lastError"
                AuditLog.append(ctx, "行程规划", "动作无法执行", kind)
            }
            finalReply = "行程规划到第 $MAX_STEPS 步还没排出来，已停下。你再说一遍想去哪、玩几天，我重新排。"
            AuditLog.append(ctx, "行程规划", "步数上限", "到 $MAX_STEPS 步未完成")
        } finally {
            running = false
            if (finalReply != null && alive()) onReply(finalReply)
        }
    }

    // ---------- 问模型（阻塞式，等 CustomClient 回调；抄 CodeLoop 的 askModel） ----------

    /**
     * 问模型一次。429 限流时指数退避重试（2 秒起、翻倍、30 秒封顶，最多 3 次），
     * 非 429 错误直接抛。注意：error 是被闭包改写的 var，判空后要先存到
     * val 再用，否则智能转换失效（jk-v63 编译错的教训）。
     */
    private fun askModel(ctx: Context, systemPrompt: String, userText: String): String? {
        var backoffMs = 0L
        var lastError: String? = null
        repeat(3) { attempt ->
            if (backoffMs > 0) {
                AuditLog.append(ctx, "行程规划", "问模型 429 限流", "第 ${attempt + 1} 次，等 ${backoffMs / 1000} 秒再试")
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
            val err = error
            if (err == null) return result
            lastError = err
            if (!err.contains("429")) throw IllegalStateException(err)
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
        val key = action.optString("question").ifBlank { action.optString("destination") }
        return "$kind:$key"
    }

    // ---------- 阶段 1：高德核实 ----------

    /**
     * 拿高德核实地点和路线，再按核实结果改行程。返回改好的（summary，itinerary），
     * 核实不到或改不下去返回 null——调用方原样存，不卡住行程。
     * 全程 fail-soft：核实子任务超时、没开无障碍、改行程问模型失败，都只记
     * 一笔审计，不拦着出结果。
     */
    private fun verifyAndRevise(
        ctx: Context, dest: String, summary: String, itinerary: String,
        places: List<String>, legs: List<String>, isAliveFn: () -> Boolean
    ): Pair<String, String>? {
        if (places.isEmpty() && legs.isEmpty()) {
            AuditLog.append(ctx, "行程规划", "核实跳过", "模型没给地点和路线，没什么可核实的")
            return null
        }
        // 核实子任务是内部任务：先把用户自己的断点取走（TaskLoop.run 开头会清断点），
        // 核实完再还回来，免得用户一句“继续”被内部任务顶掉。
        val savedBreakpoint = TaskResumeState.take()
        try {
            val placeResult = if (places.isNotEmpty()) {
                val list = places.take(10)
                AuditLog.append(ctx, "行程规划", "核实地点", list.joinToString("、"))
                runVerifyTask(
                    ctx,
                    "打开高德地图，依次搜索以下地点，搜完告诉我每个地方存不存在、地址是什么（只报结果，不用导航）：${list.joinToString("、")}",
                    isAliveFn
                )
            } else "（没有要核实的地点）"
            val routeResult = if (legs.isNotEmpty()) {
                val list = legs.take(8)
                AuditLog.append(ctx, "行程规划", "核实路线", list.joinToString("、"))
                runVerifyTask(
                    ctx,
                    "打开高德地图，依次查以下几段路的驾车路线，告诉我每段的距离和大概开车时间：${list.joinToString("、")}",
                    isAliveFn
                )
            } else "（没有要核实的路线）"
            // 按核实结果改行程。
            val userText = "原行程（目的地：$dest）：\n$itinerary\n\n" +
                "地点核实结果（高德）：\n$placeResult\n\n" +
                "路线核实结果（高德）：\n$routeResult\n\n" +
                "现在只输出下一步的一个 JSON 动作。"
            val reply = try {
                askModel(ctx, TRIP_REVISE_PROMPT, userText)
            } catch (e: Exception) {
                AuditLog.append(ctx, "行程规划", "改行程问模型失败", e.message ?: "")
                return null
            } ?: return null
            val action = parseAction(reply) ?: return null
            if (action.optString("action") != "done") return null
            val newSummary = action.optString("summary").ifBlank { summary }
            val newItinerary = action.optString("itinerary").ifBlank { itinerary }
            AuditLog.append(ctx, "行程规划", "核实改行程", "已按高德结果改")
            return newSummary to newItinerary
        } finally {
            // 清掉核实子任务可能留下的断点（那是内部任务，用户续它没意义），
            // 再把用户原来的断点还回来。
            TaskResumeState.clear()
            if (savedBreakpoint != null) TaskResumeState.restore(savedBreakpoint)
        }
    }

    /**
     * 调一次 TaskLoop 去高德核实，阻塞等结果。TaskLoop 的 onReply 只在 alive 时
     * 才调（不 alive 时不调），所以不能一次等满超时：每 10 秒看一眼是不是被
     * 打断了，超时或被打断都返回说明，不卡住行程。
     */
    private fun runVerifyTask(ctx: Context, taskText: String, isAliveFn: () -> Boolean): String {
        val latch = CountDownLatch(1)
        var result: String? = null
        TaskLoop.run(ctx, taskText, isAlive = isAliveFn) { reply ->
            result = reply
            latch.countDown()
        }
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (true) {
            if (!isAliveFn()) return "核实被打断（用户发了新指令），没拿到结果"
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) return "核实超时（${VERIFY_TIMEOUT_MS / 1000 / 60} 分钟没跑完），没拿到结果"
            if (latch.await(minOf(remaining, 10_000), TimeUnit.MILLISECONDS)) break
        }
        // 注意：result 是被闭包改写的 var，判空前先存到 val 再用，
        // 否则智能转换失效（jk-v63 编译错的教训）。
        val r = result
        if (r.isNullOrBlank()) return "核实没返回结果"
        return r
    }

    private fun extractStringList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until arr.length()) {
            val s = arr.optString(i).trim()
            if (s.isNotEmpty()) out += s
        }
        return out
    }

    // ---------- 行程文件 ----------

    /** 看行程用的摘要：取标题行和每天的标题行，最多 12 行；没有就取前 300 字。 */
    private fun tripHeadlines(content: String): String {
        val heads = content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") || (it.contains("第") && it.contains("天") && it.length <= 40) }
            .take(12)
            .toList()
        return if (heads.isNotEmpty()) heads.joinToString("\n") else content.take(300)
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(Date())

    /** 行程存成 Markdown：trips/<时间>-<目的地>.md，返回文件名。 */
    private fun saveItinerary(ctx: Context, destination: String, content: String): String {
        val safe = destination.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(20).ifBlank { "行程" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmm", Locale.getDefault()).format(Date())
        val name = "$stamp-$safe.md"
        return try {
            val file = File(tripDir(ctx), name)
            file.writeText("# $destination 行程（${todayString()}规划）\n\n$content")
            name
        } catch (e: Exception) {
            "（存文件失败：${e.message}）"
        }
    }

        /**
         * 行程规划的 system prompt：动作格式 + 规则。模型每步只输出一个 JSON 动作。
         * 只问目的地和天数这两样关键的；人数、预算、想玩什么按默认来（2 个人、
         * 中等预算、经典景点），在行程里把假设写清楚。
         */
        private const val TRIP_SYSTEM_PROMPT = "你是一个手机上的行程规划助手，正在帮用户规划一次出游。" +
            "用户在河南新乡，出发地默认新乡，除非他说了别的出发地。" +
            "你每次只输出一个 JSON 动作，不要任何其他文字。可用动作：\n" +
            "1. {\"action\":\"ask\",\"question\":\"问题\"}：关键信息缺了才问，一次只问一个问题。" +
            "只问目的地或天数这两样关键的；人数、预算、想玩什么不用问，按默认来（2 个人、中等预算、经典景点），" +
            "在行程里把假设写清楚。\n" +
            "2. {\"action\":\"done\",\"destination\":\"目的地\",\"summary\":\"简短口头版\",\"itinerary\":\"完整行程\",\"places\":[\"地点1\",\"地点2\"],\"legs\":[\"A到B\",\"C到D\"]}：行程排好了。" +
            "summary 是念给用户听的短版（几天、每天一句话，不超过 100 字）；itinerary 是完整行程（Markdown，" +
            "按天排：每天上午/下午/晚上去哪、先后顺序、每个地方大概待多久、两地之间怎么走、大概吃什么）。\n" +
            "只写有名的地方，别编具体的饭馆名、酒店名；不确定的地方宁可不写；每天排 2 到 4 个景点，别赶；" +
            "最后一天要写返程。\n" +
            "places 是行程里提到的所有地点名（按出现顺序，去重）；legs 是相邻两站之间的路（格式「A到B」），" +
            "这两个字段是给高德核实用的，别漏。\n" +
            "3. {\"action\":\"fail\",\"reason\":\"做不下去的原因\"}：排不出来（比如没说去哪、用户又不肯说）。\n" +
            "规则：先看用户给了目的地和天数没有，缺关键的就 ask；齐了就 done；" +
            "用户的补充回答如果跟行程无关（问天气、闲聊），输出 fail，reason 说清这句不是在回答行程问题。"

        /**
         * 阶段 1 改行程的 system prompt：输入是原行程 + 高德核实结果，
         * 输出改好的行程。高德给的距离和时间是实的，按它改；高德说不存在
         * 的地方删掉；没核实到的保留原样并标注。
         */
        private const val TRIP_REVISE_PROMPT = "你是一个行程规划助手，正在按高德地图的核实结果修改一份行程。" +
            "输入是原行程 + 高德核实结果（每个地方存不存在、地址；每段路的距离和时间）。" +
            "你每次只输出一个 JSON 动作，不要任何其他文字。可用动作：\n" +
            "1. {\"action\":\"done\",\"destination\":\"目的地\",\"summary\":\"简短口头版\",\"itinerary\":\"修改后的完整行程\"}：改好了。\n" +
            "2. {\"action\":\"fail\",\"reason\":\"原因\"}：核实结果太乱、改不下去。\n" +
            "规则：高德说不存在的地方，从行程里删掉（在 summary 里说删了哪个）；" +
            "高德给的距离和时间是实的，按它改每天的顺序和每个地方待多久；" +
            "没核实到的地方保留原样，但在行程里标注「没核实到」；" +
            "其他内容不动；summary 不超过 100 字，要说清改了什么、删了什么。"

        /**
         * 阶段 2 改行程的 system prompt：输入是原行程 + 用户的修改要求，
         * 输出改好的完整行程。只动用户说的那部分，其他一字不动；用户说
         * 的目的地跟原行程对不上时不硬改，输出 fail 说清。
         */
        private const val TRIP_EDIT_PROMPT = "你是一个行程规划助手，正在按用户的要求修改一份已有的行程。" +
            "输入是原行程 + 用户的修改要求。" +
            "你每次只输出一个 JSON 动作，不要任何其他文字。可用动作：\n" +
            "1. {\"action\":\"done\",\"summary\":\"改了什么（短版，不超过 100 字）\",\"itinerary\":\"修改后的完整行程\"}：改好了。\n" +
            "2. {\"action\":\"fail\",\"reason\":\"原因\"}：改不了（比如要求不清楚、跟原行程对不上）。\n" +
            "规则：只改用户说的那部分，其他地方一字不动；用户说改第几天，就只动那一天；" +
            "新加的地方只写有名的地方，别编饭馆名、酒店名；改完的行程每天还是 2 到 4 个景点，别赶；" +
            "如果用户提到的目的地跟原行程对不上（比如原行程是北京、用户说改西安的），" +
            "不要硬改，输出 fail，reason 说清对不上，让他重说。"
}
