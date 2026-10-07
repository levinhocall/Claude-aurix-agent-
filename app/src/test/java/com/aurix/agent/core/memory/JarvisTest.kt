package com.aurix.agent.core.memory

import com.aurix.agent.core.agent.IntentRouter
import com.aurix.agent.core.approval.PermissionMode
import com.aurix.agent.core.approval.needsApproval
import com.aurix.agent.core.notifications.extractOtp
import com.aurix.agent.core.tools.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisTest {
    private fun m(id: Long, t: String, uses: Int = 0) = MemoryEntity(id, t, "fact", 0, 0, uses)
    private fun r(s: String) = IntentRouter.route(s, 10)

    @Test fun jarvisModeSkipsHighButNotCritical() {
        assertFalse(needsApproval(RiskLevel.HIGH, PermissionMode.JARVIS))
        assertTrue(needsApproval(RiskLevel.CRITICAL, PermissionMode.JARVIS))
        assertTrue(needsApproval(RiskLevel.HIGH, PermissionMode.STANDARD))
        assertFalse(needsApproval(RiskLevel.MEDIUM, PermissionMode.JARVIS))
    }

    @Test fun memoryRanking() {
        val items = listOf(m(1, "I am vegetarian"), m(2, "My sister is Priya"), m(3, "I prefer vegetarian food at restaurants", uses = 5))
        assertEquals(listOf(3L, 1L), rankMemories(items, "suggest a vegetarian restaurant").map { it.id })
        assertTrue(rankMemories(items, "weather today").isEmpty())
    }

    @Test fun otpExtraction() {
        assertEquals("482913", extractOtp("482913 is your OTP for login. Do not share."))
        assertEquals("1234", extractOtp("Your verification code: 1234"))
        assertNull(extractOtp("Order 55612 shipped"))
    }

    @Test fun routerNewCommands() {
        assertEquals("MEMORY_SAVE", r("remember that I am vegetarian")!!.tool)
        assertEquals("I am vegetarian", r("yaad rakhna ki I am vegetarian")!!.input.getString("text"))
        val e = r("emergency contact Rahul 98765 43210 add karo")!!
        assertEquals("emergency", e.input.getString("kind")); assertEquals("Rahul: 9876543210", e.input.getString("text"))
        assertEquals("EMERGENCY_SOS", r("sos")!!.tool)
        val d = r("driving mode on")!!
        assertEquals("SET_VOLUME", d.tool); assertEquals(1, d.extra.size); assertEquals("BRIGHTNESS", d.extra[0].first)
        assertEquals(0, r("sleep mode")!!.input.getInt("level"))
        assertEquals("NOTIFICATION_OTP", r("otp kya hai")!!.tool)
        assertEquals("NOTIFICATIONS_LIST", r("notifications padho")!!.tool)
        assertEquals("NOTIFICATIONS_LIST", r("missed calls batao")!!.input.let { r("missed calls batao")!!.tool })
        assertEquals("NOTIFICATIONS_CLEAR", r("notifications clear karo")!!.tool)
        assertEquals("SCREEN_KEY", r("notification panel kholo")!!.tool)
    }
}
