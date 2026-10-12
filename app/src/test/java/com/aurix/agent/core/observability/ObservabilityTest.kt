package com.aurix.agent.core.observability

import com.aurix.agent.core.mission.ModelCallEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ObservabilityTest {
    // fixtures are assembled at runtime so no real-looking credential literal exists in the repo
    private val fakeOpenAi = "sk-" + "A1b2C3d4E5f6G7h8I9j0K1l2"
    private val fakeGroq = "gsk_" + "Z".repeat(24)
    private val fakeGh = "gh" + "p_" + "y".repeat(30)

    @Test fun redactsKeyShapes() {
        for (k in listOf(fakeOpenAi, fakeGroq, fakeGh)) {
            val out = Redactor.secrets("calling with $k now")
            assertFalse(out, out.contains(k)); assertTrue(out.contains("[REDACTED]"))
        }
    }

    @Test fun redactsBearerAndAssignments() {
        val b = "abcdefghijklmnop1234"
        assertFalse(Redactor.secrets("Authorization: Bearer $b").contains(b))
        val j = Redactor.secrets("""{"password":"hunter2hunter2","tool":"SEND_SMS"}""")
        assertFalse(j.contains("hunter2hunter2")); assertTrue(j.contains("SEND_SMS"))
        assertFalse(Redactor.secrets("api_key=supersecretvalue99").contains("supersecretvalue99"))
    }

    @Test fun leavesPlainTextAlone() {
        assertEquals("battery 80 percent, alarm at 7:30", Redactor.secrets("battery 80 percent, alarm at 7:30"))
        assertEquals("", Redactor.secrets(""))
    }

    @Test fun allMasksOtpAndEmail() {
        val o = Redactor.all("Your OTP is 482913. Mail ravi.kumar@example.com")
        assertFalse(o.contains("482913")); assertTrue(o.contains("••••"))
        assertTrue(o, o.contains("r***@example.com"))
    }

    @Test fun costEstimateIsNullWhenUnknown() {
        assertNull(CostEstimator.estimateUsd("mystery-model-x", 1000, 1000))
        assertEquals(0.0, CostEstimator.estimateUsd("anything", 1000, 1000, local = true)!!, 0.0)
        assertEquals(0.15, CostEstimator.estimateUsd("gpt-4o-mini", 1_000_000, 0)!!, 1e-9)
    }

    private fun row(ok: Boolean, ms: Long, reported: Boolean, model: String = "m1", err: String? = null, cost: Double? = 0.001, retried: Boolean = false) =
        ModelCallEntity(missionId = "m", ts = 1, purpose = "step", model = model, latencyMs = ms, ok = ok, errorType = err, promptTokens = 10, completionTokens = 5, tokensReported = reported, costUsdEstimate = cost, routeNote = "", retried = retried)

    @Test fun summaryKeepsReportedAndEstimatedSeparate() {
        val s = Diagnostics.summarize(listOf(row(true, 100, true), row(true, 200, false), row(false, 900, false, err = "TIMEOUT", cost = null, retried = true), row(true, 300, true, model = "m2", cost = null)))
        assertEquals(4, s.calls); assertEquals(1, s.failures); assertEquals(1, s.retried)
        assertEquals(30L, s.tokensReported)        // two reported rows
        assertEquals(30L, s.tokensEstimated)       // two estimated rows (one failed)
        assertEquals(0.002, s.costEstimateUsd, 1e-9)
        assertEquals(1, s.callsWithUnknownCost)    // m2 succeeded with no known price; the failed call is not counted
        assertEquals("TIMEOUT", s.topErrors.first().first)
        assertTrue(s.render().contains("ESTIMATED"))
        assertEquals("No model calls recorded yet.", Diagnostics.summarize(emptyList()).render())
    }
}
