package com.aurix.agent.core.agent

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.aurix.agent.core.background.MissionWorker
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Mission -> persistent queue (WorkManager) -> MissionWorker -> AgentRuntime -> Room checkpoints -> resume.
 * Missions need a network connection to start (queued otherwise); they survive UI close and process death.
 */
@Singleton
class MissionManager @Inject constructor(
    private val dao: MissionDao,
    private val runtime: AgentRuntime,
    private val events: AgentEvents,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val scope: CoroutineScope,
) {
    private fun wm() = WorkManager.getInstance(context)
    private fun workName(id: String) = "mission-$id"

    suspend fun create(objective: String, context: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        dao.insertMission(MissionEntity(id = id, objective = objective, createdAt = now, updatedAt = now, status = MissionStatus.CREATED, currentAction = "Queued"))
        events.emit(id, AgentEventType.MISSION_CREATED, objective.take(120))
        if (!context.isNullOrBlank()) events.emit(id, AgentEventType.CONTEXT_PROVIDED, context.take(480))
        start(id, IntentRouter.route(objective) == null)
        return id
    }

    fun start(id: String, needsNetwork: Boolean = true) {
        val req = OneTimeWorkRequestBuilder<MissionWorker>()
            .setInputData(workDataOf(MissionWorker.KEY_ID to id))
            .setConstraints(Constraints.Builder().apply { if (needsNetwork) setRequiredNetworkType(NetworkType.CONNECTED) }.build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
            .build()
        wm().enqueueUniqueWork(workName(id), ExistingWorkPolicy.KEEP, req)
    }

    fun resume(id: String) { scope.launch { start(id, needsNetworkFor(id)) } }

    private suspend fun needsNetworkFor(id: String): Boolean =
        dao.getMission(id)?.let { IntentRouter.route(it.objective) == null } ?: true
    fun pause(id: String) = stop(id, MissionStatus.PAUSED)
    fun cancel(id: String) = stop(id, MissionStatus.CANCELLED)

    private fun stop(id: String, status: MissionStatus) {
        if (runtime.isRunning(id)) {
            runtime.requestStop(id, status)   // runtime writes the final state when its coroutine is cancelled
            wm().cancelUniqueWork(workName(id))
        } else {
            wm().cancelUniqueWork(workName(id))
            scope.launch {
                val m = dao.getMission(id) ?: return@launch
                if (m.status.isTerminal()) return@launch
                dao.updateMission(m.copy(status = status, currentAction = if (status == MissionStatus.CANCELLED) "Cancelled" else "Paused", updatedAt = System.currentTimeMillis()))
                events.emit(id, if (status == MissionStatus.CANCELLED) AgentEventType.MISSION_CANCELLED else AgentEventType.MISSION_PAUSED)
            }
        }
    }

    /** Process start: re-attach any unfinished mission to the queue (KEEP = no duplicate if WorkManager already has it). */
    fun onAppStart() {
        scope.launch { dao.getActiveMissions().forEach { start(it.id, IntentRouter.route(it.objective) == null) } }
    }
}
