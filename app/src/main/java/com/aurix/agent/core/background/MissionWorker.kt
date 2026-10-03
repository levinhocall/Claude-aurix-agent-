package com.aurix.agent.core.background

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.aurix.agent.core.agent.AgentRuntime
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionStatus
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch

/**
 * Durable executor: WorkManager persists the job, so if Android kills the process the worker is rescheduled and
 * AgentRuntime resumes from the last Room checkpoint. Runs as a foreground (dataSync) service with a notification.
 */
@HiltWorker
class MissionWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val runtime: AgentRuntime,
    private val dao: MissionDao,
    private val notifier: MissionNotifier,
) : CoroutineWorker(appContext, params) {

    override suspend fun getForegroundInfo(): ForegroundInfo {
        val id = inputData.getString(KEY_ID).orEmpty()
        val m = dao.getMission(id)
        val text = if (m == null) "Mission" else m.currentAction.ifBlank { m.objective }
        return notifier.foregroundInfo(id, "AURIX is working", text)
    }

    override suspend fun doWork(): Result {
        val id = inputData.getString(KEY_ID) ?: return Result.failure()
        try {
            setForeground(getForegroundInfo())
        } catch (e: CancellationException) {
            throw e
        } catch (e: IllegalStateException) {
            // Android refused to start a foreground service right now; continue as ordinary background work.
        }
        return coroutineScope {
            val watcher = launch {
                dao.observeMission(id).collect { m ->
                    if (m != null && m.status.isActive()) {
                        try { setForegroundAsync(notifier.foregroundInfo(id, "AURIX is working", m.currentAction.ifBlank { m.objective })) } catch (e: Exception) { }
                    }
                }
            }
            try { runtime.run(id) } finally { watcher.cancel() }
            val m = dao.getMission(id)
            if (m != null) notifier.done(m)
            val transient = m != null && m.status == MissionStatus.PAUSED &&
                (m.currentAction.contains("NETWORK_ERROR") || m.currentAction.contains("TIMEOUT") || m.currentAction.contains("RATE_LIMIT"))
            if (transient && runAttemptCount < 6) Result.retry() else Result.success()
        }
    }

    companion object { const val KEY_ID = "mission_id" }
}
