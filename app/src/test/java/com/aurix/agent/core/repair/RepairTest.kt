package com.aurix.agent.core.repair

import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.AiErrorType
import com.aurix.agent.core.ai.AiMessage
import com.aurix.agent.core.tools.ToolErrorType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RepairTest {
    @Test fun classifiesProviderAndToolErrors() {
        assertEquals(FailureKind.CONTEXT_OVERFLOW, FailureClassifier.classify(AiError(AiErrorType.CONTEXT_OVERFLOW, "x")))
        assertEquals(FailureKind.STRUCTURED_OUTPUT, FailureClassifier.classify(AiError(AiErrorType.INVALID_RESPONSE, "x")))
        assertEquals(FailureKind.PROVIDER_AUTH, FailureClassifier.classify(AiError(AiErrorType.AUTH_ERROR, "x")))
        assertEquals(FailureKind.PROVIDER_TRANSIENT, FailureClassifier.classify(AiError(AiErrorType.RATE_LIMIT, "x")))
        assertEquals(FailureKind.TOOL_INVALID_INPUT, FailureClassifier.classifyTool(ToolErrorType.INVALID_INPUT))
        assertEquals(FailureKind.UNKNOWN, FailureClassifier.classifyTool(null))
    }

    @Test fun ledgerBoundsEveryKindOfRepair() {
        val l = RepairLedger(RepairBudget(maxTotal = 8, maxPerKind = 3, maxBuildRepairs = 3, maxSameFingerprint = 2))
        assertTrue(l.decide(FailureKind.CONTEXT_OVERFLOW, "a").allowed)
        assertTrue(l.decide(FailureKind.CONTEXT_OVERFLOW, "a").allowed)
        val third = l.decide(FailureKind.CONTEXT_OVERFLOW, "a")
        assertFalse(third.allowed); assertEquals(RepairStrategy.STOP, third.strategy) // same failure three times = no progress
        assertEquals(2, l.total)
    }

    @Test fun structuredOutputEscalatesThenStops() {
        val l = RepairLedger()
        assertEquals(RepairStrategy.REPROMPT_STRICT, l.decide(FailureKind.STRUCTURED_OUTPUT, "a").strategy)
        assertEquals(RepairStrategy.SWITCH_MODEL, l.decide(FailureKind.STRUCTURED_OUTPUT, "b").strategy)
        assertEquals(RepairStrategy.SWITCH_MODEL, l.decide(FailureKind.STRUCTURED_OUTPUT, "c").strategy)
        assertFalse(l.decide(FailureKind.STRUCTURED_OUTPUT, "d").allowed) // per-kind limit of 3
    }

    @Test fun totalBudgetStopsSpending() {
        val l = RepairLedger(RepairBudget(maxTotal = 3, maxPerKind = 10, maxSameFingerprint = 10))
        repeat(3) { assertTrue(l.decide(FailureKind.PROVIDER_TRANSIENT, "f$it").allowed) }
        assertFalse(l.decide(FailureKind.CONTEXT_OVERFLOW, "z").allowed)
    }

    @Test fun authAndUnsupportedAreNeverAutoRepaired() {
        val l = RepairLedger()
        assertFalse(l.decide(FailureKind.PROVIDER_AUTH, "k").allowed)
        assertEquals(RepairStrategy.ASK_USER, l.decide(FailureKind.TOOL_PERMISSION, "p").strategy)
        assertFalse(l.decide(FailureKind.UNSUPPORTED, "u").allowed)
        assertEquals(0, l.total)
    }

    @Test fun buildRepairRoundsAreCapped() {
        assertNull(BuildRepair.blockReason(emptyList()))
        assertNull(BuildRepair.blockReason(listOf("a", "b", "c")))
        assertNotNull(BuildRepair.blockReason(listOf("a", "b", "c", "d")))
        assertNotNull(BuildRepair.blockReason(listOf("x", "x", "x")))
    }

    @Test fun buildFingerprintIsStableAcrossLineNumbers() {
        assertEquals("e: A.kt:#:# Unresolved reference: x", FailureClassifier.buildFingerprint("noise\ne: A.kt:12:5 Unresolved reference: x"))
        assertEquals("unknown", FailureClassifier.buildFingerprint("all quiet"))
    }

    @Test fun repairPolicyBlocksWeakeningAndUnrelatedChanges() {
        assertNotNull(RepairPolicy.checkEdit("app/src/main/AndroidManifest.xml", """android:allowBackup="false"""", """android:allowBackup="true"""", null, ""))
        assertNotNull(RepairPolicy.checkEdit("app/src/main/res/xml/network_security_config.xml", """cleartextTrafficPermitted="false"""", """cleartextTrafficPermitted="true"""", null, ""))
        assertNotNull(RepairPolicy.checkEdit("app/src/test/FooTest.kt", "fun t() {", "@Ignore fun t() {", null, ""))
        assertNotNull(RepairPolicy.checkEdit("app/src/test/FooTest.kt", "assertEquals(1, x)", "", null, ""))
        assertNotNull(RepairPolicy.checkEdit("app/src/test/FooTest.kt", "assertEquals(1, x)\nassertTrue(y)", "assertTrue(y)", null, ""))
        assertNotNull(RepairPolicy.checkEdit("local.properties", "a", "b", null, ""))
        assertNull(RepairPolicy.checkEdit("app/src/main/Foo.kt", "val a = 1", "val a = 2", null, ""))
        // scoped to a failure: a file the failure never mentions and this mission never touched is refused
        assertNotNull(RepairPolicy.checkEdit("app/src/Other.kt", "a", "b", setOf("app/src/A.kt"), "A.kt:3: error"))
        assertNull(RepairPolicy.checkEdit("app/src/Other.kt", "a", "b", setOf("app/src/A.kt"), "Other.kt:9: error: boom"))
        assertNull(RepairPolicy.checkEdit("app/src/A.kt", "a", "b", setOf("app/src/A.kt"), "x"))
    }

    @Test fun compactionKeepsAValidMessageSequence() {
        val big = "z".repeat(10_000)
        val msgs = listOf(AiMessage("system", "s"), AiMessage("user", big), AiMessage("assistant", "a1"), AiMessage("tool", "t1"), AiMessage("assistant", "a2"), AiMessage("tool", "t2"), AiMessage("assistant", "a3"), AiMessage("tool", "t3"), AiMessage("tool", "t3b"))
        val out = ContextCompactor.compact(msgs)
        assertEquals("system", out[0].role); assertEquals("user", out[1].role)
        assertTrue(out[1].content.length < 4_000)
        assertFalse("must not start with an orphaned tool reply", out[2].role == "tool")
        assertTrue(out.size < msgs.size)
    }
}
