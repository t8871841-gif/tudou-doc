package com.jarvis.app.skill

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * 批四：技能的存取与命中匹配。
 *
 * 第一梯队批三升级匹配逻辑：原来纯包含式（触发句必须整句被用户的话包含、或话里含技能名）
 * 太死——"帮我刷一下朋友圈"录成技能后，用户说"去看看朋友圈"完全不命中。现在加一层
 * 基于词块重叠度的模糊打分作为补充，不要求整句包含，只要关键词重叠比例够高就算命中。
 *
 * 如实说明：这不是真正的语义匹配（没有引入 embedding/向量模型，工程里也没有这个基础
 * 设施），本质上还是字面匹配，只是从"整句包含"放宽到"词块重叠度"。"去看看朋友圈"和
 * "刷一下朋友圈"重叠度高能命中，但"发个消息给我老婆"和"给媳妇发微信"这种同义词不同字
 * 的情况仍然搜不到——真正的语义匹配需要向量检索，超出本批范围。
 */
object SkillService {

    private fun getDao(ctx: Context) = SkillDatabase.getDatabase(ctx).skillDao()

    suspend fun addSkill(ctx: Context, name: String, triggerText: String, stepsJson: String): Long =
        withContext(Dispatchers.IO) {
            getDao(ctx).insertSkill(
                SkillItem(name = name, triggerText = triggerText, stepsJson = stepsJson)
            )
        }

    suspend fun getAllSkills(ctx: Context): List<SkillItem> = withContext(Dispatchers.IO) {
        getDao(ctx).getAllSkills()
    }

    suspend fun deleteSkill(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
        getDao(ctx).deleteSkillById(id)
    }

    suspend fun setEnabled(ctx: Context, item: SkillItem, on: Boolean) = withContext(Dispatchers.IO) {
        getDao(ctx).updateSkill(item.copy(enabled = on, updatedAt = System.currentTimeMillis()))
    }

    suspend fun recordSuccess(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
        val dao = getDao(ctx)
        dao.getAllSkills().firstOrNull { it.id == id }?.let {
            dao.updateSkill(it.copy(successCount = it.successCount + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    suspend fun recordFailure(ctx: Context, id: Long) = withContext(Dispatchers.IO) {
        val dao = getDao(ctx)
        dao.getAllSkills().firstOrNull { it.id == id }?.let {
            dao.updateSkill(it.copy(failCount = it.failCount + 1, updatedAt = System.currentTimeMillis()))
        }
    }

    // 词块重叠度达到这个比例才算模糊命中；低于此值交给正常聊天/任务流程处理，
    // 避免随口一句话里恰好带几个字就被技能"抢答"。
    private const val FUZZY_MATCH_THRESHOLD = 0.6

    /**
     * 命中优先级：①triggerText 整句被 text 包含，或 text 包含技能名——字面包含，得分最高
     * （=触发句/名字长度 × 100，保证永远压过模糊匹配）；②都没有整句包含时，退到词块重叠度
     * 模糊打分，重叠比例需达到 [FUZZY_MATCH_THRESHOLD] 才算候选。多个技能都命中时取分最高的。
     */
    suspend fun match(ctx: Context, text: String): SkillItem? = withContext(Dispatchers.IO) {
        val t = text.trim()
        if (t.isEmpty()) return@withContext null
        val textTokens = tokenize(t)
        var best: SkillItem? = null
        var bestScore = -1.0
        for (s in getDao(ctx).getAllSkills()) {
            if (!s.enabled) continue
            var score = -1.0
            if (s.triggerText.isNotBlank() && t.contains(s.triggerText)) score = maxOf(score, s.triggerText.length * 100.0)
            if (s.name.isNotBlank() && t.contains(s.name)) score = maxOf(score, s.name.length * 100.0)
            if (score < 0) {
                // 字面没整句命中，退到词块重叠度：取 triggerText 和 name 里表现较好的那个。
                val candidates = listOfNotNull(
                    s.triggerText.takeIf { it.isNotBlank() },
                    s.name.takeIf { it.isNotBlank() }
                )
                for (cand in candidates) {
                    val candTokens = tokenize(cand)
                    if (candTokens.isEmpty()) continue
                    val overlap = candTokens.count { ct -> textTokens.any { it == ct } }
                    val ratio = overlap.toDouble() / candTokens.size
                    if (ratio >= FUZZY_MATCH_THRESHOLD) score = maxOf(score, ratio * 50.0)
                }
            }
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        best
    }

    /** 和 MemoryService 同款的简单分词：中文按 2 字滑窗切，英文/数字按连续片段整体切。 */
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

    /** stepsJson → 步骤对象列表；解析失败返回空列表（重播端会据此如实报"技能里没有步骤/已损坏"）。 */
    fun parseSteps(stepsJson: String): List<JSONObject> {
        return try {
            val arr = JSONArray(stepsJson)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    /** 一串点按目标 → 只含 tap 步骤的 stepsJson（轨迹存技能用）。 */
    fun tapsToStepsJson(targets: List<String>): String {
        val arr = JSONArray()
        for (t in targets) {
            arr.put(JSONObject().put("t", "tap").put("target", t))
        }
        return arr.toString()
    }
}
