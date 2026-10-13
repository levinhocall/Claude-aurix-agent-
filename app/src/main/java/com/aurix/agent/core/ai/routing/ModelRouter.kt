package com.aurix.agent.core.ai.routing

import com.aurix.agent.core.ai.AiRequest

object ModelRouter {
    fun tier(policy: RoutingPolicy, purpose: String, escalate: Boolean): ModelTier = when (policy) {
        RoutingPolicy.QUALITY -> ModelTier.STRONG
        RoutingPolicy.SPEED, RoutingPolicy.ECONOMY -> ModelTier.FAST
        RoutingPolicy.BALANCED -> when {
            (purpose == "test" || purpose == "plan") -> ModelTier.FAST
            purpose == "step" && !escalate -> ModelTier.FAST
            else -> ModelTier.STRONG
        }
    }

    fun modelFor(p: ProviderEntry, policy: RoutingPolicy, req: AiRequest): String {
        if (req.model.isNotBlank()) return req.model
        val strong = tier(policy, req.purpose, req.escalate) == ModelTier.STRONG
        var chosen = if (strong) p.strongModel else p.fastModel
        val other = if (strong) p.fastModel else p.strongModel
        // Slots may be misassigned (e.g. a flash model in the strong slot): when the registry is sure about both, trust it.
        if (chosen.isNotBlank() && other.isNotBlank()) {
            val want = if (strong) ModelTier.STRONG else ModelTier.FAST
            val c = ModelCapabilityRegistry.tierOf(chosen)
            val o = ModelCapabilityRegistry.tierOf(other)
            if (c != null && o != null && c != want && o == want) chosen = other
        }
        return chosen.ifBlank { other }
    }

    /** A different model of the same provider (used when one model misbehaves, e.g. emits native tool calls). */
    fun alternateModel(p: ProviderEntry, current: String): String? =
        listOf(p.strongModel, p.fastModel).filter { it.isNotBlank() && it != current }.firstOrNull()
}
