package com.aurix.agent.core.agent

import com.aurix.agent.core.mission.MissionStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrainTest {
    @Test fun classifierPhoneCommandIsTrivialAndLocal() {
        val s = IntentClassifier.classify("flashlight on karo")
        assertEquals(TaskCategory.PHONE_COMMAND, s.category)
        assertEquals(Complexity.TRIVIAL, s.complexity)
        assertEquals(TaskRisk.LOW, s.risk)
    }

    @Test fun classifierRisks() {
        assertEquals(TaskRisk.SENSITIVE, IntentClassifier.classify("mom ko message bhejo hello").risk)
        assertEquals(TaskRisk.CRITICAL, IntentClassifier.classify("delete all my old photos").risk)
        assertEquals(TaskRisk.READ_ONLY, IntentClassifier.classify("what is the capital of France?").risk)
    }

    @Test fun classifierCapabilities() {
        val code = IntentClassifier.classify("write a kotlin function to parse json and then add tests")
        assertEquals(TaskCategory.CODING, code.category)
        assertTrue(Capability.CODING in code.capabilities)
        assertEquals(Complexity.COMPLEX, code.complexity)
        val r = IntentClassifier.classify("find the latest news about electric cars")
        assertTrue(r.needsLiveData && Capability.WEB in r.capabilities)
        assertTrue(Capability.VISION in IntentClassifier.classify("is screenshot mein kya likha hai").capabilities)
        assertTrue(IntentClassifier.classify("hi").needsClarification)
    }

    @Test fun contextBuilderKeepsMustHaveAndTrimsLowPriority() {
        val sections = listOf(
            ContextBuilder.Section("OBJECTIVE " + "o".repeat(100), 0, minChars = 50),
            ContextBuilder.Section("DONE " + "d".repeat(2000), 1, minChars = 100),
            ContextBuilder.Section("LATER " + "l".repeat(2000), 2),
            ContextBuilder.Section("CURRENT step", 0, minChars = 10),
        )
        val out = ContextBuilder.build(sections, budgetTokens = 200) // ~640 chars
        assertTrue(out.contains("OBJECTIVE") && out.contains("CURRENT step"))
        assertFalse(out.contains("LATER"))
        assertTrue(out.length <= 800)
        assertEquals(sections.joinToString("\n") { it.text }, ContextBuilder.build(sections, budgetTokens = 5_000))
        assertTrue(ContextBuilder.inputBudget(32_000, 4_096) in 20_000..28_000)
    }

    @Test fun missionStateTransitions() {
        assertFalse(MissionStatus.COMPLETED.canMoveTo(MissionStatus.RUNNING))
        assertFalse(MissionStatus.CANCELLED.canMoveTo(MissionStatus.PAUSED))
        assertTrue(MissionStatus.FAILED.canMoveTo(MissionStatus.RUNNING))
        assertTrue(MissionStatus.RUNNING.canMoveTo(MissionStatus.WAITING_FOR_APPROVAL))
        assertTrue(MissionStatus.PAUSED.canMoveTo(MissionStatus.RUNNING))
    }
}
