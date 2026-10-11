package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.routing.Backoff
import com.aurix.agent.core.ai.routing.BreakerState
import com.aurix.agent.core.ai.routing.CircuitBreaker
import com.aurix.agent.core.ai.routing.Failover
import com.aurix.agent.core.ai.routing.KeyEntry
import com.aurix.agent.core.ai.routing.KeyPool
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.ProviderType
import com.aurix.agent.core.ai.routing.RoutingPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ResilienceTest {
    @Test fun breakerOpensAfterFailuresThenHalfOpenProbe() {
        var now = 0L
        val b = CircuitBreaker(failureThreshold = 2, openMs = 1_000, clock = { now })
        assertTrue(b.tryAcquire()); b.onFailure()
        assertEquals(BreakerState.CLOSED, b.state())
        assertTrue(b.tryAcquire()); b.onFailure()
        assertEquals(BreakerState.OPEN, b.state())
        assertFalse(b.tryAcquire())
        now = 1_000
        assertEquals(BreakerState.HALF_OPEN, b.state())
        assertTrue(b.tryAcquire())        // the single probe
        assertFalse(b.tryAcquire())       // no second probe in flight
        b.onFailure()                     // probe failed -> open again
        assertEquals(BreakerState.OPEN, b.state())
        now = 2_500
        assertTrue(b.tryAcquire()); b.onSuccess()
        assertEquals(BreakerState.CLOSED, b.state())
    }

    @Test fun backoffGrowsHasJitterAndHonoursRetryAfter() {
        assertEquals(500L, Backoff.delayMs(0, random = { 0.0 }))
        assertEquals(1_000L, Backoff.delayMs(0, random = { 1.0 }))
        assertEquals(2_000L, Backoff.delayMs(1, random = { 1.0 }))
        assertEquals(30_000L, Backoff.delayMs(10, random = { 1.0 }))
        assertEquals(7_000L, Backoff.delayMs(0, retryAfterMs = 7_000))
        assertEquals(30_000L, Backoff.delayMs(0, retryAfterMs = 999_000))
    }

    @Test fun failoverSkipsProviderWithOpenBreaker() = runBlocking {
        var calls = 0
        val bad = object : AiProvider {
            override val id = "bad"
            override suspend fun complete(request: AiRequest, apiKey: String): AiResponse { calls++; throw AiError(AiErrorType.MODEL_ERROR, "HTTP 503") }
        }
        val good = object : AiProvider {
            override val id = "good"
            override suspend fun complete(request: AiRequest, apiKey: String) = AiResponse("ok", TokenUsage(1, 1), "m")
        }
        fun prov(id: String) = ProviderEntry(id, ProviderType.OPENAI_COMPATIBLE, id, "https://x", "f", "s", true, false, listOf(KeyEntry("$id-0", "k", "secret-$id")))
        var t = 0L // key cooldowns expire between calls, so only the breaker can stop "bad" from being called
        val f = Failover({ listOf(prov("bad"), prov("good")) }, { RoutingPolicy.QUALITY }, KeyPool { t += 100_000L; t }, { if (it.id == "bad") bad else good }, {}, {}, { }, { 0L }, { 0.0 })
        repeat(5) { assertEquals("ok", f.complete(AiRequest(emptyList())).text) }
        assertEquals(3, calls) // after 3 failures the breaker is open and "bad" is no longer called
        assertEquals(BreakerState.OPEN, f.breakerState("bad"))
    }

    @Test fun contextOverflowAndEmptyBodiesAreTyped() {
        val e = mapHttpError(400, null, """{"error":{"message":"This model's maximum context length is 8192 tokens"}}""", "k")
        assertEquals(AiErrorType.CONTEXT_OVERFLOW, e.type)
        assertFalse(e.modelSpecific)
        assertEquals(AiErrorType.AUTH_ERROR, mapHttpError(401, null, "{}", "k").type)
        assertEquals(AiErrorType.RATE_LIMIT, mapHttpError(429, "7", "{}", "k").type)
        assertEquals(7_000L, mapHttpError(429, "7", "{}", "k").retryAfterMs)
    }
}
