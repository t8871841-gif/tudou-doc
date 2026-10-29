package com.jarvis.app.memory

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MemoryDomain {
    PROFILE,    // 用户画像（职业、兴趣、身份）
    PREFERENCE, // 偏好与习惯
    FACT,       // 事实与数据
    GENERAL     // 通用信息
}

/** 第一梯队批一新增：记忆来源——区分用户明确要求记的，和 Jarvis 自己从聊天里猜的。 */
enum class MemorySource {
    STATED,   // 用户用「记住：」明确要求记的
    INFERRED  // 对话结束后自动提取的，没有用户明确确认过
}

@Entity(tableName = "jarvis_memories")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val domain: String = MemoryDomain.GENERAL.name,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    // 第一梯队批一新增，三个字段均带默认值，兼容旧数据（迁移时补默认值）：
    // 来源——STATED 用户明说的，权重更高、永久保留；INFERRED 是 Jarvis 自己猜的。
    // @ColumnInfo(defaultValue) 必须与 Migration 1→2 里 ALTER TABLE 的 DEFAULT 逐字一致，
    // Room 打开数据库校验表结构时要比对这一项，只写一边会报迁移未正确处理而崩溃。
    @ColumnInfo(defaultValue = "'STATED'")
    val source: String = MemorySource.STATED.name,
    // 重要度——目前只分 1（普通）/2（用户明确要求记的），参与检索排序时加权。
    @ColumnInfo(defaultValue = "1")
    val importance: Int = 1,
    // 过期时间（毫秒时间戳）。null = 永久保留。INFERRED 且模型判断不是长期信息时，
    // 会给一个默认有效期（见 MemoryService.IMPLICIT_DEFAULT_TTL_DAYS），避免临时性
    // 内容（比如「今天有点累」被误判成该记的事）永久占着记忆槽位。
    val expiresAt: Long? = null
)
