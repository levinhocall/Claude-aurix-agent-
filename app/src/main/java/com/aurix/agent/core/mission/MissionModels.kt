package com.aurix.agent.core.mission

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

enum class MissionStatus {
    CREATED, PLANNING, RUNNING, WAITING_FOR_TOOL, WAITING_FOR_APPROVAL, RECOVERING, PAUSED, COMPLETED, FAILED, CANCELLED;

    fun isTerminal() = this == COMPLETED || this == FAILED || this == CANCELLED
    fun isActive() = this == CREATED || this == PLANNING || this == RUNNING || this == WAITING_FOR_TOOL || this == RECOVERING
}

enum class StepStatus { PENDING, RUNNING, DONE, FAILED, SKIPPED }

@Entity(tableName = "missions")
data class MissionEntity(
    @PrimaryKey val id: String,
    val objective: String,
    val createdAt: Long,
    val updatedAt: Long,
    val status: MissionStatus,
    val priority: Int = 0,
    val currentStep: Int = 0,
    val totalSteps: Int = 0,
    val currentAction: String = "",
    val finalResult: String? = null,
    val error: String? = null,
    val tokensUsed: Int = 0,
    val iterations: Int = 0,
    val recoveries: Int = 0,
)

@Entity(
    tableName = "mission_steps",
    primaryKeys = ["missionId", "idx"],
    foreignKeys = [ForeignKey(MissionEntity::class, ["id"], ["missionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("missionId")],
)
data class StepEntity(
    val missionId: String,
    val idx: Int,
    val title: String,
    val status: StepStatus,
    val result: String? = null,
    val attempts: Int = 0,
)

@Entity(
    tableName = "mission_events",
    foreignKeys = [ForeignKey(MissionEntity::class, ["id"], ["missionId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("missionId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val missionId: String,
    val ts: Long,
    val type: String,
    val detail: String,
)
