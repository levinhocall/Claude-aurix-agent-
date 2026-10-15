package com.aurix.agent.core.tools.device

import com.aurix.agent.core.tools.device.RecipientGuard.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecipientGuardTest {
    @Test fun schemaExampleAndFillerNumbersAreRecognisedAsFake() {
        assertTrue(RecipientGuard.isPlaceholder("+919876543210")) // the number the model copied in the bug report
        assertTrue(RecipientGuard.isPlaceholder("+91 98765 43210"))
        assertTrue(RecipientGuard.isPlaceholder("1234567890"))
        assertTrue(RecipientGuard.isPlaceholder("9999999999"))
        assertFalse(RecipientGuard.isPlaceholder("+919812345678"))
        assertFalse(RecipientGuard.isPlaceholder("Nazib"))
    }

    @Test fun modelSuppliedNumberIsNeverTrusted() {
        assertEquals(Verdict.RESOLVE, RecipientGuard.verdict("+919876543210", "draft a love letter and send on WhatsApp to nazib", ""))
        assertEquals(Verdict.RESOLVE, RecipientGuard.verdict("", "message nazib", ""))
        assertEquals(Verdict.RESOLVE, RecipientGuard.verdict("+919812345678", "message nazib", ""))
    }

    @Test fun numberTheUserTypedIsTrusted() {
        assertEquals(Verdict.TRUSTED, RecipientGuard.verdict("+919812345678", "whatsapp 98123 45678 hello", ""))
        assertEquals(Verdict.TRUSTED, RecipientGuard.verdict("+919812345678", "message nazib", "User answered: +91 98123-45678"))
        assertEquals(Verdict.TRUSTED, RecipientGuard.verdict("9876543210", "call 98765 43210", "")) // typed in the request itself
    }

    @Test fun placeholderPickedFromAnAnswerIsStillRejected() {
        assertEquals(Verdict.RESOLVE, RecipientGuard.verdict("+919876543210", "message nazib", "User answered: +919876543210"))
    }

    @Test fun optionsNeverOfferAMadeUpNumber() {
        assertEquals(listOf("Other number"), RecipientGuard.cleanOptions(listOf("+919876543210", "Other number")))
    }
}
