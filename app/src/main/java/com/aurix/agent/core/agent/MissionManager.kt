package com.aurix.agent.core.agent

import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.di.ApplicationScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns running mission jobs. Phase 1 runs missions in the app process only:
 * if Android kills the process, state is safe in Room and the mission shows PAUSED (Resume).
 * Phase 4 moves execution into WorkManager / a foreground service.
 */
@Singleton
class MissionManager @Inject constructor(
    private val dao: MissionDao,
    private val runtime: AgentRuntime,
    private val events: AgentEvents,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private val jobs = ConcurrentHashMap<String, Job>()

    suspend fun create(objective: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertMission(MissionEntity(id = id, objective = objective, createdAt = now, updatedAt = now, status = MissionStatus.CREATED, currentAction = "Queued"))
        events.emit(id, AgentEventType.MISSION_CREATED, objective.take(120))
        start(id)
        return id
    }

    fun start(id: String) {
        if (jobs[id]?.isActive == true) return
        val job = scope.launch { runtime.run(id) }
        jobs[id] = job
        job.invokeOnCompletion { jobs.remove(id, job) }
    }

    fun resume(id: String) = start(id)
    fun pause(id: String) = stop(id, MissionStatus.PAUSED)
    fun cancel(id: String) = stop(id, MissionStatus.CANCELLED)

    private fun stop(id: String, status: MissionStatus) {
        val job = jobs[id]
        if (job != null && job.isActive) {
            runtime.requestStop(id, status)
            job.cancel()
        } else {
            scope.launch {
                val m = dao.getMission(id) ?: return@launch
                if (m.status.isTerminal()) return@launch
                dao.updateMission(m.copy(status = status, currentAction = if (status == MissionStatus.CANCELLED) "Cancelled" else "Paused", updatedAt = System.currentTimeMillis()))
                events.emit(id, if (status == MissionStatus.CANCELLED) AgentEventType.MISSION_CANCELLED else AgentEventType.MISSION_PAUSED)
            }
        }
    }

    /** Called once per process start: anything that was mid-run lost its executor, so say so honestly. */
    fun onAppStart() {
        scope.launch {
            dao.markInterrupted("Interrupted: app process was stopped. Tap Resume to continue.", System.currentTimeMillis())
        }
    }
}
