package com.aurix.agent.core.mission

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(entities = [MissionEntity::class, StepEntity::class, EventEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun missionDao(): MissionDao
}
