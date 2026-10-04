package com.aurix.agent.core.approval

import com.aurix.agent.core.agent.AgentEventType
import com.aurix.agent.core.agent.AgentEvents
import com.aurix.agent.core.background.MissionNotifier
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** Lets the agent ask the user a short question (ambiguity, missing info). Persisted like approvals, so it survives restarts. */
@Singleton
class QuestionManager @Inject constructor(
    private val dao: MissionDao,
    private val events: AgentEvents,
    private val notifier: MissionNotifier,
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<String>>()

    suspend fun ask(missionId: String, question: String, options: List<String>): String {
        val key = Integer.toHexString((question + "|" + options.joinToString("|")).hashCode())
        answerFor(missionId, key)?.let { return it }
        val wkey = "$missionId:$key"
        val waiter = CompletableDeferred<String>()
        waiters[wkey] = waiter
        try {
            answerFor(missionId, key)?.let { return it }
            val asked = dao.getQuestionEvents(missionId).any {
                it.type == "QUESTION_ASKED" && (try { JSONObject(it.detail).optString("key") == key } catch (e: Exception) { false })
            }
            if (!asked) {
                events.emit(
                    missionId, AgentEventType.QUESTION_ASKED,
                    JSONObject().put("key", key).put("question", question.take(200)).put("options", JSONArray(options.take(5).map { it.take(40) })).toString(),
                )
            }
            dao.getMission(missionId)?.let { m ->
                dao.updateMission(m.copy(status = MissionStatus.WAITING_FOR_APPROVAL, currentAction = "Waiting for your answer", updatedAt = System.currentTimeMillis()))
                notifier.approvalNeeded(m, question)
            }
            return withTimeoutOrNull(TIMEOUT_MS) { waiter.await() }
                ?: throw ToolException(ToolErrorType.TIMEOUT, "The user did not answer in time")
        } finally {
            waiters.remove(wkey)
        }
    }

    suspend fun answer(missionId: String, key: String, text: String) {
        events.emit(missionId, AgentEventType.QUESTION_ANSWERED, "$key|${text.take(300)}")
        waiters["$missionId:$key"]?.complete(text)
    }

    private suspend fun answerFor(missionId: String, key: String): String? =
        dao.getQuestionEvents(missionId).lastOrNull { it.type == "QUESTION_ANSWERED" && it.detail.substringBefore('|') == key }?.detail?.substringAfter('|')

    private companion object { const val TIMEOUT_MS = 30 * 60_000L }
}
