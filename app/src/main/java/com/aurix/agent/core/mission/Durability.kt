package com.aurix.agent.core.mission

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** One row per mission: the latest durable snapshot used to resume after process death / reboot. */
@Entity(tableName = "mission_checkpoints")
data class CheckpointEntity(
    @PrimaryKey val missionId: String,
    val version: Int,
    val seq: Long,
    val savedAt: Long,
    val status: String,
    val planHash: String,
    val body: String,
)

/** Journal of externally visible actions. INTENDED is written BEFORE the tool runs, so a crash leaves evidence. */
@Entity(tableName = "side_effects", indices = [Index("missionId")])
data class SideEffectEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val missionId: String,
    val stepIdx: Int,
    val tool: String,
    val inputHash: String,
    val summary: String,
    val state: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    val note: String? = null,
)

/** Evidence-based verification outcome (stepIdx = -1 for the whole mission). */
@Entity(tableName = "verifications", indices = [Index("missionId")])
data class VerificationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val missionId: String,
    val stepIdx: Int,
    val state: String,
    val evidence: String,
    val ts: Long,
)

/** One model call: what was picked and why, how long it took, tokens (reported vs estimated) and cost (estimated only). */
@Entity(tableName = "model_calls", indices = [Index("ts"), Index("missionId")])
data class ModelCallEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val missionId: String,
    val ts: Long,
    val purpose: String,
    val model: String,
    val latencyMs: Long,
    val ok: Boolean,
    val errorType: String?,
    val promptTokens: Int,
    val completionTokens: Int,
    val tokensReported: Boolean,
    val costUsdEstimate: Double?,
    val routeNote: String,
    val retried: Boolean,
)
