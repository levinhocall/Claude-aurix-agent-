package com.aurix.agent.core.tools.device

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BatteryGuardTest {
    @Test fun brandsMapToFamiliesCaseInsensitively() {
        assertEquals("xiaomi", OemGuard.family("Xiaomi")); assertEquals("xiaomi", OemGuard.family("POCO"))
        assertEquals("oppo", OemGuard.family("realme")); assertEquals("oppo", OemGuard.family("OnePlus"))
        assertEquals("vivo", OemGuard.family("iQOO")); assertEquals("transsion", OemGuard.family("TECNO"))
        assertEquals("generic", OemGuard.family("Google")); assertEquals("generic", OemGuard.family(""))
    }

    @Test fun knownBrandsHaveCandidatesUnknownDoNot() {
        assertTrue(OemGuard.candidates("vivo").size >= 2)
        assertTrue(OemGuard.candidates("Xiaomi").all { it.pkg.isNotBlank() && it.cls.contains('.') })
        assertTrue(OemGuard.candidates("Google").isEmpty())
    }

    @Test fun adviceOnlyClaimsWhatItCanVerify() {
        val a = OemGuard.advice("realme", ignoringOptimizations = false, bucket = "RARE", screenControlOn = false)
        assertTrue(a.contains("OFF -> run BATTERY_GUARD action=battery"))
        assertTrue(a.contains("NO ->"))
        assertTrue(a.contains("cannot read back"))
        val b = OemGuard.advice("Google", true, null, true)
        assertTrue(b.contains("exemption: ON")); assertTrue(b.contains("no OEM-specific"))
    }
}
