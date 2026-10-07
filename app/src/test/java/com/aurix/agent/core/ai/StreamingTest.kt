package com.aurix.agent.core.ai

import com.aurix.agent.core.agent.extractSay
import com.aurix.agent.core.ai.routing.Capabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingTest {
    @Test fun sayIsExtractedFromPartialJson() {
        assertEquals("Searching the web", extractSay("{\"action\":\"tool\",\"say\":\"Searching the web\",\"tool\":\"WEB_SE"))
        assertEquals("Searching the", extractSay("{\"action\":\"tool\",\"say\":\"Searching the"))
        assertEquals("He said \"hi\"", extractSay("{\"say\":\"He said \\\"hi\\\"\",\"x\":1}"))
        assertNull(extractSay("{\"action\":\"finish\""))
        assertNull(extractSay("{\"say\":\"ab"))
    }

    @Test fun jsonModeCapabilities() {
        assertTrue(Capabilities.jsonMode("https://api.openai.com/v1", "gpt-4o"))
        assertTrue(Capabilities.jsonMode("https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-pro"))
        assertTrue(Capabilities.jsonMode("https://api.groq.com/openai/v1", "llama-3.3-70b-versatile"))
        assertFalse(Capabilities.jsonMode("https://api.groq.com/openai/v1", "openai/gpt-oss-120b"))
        assertFalse(Capabilities.jsonMode("https://openrouter.ai/api/v1", "anthropic/claude-sonnet-4.5"))
        assertFalse(Capabilities.jsonMode("http://192.168.1.5:11434/v1", "llama3.2"))
    }
}
