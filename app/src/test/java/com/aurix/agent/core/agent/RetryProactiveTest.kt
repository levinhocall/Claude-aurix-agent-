package com.aurix.agent.core.agent

import com.aurix.agent.core.proactive.matchesCar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RetryProactiveTest {
    @Test fun retryParsing() {
        assertEquals(RetryKind.SAME, parseRetry("Retry"))
        assertEquals(RetryKind.SAME, parseRetry("please try again!"))
        assertEquals(RetryKind.SAME, parseRetry("dobara karo"))
        assertEquals(RetryKind.DIFFERENT, parseRetry("doosre tareeke se karo"))
        assertEquals(RetryKind.DIFFERENT, parseRetry("try another method"))
        assertEquals(RetryKind.NONE, parseRetry("set alarm 7"))
    }
    @Test fun carMatch() {
        assertTrue(matchesCar("Honda City BT", "honda"))
        assertFalse(matchesCar("JBL Flip", "honda"))
        assertFalse(matchesCar("Honda", ""))
        assertFalse(matchesCar(null, "honda"))
    }
}
