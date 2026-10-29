package com.jarvis.app.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [MemoryItem::class], version = 2, exportSchema = false)
abstract class MemoryDatabase : RoomDatabase() {
    abstract fun memoryDao(): MemoryDao

    companion object {
        @Volatile
        private var INSTANCE: MemoryDatabase? = null

        // 正式迁移 1→2：保留旧数据，不清空。旧行补默认值：source 补 STATED、
        // importance 补 1、expiresAt 补空（NULL）。NOT NULL 列加字段必须带 DEFAULT，
        // 否则 SQLite 拒绝执行；DEFAULT 须与 MemoryItem 的 @ColumnInfo 逐字一致。
        // （原合集用清空重建式迁移，已改掉：真机上已有记忆，
        // 不能按“没有真实用户数据”处理。）
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE jarvis_memories ADD COLUMN source TEXT NOT NULL DEFAULT 'STATED'")
                db.execSQL("ALTER TABLE jarvis_memories ADD COLUMN importance INTEGER NOT NULL DEFAULT 1")
                db.execSQL("ALTER TABLE jarvis_memories ADD COLUMN expiresAt INTEGER")
            }
        }

        fun getDatabase(context: Context): MemoryDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    MemoryDatabase::class.java,
                    "jarvis_memory.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
