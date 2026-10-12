package com.aurix.agent.core.verification

import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.mission.ToolCallEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VerificationTest {
    private fun step(i: Int, st: StepStatus, result: String? = null) = StepEntity("m", i, "s$i", st, result)
    private fun call(id: Long, step: Int, tool: String, ok: Boolean, out: String = "") = ToolCallEntity(id, "m", step, tool, "{}", ok, if (ok) null else "TOOL_ERROR", out, 1, id)
    private fun input(steps: List<StepEntity>, calls: List<ToolCallEntity> = emptyList(), files: Map<String, FileEvidence> = emptyMap(), claimed: String = "", exists: (String) -> Boolean? = { null }) =
        VerifyInput(steps, calls, files, VerificationEngine.deriveCriteria(calls, files.keys.toList()), claimed, exists)

    @Test fun plannedWhenNothingRan() {
        assertEquals(VerificationState.PLANNED, VerificationEngine.assessMission(input(listOf(step(0, StepStatus.PENDING)))).state)
        assertEquals(VerificationState.PLANNED, VerificationEngine.assessStep(step(0, StepStatus.PENDING), emptyList()))
    }

    @Test fun attemptedWhenOnlyFailures() {
        val a = VerificationEngine.assessMission(input(listOf(step(0, StepStatus.RUNNING)), listOf(call(1, 0, "WEB_SEARCH", false))))
        assertEquals(VerificationState.ATTEMPTED, a.state)
    }

    @Test fun executedIsNotVerified() {
        val s = listOf(step(0, StepStatus.DONE, "set"))
        val a = VerificationEngine.assessMission(input(s, listOf(call(1, 0, "SET_ALARM", true, "Alarm set"))))
        assertEquals(VerificationState.EXECUTED, a.state)
    }

    @Test fun modelClaimAloneNeverVerifies() {
        // the model claims a saved file but nothing exists on disk -> not verified
        val s = listOf(step(0, StepStatus.DONE, "wrote report"))
        val a = VerificationEngine.assessMission(input(s, emptyList(), claimed = "I saved the summary to report.txt", exists = { false }))
        assertEquals(VerificationState.FAILED, a.state)
        assertTrue(a.results.any { it.label.contains("report.txt") && !it.passed })
    }

    @Test fun existingFileVerifies() {
        val s = listOf(step(0, StepStatus.DONE))
        val f = mapOf("report.txt" to FileEvidence("report.txt", true, 120, "hello"))
        val a = VerificationEngine.assessMission(input(s, listOf(call(1, 0, "FILE_WRITE", true, "ok\n[verified: report.txt (120 bytes)]")), f, "saved report.txt", { true }))
        assertEquals(VerificationState.VERIFIED, a.state)
        assertEquals(VerificationState.VERIFIED, VerificationEngine.assessStep(s[0], listOf(call(1, 0, "FILE_WRITE", true, "ok\n[verified: report.txt (120 bytes)]"))))
    }

    @Test fun missingTrackedFileFailsOrPartials() {
        val s = listOf(step(0, StepStatus.DONE))
        val gone = mapOf("a.txt" to FileEvidence("a.txt", false, 0, ""))
        assertEquals(VerificationState.FAILED, VerificationEngine.assessMission(input(s, listOf(call(1, 0, "FILE_WRITE", true)), gone)).state)
        val mixed = mapOf("a.txt" to FileEvidence("a.txt", false, 0, ""), "b.txt" to FileEvidence("b.txt", true, 5, "x"))
        assertEquals(VerificationState.PARTIALLY_COMPLETED, VerificationEngine.assessMission(input(s, listOf(call(1, 0, "FILE_WRITE", true)), mixed)).state)
    }

    @Test fun unresolvedFailedStepMeansPartial() {
        val s = listOf(step(0, StepStatus.DONE), step(1, StepStatus.FAILED, "boom"))
        val a = VerificationEngine.assessMission(input(s, listOf(call(1, 0, "WEB_SEARCH", true))))
        assertEquals(VerificationState.PARTIALLY_COMPLETED, a.state)
    }

    @Test fun failedStepReplacedByLaterSuccessIsResolved() {
        val s = listOf(step(0, StepStatus.FAILED, "boom"), step(1, StepStatus.DONE))
        val a = VerificationEngine.assessMission(input(s, listOf(call(1, 0, "WEB_SEARCH", false), call(2, 1, "WEB_SEARCH", true))))
        assertEquals(VerificationState.EXECUTED, a.state)
    }

    @Test fun declinedActionIsReportedHonestly() {
        val a = VerificationEngine.assessMission(input(listOf(step(0, StepStatus.FAILED, VerificationEngine.DECLINED))))
        assertEquals(VerificationState.FAILED, a.state)
        assertTrue(a.notes.any { it.contains("declined") })
    }

    @Test fun answerOnlyMissionIsExecutedWithNote() {
        val a = VerificationEngine.assessMission(input(listOf(step(0, StepStatus.DONE, "answer"))))
        assertEquals(VerificationState.EXECUTED, a.state)
        assertTrue(a.notes.isNotEmpty())
    }

    @Test fun buildMustPassOnFinalCode() {
        val s = listOf(step(0, StepStatus.DONE))
        val pass = call(2, 0, "PROJECT_RUN", true, "EXIT=0\nRESULT=PASS tests=12")
        val edit = call(1, 0, "PROJECT_APPLY_EDIT", true, "edited")
        assertEquals(VerificationState.VERIFIED, VerificationEngine.assessMission(input(s, listOf(edit, pass))).state)
        // edit AFTER the passing run invalidates it
        val lateEdit = call(3, 0, "PROJECT_APPLY_EDIT", true, "edited again")
        val a = VerificationEngine.assessMission(input(s, listOf(edit, pass, lateEdit)))
        assertFalse(a.state == VerificationState.VERIFIED)
        // a failing run is not success even though the tool call itself returned ok
        val fail = call(2, 0, "PROJECT_RUN", true, "EXIT=1\nRESULT=FAIL failed=2")
        assertEquals(VerificationState.FAILED, VerificationEngine.assessMission(input(s, listOf(edit, fail))).state)
        // no run at all
        assertEquals(VerificationState.FAILED, VerificationEngine.assessMission(input(s, listOf(edit))).state)
    }

    @Test fun claimedPathsIgnoresUrlsAndAbsolutePaths() {
        val p = VerificationEngine.claimedPaths("See https://example.com/a.json and /sdcard/x.txt, saved notes.md and src/Main.kt")
        assertEquals(listOf("notes.md", "src/Main.kt"), p)
    }
}
