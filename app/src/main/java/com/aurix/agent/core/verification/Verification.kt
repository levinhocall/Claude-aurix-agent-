package com.aurix.agent.core.verification

import com.aurix.agent.core.agent.AgentEventType
import com.aurix.agent.core.agent.AgentEvents
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.mission.ToolCallEntity
import com.aurix.agent.core.mission.VerificationEntity
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.Workspace
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

/** PLANNED < ATTEMPTED < EXECUTED < VERIFIED. Only independent evidence reaches VERIFIED; a model saying "done" never does. */
enum class VerificationState { PLANNED, ATTEMPTED, EXECUTED, VERIFIED, PARTIALLY_COMPLETED, FAILED;
    /** Honest label for the UI. */
    fun label() = when (this) {
        PLANNED -> "Planned, not run"; ATTEMPTED -> "Attempted, no success evidence"; EXECUTED -> "Executed, not independently verified"
        VERIFIED -> "Verified"; PARTIALLY_COMPLETED -> "Partially completed"; FAILED -> "Failed"
    }
}

sealed interface Criterion { val label: String }
data class FileExists(val path: String, val minBytes: Long = 1) : Criterion { override val label get() = "file $path exists" }
data class FileContains(val path: String, val text: String) : Criterion { override val label get() = "file $path contains expected text" }
data class ToolSucceeded(val tool: String) : Criterion { override val label get() = "$tool succeeded" }
/** The last real build/test run (tool output carries RESULT=PASS) must have passed AND no edit may have happened after it. */
object PassingRunAfterLastEdit : Criterion { override val label get() = "build/tests pass on the final code" }

data class FileEvidence(val path: String, val exists: Boolean, val bytes: Long, val head: String)

data class VerifyInput(
    val steps: List<StepEntity>,
    val calls: List<ToolCallEntity>,
    val files: Map<String, FileEvidence>,          // evidence read from disk right now
    val criteria: List<Criterion>,
    val claimedText: String = "",                  // the model's own final answer: only used to look for unsupported claims
    val fileExists: (String) -> Boolean? = { null }, // null = cannot check
)

data class CriterionResult(val label: String, val passed: Boolean, val detail: String)
data class Assessment(val state: VerificationState, val summary: String, val results: List<CriterionResult>, val notes: List<String>) {
    fun toJson(): String = JSONObject().put("state", state.name).put("summary", summary)
        .put("results", org.json.JSONArray(results.map { JSONObject().put("c", it.label).put("ok", it.passed).put("d", it.detail) }))
        .put("notes", org.json.JSONArray(notes)).toString()
}

object VerificationEngine {
    private val pathRegex = Regex("""[A-Za-z0-9_\-./]+\.(?:txt|md|csv|json|pdf|docx|xlsx|html|xml|kt|java|py|js|ts|zip)\b""")
    const val DECLINED = "Denied by the user"

    fun claimedPaths(text: String): List<String> =
        pathRegex.findAll(text).map { it.value }.filter { !it.contains("://") && !it.startsWith("www.") && !it.startsWith("/") && it.length <= 120 }.distinct().take(12).toList()

    fun assessStep(step: StepEntity, calls: List<ToolCallEntity>): VerificationState {
        val mine = calls.filter { it.stepIdx == step.idx }
        val ok = mine.filter { it.ok }
        return when (step.status) {
            StepStatus.PENDING -> VerificationState.PLANNED
            StepStatus.RUNNING -> if (mine.isEmpty()) VerificationState.PLANNED else VerificationState.ATTEMPTED
            StepStatus.FAILED, StepStatus.SKIPPED -> VerificationState.FAILED
            StepStatus.DONE -> when {
                ok.any { it.output.contains("[verified:") } -> VerificationState.VERIFIED // the executor checked the produced file on disk
                ok.isNotEmpty() -> VerificationState.EXECUTED
                mine.isEmpty() -> VerificationState.EXECUTED                                // answer-only step: nothing to verify against
                else -> VerificationState.ATTEMPTED                                         // marked done but every tool call failed
            }
        }
    }

    private fun evaluate(c: Criterion, i: VerifyInput): CriterionResult = when (c) {
        is FileExists -> i.files[c.path].let { f -> CriterionResult(c.label, f != null && f.exists && f.bytes >= c.minBytes, if (f == null || !f.exists) "missing" else "${f.bytes} bytes") }
        is FileContains -> i.files[c.path].let { f -> CriterionResult(c.label, f != null && f.exists && f.head.contains(c.text), if (f == null || !f.exists) "missing" else "checked first ${f.head.length} chars") }
        is ToolSucceeded -> CriterionResult(c.label, i.calls.any { it.tool == c.tool && it.ok }, "${i.calls.count { it.tool == c.tool && it.ok }}/${i.calls.count { it.tool == c.tool }} calls ok")
        PassingRunAfterLastEdit -> {
            val runs = i.calls.filter { it.tool == "PROJECT_RUN" }
            val lastRun = runs.maxByOrNull { it.id }
            val lastEdit = i.calls.filter { it.tool == "PROJECT_APPLY_EDIT" && it.ok }.maxByOrNull { it.id }
            when {
                lastRun == null -> CriterionResult(c.label, false, "no build/test command was ever run")
                !lastRun.ok || !lastRun.output.contains("RESULT=PASS") -> CriterionResult(c.label, false, "last run did not pass: " + lastRun.output.lineSequence().firstOrNull { it.startsWith("RESULT=") }.orEmpty())
                lastEdit != null && lastEdit.id > lastRun.id -> CriterionResult(c.label, false, "code was edited after the last passing run; run again")
                else -> CriterionResult(c.label, true, "last run passed")
            }
        }
    }

    /** A step failed and was never replaced by a later successful step = unresolved. */
    fun unresolvedFailures(steps: List<StepEntity>): List<StepEntity> {
        val lastDone = steps.filter { it.status == StepStatus.DONE }.maxOfOrNull { it.idx } ?: -1
        return steps.filter { it.status == StepStatus.FAILED && it.idx > lastDone && it.result != DECLINED }
    }

    fun assessMission(i: VerifyInput): Assessment {
        val notes = mutableListOf<String>()
        val results = i.criteria.map { evaluate(it, i) }.toMutableList()
        // claims in the model's own text that point to files which do not exist
        val tracked = i.files.keys
        for (p in claimedPaths(i.claimedText)) {
            if (tracked.any { it == p || it.endsWith("/$p") || p.endsWith("/$it") }) continue
            if (i.fileExists(p) == false) results += CriterionResult("claimed file $p exists", false, "the answer mentions it but it does not exist")
        }
        val steps = i.steps
        val done = steps.count { it.status == StepStatus.DONE }
        val open = steps.count { it.status == StepStatus.PENDING || it.status == StepStatus.RUNNING }
        val okCalls = i.calls.count { it.ok }
        val declined = steps.isNotEmpty() && steps.all { it.status == StepStatus.FAILED && it.result == DECLINED }
        val unresolved = unresolvedFailures(steps)
        val failedC = results.filter { !it.passed }
        val passedC = results.filter { it.passed }
        val state = when {
            steps.isEmpty() -> VerificationState.PLANNED
            declined -> VerificationState.FAILED.also { notes += "You declined the action; nothing was executed." }
            failedC.isNotEmpty() -> if (passedC.isNotEmpty()) VerificationState.PARTIALLY_COMPLETED else VerificationState.FAILED
            unresolved.isNotEmpty() -> if (done > 0 || okCalls > 0) VerificationState.PARTIALLY_COMPLETED else VerificationState.FAILED
            open > 0 -> if (i.calls.isEmpty() && done == 0) VerificationState.PLANNED else VerificationState.ATTEMPTED
            passedC.isNotEmpty() -> VerificationState.VERIFIED
            done > 0 && okCalls > 0 -> VerificationState.EXECUTED
            done > 0 && i.calls.isEmpty() -> VerificationState.EXECUTED.also { notes += "Answer produced without tools; no independent evidence exists." }
            i.calls.isNotEmpty() -> VerificationState.ATTEMPTED
            else -> VerificationState.FAILED
        }
        if (unresolved.isNotEmpty()) notes += "Unresolved failed step(s): " + unresolved.joinToString { "#${it.idx + 1}" }
        val summary = state.label() + when {
            failedC.isNotEmpty() -> ": " + failedC.take(3).joinToString("; ") { "${it.label} — ${it.detail}" }
            passedC.isNotEmpty() -> ": " + passedC.size + " check(s) passed"
            else -> ""
        }
        return Assessment(state, summary.take(300), results, notes)
    }

    /** Acceptance criteria derived from what really happened (never from what the model says it wants). */
    fun deriveCriteria(calls: List<ToolCallEntity>, trackedFiles: List<String>): List<Criterion> {
        val c = mutableListOf<Criterion>()
        trackedFiles.forEach { c += FileExists(it) }
        if (calls.any { it.tool == "PROJECT_RUN" } || calls.any { it.tool == "PROJECT_APPLY_EDIT" }) c += PassingRunAfterLastEdit
        return c
    }
}

@Singleton
class VerificationService @Inject constructor(
    private val dao: MissionDao,
    private val workspace: Workspace,
    private val events: AgentEvents,
) {
    suspend fun assess(missionId: String, claimed: String): Assessment {
        val steps = dao.getSteps(missionId)
        val calls = dao.getAllToolCallsFor(missionId)
        val tracked = dao.getFiles(missionId)
        val evidence = HashMap<String, FileEvidence>()
        for (f in tracked) {
            evidence[f.path] = try {
                val file = workspace.resolve(missionId, f.path)
                if (file.isFile) FileEvidence(f.path, true, file.length(), file.bufferedReader().use { r -> CharArray(4000).let { b -> String(b, 0, maxOf(0, r.read(b))) } }) else FileEvidence(f.path, false, 0, "")
            } catch (e: ToolException) { FileEvidence(f.path, false, 0, "") } catch (e: java.io.IOException) { FileEvidence(f.path, false, 0, "") }
        }
        val exists: (String) -> Boolean? = { p -> try { workspace.resolve(missionId, p).exists() } catch (e: ToolException) { null } }
        return VerificationEngine.assessMission(VerifyInput(steps, calls, evidence, VerificationEngine.deriveCriteria(calls, tracked.map { it.path }), claimed, exists))
    }

    /** Persists the mission-level result plus one row per step, and tells the transcript. */
    suspend fun record(missionId: String, claimed: String): Assessment {
        val a = assess(missionId, claimed)
        val now = System.currentTimeMillis()
        val steps = dao.getSteps(missionId)
        val calls = dao.getAllToolCallsFor(missionId)
        steps.forEach { dao.insertVerification(VerificationEntity(missionId = missionId, stepIdx = it.idx, state = VerificationEngine.assessStep(it, calls).name, evidence = (it.result ?: "").take(160), ts = now)) }
        dao.insertVerification(VerificationEntity(missionId = missionId, stepIdx = -1, state = a.state.name, evidence = a.toJson(), ts = now))
        events.emit(missionId, AgentEventType.VERIFICATION, JSONObject().put("state", a.state.name).put("summary", a.summary).toString())
        return a
    }
}
