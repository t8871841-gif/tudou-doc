package com.jarvis.app.memory

import androidx.room.Entity
import androidx.room.PrimaryKey

enum class MemoryDomain {
    PROFILE,    // 用户画像（职业、兴趣、身份）
    PREFERENCE, // 偏好与习惯
    FACT,       // 事实与数据
    GENERAL     // 通用信息
}

@Entity(tableName = "jarvis_memories")
data class MemoryItem(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0L,
    val domain: String = MemoryDomain.GENERAL.name,
    val content: String,
    val timestamp: Long = System.currentTimeMillis()
)
