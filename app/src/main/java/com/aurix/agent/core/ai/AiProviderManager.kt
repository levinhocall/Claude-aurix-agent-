package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.providers.anthropic.AnthropicProvider
import com.aurix.agent.core.ai.providers.openai.OpenAiCompatibleProvider
import com.aurix.agent.core.ai.routing.Failover
import com.aurix.agent.core.ai.routing.KeyEntry
import com.aurix.agent.core.ai.routing.KeyPool
import com.aurix.agent.core.ai.routing.ModelRouter
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.ProviderStore
import com.aurix.agent.core.ai.routing.ProviderType
import com.aurix.agent.core.ai.routing.RoutingPolicy
import com.aurix.agent.core.ai.routing.UsageTracker
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** The only AI entry point for the runtime: routing policy, key pool, cooldowns and cross-provider failover. */
@Singleton
class AiProviderManager @Inject constructor(
    private val store: ProviderStore,
    private val pool: KeyPool,
    private val usage: UsageTracker,
    private val client: OkHttpClient,
) {
    private val failover = Failover(
        providers = { store.providers() },
        policy = { store.policy() },
        pool = pool,
        adapter = { p -> adapterFor(p) },
        checkBudget = { usage.checkBudget() },
        onTokens = { usage.add(it) },
    )

    suspend fun complete(request: AiRequest, onDelta: (suspend (String) -> Unit)? = null): AiResponse = failover.complete(request, onDelta)

    fun health(providerId: String): String {
        val h = failover.healthOf(providerId)
        return if (h.ok + h.fail == 0) "no calls yet" else "${h.ok} ok · ${h.fail} failed · avg ${"%.1f".format(h.avgMs / 1000.0)}s" + (h.lastError?.let { " · last: $it" } ?: "")
    }

    fun hasProvider(): Boolean = store.providers().any { it.enabled && (it.local || it.keys.isNotEmpty()) }

    private fun adapterFor(p: ProviderEntry): AiProvider = when (p.type) {
        ProviderType.OPENAI_COMPATIBLE -> OpenAiCompatibleProvider(p.id, p.baseUrl, client)
        ProviderType.ANTHROPIC -> AnthropicProvider(p.id, p.baseUrl, client)
    }

    /** Pings every key of one provider with a tiny request; re-enables keys that work. */
    suspend fun test(providerId: String): String {
        val p = store.providers().firstOrNull { it.id == providerId } ?: return "Provider not found"
        if (p.keys.isEmpty() && !p.local) return "Add an API key first"
        val probe = AiRequest(emptyList(), purpose = "test")
        val model = ModelRouter.modelFor(p, RoutingPolicy.ECONOMY, probe)
        if (model.isBlank()) return "Set a model name first"
        val out = StringBuilder()
        for (k in if (p.local) listOf(KeyEntry("local-${p.id}", "local", "")) else p.keys) {
            pool.reset(k.id)
            val t0 = System.currentTimeMillis()
            val line = try {
                adapterFor(p).complete(
                    AiRequest(listOf(AiMessage("user", "Reply with the single word OK.")), model = model, maxTokens = 16, purpose = "test"),
                    k.secret,
                )
                "OK (${System.currentTimeMillis() - t0} ms, model $model)"
            } catch (e: AiError) {
                if (e.type == AiErrorType.AUTH_ERROR) pool.disable(k.id, "auth failed")
                "${e.type}: ${e.message}"
            }
            out.append(k.label).append(": ").append(line).append('\n')
        }
        return out.toString().trim()
    }
}
