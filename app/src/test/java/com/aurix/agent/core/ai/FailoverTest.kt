package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.routing.Failover
import com.aurix.agent.core.ai.routing.KeyEntry
import com.aurix.agent.core.ai.routing.KeyPool
import com.aurix.agent.core.ai.routing.ModelRouter
import com.aurix.agent.core.ai.routing.ProviderCodec
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.ProviderType
import com.aurix.agent.core.ai.routing.RoutingPolicy
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private class Fake(override val id: String, val behave: (String) -> AiResponse) : AiProvider {
    val calls = mutableListOf<String>()
    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse { calls += apiKey; return behave(apiKey) }
}

private fun ok(text: String = "ok") = AiResponse(text, TokenUsage(5, 5), "m")
private fun prov(id: String, vararg keys: String, independent: Boolean = false) = ProviderEntry(
    id, ProviderType.OPENAI_COMPATIBLE, id, "https://x", "fast-$id", "strong-$id", true, independent,
    keys.mapIndexed { i, k -> KeyEntry("$id-$i", "Key $i", k) },
)

class FailoverTest {
    private fun failover(providers: List<ProviderEntry>, pool: KeyPool, fakes: Map<String, Fake>, policy: RoutingPolicy = RoutingPolicy.QUALITY) =
        Failover({ providers }, { policy }, pool, { fakes.getValue(it.id) }, {}, {}, { })

    @Test fun authErrorDisablesKeyAndNextKeyWorks() = runBlocking {
        val a = Fake("a") { k -> if (k == "bad") throw AiError(AiErrorType.AUTH_ERROR, "401") else ok() }
        val pool = KeyPool { 0L }
        val resp = failover(listOf(prov("a", "bad", "good")), pool, mapOf("a" to a)).complete(AiRequest(emptyList()))
        assertEquals("ok", resp.text)
        assertEquals(listOf("bad", "good"), a.calls)
        assertTrue(pool.status("a-0").startsWith("DISABLED"))
    }

    @Test fun rateLimitDoesNotRotateKeysWithinSameAccountButFailsOverToNextProvider() = runBlocking {
        val a = Fake("a") { throw AiError(AiErrorType.RATE_LIMIT, "429", 30_000) }
        val b = Fake("b") { ok("from-b") }
        val resp = failover(listOf(prov("a", "k1", "k2"), prov("b", "k3")), KeyPool { 0L }, mapOf("a" to a, "b" to b)).complete(AiRequest(emptyList()))
        assertEquals("from-b", resp.text)
        assertEquals(1, a.calls.size)
    }

    @Test fun rateLimitUsesSecondKeyOnlyWhenKeysAreIndependent() = runBlocking {
        val a = Fake("a") { k -> if (k == "k1") throw AiError(AiErrorType.RATE_LIMIT, "429", 30_000) else ok("second") }
        val resp = failover(listOf(prov("a", "k1", "k2", independent = true)), KeyPool { 0L }, mapOf("a" to a)).complete(AiRequest(emptyList()))
        assertEquals("second", resp.text)
    }

    @Test fun invalidRequestIsNotRetriedElsewhere() = runBlocking {
        val a = Fake("a") { throw AiError(AiErrorType.INVALID_INPUT, "bad request") }
        val b = Fake("b") { ok() }
        try {
            failover(listOf(prov("a", "k"), prov("b", "k")), KeyPool { 0L }, mapOf("a" to a, "b" to b)).complete(AiRequest(emptyList()))
            fail("expected AiError")
        } catch (e: AiError) {
            assertEquals(AiErrorType.INVALID_INPUT, e.type)
            assertTrue(b.calls.isEmpty())
        }
    }

    @Test fun cooldownExpiresAndKeyReturns() {
        var now = 0L
        val pool = KeyPool { now }
        val keys = listOf(KeyEntry("k", "Key", "s"))
        pool.cooldown("k", 10_000, "rate limited")
        assertTrue(pool.available(keys).isEmpty())
        now = 10_001
        assertEquals(1, pool.available(keys).size)
    }

    @Test fun leastRecentlyUsedKeyIsPickedFirst() {
        var now = 0L
        val pool = KeyPool { now }
        val keys = listOf(KeyEntry("a", "A", "1"), KeyEntry("b", "B", "2"))
        now = 1; pool.markUsed("a")
        assertEquals("b", pool.available(keys).first().id)
    }

    @Test fun routerTiers() {
        val p = prov("a", "k")
        val step = AiRequest(emptyList(), purpose = "step")
        assertEquals("strong-a", ModelRouter.modelFor(p, RoutingPolicy.QUALITY, step))
        assertEquals("fast-a", ModelRouter.modelFor(p, RoutingPolicy.BALANCED, step))
        assertEquals("strong-a", ModelRouter.modelFor(p, RoutingPolicy.BALANCED, step.copy(escalate = true)))
        assertEquals("strong-a", ModelRouter.modelFor(p, RoutingPolicy.BALANCED, AiRequest(emptyList(), purpose = "plan")))
        assertEquals("fast-a", ModelRouter.modelFor(p, RoutingPolicy.ECONOMY, AiRequest(emptyList(), purpose = "verify")))
    }

    @Test fun codecRoundTrip() {
        val list = listOf(prov("a", "k1", "k2", independent = true), prov("b", "k3").copy(type = ProviderType.ANTHROPIC, enabled = false))
        assertEquals(list, ProviderCodec.decode(ProviderCodec.encode(list)))
    }
}

class ModelFallbackTest {
    @Test fun toolMisfireFallsBackToOtherModelOfSameProvider() = runBlocking {
        val seen = mutableListOf<String>()
        val prov = object : AiProvider {
            override val id = "g"
            override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
                seen += request.model
                if (request.model == "strong-g") throw AiError(AiErrorType.MODEL_ERROR, "HTTP 400: Tool choice is none", modelSpecific = true)
                return ok("recovered")
            }
        }
        val p = prov("g", "k")
        val f = Failover({ listOf(p) }, { RoutingPolicy.QUALITY }, KeyPool { 0L }, { prov }, {}, {}, { })
        assertEquals("recovered", f.complete(AiRequest(emptyList())).text)
        assertEquals(listOf("strong-g", "fast-g"), seen)
    }

    @Test fun toolChoiceNoneIs400MappedAsModelSpecific() {
        val e = mapHttpError(400, null, """{"error":{"message":"Tool choice is none, but model called a tool"}}""", "secret")
        assertEquals(AiErrorType.MODEL_ERROR, e.type)
        assertTrue(e.modelSpecific)
    }
}
