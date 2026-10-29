package com.jarvis.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Query("SELECT * FROM jarvis_memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<MemoryItem>

    // 第一梯队批一：检索/注入只看未过期的（expiresAt 为 null 视为永久，不受影响）。
    @Query("SELECT * FROM jarvis_memories WHERE expiresAt IS NULL OR expiresAt > :now ORDER BY timestamp DESC")
    suspend fun getActiveMemories(now: Long): List<MemoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryItem): Long

    @Query("DELETE FROM jarvis_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM jarvis_memories")
    suspend fun clearAllMemories()

    // 第一梯队批一：清掉已过期的隐式记忆，避免数据库里堆积废弃条目。
    @Query("DELETE FROM jarvis_memories WHERE expiresAt IS NOT NULL AND expiresAt <= :now")
    suspend fun purgeExpired(now: Long): Int
}
