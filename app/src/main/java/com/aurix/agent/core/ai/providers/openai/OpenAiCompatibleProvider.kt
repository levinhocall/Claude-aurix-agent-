package com.aurix.agent.core.ai.providers.openai

import com.aurix.agent.core.ai.*
import com.aurix.agent.core.ai.routing.Capabilities
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** OpenAI, Groq, Gemini (OpenAI-compat endpoint), OpenRouter, Ollama and any other OpenAI-compatible API. */
class OpenAiCompatibleProvider(
    override val id: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
) : AiProvider {
    private val isOpenAi = baseUrl.contains("api.openai.com")
    private val url get() = baseUrl.trimEnd('/') + "/chat/completions"
    private fun headers(key: String): Map<String, String> = if (key.isBlank()) emptyMap() else mapOf("Authorization" to "Bearer $key")

    private fun payload(r: AiRequest, json: Boolean): JSONObject {
        val p = JSONObject().put("model", r.model).put("messages", JSONArray().also { arr ->
            r.messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
        })
        if (isOpenAi) p.put("max_completion_tokens", r.maxTokens) // newer OpenAI models reject max_tokens / custom temperature
        else p.put("max_tokens", r.maxTokens).put("temperature", r.temperature)
        if (json && Capabilities.jsonMode(baseUrl, r.model)) p.put("response_format", JSONObject().put("type", "json_object"))
        return p
    }

    private fun mentionsJsonMode(e: AiError) = e.message?.contains("response_format", ignoreCase = true) == true

    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
        val json = try {
            client.postJson(url, headers(apiKey), payload(request, request.json), apiKey)
        } catch (e: AiError) {
            if (request.json && mentionsJsonMode(e)) client.postJson(url, headers(apiKey), payload(request, false), apiKey) else throw e
        }
        val content = json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
        if (content.isBlank()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        val u = json.optJSONObject("usage")
        return AiResponse(content, TokenUsage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0), json.optString("model", request.model))
    }

    override suspend fun stream(request: AiRequest, apiKey: String, onDelta: suspend (String) -> Unit): AiResponse {
        suspend fun run(withJson: Boolean): AiResponse {
            val p = payload(request, withJson).put("stream", true)
            if (isOpenAi || baseUrl.contains("groq.com") || baseUrl.contains("openrouter.ai")) p.put("stream_options", JSONObject().put("include_usage", true))
            val text = StringBuilder()
            var usage = TokenUsage()
            var model = request.model
            client.postSse(url, headers(apiKey), p, apiKey) { d ->
                val j = try { JSONObject(d) } catch (e: JSONException) { return@postSse }
                j.optJSONObject("usage")?.let { u -> usage = TokenUsage(u.optInt("prompt_tokens"), u.optInt("completion_tokens")) }
                j.optString("model").takeIf { it.isNotBlank() }?.let { model = it }
                val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content").orEmpty()
                if (delta.isNotEmpty()) { text.append(delta); onDelta(delta) }
            }
            if (text.isBlank()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
            return AiResponse(text.toString(), usage, model)
        }
        return try { run(request.json) } catch (e: AiError) { if (request.json && mentionsJsonMode(e)) run(false) else throw e }
    }
}
