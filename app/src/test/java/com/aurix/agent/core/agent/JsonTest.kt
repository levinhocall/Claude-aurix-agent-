package com.aurix.agent.core.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class JsonTest {
    @Test fun parsesFencedJson() {
        val raw = "Sure!\n```json\n{\"steps\":[\"a\",\" b \",\"\"]}\n```"
        assertEquals(listOf("a", "b"), parseStepList(raw))
    }
    @Test fun rejectsGarbage() {
        assertNull(parseStepList("no json here"))
        assertNull(parseStepList("{\"steps\":[]}"))
    }
    @Test fun capsStepCount() {
        val raw = "{\"steps\":[" + (1..20).joinToString(",") { "\"s$it\"" } + "]}"
        assertEquals(12, parseStepList(raw)!!.size)
    }
}
