package com.aurix.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTest {
    @Test fun wakeWordVariants() {
        assertTrue(WakeWord.matches("hey aurix"))
        assertTrue(WakeWord.matches("Hey Orix open youtube"))
        assertTrue(WakeWord.matches("hello oryx"))
        assertTrue(WakeWord.matches("ok aurics"))
        assertTrue(WakeWord.matches("orix"))
        assertTrue(WakeWord.matches("hey or ix play music"))
    }

    @Test fun wakeWordRejectsOtherSpeech() {
        assertFalse(WakeWord.matches("hey there how are you"))
        assertFalse(WakeWord.matches("what is the time"))
        assertFalse(WakeWord.matches(""))
        assertFalse(WakeWord.matches("play some music for me"))
    }

    @Test fun yesNo() {
        assertEquals(true, parseYesNo("yes please"))
        assertEquals(true, parseYesNo("haan kar do"))
        assertEquals(false, parseYesNo("no don't"))
        assertEquals(false, parseYesNo("nahi mat bhejo"))
        assertNull(parseYesNo("hmm maybe"))
    }

    @Test fun stopPhrases() {
        assertTrue(isStopPhrase("stop"))
        assertTrue(isStopPhrase("ok goodbye"))
        assertFalse(isStopPhrase("play something"))
    }

    @Test fun spokenTextStripsMarkdown() {
        assertEquals("Hello world see link", spokenText("**Hello** `world` see https://x.com/a"))
        assertEquals("Title item", spokenText("# Title\n- item").replace("- ", ""))
    }
}
