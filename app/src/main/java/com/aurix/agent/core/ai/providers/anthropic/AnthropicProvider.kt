package com.aurix.agent.core.ai.providers.anthropic

import com.aurix.agent.core.ai.*
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/** Native Anthropic Messages API. baseUrl example: https://api.anthropic.com */
class AnthropicProvider(
    override val id: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
) : AiProvider {

    private fun body(request: AiRequest): JSONObject {
        val system = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val msgs = JSONArray()
        request.messages.filter { it.role != "system" }.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject().put("model", request.model).put("max_tokens", request.maxTokens).put("messages", msgs)
        if (system.isNotBlank()) payload.put("system", system)
        return payload
    }

    private val url get() = baseUrl.trimEnd('/') + "/v1/messages"
    private fun headers(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

    override suspend fun stream(request: AiRequest, apiKey: String, onDelta: suspend (String) -> Unit): AiResponse {
        val text = StringBuilder()
        var inTok = 0; var outTok = 0; var model = request.model
        client.postSse(url, headers(apiKey), body(request).put("stream", true), apiKey) { d ->
            val j = try { JSONObject(d) } catch (e: JSONException) { return@postSse }
            when (j.optString("type")) {
                "message_start" -> j.optJSONObject("message")?.let { m -> inTok = m.optJSONObject("usage")?.optInt("input_tokens") ?: 0; model = m.optString("model", model) }
                "content_block_delta" -> {
                    val t = j.optJSONObject("delta")?.optString("text").orEmpty()
                    if (t.isNotEmpty()) { text.append(t); onDelta(t) }
                }
                "message_delta" -> outTok = j.optJSONObject("usage")?.optInt("output_tokens") ?: outTok
                "error" -> throw AiError(AiErrorType.MODEL_ERROR, j.optJSONObject("error")?.optString("message").orEmpty().take(200))
            }
        }
        if (text.isBlank()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        return AiResponse(text.toString(), TokenUsage(inTok, outTok), model)
    }

    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
        val system = request.messages.filter { it.role == "system" }.joinToString("\n\n") { it.content }
        val msgs = JSONArray()
        request.messages.filter { it.role != "system" }.forEach { msgs.put(JSONObject().put("role", it.role).put("content", it.content)) }
        val payload = JSONObject().put("model", request.model).put("max_tokens", request.maxTokens).put("messages", msgs)
        if (system.isNotBlank()) payload.put("system", system)
        val json = client.postJson(
            baseUrl.trimEnd('/') + "/v1/messages",
            mapOf("x-api-key" to apiKey, "anthropic-version" to "2023-06-01"),
            payload, apiKey,
        )
        val blocks = json.optJSONArray("content")
        val text = buildString {
            if (blocks != null) for (i in 0 until blocks.length()) {
                val b = blocks.optJSONObject(i) ?: continue
                if (b.optString("type") == "text") append(b.optString("text"))
            }
        }
        if (text.isBlank()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        val u = json.optJSONObject("usage")
        return AiResponse(text, TokenUsage(u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0), json.optString("model", request.model))
    }
}
