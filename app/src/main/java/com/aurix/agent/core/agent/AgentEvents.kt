package com.aurix.agent.core.agent

import com.aurix.agent.core.mission.EventEntity
import com.aurix.agent.core.mission.MissionDao
import javax.inject.Inject
import javax.inject.Singleton

enum class AgentEventType {
    MISSION_CREATED, PLAN_CREATED, PLAN_REVISED, STEP_STARTED, STEP_COMPLETED,
    MODEL_REQUEST, MODEL_RESPONSE, TOOL_STARTED, TOOL_COMPLETED, TOOL_FAILED, RECOVERY_STARTED,
    MISSION_PAUSED, MISSION_RESUMED, MISSION_COMPLETED, MISSION_FAILED, MISSION_CANCELLED,
}

/** Persistent event log (survives restarts). Never log secrets here. */
@Singleton
class AgentEvents @Inject constructor(private val dao: MissionDao) {
    suspend fun emit(missionId: String, type: AgentEventType, detail: String = "") {
        dao.insertEvent(EventEntity(missionId = missionId, ts = System.currentTimeMillis(), type = type.name, detail = detail.take(500)))
    }
}
