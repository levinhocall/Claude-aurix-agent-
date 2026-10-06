package com.aurix.agent.core.ai.providers.openai

import com.aurix.agent.core.ai.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/** OpenAI, Groq, Gemini (OpenAI-compat endpoint), OpenRouter, and any other OpenAI-compatible API. */
class OpenAiCompatibleProvider(
    override val id: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
) : AiProvider {

    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
        val isOpenAi = baseUrl.contains("api.openai.com")
        val payload = JSONObject()
            .put("model", request.model)
            .put("messages", JSONArray().also { arr ->
                request.messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
            })
        if (isOpenAi) {
            payload.put("max_completion_tokens", request.maxTokens) // newer OpenAI models reject max_tokens / custom temperature
        } else {
            payload.put("max_tokens", request.maxTokens).put("temperature", request.temperature)
        }
        val json = client.postJson(baseUrl.trimEnd('/') + "/chat/completions", if (apiKey.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $apiKey"), payload, apiKey)
        val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        val u = json.optJSONObject("usage")
        return AiResponse(content, TokenUsage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0), json.optString("model", request.model))
    }
}
