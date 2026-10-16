package com.aurix.agent.core.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceTextTest {
    @Test fun biasKeepsNamesDedupesAndSkipsNumbers() {
        val b = VoiceBias.build(listOf("Nazib", "nazib", " Mom ", "+91 98765 43210", "A", "x".repeat(60)))
        assertEquals(listOf("AURIX", "WhatsApp", "Nazib", "Mom"), b)
        assertEquals(100, VoiceBias.build((1..500).map { "Name$it" }).size)
    }

    @Test fun chunksStartShortMergeTinyPiecesAndCutLongOnes() {
        val c = SpeechChunker.split("Ho gaya. Maine report save kar di hai aur file verify bhi ho gayi. Kuch aur?")
        assertTrue(c.first().length >= 30) // "Ho gaya." (8 chars) is merged forward
        assertEquals(2, c.size)
        val long = SpeechChunker.split("word ".repeat(120))
        assertTrue(long.all { it.length <= 200 } && long.size >= 3)
        assertEquals(listOf("Pehla vaakya yahan hai, kaafi lamba. Doosra bhi yahan."), listOf(SpeechChunker.split("Pehla vaakya yahan hai, kaafi lamba. Doosra bhi yahan.").joinToString(" ")))
    }

    @Test fun speakableStripsNoiseAndSpellsNumbersAndSymbols() {
        assertEquals("9 8 7 6 5 4 3 2 1 0", speakable("+91 98765 43210"))
        assertEquals("Call 9 8 1 2 3 4 5 6 7 8 now", speakable("Call 98123-45678 now"))
        val s = speakable("**Done** 😀 see https://x.com/a 50% off ₹1,200")
        assertFalse(s.contains("http") || s.contains("*") || s.contains("\uD83D"))
        assertTrue(s.contains("link") && s.contains("50 percent") && s.contains("1,200 rupees"))
        assertEquals("60000 hai", speakable("60000 hai"))
    }
}
