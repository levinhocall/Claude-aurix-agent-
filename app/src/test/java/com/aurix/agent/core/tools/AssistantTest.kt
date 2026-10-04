package com.aurix.agent.core.tools

import com.aurix.agent.core.agent.cleanTitle
import com.aurix.agent.core.agent.toolHints
import com.aurix.agent.core.tools.device.contactScore
import com.aurix.agent.core.tools.device.foldText
import com.aurix.agent.core.tools.screen.ScreenGuard
import com.aurix.agent.core.tools.storage.resolveStorage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AssistantTest {
    @Test fun foldsAccents() {
        assertEquals("mom", foldText("møm"))
        assertEquals("cafe", foldText("Café"))
        assertEquals("sharadha mom", foldText("Sharadha Mom )"))
    }

    @Test fun exactContactBeatsPartialMatch() {
        val exact = contactScore("møm", "mom")
        val partial = contactScore("Vaishali Jawreni (Sharadha Mom )", "mom")
        assertEquals(100, exact)
        assertTrue(partial in 1..99)
        assertTrue(exact > partial)
        assertEquals(0, contactScore("Rahul", "mom"))
    }

    @Test fun stepHints() {
        assertEquals(setOf("WEB_SEARCH", "FILE_WRITE"), toolHints("Find jobs and save [WEB_SEARCH, FILE_WRITE]"))
        assertEquals("Find jobs and save", cleanTitle("Find jobs and save [WEB_SEARCH, FILE_WRITE]"))
        assertTrue(toolHints("No hints here").isEmpty())
        assertEquals("Plain step", cleanTitle("Plain step"))
    }

    @Test fun storagePathsStayInsideRoot() {
        val root = File(System.getProperty("java.io.tmpdir"), "aurix-storage-test").apply { mkdirs() }
        assertEquals(File(root, "Download").canonicalFile, resolveStorage(root, "Downloads"))
        assertEquals(File(root, "Pictures/Screenshots/a.png").canonicalFile, resolveStorage(root, "screenshots/a.png"))
        assertThrows(ToolException::class.java) { resolveStorage(root, "../etc/passwd") }
        assertThrows(ToolException::class.java) { resolveStorage(root, "Android/data/com.x") }
        assertEquals(root.canonicalFile, resolveStorage(root, ""))
    }

    @Test fun screenGuardRules() {
        assertTrue(ScreenGuard.isSensitive("com.phonepe.app"))
        assertTrue(ScreenGuard.isSensitive("com.android.settings"))
        assertFalse(ScreenGuard.isSensitive("com.google.android.youtube"))
        assertThrows(ToolException::class.java) { ScreenGuard.check("com.aurix.agent") }
        assertThrows(ToolException::class.java) { ScreenGuard.check("com.google.android.permissioncontroller") }
        ScreenGuard.check("com.google.android.youtube")
    }
}
