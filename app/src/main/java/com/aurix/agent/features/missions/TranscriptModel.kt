package com.aurix.agent.features.missions

import com.aurix.agent.core.mission.EventEntity
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionFileEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.ToolCallEntity
import org.json.JSONObject

sealed interface TItem { val key: String }
data class UserMsg(val text: String) : TItem { override val key = "user" }
data class PlanCard(val steps: List<StepEntity>) : TItem { override val key = "plan" }
data class Note(val id: Long, val text: String) : TItem { override val key = "n-$id" }
data class Info(val id: Long, val text: String, val warn: Boolean) : TItem { override val key = "i-$id" }
data class ToolRow(val call: ToolCallEntity) : TItem { override val key = "t-${call.id}" }
data class LiveRow(val text: String, val tool: Boolean) : TItem { override val key = "live" }
data class ResultMsg(val text: String) : TItem { override val key = "result" }
data class ErrorMsg(val text: String) : TItem { override val key = "error" }
data class ApprovalCard(val approvalKey: String, val tool: String, val summary: String, val reason: String) : TItem { override val key = "approval-$approvalKey" }
data class QuestionCard(val qkey: String, val question: String, val options: List<String>) : TItem { override val key = "question-$qkey" }
data class FilesBlock(val files: List<MissionFileEntity>) : TItem { override val key = "files" }

data class DetailState(val mission: MissionEntity? = null, val items: List<TItem> = emptyList(), val model: String? = null)

fun buildDetailState(
    m: MissionEntity?, steps: List<StepEntity>, events: List<EventEntity>, calls: List<ToolCallEntity>, files: List<MissionFileEntity>,
): DetailState {
    if (m == null) return DetailState()
    val timed = mutableListOf<Pair<Long, TItem>>()
    val requests = LinkedHashMap<String, JSONObject>()
    val decided = HashSet<String>()
    val questions = LinkedHashMap<String, JSONObject>()
    val answered = HashSet<String>()
    for (e in events) {
        when (e.type) {
            "QUESTION_ASKED" -> try { val j = JSONObject(e.detail); questions[j.optString("key")] = j } catch (ex: Exception) { }
            "QUESTION_ANSWERED" -> { answered += e.detail.substringBefore('|'); timed += e.ts to Info(e.id, "↩ You: " + e.detail.substringAfter('|').take(120), false) }
            "APPROVAL_REQUESTED" -> try { val j = JSONObject(e.detail); requests[j.optString("key")] = j } catch (ex: Exception) { }
            "APPROVAL_GRANTED", "APPROVAL_DENIED" -> {
                val k = e.detail.substringBefore('|')
                decided += k
                val summary = requests[k]?.optString("summary").orEmpty()
                timed += e.ts to Info(e.id, (if (e.type == "APPROVAL_GRANTED") "✓ Allowed: " else "✗ Denied: ") + summary, e.type == "APPROVAL_DENIED")
            }
            "ASSISTANT_NOTE" -> timed += e.ts to Note(e.id, e.detail)
            "RECOVERY_STARTED" -> timed += e.ts to Info(e.id, "↻ " + e.detail, true)
            "PLAN_REVISED" -> timed += e.ts to Info(e.id, "⟳ Plan revised (${e.detail})", false)
            "MISSION_RESUMED" -> timed += e.ts to Info(e.id, "▶ Resumed", false)
            "MISSION_PAUSED" -> timed += e.ts to Info(e.id, "⏸ Paused" + if (e.detail.isBlank()) "" else " — ${e.detail.take(160)}", false)
        }
    }
    calls.forEach { timed += it.ts to ToolRow(it) }
    timed.sortBy { it.first }

    val out = mutableListOf<TItem>(UserMsg(m.objective))
    if (steps.isNotEmpty()) out += PlanCard(steps)
    out += timed.map { it.second }
    questions.filterKeys { it !in answered }.forEach { (k, j) -> out += QuestionCard(k, j.optString("question"), j.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) } }.orEmpty()) }
    requests.filterKeys { it !in decided }.forEach { (k, j) -> out += ApprovalCard(k, j.optString("tool"), j.optString("summary"), j.optString("reason")) }
    when (m.status) {
        MissionStatus.PLANNING, MissionStatus.CREATED -> out += LiveRow(if (m.status == MissionStatus.CREATED) m.currentAction.ifBlank { "Queued" } else "Planning…", false)
        MissionStatus.WAITING_FOR_TOOL -> out += LiveRow(m.currentAction, true)
        MissionStatus.RUNNING -> out += LiveRow(m.currentAction.ifBlank { "Working…" }, false)
        MissionStatus.RECOVERING -> out += LiveRow("Recovering…", false)
        MissionStatus.WAITING_FOR_APPROVAL -> {}
        else -> {}
    }
    if (!m.finalResult.isNullOrBlank()) out += ResultMsg(m.finalResult)
    if (files.isNotEmpty()) out += FilesBlock(files)
    if (!m.error.isNullOrBlank() && m.status != MissionStatus.COMPLETED) out += ErrorMsg(m.error)
    val model = events.lastOrNull { it.type == "MODEL_RESPONSE" }?.detail?.substringAfterLast(", ")?.takeIf { it.isNotBlank() }
    return DetailState(m, out, model)
}
