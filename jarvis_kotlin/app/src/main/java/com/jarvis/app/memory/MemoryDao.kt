package com.jarvis.app.memory

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface MemoryDao {
    @Query("SELECT * FROM jarvis_memories ORDER BY timestamp DESC")
    suspend fun getAllMemories(): List<MemoryItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMemory(memory: MemoryItem): Long

    @Query("DELETE FROM jarvis_memories WHERE id = :id")
    suspend fun deleteMemoryById(id: Long)

    @Query("DELETE FROM jarvis_memories")
    suspend fun clearAllMemories()
}
