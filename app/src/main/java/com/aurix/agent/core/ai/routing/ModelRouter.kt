package com.aurix.agent.core.ai.routing

import com.aurix.agent.core.ai.AiRequest

object ModelRouter {
    fun tier(policy: RoutingPolicy, purpose: String, escalate: Boolean): ModelTier = when (policy) {
        RoutingPolicy.QUALITY -> ModelTier.STRONG
        RoutingPolicy.ECONOMY -> ModelTier.FAST
        RoutingPolicy.BALANCED -> when {
            purpose == "test" -> ModelTier.FAST
            purpose == "step" && !escalate -> ModelTier.FAST
            else -> ModelTier.STRONG
        }
    }

    fun modelFor(p: ProviderEntry, policy: RoutingPolicy, req: AiRequest): String {
        if (req.model.isNotBlank()) return req.model
        val strong = tier(policy, req.purpose, req.escalate) == ModelTier.STRONG
        val chosen = if (strong) p.strongModel else p.fastModel
        return chosen.ifBlank { if (strong) p.fastModel else p.strongModel }
    }

    /** A different model of the same provider (used when one model misbehaves, e.g. emits native tool calls). */
    fun alternateModel(p: ProviderEntry, current: String): String? =
        listOf(p.strongModel, p.fastModel).filter { it.isNotBlank() && it != current }.firstOrNull()
}
