package com.aurix.agent.core.agent

import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.AiErrorType
import com.aurix.agent.core.ai.AiMessage
import com.aurix.agent.core.ai.AiProviderManager
import com.aurix.agent.core.ai.AiRequest
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Task -> Plan -> Execute -> Observe -> Verify -> Recover -> Complete.
 * Every transition is checkpointed in Room, so run(id) can resume an interrupted mission.
 * Phase 1: no tools yet — steps are reasoning/writing only, and the prompts forbid faking tool use.
 */
@Singleton
class AgentRuntime @Inject constructor(
    private val dao: MissionDao,
    private val ai: AiProviderManager,
    private val events: AgentEvents,
) {
    private val limits = AgentLimits()
    private val stopRequests = ConcurrentHashMap<String, MissionStatus>()

    fun requestStop(id: String, status: MissionStatus) { stopRequests[id] = status }

    private class Run(var m: MissionEntity) {
        val startedAt = System.currentTimeMillis()
        var replans = 0
        var verifyRounds = 0
        val recent = ArrayDeque<Int>()
    }

    private class StopMission(val status: MissionStatus, message: String) : Exception(message)

    suspend fun run(missionId: String) {
        val loaded = dao.getMission(missionId) ?: return
        if (loaded.status.isTerminal()) return
        val wasPaused = loaded.status == MissionStatus.PAUSED
        val r = Run(loaded)
        try {
            stopRequests.remove(missionId)
            r.m = save(r.m.copy(status = MissionStatus.RUNNING, error = null, currentAction = "Starting"))
            if (wasPaused) events.emit(missionId, AgentEventType.MISSION_RESUMED)
            if (dao.getSteps(missionId).isEmpty()) plan(r)
            execute(r)
        } catch (e: CancellationException) {
            withContext(NonCancellable) {
                val target = stopRequests.remove(missionId) ?: MissionStatus.PAUSED
                end(missionId, target, if (target == MissionStatus.CANCELLED) "Cancelled" else "Paused", null)
            }
            throw e
        } catch (e: StopMission) {
            end(missionId, e.status, "Failed", e.message)
        } catch (e: AiError) {
            val userFixable = e.type == AiErrorType.AUTH_ERROR || e.type == AiErrorType.NETWORK_ERROR ||
                e.type == AiErrorType.RATE_LIMIT || e.type == AiErrorType.TIMEOUT
            if (userFixable) end(missionId, MissionStatus.PAUSED, "Paused (${e.type.name}) — fix and tap Resume", e.message)
            else end(missionId, MissionStatus.FAILED, "Failed", "${e.type.name}: ${e.message}")
        } catch (e: Exception) {
            end(missionId, MissionStatus.FAILED, "Failed", "UNKNOWN_ERROR: ${e.message?.take(300)}")
        }
    }

    // ---------------------------------------------------------------- planning
    private suspend fun plan(r: Run) {
        r.m = save(r.m.copy(status = MissionStatus.PLANNING, currentAction = "Creating plan"))
        var titles: List<String>? = null
        for (attempt in 1..limits.maxPlanAttempts) {
            titles = parseStepList(model(r, "plan", AgentPrompts.PLAN, "Objective: ${r.m.objective}"))
            if (titles != null) break
            events.emit(r.m.id, AgentEventType.RECOVERY_STARTED, "Plan was not valid JSON (attempt $attempt)")
        }
        val steps = titles ?: throw StopMission(MissionStatus.FAILED, "INVALID_INPUT: could not produce a valid plan")
        dao.upsertSteps(steps.mapIndexed { i, t -> StepEntity(r.m.id, i, t, StepStatus.PENDING) })
        r.m = save(r.m.copy(status = MissionStatus.RUNNING, totalSteps = steps.size, currentAction = "Plan ready"))
        events.emit(r.m.id, AgentEventType.PLAN_CREATED, "${steps.size} steps")
    }

    // ---------------------------------------------------------------- loop
    private suspend fun execute(r: Run) {
        while (true) {
            currentCoroutineContext().ensureActive()
            val steps = dao.getSteps(r.m.id)
            val step = steps.firstOrNull { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }
            if (step == null) {
                if (verify(r, steps)) return
            } else {
                runStep(r, steps, step)
            }
        }
    }

    private suspend fun runStep(r: Run, steps: List<StepEntity>, step: StepEntity) {
        val id = r.m.id
        val attempt = step.attempts + 1
        dao.upsertSteps(listOf(step.copy(status = StepStatus.RUNNING, attempts = attempt)))
        val doneCount = steps.count { it.status == StepStatus.DONE }
        r.m = save(r.m.copy(currentStep = doneCount, totalSteps = steps.size, currentAction = step.title))
        events.emit(id, AgentEventType.STEP_STARTED, "${doneCount + 1}/${steps.size}: ${step.title}")

        val prompt = buildString {
            append("Objective: ${r.m.objective}\n\nPlan:\n")
            append(steps.joinToString("\n") { "${it.idx + 1}. ${it.title} [${it.status}]" })
            append("\n\nCompleted so far:\n")
            append(steps.filter { it.status == StepStatus.DONE }.joinToString("\n") { "- ${it.title}: ${it.result.orEmpty().take(600)}" }.ifEmpty { "(nothing yet)" })
            append("\n\nCurrent step: ${step.title}")
            if (step.attempts > 0 && !step.result.isNullOrBlank()) append("\nPrevious attempt failed: ${step.result}")
        }
        val o = extractJson(model(r, "step", AgentPrompts.STEP, prompt))
        val result = o?.optString("result").orEmpty()
        when {
            o == null -> recover(r, steps, step, attempt, "Model returned invalid JSON")
            o.optString("status") == "done" -> {
                val h = (step.title + "|" + result).hashCode()
                r.recent.addLast(h)
                if (r.recent.size > 6) r.recent.removeFirst()
                dao.upsertSteps(listOf(step.copy(status = StepStatus.DONE, attempts = attempt, result = result)))
                if (r.recent.count { it == h } >= limits.loopRepeatThreshold)
                    throw StopMission(MissionStatus.FAILED, "Loop detected: identical step output repeated")
                events.emit(id, AgentEventType.STEP_COMPLETED, step.title)
                r.m = save(r.m.copy(currentStep = doneCount + 1))
            }
            else -> recover(r, steps, step, attempt, result.ifBlank { "Step blocked" })
        }
    }

    // ---------------------------------------------------------------- recovery
    private suspend fun recover(r: Run, steps: List<StepEntity>, step: StepEntity, attempt: Int, reason: String) {
        val id = r.m.id
        r.m = save(r.m.copy(status = MissionStatus.RECOVERING, recoveries = r.m.recoveries + 1, currentAction = "Recovering: ${step.title}"))
        if (attempt < limits.maxStepAttempts) {
            events.emit(id, AgentEventType.RECOVERY_STARTED, "Retry step ${step.idx + 1}: ${reason.take(150)}")
            dao.upsertSteps(listOf(step.copy(status = StepStatus.PENDING, attempts = attempt, result = reason)))
        } else {
            events.emit(id, AgentEventType.RECOVERY_STARTED, "Replanning after step ${step.idx + 1}: ${reason.take(150)}")
            dao.upsertSteps(listOf(step.copy(status = StepStatus.FAILED, attempts = attempt, result = reason)))
            r.replans += 1
            if (r.replans > limits.maxReplans)
                throw StopMission(MissionStatus.FAILED, "Step '${step.title}' kept failing: ${reason.take(200)}")
            replan(r, steps, step, reason)
        }
        r.m = save(r.m.copy(status = MissionStatus.RUNNING))
    }

    private suspend fun replan(r: Run, steps: List<StepEntity>, failed: StepEntity, reason: String) {
        val id = r.m.id
        val done = steps.filter { it.status == StepStatus.DONE }.joinToString("\n") { "- ${it.title}: ${it.result.orEmpty().take(400)}" }.ifEmpty { "(nothing)" }
        val raw = model(r, "replan", AgentPrompts.REPLAN, "Objective: ${r.m.objective}\nCompleted:\n$done\nFailed step: ${failed.title}\nReason: $reason")
        val titles = parseStepList(raw) ?: throw StopMission(MissionStatus.FAILED, "INVALID_INPUT: could not revise the plan")
        dao.deleteUnfinishedSteps(id)
        val start = (dao.getSteps(id).maxOfOrNull { it.idx } ?: -1) + 1
        dao.upsertSteps(titles.mapIndexed { i, t -> StepEntity(id, start + i, t, StepStatus.PENDING) })
        r.m = save(r.m.copy(totalSteps = dao.getSteps(id).size))
        events.emit(id, AgentEventType.PLAN_REVISED, "${titles.size} new steps")
    }

    // ---------------------------------------------------------------- verification
    /** Returns true when the mission is finished (COMPLETED), false when new gap-steps were queued. */
    private suspend fun verify(r: Run, steps: List<StepEntity>): Boolean {
        val id = r.m.id
        r.m = save(r.m.copy(currentStep = steps.count { it.status == StepStatus.DONE }, currentAction = "Verifying result"))
        val results = steps.joinToString("\n") { "${it.idx + 1}. ${it.title} [${it.status}]: ${it.result.orEmpty().take(1500)}" }
        val o = extractJson(model(r, "verify", AgentPrompts.VERIFY, "Objective: ${r.m.objective}\n\nExecuted steps:\n$results"))
        val summary = o?.optString("final_answer").orEmpty()
        if (o != null && o.optBoolean("satisfied") && summary.isNotBlank()) {
            r.m = save(r.m.copy(finalResult = summary))
            end(id, MissionStatus.COMPLETED, "Completed", null)
            return true
        }
        val gaps = o?.optJSONArray("gaps")?.let { a -> (0 until a.length()).map { a.optString(it).trim() }.filter { it.isNotEmpty() } }.orEmpty()
        r.verifyRounds += 1
        if (r.verifyRounds > limits.maxVerifyRounds || gaps.isEmpty()) {
            if (summary.isNotBlank()) r.m = save(r.m.copy(finalResult = summary))
            throw StopMission(MissionStatus.FAILED, "Verification failed: " + gaps.joinToString("; ").ifEmpty { "verifier rejected the result" }.take(300))
        }
        events.emit(id, AgentEventType.RECOVERY_STARTED, "Verification found ${gaps.size} gap(s); adding steps")
        val start = (steps.maxOfOrNull { it.idx } ?: -1) + 1
        dao.upsertSteps(gaps.take(4).mapIndexed { i, g -> StepEntity(id, start + i, g, StepStatus.PENDING) })
        r.m = save(r.m.copy(totalSteps = dao.getSteps(id).size))
        return false
    }

    // ---------------------------------------------------------------- helpers
    private suspend fun model(r: Run, purpose: String, system: String, user: String): String {
        val m = r.m
        if (m.iterations >= limits.maxIterations) throw StopMission(MissionStatus.FAILED, "Iteration limit reached (${limits.maxIterations})")
        if (System.currentTimeMillis() - r.startedAt > limits.maxMissionMillis) throw StopMission(MissionStatus.FAILED, "Time limit reached for this run")
        if (m.tokensUsed >= limits.maxTokens) throw StopMission(MissionStatus.FAILED, "Token budget reached (${limits.maxTokens})")
        events.emit(m.id, AgentEventType.MODEL_REQUEST, purpose)
        val resp = ai.complete(AiRequest(listOf(AiMessage("system", system), AiMessage("user", user))))
        val used = if (resp.usage.total > 0) resp.usage.total else (user.length + system.length + resp.text.length) / 4
        r.m = save(r.m.copy(iterations = r.m.iterations + 1, tokensUsed = r.m.tokensUsed + used))
        events.emit(m.id, AgentEventType.MODEL_RESPONSE, "$purpose, ~$used tokens")
        return resp.text
    }

    private suspend fun save(m: MissionEntity): MissionEntity {
        val u = m.copy(updatedAt = System.currentTimeMillis())
        dao.updateMission(u)
        return u
    }

    private suspend fun end(id: String, status: MissionStatus, action: String, error: String?) {
        val m = dao.getMission(id) ?: return
        val steps = dao.getSteps(id)
        dao.updateMission(
            m.copy(
                status = status, currentAction = action, error = error,
                currentStep = steps.count { it.status == StepStatus.DONE }, totalSteps = steps.size,
                updatedAt = System.currentTimeMillis(),
            )
        )
        val type = when (status) {
            MissionStatus.COMPLETED -> AgentEventType.MISSION_COMPLETED
            MissionStatus.FAILED -> AgentEventType.MISSION_FAILED
            MissionStatus.CANCELLED -> AgentEventType.MISSION_CANCELLED
            else -> AgentEventType.MISSION_PAUSED
        }
        events.emit(id, type, error.orEmpty())
    }
}
