package com.aurix.agent.core.mission

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface MissionDao {
    @Insert suspend fun insertMission(m: MissionEntity)
    @Update suspend fun updateMission(m: MissionEntity)

    @Query("SELECT * FROM missions ORDER BY createdAt DESC") fun observeMissions(): Flow<List<MissionEntity>>
    @Query("SELECT * FROM missions WHERE id = :id") fun observeMission(id: String): Flow<MissionEntity?>
    @Query("SELECT * FROM missions WHERE id = :id") suspend fun getMission(id: String): MissionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSteps(steps: List<StepEntity>)
    @Query("SELECT * FROM mission_steps WHERE missionId = :id ORDER BY idx") fun observeSteps(id: String): Flow<List<StepEntity>>
    @Query("SELECT * FROM mission_steps WHERE missionId = :id ORDER BY idx") suspend fun getSteps(id: String): List<StepEntity>
    @Query("DELETE FROM mission_steps WHERE missionId = :id AND status IN ('PENDING','RUNNING')") suspend fun deleteUnfinishedSteps(id: String)

    @Insert suspend fun insertEvent(e: EventEntity)
    @Query("SELECT * FROM mission_events WHERE missionId = :id ORDER BY id DESC LIMIT 200") fun observeEvents(id: String): Flow<List<EventEntity>>

    @Query("UPDATE missions SET status = 'PAUSED', currentAction = :msg, updatedAt = :now WHERE status IN ('PLANNING','RUNNING','RECOVERING','WAITING_FOR_TOOL')")
    suspend fun markInterrupted(msg: String, now: Long): Int

    @Insert suspend fun insertToolCall(c: ToolCallEntity)
    @Query("SELECT * FROM tool_calls WHERE missionId = :id ORDER BY id DESC LIMIT 100") fun observeToolCalls(id: String): Flow<List<ToolCallEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertFile(f: MissionFileEntity)
    @Query("SELECT * FROM mission_files WHERE missionId = :id ORDER BY createdAt") fun observeFiles(id: String): Flow<List<MissionFileEntity>>
    @Query("SELECT * FROM mission_files WHERE missionId = :id ORDER BY createdAt") suspend fun getFiles(id: String): List<MissionFileEntity>

    @Query("SELECT * FROM mission_events WHERE missionId = :id ORDER BY id ASC") fun observeEventsAsc(id: String): Flow<List<EventEntity>>
    @Query("SELECT * FROM tool_calls WHERE missionId = :id ORDER BY id ASC") fun observeToolCallsAsc(id: String): Flow<List<ToolCallEntity>>
    @Query("SELECT * FROM missions WHERE status IN ('CREATED','PLANNING','RUNNING','WAITING_FOR_TOOL','WAITING_FOR_APPROVAL','RECOVERING')") suspend fun getActiveMissions(): List<MissionEntity>

    @Query("SELECT * FROM mission_events WHERE missionId = :id AND type LIKE 'APPROVAL_%' ORDER BY id ASC") suspend fun getApprovalEvents(id: String): List<EventEntity>

    @Query("SELECT * FROM mission_events WHERE missionId = :id AND type LIKE 'QUESTION_%' ORDER BY id ASC") suspend fun getQuestionEvents(id: String): List<EventEntity>

    @Query("SELECT detail FROM mission_events WHERE missionId = :id AND type = 'CONTEXT_PROVIDED' ORDER BY id LIMIT 1") suspend fun getContext(id: String): String?

    @Query("SELECT * FROM tool_calls ORDER BY ts DESC LIMIT 5000") suspend fun getAllToolCalls(): List<ToolCallEntity>
    @Query("SELECT * FROM mission_events WHERE type LIKE 'APPROVAL_%' OR type LIKE 'QUESTION_%' OR type IN ('MISSION_CREATED','MISSION_COMPLETED','MISSION_FAILED','MISSION_CANCELLED','MISSION_PAUSED','MISSION_RESUMED') ORDER BY ts DESC LIMIT 5000") suspend fun getAuditEvents(): List<EventEntity>

    @Insert suspend fun insertMemory(m: com.aurix.agent.core.memory.MemoryEntity): Long
    @Query("SELECT * FROM memories ORDER BY lastUsedAt DESC LIMIT 300") suspend fun allMemories(): List<com.aurix.agent.core.memory.MemoryEntity>
    @Query("SELECT * FROM memories ORDER BY lastUsedAt DESC LIMIT 100") fun observeMemories(): Flow<List<com.aurix.agent.core.memory.MemoryEntity>>
    @Query("SELECT * FROM memories WHERE kind = :kind") suspend fun memoriesOfKind(kind: String): List<com.aurix.agent.core.memory.MemoryEntity>
    @Query("DELETE FROM memories WHERE id = :id") suspend fun deleteMemory(id: Long)
    @Query("DELETE FROM memories") suspend fun clearMemories()
    @Query("UPDATE memories SET lastUsedAt = :now, uses = uses + 1 WHERE id IN (:ids)") suspend fun touchMemories(ids: List<Long>, now: Long)

    @Query("SELECT * FROM missions WHERE id != :excl AND status IN ('FAILED','PAUSED','CANCELLED') ORDER BY updatedAt DESC LIMIT 1") suspend fun lastProblemMission(excl: String): MissionEntity?
    @Query("SELECT * FROM tool_calls WHERE missionId = :id AND ok = 0 ORDER BY id DESC LIMIT 1") suspend fun lastFailedToolCall(id: String): ToolCallEntity?
}
