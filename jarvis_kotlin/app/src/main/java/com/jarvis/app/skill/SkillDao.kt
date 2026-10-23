package com.jarvis.app.skill

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface SkillDao {
    @Query("SELECT * FROM jarvis_skills ORDER BY updatedAt DESC")
    suspend fun getAllSkills(): List<SkillItem>

    @Insert
    suspend fun insertSkill(skill: SkillItem): Long

    @Update
    suspend fun updateSkill(skill: SkillItem)

    @Query("DELETE FROM jarvis_skills WHERE id = :id")
    suspend fun deleteSkillById(id: Long)
}
