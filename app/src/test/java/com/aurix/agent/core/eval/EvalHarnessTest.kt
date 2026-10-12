package com.aurix.agent.core.eval

import com.aurix.agent.core.agent.IntentClassifier
import com.aurix.agent.core.agent.IntentRouter
import com.aurix.agent.core.agent.TaskCategory
import com.aurix.agent.core.memory.MemoryEntity
import com.aurix.agent.core.memory.rankMemories
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.mission.ToolCallEntity
import com.aurix.agent.core.verification.FileEvidence
import com.aurix.agent.core.verification.VerificationEngine
import com.aurix.agent.core.verification.VerificationState
import com.aurix.agent.core.verification.VerifyInput
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * P4.4 quality evaluation: repeatable, credential-free golden sets with explicit accuracy thresholds.
 * Metrics are computed from real behaviour of the deterministic components; nothing here trusts a model's self-reported confidence.
 * Live provider checks live in LiveProviderTest and are skipped unless credentials are supplied through the environment.
 */
class EvalHarnessTest {
    private fun report(name: String, ok: Int, total: Int, failures: List<String>) {
        println("EVAL $name: $ok/$total (${"%.0f".format(100.0 * ok / total)}%)" + if (failures.isEmpty()) "" else " failures=$failures")
        assertTrue("$name accuracy ${ok}/$total below threshold; failures=$failures", failures.isEmpty())
    }

    @Test fun routingAccuracy() {
        val golden = listOf(
            "flashlight on" to "FLASHLIGHT", "torch band karo" to "FLASHLIGHT", "Flashlight chalu karo" to "FLASHLIGHT",
            "mujhe kal 8 baje alarm laga" to "SET_ALARM", "set alarm for 6:30 pm" to "SET_ALARM",
            "5 minute ka timer laga" to "SET_TIMER", "timer 30 seconds" to "SET_TIMER", "alarm in 10 minutes" to "SET_TIMER",
        )
        val bad = golden.filter { (q, tool) -> IntentRouter.route(q, 10)?.tool != tool }.map { it.first }
        report("routing-offline", golden.size - bad.size, golden.size, bad)
        // ambiguous / unsupported phrasing must fall through to the AI planner, never be guessed at
        val mustNotRoute = listOf("alarm", "cancel alarm 7 am")
        val wrong = mustNotRoute.filter { IntentRouter.route(it, 10) != null }
        report("routing-no-guess", mustNotRoute.size - wrong.size, mustNotRoute.size, wrong)
    }

    @Test fun capabilityMismatchClassification() {
        val golden = listOf(
            "flashlight on karo" to TaskCategory.PHONE_COMMAND,
            "write a kotlin function to parse json and then add tests" to TaskCategory.CODING,
        )
        val bad = golden.filter { (q, c) -> IntentClassifier.classify(q).category != c }.map { it.first }
        report("task-category", golden.size - bad.size, golden.size, bad)
    }

    @Test fun memoryRetrieval() {
        val items = listOf(
            MemoryEntity(1, "I am vegetarian", "fact", 0, 0, 0), MemoryEntity(2, "My sister is Priya", "fact", 0, 0, 0),
            MemoryEntity(3, "I prefer vegetarian food at restaurants", "fact", 0, 0, 5),
        )
        val cases = listOf(
            Triple("suggest a vegetarian restaurant", listOf(3L, 1L), "both vegetarian facts, most-used first"),
            Triple("call my sister Priya", listOf(2L), "sister fact only"),
            Triple("weather today", emptyList<Long>(), "unrelated query retrieves nothing"),
        )
        val bad = cases.filter { (q, expected, _) -> rankMemories(items, q).map { it.id } != expected }.map { it.third }
        report("memory-retrieval", cases.size - bad.size, cases.size, bad)
    }

    // ---- verification accuracy: the engine must reach the right state from evidence alone
    private fun step(i: Int, st: StepStatus, r: String? = null) = StepEntity("m", i, "s$i", st, r)
    private fun call(id: Long, s: Int, tool: String, ok: Boolean, out: String = "") = ToolCallEntity(id, "m", s, tool, "{}", ok, if (ok) null else "TOOL_ERROR", out, 1, id)
    private fun assess(steps: List<StepEntity>, calls: List<ToolCallEntity> = emptyList(), files: Map<String, FileEvidence> = emptyMap(), claimed: String = "", exists: (String) -> Boolean? = { null }) =
        VerificationEngine.assessMission(VerifyInput(steps, calls, files, VerificationEngine.deriveCriteria(calls, files.keys.toList()), claimed, exists)).state

    @Test fun verificationAccuracy() {
        val pass = "EXIT=0\nRESULT=PASS gradle=BUILD SUCCESSFUL"
        val cases: List<Pair<String, Pair<VerificationState, VerificationState>>> = listOf(
            "nothing ran" to (VerificationState.PLANNED to assess(listOf(step(0, StepStatus.PENDING)))),
            "phone action without proof" to (VerificationState.EXECUTED to assess(listOf(step(0, StepStatus.DONE, "set")), listOf(call(1, 0, "SET_ALARM", true, "Alarm set")))),
            "only failures" to (VerificationState.ATTEMPTED to assess(listOf(step(0, StepStatus.RUNNING)), listOf(call(1, 0, "WEB_SEARCH", false)))),
            "file exists on disk" to (VerificationState.VERIFIED to assess(listOf(step(0, StepStatus.DONE, "wrote")), listOf(call(1, 0, "FILE_WRITE", true, "ok\n[verified: r.txt (5 bytes)]")), mapOf("r.txt" to FileEvidence("r.txt", true, 5, "hello")))),
            "model claims a file that is not there" to (VerificationState.FAILED to assess(listOf(step(0, StepStatus.DONE, "wrote")), emptyList(), emptyMap(), "Saved to report.txt", { false })),
            "one step done, a later one failed" to (VerificationState.PARTIALLY_COMPLETED to assess(listOf(step(0, StepStatus.DONE, "a"), step(1, StepStatus.FAILED, "boom")), listOf(call(1, 0, "WEB_SEARCH", true, "r")))),
            "everything failed" to (VerificationState.FAILED to assess(listOf(step(0, StepStatus.FAILED, "boom")), listOf(call(1, 0, "WEB_SEARCH", false)))),
            "edit then passing run" to (VerificationState.VERIFIED to assess(listOf(step(0, StepStatus.DONE, "fixed")), listOf(call(1, 0, "PROJECT_APPLY_EDIT", true, "EDITED"), call(2, 0, "PROJECT_RUN", true, pass)))),
            "edit after the passing run" to (VerificationState.FAILED to assess(listOf(step(0, StepStatus.DONE, "fixed")), listOf(call(1, 0, "PROJECT_RUN", true, pass), call(2, 0, "PROJECT_APPLY_EDIT", true, "EDITED")))),
            "run never passed" to (VerificationState.FAILED to assess(listOf(step(0, StepStatus.DONE, "fixed")), listOf(call(1, 0, "PROJECT_APPLY_EDIT", true, "EDITED"), call(2, 0, "PROJECT_RUN", true, "EXIT=1\nRESULT=FAIL exit=1")))),
        )
        val bad = cases.filter { it.second.first != it.second.second }.map { "${it.first}: expected ${it.second.first} got ${it.second.second}" }
        report("verification", cases.size - bad.size, cases.size, bad)
    }
}
