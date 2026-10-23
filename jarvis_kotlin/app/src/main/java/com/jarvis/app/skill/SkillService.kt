package com.jarvis.app.skill

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/** 批四：技能的存取与命中匹配。匹配是包含式（触发句被包含、或话里含技能名），不是语义匹配。 */
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

    /** 命中：enabled 技能中，triggerText 被 text 包含（得分=触发句长度）或 text 包含技能名（得分=名长），取最高分。 */
    suspend fun match(ctx: Context, text: String): SkillItem? = withContext(Dispatchers.IO) {
        val t = text.trim()
        if (t.isEmpty()) return@withContext null
        var best: SkillItem? = null
        var bestScore = -1
        for (s in getDao(ctx).getAllSkills()) {
            if (!s.enabled) continue
            var score = -1
            if (s.triggerText.isNotBlank() && t.contains(s.triggerText)) score = maxOf(score, s.triggerText.length)
            if (s.name.isNotBlank() && t.contains(s.name)) score = maxOf(score, s.name.length)
            if (score > bestScore) {
                bestScore = score
                best = s
            }
        }
        best
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
