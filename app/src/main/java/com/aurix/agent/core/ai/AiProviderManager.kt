package com.aurix.agent.core.ai

import com.aurix.agent.core.ai.providers.openai.OpenAiCompatibleProvider
import com.aurix.agent.core.security.SecureSettings
import kotlinx.coroutines.delay
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Phase 1: one configured OpenAI-compatible provider + bounded retry on transient errors.
 * Phase 3 replaces this with the key pool, cooldowns, routing policies and cross-provider fallback.
 */
@Singleton
class AiProviderManager @Inject constructor(
    private val settings: SecureSettings,
    private val client: OkHttpClient,
) {
    suspend fun complete(request: AiRequest): AiResponse {
        val cfg = settings.load()
            ?: throw AiError(AiErrorType.AUTH_ERROR, "No API key configured. Open Settings and add one.")
        val provider: AiProvider = OpenAiCompatibleProvider("openai-compatible", cfg.baseUrl, client)
        val req = if (request.model.isBlank()) request.copy(model = cfg.model) else request
        var attempt = 0
        while (true) {
            try {
                return provider.complete(req, cfg.apiKey)
            } catch (e: AiError) {
                val transient = e.type == AiErrorType.NETWORK_ERROR || e.type == AiErrorType.TIMEOUT || e.type == AiErrorType.RATE_LIMIT
                if (!transient || attempt >= MAX_RETRIES) throw e
                attempt++
                delay((e.retryAfterMs ?: (1_000L shl attempt)).coerceAtMost(30_000L))
            }
        }
    }

    private companion object { const val MAX_RETRIES = 3 }
}
