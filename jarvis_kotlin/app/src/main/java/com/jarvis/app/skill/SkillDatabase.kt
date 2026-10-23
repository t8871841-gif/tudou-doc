package com.jarvis.app.skill

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/** 技能独立库（jarvis_skills.db），不与批三的记忆库混用，互不影响升级。 */
@Database(entities = [SkillItem::class], version = 1, exportSchema = false)
abstract class SkillDatabase : RoomDatabase() {
    abstract fun skillDao(): SkillDao

    companion object {
        @Volatile
        private var INSTANCE: SkillDatabase? = null

        fun getDatabase(context: Context): SkillDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SkillDatabase::class.java,
                    "jarvis_skills.db"
                ).build()
                INSTANCE = instance
                instance
            }
        }
    }
}
