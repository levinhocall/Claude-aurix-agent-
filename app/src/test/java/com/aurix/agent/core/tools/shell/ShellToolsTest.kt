package com.aurix.agent.core.tools.shell

import com.aurix.agent.core.tools.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ShellToolsTest {
    @Test fun settingAndStateParsingIsTolerantButClosed() {
        assertEquals(PhoneToggle.WIFI, PhoneToggle.parse("Wi-Fi".lowercase().replace("-", "")))
        assertEquals(PhoneToggle.MOBILE_DATA, PhoneToggle.parse("mobile data"))
        assertEquals(PhoneToggle.AIRPLANE, PhoneToggle.parse("flight mode"))
        assertNull(PhoneToggle.parse("wifi; rm -rf /"))
        assertNull(PhoneToggle.parse(""))
        assertEquals(true, PhoneToggle.parseState("ON")); assertEquals(false, PhoneToggle.parseState("disable")); assertNull(PhoneToggle.parseState("maybe"))
    }

    @Test fun commandsAreFixedConstantsAndAirplaneIsHighRisk() {
        for (t in PhoneToggle.values()) for (on in listOf(true, false)) {
            val c = t.command(on)
            assertTrue(c.startsWith("svc ") || c.startsWith("cmd connectivity "))
            assertFalse(c.contains(";") || c.contains("&") || c.contains("|"))
        }
        assertEquals(RiskLevel.HIGH, PhoneToggle.AIRPLANE.risk)
        assertEquals(RiskLevel.MEDIUM, PhoneToggle.WIFI.risk)
    }

    @Test fun stateReadbackNeverGuesses() {
        assertEquals(true, PhoneToggle.readsAsOn("1\n")); assertEquals(true, PhoneToggle.readsAsOn("2")); assertEquals(false, PhoneToggle.readsAsOn("0"))
        assertNull(PhoneToggle.readsAsOn("null")); assertNull(PhoneToggle.readsAsOn(""))
    }

    @Test fun accessibilityListKeepsOtherServicesAndNeverDuplicates() {
        val me = "com.aurix.agent/com.aurix.agent.core.tools.screen.AurixAccessibilityService"
        assertEquals(me, mergeAccessibilityService("null", me))
        assertEquals(me, mergeAccessibilityService("", me))
        assertEquals("a/B:$me", mergeAccessibilityService("a/B", me))
        assertEquals("a/B:$me", mergeAccessibilityService("a/B:$me", me))
    }
}
