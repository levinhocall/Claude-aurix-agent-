package com.aurix.agent.core.mission

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MissionEntity::class, StepEntity::class, EventEntity::class, ToolCallEntity::class, MissionFileEntity::class, com.aurix.agent.core.memory.MemoryEntity::class, CheckpointEntity::class, SideEffectEntity::class, VerificationEntity::class, ModelCallEntity::class], version = 4, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun missionDao(): MissionDao
}
