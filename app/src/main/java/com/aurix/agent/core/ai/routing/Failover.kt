package com.aurix.agent.core.ai.routing

import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.AiErrorType
import com.aurix.agent.core.ai.AiProvider
import com.aurix.agent.core.ai.AiRequest
import com.aurix.agent.core.ai.AiResponse
import kotlinx.coroutines.delay

/**
 * Provider/key selection and failover.
 *  - AUTH error      -> key disabled, try next key
 *  - RATE_LIMIT      -> key cools down (honours Retry-After); next key of the SAME provider is used only if the
 *                       provider is marked independentKeys, otherwise we fail over to the next provider
 *  - provider (5xx)  -> key cools down briefly, next provider
 *  - network/timeout -> one quick retry, then next provider (rotating keys cannot fix connectivity)
 *  - invalid request -> surfaced immediately (our bug, not the key's)
 * Keys are never used to dodge quotas: cooldowns are always respected.
 */
internal class Failover(
    private val providers: () -> List<ProviderEntry>,
    private val policy: () -> RoutingPolicy,
    private val pool: KeyPool,
    private val adapter: (ProviderEntry) -> AiProvider,
    private val checkBudget: () -> Unit,
    private val onTokens: (Int) -> Unit,
    private val sleep: suspend (Long) -> Unit = { delay(it) },
) {
    suspend fun complete(request: AiRequest): AiResponse {
        checkBudget()
        val usable = providers().filter { it.enabled && it.keys.isNotEmpty() }
        if (usable.isEmpty()) throw AiError(AiErrorType.AUTH_ERROR, "No provider with an API key is configured. Open Settings.")
        var last: AiError? = null
        for (round in 0..1) {
            for (p in usable) {
                val model = ModelRouter.modelFor(p, policy(), request)
                var tried = 0
                while (tried < MAX_KEYS_PER_PROVIDER) {
                    val key = pool.available(p.keys).firstOrNull() ?: break
                    tried++
                    pool.markUsed(key.id)
                    try {
                        val resp = callOnce(p, request.copy(model = model), key.secret)
                        pool.success(key.id, resp.usage.total)
                        onTokens(resp.usage.total)
                        return resp
                    } catch (e: AiError) {
                        last = e
                        when (e.type) {
                            AiErrorType.AUTH_ERROR -> pool.disable(key.id, "auth failed")
                            AiErrorType.RATE_LIMIT -> {
                                pool.cooldown(key.id, (e.retryAfterMs ?: 60_000L).coerceIn(5_000L, 600_000L), "rate limited")
                                if (!p.independentKeys) break
                            }
                            AiErrorType.MODEL_ERROR -> { pool.cooldown(key.id, 20_000L, "provider error"); break }
                            AiErrorType.NETWORK_ERROR, AiErrorType.TIMEOUT -> break
                            else -> throw e
                        }
                    }
                }
            }
            val wait = soonest(usable)
            val l = last
            if (round == 0 && (l == null || l.type == AiErrorType.RATE_LIMIT) && wait != null && wait in 1..45_000L) {
                sleep(wait + 250)
                continue
            }
            break
        }
        last?.let { throw it }
        val wait = soonest(usable)
        throw AiError(
            AiErrorType.RATE_LIMIT,
            "All API keys are cooling down" + (wait?.let { "; next available in ${it / 1000}s" } ?: " or disabled. Check keys in Settings."),
            wait,
        )
    }

    private fun soonest(list: List<ProviderEntry>): Long? = list.mapNotNull { pool.nextAvailableIn(it.keys) }.minOrNull()

    private suspend fun callOnce(p: ProviderEntry, req: AiRequest, secret: String): AiResponse {
        val a = adapter(p)
        var attempt = 0
        while (true) {
            try {
                return a.complete(req, secret)
            } catch (e: AiError) {
                if ((e.type == AiErrorType.NETWORK_ERROR || e.type == AiErrorType.TIMEOUT) && attempt < 1) {
                    attempt++
                    sleep(1_500)
                    continue
                }
                throw e
            }
        }
    }

    private companion object { const val MAX_KEYS_PER_PROVIDER = 3 }
}
