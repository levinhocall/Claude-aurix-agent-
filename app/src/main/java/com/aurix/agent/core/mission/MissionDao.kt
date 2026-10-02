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
}
