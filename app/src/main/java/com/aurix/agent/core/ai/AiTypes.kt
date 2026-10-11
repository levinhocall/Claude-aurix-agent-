package com.aurix.agent.core.ai

import org.json.JSONObject

/** A native function call requested by the model. Unparseable arguments arrive as {"__invalid_arguments": "<raw>"}. */
data class AiToolCall(val id: String, val name: String, val arguments: JSONObject)

/** A tool the model may call natively; [parameters] is a JSON Schema object. */
data class AiTool(val name: String, val description: String, val parameters: JSONObject)

/** role: system | user | assistant | tool. Assistant messages may carry [toolCalls]; tool messages answer one call via [toolCallId]. */
data class AiMessage(val role: String, val content: String, val toolCalls: List<AiToolCall> = emptyList(), val toolCallId: String? = null)

/** model == "" means "let the router decide" (Phase 3 adds real routing policies). */
data class AiRequest(
    val messages: List<AiMessage>,
    val model: String = "",
    val temperature: Double = 0.2,
    val maxTokens: Int = 2048,
    val purpose: String = "",      // plan | replan | step | verify | test — used by the router
    val escalate: Boolean = false, // retry after failure: router may pick a stronger model
    val json: Boolean = false,     // reply must be a JSON object (JSON mode where the model supports it)
    val tools: List<AiTool> = emptyList(), // native function calling (the model may answer with toolCalls)
)

data class TokenUsage(val promptTokens: Int = 0, val completionTokens: Int = 0) {
    val total: Int get() = promptTokens + completionTokens
}

data class AiResponse(val text: String, val usage: TokenUsage, val model: String, val toolCalls: List<AiToolCall> = emptyList())

enum class AiErrorType { NETWORK_ERROR, AUTH_ERROR, RATE_LIMIT, TIMEOUT, MODEL_ERROR, INVALID_INPUT, BUDGET_EXCEEDED, UNKNOWN_ERROR }

class AiError(val type: AiErrorType, message: String, val retryAfterMs: Long? = null, val modelSpecific: Boolean = false) : Exception(message)

/** Provider-specific code lives behind this interface. The runtime never sees a concrete provider. */
interface AiProvider {
    val id: String
    suspend fun complete(request: AiRequest, apiKey: String): AiResponse

    /** Streams text deltas to [onDelta]; providers without streaming just fall back to complete(). */
    suspend fun stream(request: AiRequest, apiKey: String, onDelta: suspend (String) -> Unit): AiResponse = complete(request, apiKey)
}
