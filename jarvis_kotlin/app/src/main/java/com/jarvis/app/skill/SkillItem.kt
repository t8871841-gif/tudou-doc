package com.jarvis.app.skill

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 批四：固化的技能。stepsJson 为步骤数组 JSON，步骤类型只有四种（见 SkillRunner）：
 * {"t":"open","pkg":"包名"} / {"t":"tap","target":"目标描述"} / {"t":"wait","ms":800} /
 * {"t":"swipe","dir":"down"|"up"}。输入文字（input）没有已验证原语，本批不做。
 */
@Entity(tableName = "jarvis_skills")
data class SkillItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val name: String,
    val triggerText: String,
    val stepsJson: String,
    val enabled: Boolean = true,
    val successCount: Int = 0,
    val failCount: Int = 0,
    val updatedAt: Long = System.currentTimeMillis()
)
