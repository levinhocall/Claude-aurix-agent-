package com.aurix.agent.core.ai

data class AiMessage(val role: String, val content: String)

/** model == "" means "let the router decide" (Phase 3 adds real routing policies). */
data class AiRequest(
    val messages: List<AiMessage>,
    val model: String = "",
    val temperature: Double = 0.2,
    val maxTokens: Int = 2048,
)

data class TokenUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val total: Int get() = promptTokens + completionTokens
}

data class AiResponse(val text: String, val usage: TokenUsage, val model: String)

enum class AiErrorType { NETWORK_ERROR, AUTH_ERROR, RATE_LIMIT, TIMEOUT, MODEL_ERROR, INVALID_INPUT, UNKNOWN_ERROR }

class AiError(val type: AiErrorType, message: String, val retryAfterMs: Long? = null) : Exception(message)

/** Provider-specific code lives behind this interface. The runtime never sees a concrete provider. */
interface AiProvider {
    val id: String
    suspend fun complete(request: AiRequest, apiKey: String): AiResponse
}
