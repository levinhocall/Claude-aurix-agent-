package com.aurix.agent.core.durability

import com.aurix.agent.core.agent.AgentEventType
import com.aurix.agent.core.agent.AgentEvents
import com.aurix.agent.core.approval.QuestionManager
import javax.inject.Inject
import javax.inject.Singleton

/**
 * After a crash/restart, an action whose outcome is unknown (INTENDED/UNCERTAIN) is never repeated automatically.
 * The user is asked what happened; unknown answers fail safe (not repeated). Throws ToolException(TIMEOUT) if unanswered.
 */
@Singleton
class SideEffectReconciler @Inject constructor(
    private val journal: SideEffectJournal,
    private val questions: QuestionManager,
    private val events: AgentEvents,
) {
    suspend fun reconcile(missionId: String): List<String> {
        val notes = mutableListOf<String>()
        for (e in journal.unresolved(missionId)) {
            val answer = questions.ask(missionId, "Before the interruption I tried: ${e.summary}. I can't tell whether it went through (ref ${e.id}). What happened?", ReconcileAnswers.OPTIONS)
            val state = ReconcileAnswers.stateFor(answer)
            journal.resolve(e, state, "user answered")
            events.emit(missionId, AgentEventType.RECOVERY_STARTED, "Reconciled ${e.tool}: $state")
            notes += "${e.tool}: $state"
        }
        return notes
    }
}
