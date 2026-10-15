package com.aurix.agent.core.tools.screen

import com.aurix.agent.core.durability.SideEffectPolicy
import com.aurix.agent.core.security.UntrustedContent
import com.aurix.agent.core.tools.RiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScreenLookTest {
    private fun item(t: String, x: Int, y: Int) = LookItem(t, x, y, 100, 40, 1080, 2400)

    @Test fun emptyOcrSaysSoInsteadOfInventingText() {
        val out = LookFormat.format("com.x", emptyList())
        assertTrue(out.contains("no readable text"))
        assertFalse(out.contains("@"))
    }

    @Test fun readingOrderIsTopToBottomThenLeftToRightWithTapCoordinates() {
        val out = LookFormat.format("com.x", listOf(item("Play", 800, 1200), item("Title", 540, 300), item("Next", 200, 1200)))
        val lines = out.lines()
        assertTrue(lines[0].contains("1080x2400"))
        assertEquals("[1] \"Title\" @540,300", lines[1])
        assertEquals("[2] \"Next\" @200,1200", lines[2])
        assertEquals("[3] \"Play\" @800,1200", lines[3])
    }

    @Test fun duplicatesFromTwoRecognizersAndNoiseAreDropped() {
        val out = LookFormat.dedupe(listOf(item("Settings", 500, 100), item("settings", 510, 108), item("...", 5, 5), item("  ", 9, 9), item("Settings", 500, 900)))
        assertEquals(2, out.size)
    }

    @Test fun capsLongOutput() {
        val many = (0 until 80).map { item("word$it", 100, it * 30) }
        val out = LookFormat.format("com.x", many)
        assertEquals(40, out.lines().count { it.startsWith("[") })
        assertTrue(out.contains("more text"))
    }

    @Test fun screenLookIsReadOnlyAndFencedAsUntrusted() {
        assertFalse(SideEffectPolicy.isNonIdempotent("SCREEN_LOOK", RiskLevel.MEDIUM))
        assertTrue(UntrustedContent.isSource("SCREEN_LOOK"))
    }
}
