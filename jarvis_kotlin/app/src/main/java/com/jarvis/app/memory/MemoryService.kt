package com.jarvis.app.memory

import android.content.Context
import android.util.Log
import com.jarvis.app.JarvisPrefs
import com.jarvis.app.net.AgnesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONArray
import kotlin.coroutines.resume

/**
* 长期记忆服务。批三原版：显式前缀（记住：/记住 /请记住：/请记住 ）拦截写入 + 分域分类 +
* 对话注入。第一梯队批一升级：
*   1）新增自然语言隐式提取——对话有了回复之后，另起一次不进历史的单轮模型调用
*      （AgnesClient.askOnce），让模型自己判断这轮对话里有没有值得长期记的信息，
*      没有就不插库，不是每句话都硬记。
*   2）注入逻辑从「最近 15 条全量拼」改成按当前这句话做关键词相关性打分 + 重要度/
*      新鲜度加权，取 Top-N，避免记忆条目一多就把提示词挤爆、或者答非所问。
*   3）隐式记忆默认带有效期（IMPLICIT_DEFAULT_TTL_DAYS），显式记忆与模型判断为
*      「长期信息」的隐式记忆才永久保留；检索与展示都自动过滤已过期条目。
*/
object MemoryService {

    private const val TAG = "MemoryService"

    // 隐式记忆如果模型没有判定为长期信息，默认保留这么多天后自动过期。
    private const val IMPLICIT_DEFAULT_TTL_DAYS = 30L

    // 注入提示词时最多取几条相关记忆，防止提示词无限膨胀。
    private const val INJECT_TOP_N = 8

    private fun getDao(ctx: Context) = MemoryDatabase.getDatabase(ctx).memoryDao()

    /** 命中显式记忆指令时返回确认语（并已落库），未命中返回 null。 */
    suspend fun processExplicitMemory(ctx: Context, input: String): String? = withContext(Dispatchers.IO) {
        if (!JarvisPrefs.isMemoryEnabled(ctx)) return@withContext null

        val trimmed = input.trim()
        val prefixes = listOf("记住：", "记住 ", "请记住：", "请记住 ")
        for (prefix in prefixes) {
            if (trimmed.startsWith(prefix, ignoreCase = true)) {
                val content = trimmed.substring(prefix.length).trim()
                if (content.isNotBlank()) {
                    val domain = classifyDomain(content)
                    getDao(ctx).insertMemory(
                        MemoryItem(
                            domain = domain.name,
                            content = content,
                            source = MemorySource.STATED.name,
                            importance = 2,
                            expiresAt = null // 用户明确要求记的，永久保留
                        )
                    )
                    return@withContext "好的，我已经记住了：“$content”"
                }
            }
        }
        return@withContext null
    }

    private fun classifyDomain(content: String): MemoryDomain {
        val lower = content.lowercase()
        return when {
            lower.contains("我是") || lower.contains("职业") || lower.contains("喜欢") || lower.contains("讨厌") -> MemoryDomain.PROFILE
            lower.contains("习惯") || lower.contains("偏好") || lower.contains("总是") -> MemoryDomain.PREFERENCE
            lower.contains("生日") || lower.contains("电话") || lower.contains("地址") || lower.contains("号") -> MemoryDomain.FACT
            else -> MemoryDomain.GENERAL
        }
    }

    private val EXTRACT_SYSTEM_PROMPT = """
        你是记忆提取器，不是在和用户对话。阅读下面一轮「用户说的话」和「Jarvis 的回答」，
        判断里面有没有值得长期记住的、关于用户本人的信息，例如：身份/职业/称呼、
        长期的偏好或习惯、过敏或忌口、重要日期、常用联系人关系。
        只提取用户自己明确透露的信息，不要猜测、不要编造。
        不要记录：一次性的操作指令（比如"帮我点一下XX""打开微信""现在几点"）、
        临时情绪或当下状态（比如"今天有点累""刚吃完饭"）、以及任何与跨应用操作、
        屏幕内容、系统设置相关的内容。
        如果这轮对话里没有值得记的信息，只输出：[]
        如果有，只输出一个 JSON 数组，不要任何解释文字、不要 markdown 代码块标记，
        每一项格式：{"content":"一句话事实，用第三人称转述，比如'用户不吃香菜'",
        "domain":"PROFILE 或 PREFERENCE 或 FACT 或 GENERAL 其中之一",
        "permanent": true 或 false（是长期不变的信息填 true，不确定会不会变填 false）}
    """.trimIndent()

    /**
     * 隐式提取：调用方（JarvisAgent/MainActivity 的问答结果回调里）在拿到正常聊天回复之后
     * 异步触发，不阻塞朗读/显示。额外打一次模型请求，有延迟与费用成本，所以：
     * - 受总开关 isMemoryEnabled 与独立开关 isMemoryAutoExtractEnabled 双重控制；
     * - 只在「问答类」聊天结果上调用，不要在天气/看屏/技能/本地指令这类结构化回复上调用
     *   （调用方自己控制触发时机，这里不做文本类型判断）。
     * 内部吞掉所有失败（网络错误/解析失败/未配置 Key），不影响主流程，只记一条日志。
     */
    suspend fun extractImplicit(ctx: Context, userText: String, replyText: String) {
        if (!JarvisPrefs.isMemoryEnabled(ctx)) return
        if (!JarvisPrefs.isMemoryAutoExtractEnabled(ctx)) return
        if (userText.isBlank() || replyText.isBlank()) return

        val raw = try {
            askOnceSuspend(
                ctx, EXTRACT_SYSTEM_PROMPT,
                "用户说：$userText\nJarvis 回答：$replyText"
            )
        } catch (e: Exception) {
            Log.w(TAG, "隐式记忆提取请求失败：${e.message}")
            return
        } ?: return

        val cleaned = raw.trim().removePrefix("```json").removePrefix("```").removeSuffix("```").trim()
        if (cleaned.isBlank() || cleaned == "[]") return

        val items = try {
            JSONArray(cleaned)
        } catch (e: Exception) {
            Log.w(TAG, "隐式记忆提取结果不是合法 JSON，丢弃：${e.message}")
            return
        }

        withContext(Dispatchers.IO) {
            val existing = getDao(ctx).getAllMemories().map { it.content.trim() }.toSet()
            val now = System.currentTimeMillis()
            for (i in 0 until items.length()) {
                val obj = items.optJSONObject(i) ?: continue
                val content = obj.optString("content").trim()
                if (content.isBlank()) continue
                // 简单去重：内容完全一样就不重复插入（不做语义层面的相似度判断）。
                if (content in existing) continue
                val domainStr = obj.optString("domain").uppercase().let {
                    if (it in MemoryDomain.entries.map { d -> d.name }) it else classifyDomain(content).name
                }
                val permanent = obj.optBoolean("permanent", false)
                val expiresAt = if (permanent) null else now + IMPLICIT_DEFAULT_TTL_DAYS * 24 * 60 * 60 * 1000
                getDao(ctx).insertMemory(
                    MemoryItem(
                        domain = domainStr,
                        content = content,
                        timestamp = now,
                        source = MemorySource.INFERRED.name,
                        importance = 1,
                        expiresAt = expiresAt
                    )
                )
            }
        }
    }

    /** 把 AgnesClient.askOnce 的回调接口包成挂起函数，失败时返回 null（由调用方决定要不要重试）。 */
    private suspend fun askOnceSuspend(ctx: Context, systemPrompt: String, userText: String): String? =
        suspendCancellableCoroutine { cont ->
            AgnesClient.askOnce(
                ctx, systemPrompt, userText, jpegBase64 = null,
                onResult = { if (cont.isActive) cont.resume(it) },
                onError = { msg ->
                    Log.w(TAG, "askOnceSuspend 失败：$msg")
                    if (cont.isActive) cont.resume(null)
                }
            )
        }

    /**
     * 对话注入：按当前这句话 [query] 对所有未过期记忆做相关性打分，取 Top-N 拼进系统提示词。
     * 打分规则（没有接入向量检索，按字符/词级重叠做简化版相关性，不是语义理解）：
     *   - 关键词重叠：query 与记忆内容的 2 字及以上子串重叠越多分越高；
     *   - 重要度加权：importance=2（用户明确要求记的）固定加一个较高基础分；
     *   - 新鲜度：越新的记忆小幅加分，避免老记忆永远压过新记忆。
     * 没有命中任何关键词时，仍然把 importance=2 的记忆带上（这些是用户明确要求记住的，
     * 不应该因为这次没搜到关键词就完全不出现）。
     */
    /**
     * 第二梯队批六新增 [topN] 参数：本地小模型上下文上限只有 1024 token（见
     * `local/LocalLlm.kt` 的注释），原本给云端模型用的 Top-8 塞不下，调用方（本地模型
     * 问答路径）传更小的数字（比如 2）要一个更短的精简版，不是不给本地模型用记忆，
     * 是给的量要配得上它的上下文预算。
     */
    suspend fun getInjectableMemories(ctx: Context, query: String = "", topN: Int = INJECT_TOP_N): String = withContext(Dispatchers.IO) {
        if (!JarvisPrefs.isMemoryEnabled(ctx)) return@withContext ""
        val now = System.currentTimeMillis()
        // 顺手清理过期条目，不单独起定时任务。
        try { getDao(ctx).purgeExpired(now) } catch (_: Exception) {}

        val list = getDao(ctx).getActiveMemories(now)
        if (list.isEmpty()) return@withContext ""

        val queryTokens = tokenize(query)
        val scored = list.map { item ->
            val contentTokens = tokenize(item.content)
            val overlap = if (queryTokens.isEmpty()) 0 else queryTokens.count { t -> contentTokens.any { it.contains(t) || t.contains(it) } }
            val importanceScore = if (item.importance >= 2) 5.0 else 0.0
            val ageDays = (now - item.timestamp).coerceAtLeast(0L) / (24.0 * 60 * 60 * 1000)
            val freshnessScore = (10.0 - ageDays).coerceAtLeast(0.0) * 0.05
            val score = overlap * 3.0 + importanceScore + freshnessScore
            item to score
        }
        val picked = scored
            .filter { it.second > 0.0 } // 没命中关键词也没重要度加成的，不硬塞
            .sortedByDescending { it.second }
            .take(topN)
            .map { it.first }

        if (picked.isEmpty()) return@withContext ""

        val sb = StringBuilder("\n\n以下是关于用户的一些长期记忆，仅在与当前问题相关时参考使用，不要生硬复述：\n")
        picked.forEach { item ->
            sb.append("- [${item.domain}] ${item.content}\n")
        }
        sb.toString()
    }

    /** 简单分词：按 2 字滑窗切中文片段，数字/英文按连续片段整体切。不是真正的中文分词，够用来做重叠打分。 */
    private fun tokenize(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val tokens = mutableListOf<String>()
        val cjk = Regex("[\\u4e00-\\u9fa5]+")
        val other = Regex("[A-Za-z0-9]+")
        for (m in cjk.findAll(text)) {
            val s = m.value
            if (s.length <= 2) tokens.add(s) else {
                for (i in 0..s.length - 2) tokens.add(s.substring(i, i + 2))
            }
        }
        for (m in other.findAll(text)) tokens.add(m.value.lowercase())
        return tokens.filter { it.isNotBlank() }
    }

    suspend fun getAllMemories(ctx: Context): List<MemoryItem> = withContext(Dispatchers.IO) {
        getDao(ctx).getAllMemories()
    }

    suspend fun deleteMemory(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
        getDao(ctx).deleteMemoryById(id)
    }

    suspend fun clearAll(ctx: Context) = withContext(Dispatchers.IO) {
        getDao(ctx).clearAllMemories()
    }
}
