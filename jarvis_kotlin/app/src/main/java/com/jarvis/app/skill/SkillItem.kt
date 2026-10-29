package com.jarvis.app.skill

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 批四：固化的技能。stepsJson 为步骤数组 JSON，步骤类型（见 SkillRunner）：
 * {"t":"open","pkg":"包名"} / {"t":"tap","target":"目标描述"} / {"t":"wait","ms":800} /
 * {"t":"swipe","dir":"down"|"up"} /
 * {"t":"input","text":"要打的字"}（第一梯队批三新增——假设紧跟在成功点开了输入框的
 * tap 步骤之后，对当前焦点编辑框打字，不自己重新定位输入框）/
 * {"t":"sys","action":"dnd_on"}（第二梯队批六新增——场景联动，可用 action 见
 * SkillRunner/DeviceCommands.sceneActionHints()，只要求动作关键字被认识就算这步成功，
 * 不校验系统动作是否真的生效，比如 DND 没授权时 runSceneAction 仍返回"识别成功"，
 * 只是播报里会说明没办成——技能重播这一步因此会继续往下走，如实标注这个简化）。
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
