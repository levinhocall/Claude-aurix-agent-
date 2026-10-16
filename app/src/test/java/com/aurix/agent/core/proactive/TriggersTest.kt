package com.aurix.agent.core.proactive

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

class TriggersTest {
    private fun rule(e: TriggerEvent, p: String = "", fired: Long = 0L, on: Boolean = true) =
        TriggerRule("1", "n", e, p, "do it", enabled = on, lastFired = fired)

    @Test fun validation() {
        assertNotNull(TriggerLogic.validate(TriggerEvent.CHARGER_ON, "", " "))
        assertNotNull(TriggerLogic.validate(TriggerEvent.BLUETOOTH, "", "x"))
        assertNotNull(TriggerLogic.validate(TriggerEvent.TIME, "25:00", "x"))
        assertNotNull(TriggerLogic.validate(TriggerEvent.TIME, "7:5", "x"))
        assertNull(TriggerLogic.validate(TriggerEvent.TIME, "07:30", "x"))
        assertNull(TriggerLogic.validate(TriggerEvent.TIME, "7:30", "x"))
        assertNull(TriggerLogic.validate(TriggerEvent.BLUETOOTH, "Swift", "x"))
    }

    @Test fun matchingRespectsEventParamAndEnabled() {
        assertTrue(TriggerLogic.matches(rule(TriggerEvent.CHARGER_ON), TriggerEvent.CHARGER_ON, ""))
        assertFalse(TriggerLogic.matches(rule(TriggerEvent.CHARGER_ON), TriggerEvent.CHARGER_OFF, ""))
        assertFalse(TriggerLogic.matches(rule(TriggerEvent.CHARGER_ON, on = false), TriggerEvent.CHARGER_ON, ""))
        assertTrue(TriggerLogic.matches(rule(TriggerEvent.BLUETOOTH, "swift"), TriggerEvent.BLUETOOTH, "My Swift Car"))
        assertFalse(TriggerLogic.matches(rule(TriggerEvent.BLUETOOTH, "swift"), TriggerEvent.BLUETOOTH, "Earbuds"))
        assertFalse(TriggerLogic.matches(rule(TriggerEvent.BLUETOOTH, "swift"), TriggerEvent.BLUETOOTH, ""))
    }

    @Test fun cooldownBlocksRapidRefires() {
        val now = 10_000_000L
        assertTrue(TriggerLogic.cooldownOk(rule(TriggerEvent.CHARGER_ON), now))
        assertFalse(TriggerLogic.cooldownOk(rule(TriggerEvent.CHARGER_ON, fired = now - 60_000), now))
        assertTrue(TriggerLogic.cooldownOk(rule(TriggerEvent.CHARGER_ON, fired = now - 11 * 60_000), now))
        assertTrue(TriggerLogic.cooldownOk(rule(TriggerEvent.CHARGER_ON, fired = now + 5_000_000), now)) // clock went backwards
    }

    @Test fun nextTimeIsTodayOrTomorrow() {
        val z = TimeZone.getTimeZone("Asia/Kolkata")
        val c = Calendar.getInstance(z).apply { clear(); set(2026, Calendar.OCTOBER, 16, 21, 0, 0) }
        val now = c.timeInMillis
        val later = TriggerLogic.nextTime("22:30", now, z)!!
        assertEquals(90 * 60_000L, later - now)
        val tomorrow = TriggerLogic.nextTime("07:00", now, z)!!
        assertEquals(10 * 3_600_000L, tomorrow - now)
        assertEquals(24 * 3_600_000L, TriggerLogic.nextTime("21:00", now, z)!! - now) // exactly now → tomorrow
        assertNull(TriggerLogic.nextTime("bad", now, z))
    }

    @Test fun jsonRoundTripAndTolerance() {
        val l = listOf(rule(TriggerEvent.TIME, "07:30", fired = 5).copy(fired = 2), rule(TriggerEvent.BATTERY_LOW))
        assertEquals(l, TriggerLogic.fromJson(TriggerLogic.toJson(l)))
        assertTrue(TriggerLogic.fromJson("garbage").isEmpty())
        assertTrue(TriggerLogic.fromJson(null).isEmpty())
        val mixed = """[{"id":"a","event":"NOPE","command":"x"},{"id":"b","event":"CHARGER_ON","command":"y"}]"""
        assertEquals(listOf("b"), TriggerLogic.fromJson(mixed).map { it.id })
    }
}
