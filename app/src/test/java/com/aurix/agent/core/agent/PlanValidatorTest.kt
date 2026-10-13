package com.aurix.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PlanValidatorTest {
    private val tools = setOf("WEB_SEARCH", "FILE_WRITE", "SET_ALARM")

    @Test fun keepsAGoodPlanUntouched() {
        val p = PlanValidator.validate(listOf("Find jobs [WEB_SEARCH]", "Write report [FILE_WRITE]"), tools)!!
        assertEquals(listOf("Find jobs [WEB_SEARCH]", "Write report [FILE_WRITE]"), p.steps)
        assertTrue(p.notes.isEmpty())
    }

    @Test fun dropsUnknownToolsAndDuplicates() {
        val p = PlanValidator.validate(listOf("Search [WEB_SEARCH,HACK_PHONE]", "search [WEB_SEARCH]", "Think [NOPE]"), tools)!!
        assertEquals(listOf("Search [WEB_SEARCH]", "Think"), p.steps)
        assertTrue(p.notes.any { it.contains("unknown") } && p.notes.any { it.contains("duplicate") })
    }

    @Test fun boundsStepCountAndLength() {
        val many = (1..20).map { "step $it" }
        assertEquals(PlanValidator.MAX_STEPS, PlanValidator.validate(many, tools)!!.steps.size)
        val long = PlanValidator.validate(listOf("x".repeat(1000)), tools)!!.steps.single()
        assertTrue(long.length <= PlanValidator.MAX_TITLE)
    }

    @Test fun emptyPlanIsRejected() {
        assertNull(PlanValidator.validate(listOf("", "   "), tools))
        assertNull(PlanValidator.validate(emptyList(), tools))
    }
}
