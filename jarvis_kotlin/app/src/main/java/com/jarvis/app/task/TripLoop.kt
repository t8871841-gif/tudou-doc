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
 * 待问问题存在 trips/pending.txt（JSON：原任务 + 上一个问题 + 存入时间戳，
 * 超过 30 分钟过期作废），用户下一句回答就接着往下排，不用从头再说一遍。
 */
object TripLoop {
    private const val MAX_STEPS = 6
    /** 语义相同的动作连着出现 2 次即停（第 2 次到时 repeats==1，M18）。 */
    private const val MAX_REPEATS = 1
    /** 问模型连续失败（网络/超时/429）达到这个次数才判停，不烧规划步数（M19）。 */
    private const val MAX_ASK_FAILS = 2
    private const val ASK_TIMEOUT_MS = 60_000L
    /** 阶段 1：一次高德核实（搜地点 / 查路线）最多等 5 分钟，超时就按没核实到处理。 */
    private const val VERIFY_TIMEOUT_MS = 5 * 60 * 1000L
    private const val TRIP_DIR = "trips"
    private const val PENDING_FILE = "pending.txt"
    /** 待问问题过期时间：超过 30 分钟没答就作废（S14）。 */
    private const val PENDING_TTL_MS = 30 * 60 * 1000L

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
     * 阶段 2：改行程的句式——必须带行程锚点 + 改/换动词，才算改行程。
     * 光说“换成”不算（“把壁纸换成那张图”不是改行程）。
     * 注意：“改行程”只有 3 个字，所以长度门槛是 3，不是规划句式的 4。
     * M24：锚点收窄——光“第 X 天”不够（“第二天换件衣服”不是改行程），
     * 必须出现行程相关词（“行程”），或已存行程的目的地名（带 destination
     * 的重载才认）；否则走普通聊天。
     */
    fun looksLikeTripRevision(text: String): Boolean =
        looksLikeTripRevision(text, null)

    fun looksLikeTripRevision(text: String, destination: String?): Boolean {
        val t = text.trim()
        if (t.length < 3) return false
        val hasEditVerb = t.contains("改") || t.contains("换")
        if (!hasEditVerb) return false
        val hasTripWord = t.contains("行程")
        val hasDestination = !destination.isNullOrBlank() && t.contains(destination)
        return hasTripWord || hasDestination
    }

    /** 带 Context 的版本：还认已存行程的目的地名（“第二天改去北京”）。 */
    fun looksLikeTripRevision(ctx: Context, text: String): Boolean =
        looksLikeTripRevision(text, destinationFromLatestTrip(ctx))

    /**
     * S13：这句像不像在回答行程待问问题（“去哪、玩几天？”）。
     * JarvisAgent 在 hasPendingQuestion 时先调它：为真才进 TripLoop，
     * 为假走正常路由并不清 pending。启发式：trim 后非空，并且
     * （匹配天数模式 \d+\s*天，或者（长度 <= 12 字符且不含以下任一词：
     * 天气、你好、谢谢、怎么、什么、为什么、谁、哪、吗、呢、吧、啊、哦、嗯、请））。
     */
    fun looksLikeTripAnswer(text: String): Boolean {
        val t = text.trim()
        if (t.isEmpty()) return false
        if (Regex("\\d+\\s*天").containsMatchIn(t)) return true
        if (t.length > 12) return false
        val stopWords = listOf(
            "天气", "你好", "谢谢", "怎么", "什么", "为什么",
            "谁", "哪", "吗", "呢", "吧", "啊", "哦", "嗯", "请",
        )
        return stopWords.none { t.contains(it) }
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

    /**
     * 已存最新行程的目的地名（从文件名 "<时间>-<目的地>.md" 取，
     * 取不到再读文件首行 "# <目的地> 行程" 的标题）；没有返回 null。
     * M24 改行程锚点、S21 目的地变了另存新文件都用它。
     */
    private fun destinationFromLatestTrip(ctx: Context): String? {
        val f = latestTripFile(ctx) ?: return null
        return destinationFromFileName(f)
    }

    private fun destinationFromFileName(file: File): String? {
        val fromName = file.nameWithoutExtension.substringAfterLast('-', "").ifBlank { null }
        if (fromName != null) return fromName
        return try {
            val first = file.useLines { it.firstOrNull() } ?: return null
            Regex("^#\\s*(.+?)\\s+行程").find(first)?.groupValues?.getOrNull(1)?.ifBlank { null }
        } catch (_: Exception) {
            null
        }
    }

    /** 急停：自增序号，正在跑的循环下一步检查时自行中止（与 TaskLoop 同款）。 */
    fun cancelInFlight() {
        runSeq.incrementAndGet()
    }

    // ---------- 待问问题（pending，S14+L12：JSON 存储 + 30 分钟过期） ----------

    private fun tripDir(ctx: Context): File =
        File(ctx.filesDir, TRIP_DIR).apply { mkdirs() }

    private fun pendingFile(ctx: Context): File = File(tripDir(ctx), PENDING_FILE)

    private data class PendingData(val task: String, val question: String, val savedAt: Long)

    /** 是否有个待回答的行程问题（用户上一句被问住了，下一句就是答案）。过期返回 false，过期文件顺手删掉。 */
    fun hasPendingQuestion(ctx: Context): Boolean {
        return try {
            loadPending(ctx) != null
        } catch (_: Exception) {
            false
        }
    }

    private fun loadPending(ctx: Context): PendingData? {
        return try {
            val f = pendingFile(ctx)
            if (!f.isFile) return null
            val raw = f.readText()
            if (raw.isBlank()) return null
            val data = parsePending(raw, f.lastModified()) ?: return null
            if (System.currentTimeMillis() - data.savedAt > PENDING_TTL_MS) {
                // 过期：删掉，几天前的问题不再当成今天的答案。
                try {
                    f.delete()
                } catch (_: Exception) {
                }
                null
            } else {
                data
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun parsePending(raw: String, fileModified: Long): PendingData? {
        return try {
            val obj = JSONObject(raw)
            val task = obj.optString("task")
            if (task.isBlank()) return null
            PendingData(task, obj.optString("question"), obj.optLong("savedAt", fileModified))
        } catch (_: Exception) {
            // 兼容旧格式 "任务\n---\n问题"：存入时间取文件修改时间（只读不写，新存一律写 JSON）。
            val parts = raw.split("\n---\n")
            if (parts.size < 2 || parts[0].isBlank()) return null
            PendingData(parts[0], parts[1], fileModified)
        }
    }

    private fun savePending(ctx: Context, task: String, question: String) {
        try {
            val obj = JSONObject()
            obj.put("task", task)
            obj.put("question", question)
            obj.put("savedAt", System.currentTimeMillis())
            pendingFile(ctx).writeText(obj.toString())
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
        // S15：改行程判定优先于新规划——含明确改锚点（“第 X 天改/换”、
        // “行程 + 改/换”）时先判 revision，只有无明确改锚点时才判 fresh；
        // 否则“把去北京旅游的行程第二天改去华山”会被当成新规划、丢弃已存行程。
        // 改行程 / 看行程优先于待问回答：“第二天改去华山”不是在回答
        // “去哪、玩几天”，而是改已存的行程。
        val savedDest = destinationFromLatestTrip(ctx)
        val isRevision = looksLikeTripRevision(taskText, savedDest)
        val fresh = !isRevision && looksLikeTripTask(taskText)
        val isView = !fresh && !isRevision && looksLikeTripView(taskText)
        // S13：待问问题只在确认是有效回答时才消费。无关语句（问天气、闲聊）
        // 不消费 pending、不清 pending——JarvisAgent 那路会在进 TripLoop 前
        // 先调 looksLikeTripAnswer 把关；这里是第二道防线：万一无关语句还是
        // 进来了，直接返回、不回复（行程循环答不了天气），pending 原样保留。
        val pending = if (fresh || isRevision || isView) null else loadPending(ctx)
        val isAnswer = pending != null && looksLikeTripAnswer(taskText)
        if (pending != null && !isAnswer) {
            AuditLog.append(ctx, "行程规划", "待问问题未消费", "这句不是行程回答，pending 保留：$taskText")
            return
        }
        val effectiveTask = if (pending != null && isAnswer) {
            "${pending.task}\n用户补充回答：$taskText"
        } else {
            taskText
        }
        // 新意图（fresh/改/看）取代旧待问问题、或有效回答被正常消费时，才清 pending；
        // 过期由 loadPending 读到时顺手删文件。无关语句到不了这里。
        if (fresh || isRevision || isView || isAnswer) clearPending(ctx)
        AuditLog.append(ctx, "行程规划", effectiveTask, "开始（上限 $MAX_STEPS 步）")
        val history = mutableListOf<String>()
        var lastError = ""
        var lastSignature = ""
        var repeats = 0
        var askFailStreak = 0 // M19：问模型连续失败（网络/超时/429）的次数，不计入规划步数
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
                // S21：先写备份、校验通过再覆盖；目的地变了另存新文件，原文件不动。
                val backupName = file.name + ".bak"
                try {
                    File(file.parentFile, backupName).writeText(original)
                } catch (e: Exception) {
                    AuditLog.append(ctx, "行程规划", "改行程备份失败", e.message ?: "")
                }
                if (!looksLikeValidItinerary(newItinerary)) {
                    finalReply = "改好的行程看起来不完整（缺按天排的结构或太短），原行程没动，备份在 $backupName。"
                    AuditLog.append(ctx, "行程规划", "改行程校验失败", "新行程未通过校验，原文件未动")
                    return
                }
                val origDest = destinationFromFileName(file)
                val newDest = extractNewDestination(taskText)
                val destChanged = origDest != null && newDest != null && newDest != origDest &&
                    !newItinerary.contains(origDest)
                if (destChanged) {
                    val newName = saveItinerary(ctx, newDest ?: "", newItinerary)
                    AuditLog.append(ctx, "行程规划", "改行程完成（另存）", newName)
                    finalReply = if (changeSummary.isNotBlank()) {
                        "$changeSummary\n目的地改成 $newDest，已另存为新文件（$newName），原文件（${file.name}）没动。改的部分这次没拿高德核实，要核实再说一声。"
                    } else {
                        "目的地改成 $newDest，已另存为新文件（$newName），原文件（${file.name}）没动。改的部分这次没拿高德核实，要核实再说一声。"
                    }
                    return
                }
                try {
                    file.writeText(newItinerary)
                } catch (e: Exception) {
                    finalReply = "改好的行程存文件失败：${e.message}，原行程没动，备份在 $backupName。"
                    return
                }
                AuditLog.append(ctx, "行程规划", "改行程完成", file.name)
                finalReply = if (changeSummary.isNotBlank()) {
                    "$changeSummary\n已改好，存在原文件（${file.name}）里，备份在 $backupName。改的部分这次没拿高德核实，要核实再说一声。"
                } else {
                    "已改好，存在原文件（${file.name}）里，备份在 $backupName。改的部分这次没拿高德核实，要核实再说一声。"
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
                // M19：网络/超时/429 失败不烧规划步数（step-- 抵掉本轮 step++），
                // 只记连续失败；连续失败到 MAX_ASK_FAILS 次才判停。
                val reply = try {
                    askModel(ctx, TRIP_SYSTEM_PROMPT, userText)
                } catch (e: Exception) {
                    askFailStreak++
                    if (askFailStreak >= MAX_ASK_FAILS) {
                        finalReply = "问模型连续 $MAX_ASK_FAILS 次失败（网络/超时/限流），先停下，过一会儿再说一次想去哪、玩几天，我重新排。"
                        AuditLog.append(ctx, "行程规划", "问模型连续失败", "连续 $askFailStreak 次")
                        return
                    }
                    lastError = "问模型失败：${e.message ?: e.javaClass.simpleName}（不计步数，再试一次）"
                    history += "第 $step 步：$lastError"
                    AuditLog.append(ctx, "行程规划", "第 $step 步问模型失败", lastError)
                    step--
                    continue@stepLoop
                }
                if (reply == null) {
                    askFailStreak++
                    if (askFailStreak >= MAX_ASK_FAILS) {
                        finalReply = "问模型连续 $MAX_ASK_FAILS 次超时（${ASK_TIMEOUT_MS / 1000} 秒没回），先停下，过一会儿再说一次想去哪、玩几天，我重新排。"
                        AuditLog.append(ctx, "行程规划", "问模型连续失败", "连续 $askFailStreak 次超时")
                        return
                    }
                    lastError = "问模型超时（${ASK_TIMEOUT_MS / 1000} 秒没回，不计步数，再试一次）"
                    history += "第 $step 步：$lastError"
                    AuditLog.append(ctx, "行程规划", "第 $step 步问模型失败", lastError)
                    step--
                    continue@stepLoop
                }
                askFailStreak = 0 // 模型回了话（哪怕输出解析失败），连续失败清零
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
                        var finalItinerary = revised?.second ?: itinerary
                        // S18：超量截断要明说——行程文件里标注未核实，回复里告诉用户只核了前 N 个。
                        val droppedPlaces = places.drop(10)
                        val droppedLegs = legs.drop(8)
                        if (droppedPlaces.isNotEmpty() || droppedLegs.isNotEmpty()) {
                            val bits = mutableListOf<String>()
                            if (droppedPlaces.isNotEmpty()) {
                                bits += "地点 ${droppedPlaces.size} 个未核实（${
                                    droppedPlaces.take(5).joinToString("、")
                                }${if (droppedPlaces.size > 5) "等" else ""}）"
                            }
                            if (droppedLegs.isNotEmpty()) bits += "路线 ${droppedLegs.size} 段未核实"
                            finalItinerary += "\n\n> 未核实：本次只核对了前 ${
                                minOf(places.size, 10)
                            } 个地点 / 前 ${minOf(legs.size, 8)} 段路线；${bits.joinToString("；")}"
                        }
                        val saved = saveItinerary(ctx, dest, finalItinerary)
                        history += "第 $step 步：done — 已存 $saved"
                        AuditLog.append(ctx, "行程规划", "done", saved)
                        // S22：模型漏给 places/legs 时不明说用户会默认已核过——回复里写清楚这次没核实。
                        // S18：核实成功时也要告诉用户只核了前 N 个。
                        val verifyNote = when {
                            places.isEmpty() && legs.isEmpty() -> "（模型没给地点/路线，这次没核实）"
                            revised == null -> "（这次高德没核实到，行程是按模型知识排的）"
                            droppedPlaces.isNotEmpty() || droppedLegs.isNotEmpty() ->
                                "（只核实了前 ${minOf(places.size, 10)} 个地点 / 前 ${
                                    minOf(legs.size, 8)
                                } 段路线，其余未核实）"
                            else -> ""
                        }
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
                // ---------- 重复守卫：语义相同的动作连着来 2 次即停（M18） ----------
                val sig = signatureOf(action)
                if (lastSignature.isNotEmpty() && signaturesSimilar(sig, lastSignature)) {
                    repeats++
                } else {
                    lastSignature = sig
                    repeats = 0
                }
                if (repeats >= MAX_REPEATS) {
                    finalReply = "行程规划卡住了，同一个动作连着重复多次，已停下。你再说一遍想去哪、玩几天，我重新排。"
                    AuditLog.append(ctx, "行程规划", "重复守卫触发", "$kind 语义重复")
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

    // ---------- 问模型（阻塞式，等 CustomClient 回调） ----------

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
        // M18：签名归一化——问题意图 / 目的地 / 内容都去掉标点空白再比，
        // 一字之差不再算不同；未知动作用整段 JSON 当内容。
        val content = when (kind) {
            "ask" -> action.optString("question")
            "done" -> action.optString("destination") + "|" + action.optString("summary")
            "fail" -> action.optString("reason")
            else -> action.toString()
        }
        return "$kind:${normalizeForSignature(content)}"
    }

    /** 归一化：只留字母数字（含中文），去标点/空白/大小写差异。 */
    private fun normalizeForSignature(s: String): String =
        s.filter { it.isLetterOrDigit() }.lowercase(Locale.ROOT)

    /** M18：语义相近——签名相同，或同种动作且一方包含另一方，或字符重合度 ≥ 0.6。 */
    private fun signaturesSimilar(a: String, b: String): Boolean {
        if (a == b) return true
        val kindA = a.substringBefore(':')
        val kindB = b.substringBefore(':')
        if (kindA != kindB) return false
        val textA = a.substringAfter(':')
        val textB = b.substringAfter(':')
        if (textA.isEmpty() || textB.isEmpty()) return false
        if (textA.contains(textB) || textB.contains(textA)) return true
        val setA = textA.toSet()
        val setB = textB.toSet()
        val union = setA.union(setB).size
        if (union == 0) return false
        return setA.intersect(setB).size.toDouble() / union >= 0.6
    }

    /** S21：改好的行程校验——必须有按天排的结构、长度合理，否则不覆盖原文件。 */
    private fun looksLikeValidItinerary(revised: String): Boolean {
        if (revised.length < 50) return false
        if (revised.length > 30000) return false
        val dayMarkers = Regex("第[一二三四五六七八九十0-9]+天").findAll(revised).count()
        return dayMarkers >= 1
    }

    /** S21：从用户改行程的话里抠新目的地（“改成西安 / 换去华山”），抠不到返回 null。 */
    private fun extractNewDestination(taskText: String): String? {
        val m = Regex("[改换][到成去]([\\u4e00-\\u9fa5]{2,8})").find(taskText) ?: return null
        return m.groupValues.getOrNull(1)?.ifBlank { null }
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
                // S18：超量截断记一笔（只核前 10 个，剩下的在 done 分支标注“未核实”）。
                if (places.size > 10) AuditLog.append(ctx, "行程规划", "核实截断", "地点共 ${places.size} 个，只核前 10 个")
                AuditLog.append(ctx, "行程规划", "核实地点", list.joinToString("、"))
                runVerifyTask(
                    ctx,
                    "打开高德地图，依次搜索以下地点，搜完告诉我每个地方存不存在、地址是什么（只报结果，不用导航）：${list.joinToString("、")}",
                    isAliveFn
                )
            } else "（没有要核实的地点）"
            val routeResult = if (legs.isNotEmpty()) {
                val list = legs.take(8)
                if (legs.size > 8) AuditLog.append(ctx, "行程规划", "核实截断", "路线共 ${legs.size} 段，只核前 8 段")
                AuditLog.append(ctx, "行程规划", "核实路线", list.joinToString("、"))
                runVerifyTask(
                    ctx,
                    "打开高德地图，依次查以下几段路的驾车路线，告诉我每段的距离和大概开车时间：${list.joinToString("、")}",
                    isAliveFn
                )
            } else "（没有要核实的路线）"
            // S17：核验失败串不是高德事实，绝不能当成“不存在”喂给改行程模型。
            // 两路都失败直接返回 null、原样保存（调用方会注明未核实）；只有明确
            // “不存在/地址是 X”的结果才进 revise；单路失败单独标记、不进模型输入。
            val placeFailed = places.isNotEmpty() && isVerifyFailure(placeResult)
            val routeFailed = legs.isNotEmpty() && isVerifyFailure(routeResult)
            if (placeFailed && routeFailed) {
                AuditLog.append(ctx, "行程规划", "核实失败", "地点和路线两路都没拿到结果，不喂给改行程模型，原样保存")
                return null
            }
            val placeForModel = if (placeFailed) {
                "（地点核实失败，这次没核实到——不要当成不存在，保留原样）"
            } else placeResult
            val routeForModel = if (routeFailed) {
                "（路线核实失败，这次没核实到——保留原样）"
            } else routeResult
            // 按核实结果改行程。
            val userText = "原行程（目的地：$dest）：\n$itinerary\n\n" +
                "地点核实结果（高德）：\n$placeForModel\n\n" +
                "路线核实结果（高德）：\n$routeForModel\n\n" +
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
            // S16：先清后还——核验任务已在 runVerifyTask 超时/打断时
            // cancelInFlight 停掉，核验本身不写用户断点（saveResume=false，
            // 待 TaskLoop 那路落地）；这里清掉核验可能留下的内部断点，
            // 再把用户原来的断点还回来。顺序不能反：先还后清会把用户断点清掉。
            TaskResumeState.clear()
            if (savedBreakpoint != null) TaskResumeState.restore(savedBreakpoint)
        }
    }

    /** S17：核验失败串（超时 / 被打断 / 没返回结果 / 停在某一步）——不是高德事实。 */
    private fun isVerifyFailure(text: String): Boolean {
        return text.startsWith("核实超时") || text.startsWith("核实被打断") ||
            text.startsWith("核实没返回结果") || text.contains("任务停在第")
    }

    /**
     * 调一次 TaskLoop 去高德核实，阻塞等结果。TaskLoop 的 onReply 只在 alive 时
     * 才调（不 alive 时不调），所以不能一次等满超时：每 10 秒看一眼是不是被
     * 打断了，超时或被打断都返回说明，不卡住行程。
     * S16：超时/打断返回前调 TaskLoop.cancelInFlight() 把后台核验任务停掉，
     * 别让它继续点高德；停完等 1.5 秒，让它走到 alive 检查、把内部断点写出来，
     * 好让 verifyAndRevise 的 finally 用一次 clear() 清掉、再还用户断点。
     * 核验本身传 saveResume=false（TaskLoop.run 已有该参数），根本不写断点，
     * 上面的 1.5 秒等待只是双保险。
     */
    private fun runVerifyTask(ctx: Context, taskText: String, isAliveFn: () -> Boolean): String {
        // 差距 8c：高德核验子任务驱动屏幕，球沉底沿。TaskLoop.run 自己的 runBlocking 里也会挂，
        // 这里再显式挂一次，覆盖入队到起跑之间的窗口；恢复由 TaskLoop 的 finally 和下面的
        // finally 双重做（setTaskHidden 是幂等标志位，不会互相顶掉）。
        com.jarvis.app.OrbController.setTaskHidden(true)
        try {
            return runVerifyTaskInner(ctx, taskText, isAliveFn)
        } finally {
            com.jarvis.app.OrbController.setTaskHidden(false)
        }
    }

    private fun runVerifyTaskInner(ctx: Context, taskText: String, isAliveFn: () -> Boolean): String {
        val latch = CountDownLatch(1)
        var result: String? = null
        TaskLoop.run(ctx, taskText, isAlive = isAliveFn, saveResume = false) { reply ->
            result = reply
            latch.countDown()
        }
        val deadline = System.currentTimeMillis() + VERIFY_TIMEOUT_MS
        while (true) {
            if (!isAliveFn()) {
                TaskLoop.cancelInFlight()
                sleepQuietly(1500)
                return "核实被打断（用户发了新指令），没拿到结果"
            }
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) {
                TaskLoop.cancelInFlight()
                sleepQuietly(1500)
                return "核实超时（${VERIFY_TIMEOUT_MS / 1000 / 60} 分钟没跑完），没拿到结果"
            }
            if (latch.await(minOf(remaining, 10_000), TimeUnit.MILLISECONDS)) break
        }
        // 注意：result 是被闭包改写的 var，判空前先存到 val 再用，
        // 否则智能转换失效（jk-v63 编译错的教训）。
        val r = result
        if (r.isNullOrBlank()) return "核实没返回结果"
        return r
    }

    private fun sleepQuietly(ms: Long) {
        try {
            Thread.sleep(ms)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        }
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

    /**
     * 看行程用的摘要：取标题行和每天的标题行，最多 12 行，超了补省略号；
     * 没有标题行时取前 3 个完整段落（按空行分段），超长时按完整行截断并补省略号。
     */
    private fun tripHeadlines(content: String): String {
        val allHeads = content.lineSequence()
            .map { it.trim() }
            .filter { it.startsWith("#") || (it.contains("第") && it.contains("天") && it.length <= 40) }
            .toList()
        if (allHeads.isNotEmpty()) {
            val joined = allHeads.take(12).joinToString("\n")
            return if (allHeads.size > 12) "$joined\n…" else joined
        }
        val paras = content.split(Regex("\\n\\s*\\n")).map { it.trim() }.filter { it.isNotEmpty() }
        if (paras.isEmpty()) return ""
        var taken = paras.take(3).joinToString("\n\n")
        if (paras.size > 3) taken += "\n…"
        if (taken.length > 600) {
            // 按完整行截断，补省略号；首行本身就超长时按字截断。
            val sb = StringBuilder()
            for (line in taken.lineSequence()) {
                if (sb.length + line.length + 1 > 600) break
                if (sb.isNotEmpty()) sb.append('\n')
                sb.append(line)
            }
            taken = if (sb.isEmpty()) taken.take(600) + "…" else sb.toString() + "\n…"
        }
        return taken
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd EEEE", Locale.CHINA).format(Date())

    /** 行程存成 Markdown：trips/<时间>-<目的地>.md，返回文件名。M23：时间戳精确到秒，同名文件加序号，绝不覆盖。 */
    private fun saveItinerary(ctx: Context, destination: String, content: String): String {
        val safe = destination.filter { it.isLetterOrDigit() || it == '-' || it == '_' }
            .take(20).ifBlank { "行程" }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.getDefault()).format(Date())
        var name = "$stamp-$safe.md"
        var n = 2
        while (File(tripDir(ctx), name).exists()) {
            name = "$stamp-$safe-$n.md"
            n++
        }
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
