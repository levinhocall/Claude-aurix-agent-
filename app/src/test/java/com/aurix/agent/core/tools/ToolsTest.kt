package com.aurix.agent.core.tools

import com.aurix.agent.core.tools.web.decodeDdgUrl
import com.aurix.agent.core.tools.web.isPrivateAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.InetAddress

class ToolsTest {
    @Test fun calculatorBasics() {
        assertEquals(14.0, evalExpression("2+3*4"), 1e-9)
        assertEquals(9.0, evalExpression("(1+2)^2"), 1e-9)
        assertEquals(4.0, evalExpression("sqrt(16)"), 1e-9)
        assertEquals(-3.0, evalExpression("-1-2"), 1e-9)
        assertEquals(512.0, evalExpression("2^3^2"), 1e-9)
    }

    @Test fun calculatorRejectsBadInput() {
        assertThrows(ToolException::class.java) { evalExpression("1/0") }
        assertThrows(ToolException::class.java) { evalExpression("2+") }
        assertThrows(ToolException::class.java) { evalExpression("foo(2)") }
    }

    @Test fun workspaceBlocksTraversal() {
        val base = File(System.getProperty("java.io.tmpdir"), "aurix-ws-test").apply { mkdirs() }
        assertThrows(ToolException::class.java) { resolveSafe(base, "../evil.txt") }
        assertThrows(ToolException::class.java) { resolveSafe(base, "a/../../evil.txt") }
        assertThrows(ToolException::class.java) { resolveSafe(base, "") }
        assertTrue(resolveSafe(base, "/ok/report.md").path.endsWith("report.md"))
    }

    @Test fun ssrfPrivateRanges() {
        listOf("127.0.0.1", "10.0.0.5", "192.168.1.1", "172.16.0.1", "169.254.169.254", "100.64.0.1", "0.0.0.0")
            .forEach { assertTrue(it, isPrivateAddress(InetAddress.getByName(it))) }
        assertFalse(isPrivateAddress(InetAddress.getByName("8.8.8.8")))
    }

    @Test fun ddgRedirectDecoding() {
        assertEquals("https://example.com/a?b=1", decodeDdgUrl("//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fa%3Fb%3D1&rut=x"))
        assertNull(decodeDdgUrl("https://duckduckgo.com/y.js?ad=1"))
        assertEquals("https://direct.example/x", decodeDdgUrl("https://direct.example/x"))
    }
}
