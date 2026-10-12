package com.aurix.agent.core.durability

import com.aurix.agent.core.mission.CheckpointEntity
import com.aurix.agent.core.mission.EventEntity
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.Migrations
import com.aurix.agent.core.mission.ModelCallEntity
import com.aurix.agent.core.mission.SideEffectEntity
import com.aurix.agent.core.mission.StepEntity
import com.aurix.agent.core.mission.StepStatus
import com.aurix.agent.core.mission.VerificationEntity
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.ToolErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.sql.DriverManager

class DurabilityTest {
    private val now = 1_800_000_000_000L
    private fun mission(status: MissionStatus = MissionStatus.RUNNING) = MissionEntity("m1", "obj", now, now, status)
    private fun steps(vararg s: StepStatus) = s.mapIndexed { i, st -> StepEntity("m1", i, "step $i", st) }
    private fun cp(st: List<StepEntity>, at: Long = now, replans: Int = 2, vr: Int = 1, version: Int = CheckpointCodec.VERSION): CheckpointEntity {
        val snap = CheckpointCodec.snapshot(st)
        val body = CheckpointBody(version, 5, at, "RUNNING", replans, vr, 0, 3, 100, snap, "n")
        return CheckpointEntity("m1", version, 5, at, "RUNNING", CheckpointCodec.planHash(snap), CheckpointCodec.toJson(body))
    }

    // ---- side effects
    @Test fun nonIdempotentClassification() {
        assertTrue(SideEffectPolicy.isNonIdempotent("SEND_SMS", RiskLevel.HIGH))
        assertTrue(SideEffectPolicy.isNonIdempotent("SET_ALARM", RiskLevel.LOW))
        assertTrue(SideEffectPolicy.isNonIdempotent("SCREEN_TAP", RiskLevel.MEDIUM))
        assertTrue(SideEffectPolicy.isNonIdempotent("STORAGE_DELETE", RiskLevel.CRITICAL))
        assertTrue(SideEffectPolicy.isNonIdempotent("SOME_NEW_TOOL", RiskLevel.HIGH))
        assertFalse(SideEffectPolicy.isNonIdempotent("SCREEN_READ", RiskLevel.LOW))
        assertFalse(SideEffectPolicy.isNonIdempotent("STORAGE_LIST", RiskLevel.LOW))
        assertFalse(SideEffectPolicy.isNonIdempotent("WEATHER", RiskLevel.LOW))
        assertFalse(SideEffectPolicy.isNonIdempotent("FLASHLIGHT", RiskLevel.LOW))
    }

    @Test fun outcomeStates() {
        assertEquals(SideEffectState.COMPLETED, SideEffectPolicy.stateAfter(true, null))
        assertEquals(SideEffectState.UNCERTAIN, SideEffectPolicy.stateAfter(false, ToolErrorType.TIMEOUT))
        assertEquals(SideEffectState.UNCERTAIN, SideEffectPolicy.stateAfter(false, ToolErrorType.NETWORK_ERROR))
        assertEquals(SideEffectState.FAILED, SideEffectPolicy.stateAfter(false, ToolErrorType.INVALID_INPUT))
        assertEquals(SideEffectState.FAILED, SideEffectPolicy.stateAfter(false, ToolErrorType.PERMISSION_REQUIRED))
    }

    private fun fx(tool: String, hash: String, state: String, id: Long = 1) = SideEffectEntity(id, "m1", 0, tool, hash, "s", state, now)

    @Test fun duplicatesAreBlockedUnlessKnownNotDone() {
        val list = listOf(fx("SEND_SMS", "a", SideEffectState.COMPLETED), fx("SEND_SMS", "b", SideEffectState.FAILED, 2), fx("SEND_SMS", "c", SideEffectState.RECONCILED_NOT_DONE, 3), fx("SEND_SMS", "d", SideEffectState.UNCERTAIN, 4), fx("SEND_SMS", "e", SideEffectState.INTENDED, 5))
        assertNotNull(SideEffectPolicy.duplicateOf(list, "SEND_SMS", "a"))
        assertNull(SideEffectPolicy.duplicateOf(list, "SEND_SMS", "b"))
        assertNull(SideEffectPolicy.duplicateOf(list, "SEND_SMS", "c"))
        assertNotNull(SideEffectPolicy.duplicateOf(list, "SEND_SMS", "d"))
        assertNotNull(SideEffectPolicy.duplicateOf(list, "SEND_SMS", "e"))
        assertNull(SideEffectPolicy.duplicateOf(list, "CALL_PHONE", "a"))
        assertEquals(listOf(4L, 5L), SideEffectPolicy.unresolved(list).map { it.id })
    }

    @Test fun reconcileAnswersFailSafe() {
        assertEquals(SideEffectState.RECONCILED_DONE, ReconcileAnswers.stateFor(ReconcileAnswers.DONE))
        assertEquals(SideEffectState.RECONCILED_NOT_DONE, ReconcileAnswers.stateFor(ReconcileAnswers.NOT_DONE))
        assertEquals(SideEffectState.SKIPPED, ReconcileAnswers.stateFor(ReconcileAnswers.SKIP))
        assertEquals(SideEffectState.SKIPPED, ReconcileAnswers.stateFor("pata nahi"))
        assertEquals(SideEffectState.SKIPPED, ReconcileAnswers.stateFor(""))
        ReconcileAnswers.OPTIONS.forEach { assertTrue("option too long for QuestionManager (40)", it.length <= 40) }
    }

    // ---- checkpoints
    @Test fun codecRoundTrip() {
        val st = steps(StepStatus.DONE, StepStatus.RUNNING, StepStatus.PENDING)
        val c = cp(st)
        val b = CheckpointCodec.fromJson(c.body)!!
        assertEquals(2, b.replans); assertEquals(3, b.steps.size); assertEquals("DONE", b.steps[0].status)
        assertNull(CheckpointCodec.fromJson("{not json"))
        assertNull(CheckpointCodec.fromJson("{}"))
    }

    @Test fun validResumeRestoresCounters() {
        val st = steps(StepStatus.DONE, StepStatus.RUNNING)
        val d = CheckpointValidator.validate(cp(st), st, mission(), true, now + 1000)
        assertEquals(ResumeAction.RESUME, d.action); assertEquals(2, d.replans); assertEquals(1, d.verifyRounds)
    }

    @Test fun untrustedCheckpointsAreRebuilt() {
        val st = steps(StepStatus.DONE, StepStatus.PENDING)
        assertEquals(ResumeAction.REBUILD, CheckpointValidator.validate(null, st, mission(), true, now).action)
        assertEquals(ResumeAction.REBUILD, CheckpointValidator.validate(cp(st).copy(body = "garbage"), st, mission(), true, now).action)
        assertEquals(ResumeAction.REBUILD, CheckpointValidator.validate(cp(st).copy(planHash = "tampered"), st, mission(), true, now).action)
        assertEquals(ResumeAction.REBUILD, CheckpointValidator.validate(cp(st, version = 99), st, mission(), true, now).action)
        assertEquals(ResumeAction.REBUILD, CheckpointValidator.validate(cp(st, at = now + 3_600_000), st, mission(), true, now).action)
        // checkpoint says step 0 was DONE but the table says PENDING -> do not trust
        val regressed = steps(StepStatus.PENDING, StepStatus.PENDING)
        assertEquals(ResumeAction.REBUILD, CheckpointValidator.validate(cp(st), regressed, mission(), true, now).action)
    }

    @Test fun staleCheckpointNeedsManualResumeOnlyWhenAutomatic() {
        val st = steps(StepStatus.DONE, StepStatus.PENDING)
        val old = cp(st, at = now - 25L * 3600 * 1000)
        assertEquals(ResumeAction.MANUAL, CheckpointValidator.validate(old, st, mission(), true, now).action)
        assertEquals(ResumeAction.RESUME, CheckpointValidator.validate(old, st, mission(MissionStatus.PAUSED), false, now).action)
    }

    @Test fun otherMissionCheckpointRejected() {
        val st = steps(StepStatus.PENDING)
        assertEquals(ResumeAction.REJECT, CheckpointValidator.validate(cp(st).copy(missionId = "other"), st, mission(), true, now).action)
        assertEquals(ResumeAction.REJECT, CheckpointValidator.validate(cp(st), st, mission(MissionStatus.COMPLETED), true, now).action)
    }

    // ---- approval freshness
    private fun ev(type: String, detail: String, ts: Long) = EventEntity(0, "m1", ts, type, detail)

    @Test fun approvalFreshness() {
        assertEquals("k" to null, ApprovalFreshness.resolve(emptyList(), "k", RiskLevel.HIGH, now))
        val fresh = listOf(ev("APPROVAL_GRANTED", "k|allowed", now - 60_000))
        assertEquals("k" to true, ApprovalFreshness.resolve(fresh, "k", RiskLevel.HIGH, now))
        val stale = listOf(ev("APPROVAL_GRANTED", "k|allowed", now - 16 * 60_000))
        assertEquals("k~1" to null, ApprovalFreshness.resolve(stale, "k", RiskLevel.HIGH, now))
        val renewed = stale + ev("APPROVAL_GRANTED", "k~1|allowed", now - 1000)
        assertEquals("k~1" to true, ApprovalFreshness.resolve(renewed, "k", RiskLevel.HIGH, now))
        val denied = listOf(ev("APPROVAL_DENIED", "k|denied", now - 99 * 3_600_000L))
        assertEquals("k" to false, ApprovalFreshness.resolve(denied, "k", RiskLevel.HIGH, now))
    }

    @Test fun criticalApprovalsExpireQuickly() {
        val g = listOf(ev("APPROVAL_GRANTED", "k|allowed", now - 3 * 60_000))
        assertEquals("k~1" to null, ApprovalFreshness.resolve(g, "k", RiskLevel.CRITICAL, now))
        assertEquals("k" to true, ApprovalFreshness.resolve(g, "k", RiskLevel.HIGH, now))
    }

    // ---- migration SQL runs on a real SQLite and matches the entity fields
    @Test fun migration3to4MatchesEntities() {
        DriverManager.getConnection("jdbc:sqlite::memory:").use { c ->
            c.createStatement().use { st ->
                st.execute("CREATE TABLE missions (id TEXT PRIMARY KEY NOT NULL)")
                st.execute("INSERT INTO missions VALUES ('keep-me')")
                Migrations.SQL_3_4.forEach { st.execute(it) }
                Migrations.SQL_3_4.forEach { st.execute(it) } // idempotent
            }
            fun cols(t: String): Set<String> = c.createStatement().use { s -> s.executeQuery("PRAGMA table_info($t)").use { r -> buildSet { while (r.next()) add(r.getString("name")) } } }
            fun fields(k: Class<*>): Set<String> = k.declaredFields.filter { !it.isSynthetic && !java.lang.reflect.Modifier.isStatic(it.modifiers) }.map { it.name }.toSet()
            assertEquals(fields(CheckpointEntity::class.java), cols("mission_checkpoints"))
            assertEquals(fields(SideEffectEntity::class.java), cols("side_effects"))
            assertEquals(fields(VerificationEntity::class.java), cols("verifications"))
            assertEquals(fields(ModelCallEntity::class.java), cols("model_calls"))
            c.createStatement().use { s -> s.executeQuery("SELECT count(*) FROM missions").use { r -> r.next(); assertEquals(1, r.getInt(1)) } }
        }
    }
}
