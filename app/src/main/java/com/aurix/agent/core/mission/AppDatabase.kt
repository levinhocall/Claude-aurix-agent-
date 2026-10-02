package com.aurix.agent.core.mission

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MissionEntity::class, StepEntity::class, EventEntity::class, ToolCallEntity::class, MissionFileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun missionDao(): MissionDao
}
