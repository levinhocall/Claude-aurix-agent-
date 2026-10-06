package com.aurix.agent.core.approval

import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.device.normalizePhone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ApprovalTest {
    @Test fun riskMatrix() {
        assertTrue(needsApproval(RiskLevel.HIGH, PermissionMode.STANDARD))
        assertTrue(needsApproval(RiskLevel.HIGH, PermissionMode.STRICT))
        assertFalse(needsApproval(RiskLevel.MEDIUM, PermissionMode.STANDARD))
        assertTrue(needsApproval(RiskLevel.MEDIUM, PermissionMode.STRICT))
        assertFalse(needsApproval(RiskLevel.LOW, PermissionMode.STRICT))
    }

    @Test fun keyIsStableAndInputSensitive() {
        val a = JSONObject().put("number", "+911234567890").put("text", "hi")
        val b = JSONObject().put("number", "+911234567890").put("text", "hi")
        val c = JSONObject().put("number", "+911234567890").put("text", "bye")
        assertEquals(approvalKey("SEND_SMS", a), approvalKey("SEND_SMS", b))
        assertNotEquals(approvalKey("SEND_SMS", a), approvalKey("SEND_SMS", c))
        assertNotEquals(approvalKey("SEND_SMS", a), approvalKey("CALL_PHONE", a))
    }

    @Test fun phoneNormalisation() {
        assertEquals("+919876543210", normalizePhone("+91 98765-43210"))
        assertEquals("9876543210", normalizePhone("(987) 654 3210"))
        assertNull(normalizePhone("12"))
        assertNull(normalizePhone("call me maybe"))
    }
}

class CriticalRiskTest {
    @Test fun criticalAlwaysAsks() {
        assertTrue(needsApproval(RiskLevel.CRITICAL, PermissionMode.STANDARD))
        assertTrue(needsApproval(RiskLevel.CRITICAL, PermissionMode.STRICT))
    }

    @Test fun moneyAppsAreCriticalSettingsIsHigh() {
        assertEquals(RiskLevel.CRITICAL, com.aurix.agent.core.tools.screen.ScreenGuard.riskFor("com.phonepe.app", RiskLevel.MEDIUM))
        assertEquals(RiskLevel.HIGH, com.aurix.agent.core.tools.screen.ScreenGuard.riskFor("com.android.settings", RiskLevel.MEDIUM))
        assertEquals(RiskLevel.MEDIUM, com.aurix.agent.core.tools.screen.ScreenGuard.riskFor("com.google.android.youtube", RiskLevel.MEDIUM))
    }
}
