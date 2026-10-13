package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.routing.ModelCapabilityRegistry
import com.aurix.agent.core.ai.routing.ModelRouter
import com.aurix.agent.core.ai.routing.ModelTier
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.ProviderType
import com.aurix.agent.core.ai.routing.RoutingPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class RoutingModesTest {
    private fun prov(fast: String, strong: String) =
        ProviderEntry("p", ProviderType.OPENAI_COMPATIBLE, "P", "https://x", fastModel = fast, strongModel = strong)

    @Test fun legacyEconomyMigratesToSpeed() {
        assertEquals(RoutingPolicy.SPEED, RoutingPolicy.parse("ECONOMY"))
        assertEquals(RoutingPolicy.SPEED, RoutingPolicy.parse("speed"))
        assertEquals(RoutingPolicy.QUALITY, RoutingPolicy.parse("QUALITY"))
        assertEquals(RoutingPolicy.BALANCED, RoutingPolicy.parse("BALANCED"))
        assertEquals(RoutingPolicy.BALANCED, RoutingPolicy.parse(null))
        assertEquals(RoutingPolicy.BALANCED, RoutingPolicy.parse("garbage"))
        assertEquals(listOf(RoutingPolicy.SPEED, RoutingPolicy.BALANCED, RoutingPolicy.QUALITY), RoutingPolicy.selectable)
    }

    @Test fun speedUsesFastForEverything() {
        for (purpose in listOf("step", "plan", "verify", "test")) {
            assertEquals(ModelTier.FAST, ModelRouter.tier(RoutingPolicy.SPEED, purpose, escalate = true))
            assertEquals(ModelTier.FAST, ModelRouter.tier(RoutingPolicy.ECONOMY, purpose, escalate = true))
            assertEquals(ModelTier.STRONG, ModelRouter.tier(RoutingPolicy.QUALITY, purpose, escalate = false))
        }
        val p = prov("fast-a", "strong-a")
        assertEquals("fast-a", ModelRouter.modelFor(p, RoutingPolicy.SPEED, AiRequest(emptyList(), purpose = "verify")))
    }

    @Test fun registryClassifiesKnownIdsAndLeavesUnknownNull() {
        assertEquals(ModelTier.FAST, ModelCapabilityRegistry.tierOf("gpt-4o-mini"))
        assertEquals(ModelTier.FAST, ModelCapabilityRegistry.tierOf("models/gemini-2.5-flash"))
        assertEquals(ModelTier.FAST, ModelCapabilityRegistry.tierOf("claude-haiku-4-5-20251001"))
        assertEquals(ModelTier.STRONG, ModelCapabilityRegistry.tierOf("gemini-2.5-pro"))
        assertEquals(ModelTier.STRONG, ModelCapabilityRegistry.tierOf("claude-sonnet-5-5"))
        assertEquals(ModelTier.STRONG, ModelCapabilityRegistry.tierOf("llama-3.3-70b-versatile"))
        assertNull(ModelCapabilityRegistry.tierOf("strong-a"))
        assertNull(ModelCapabilityRegistry.tierOf(""))
        assertFalse(ModelCapabilityRegistry.describe("openai/gpt-oss-120b").protocolSafe)
    }

    @Test fun swappedSlotsAreCorrectedOnlyWhenRegistryIsSure() {
        val swapped = prov(fast = "gemini-2.5-pro", strong = "gemini-2.5-flash")
        assertEquals("gemini-2.5-flash", ModelRouter.modelFor(swapped, RoutingPolicy.SPEED, AiRequest(emptyList(), purpose = "step")))
        assertEquals("gemini-2.5-pro", ModelRouter.modelFor(swapped, RoutingPolicy.QUALITY, AiRequest(emptyList(), purpose = "step")))
        val same = prov("gpt-4o", "gpt-4o")
        assertEquals("gpt-4o", ModelRouter.modelFor(same, RoutingPolicy.SPEED, AiRequest(emptyList(), purpose = "step")))
        val unknown = prov("my-small-local", "my-big-local")
        assertEquals("my-big-local", ModelRouter.modelFor(unknown, RoutingPolicy.QUALITY, AiRequest(emptyList(), purpose = "step")))
    }
}
