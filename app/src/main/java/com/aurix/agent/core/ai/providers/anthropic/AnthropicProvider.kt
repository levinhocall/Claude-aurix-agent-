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
        val payload = JSONObject().put("model", request.model).put("max_tokens", request.maxTokens).put("messages", anthropicMessages(request.messages))
        if (system.isNotBlank()) payload.put("system", system)
        if (request.tools.isNotEmpty()) payload.put("tools", anthropicTools(request.tools))
        return payload
    }

    private val url get() = baseUrl.trimEnd('/') + "/v1/messages"
    private fun headers(key: String) = mapOf("x-api-key" to key, "anthropic-version" to "2023-06-01")

    override suspend fun stream(request: AiRequest, apiKey: String, onDelta: suspend (String) -> Unit): AiResponse {
        val text = StringBuilder()
        val blocks = java.util.TreeMap<Int, ToolBlock>()
        var inTok = 0; var outTok = 0; var model = request.model
        client.postSse(url, headers(apiKey), body(request).put("stream", true), apiKey) { d ->
            val j = try { JSONObject(d) } catch (e: JSONException) { return@postSse }
            when (j.optString("type")) {
                "message_start" -> j.optJSONObject("message")?.let { m -> inTok = m.optJSONObject("usage")?.optInt("input_tokens") ?: 0; model = m.optString("model", model) }
                "content_block_start" -> j.optJSONObject("content_block")?.takeIf { it.optString("type") == "tool_use" }?.let { b ->
                    blocks[j.optInt("index")] = ToolBlock(b.optString("id"), b.optString("name"))
                }
                "content_block_delta" -> {
                    val d = j.optJSONObject("delta")
                    if (d?.optString("type") == "input_json_delta") blocks[j.optInt("index")]?.args?.append(d.optString("partial_json"))
                    else {
                        val t = d?.optString("text").orEmpty()
                        if (t.isNotEmpty()) { text.append(t); onDelta(t) }
                    }
                }
                "message_delta" -> outTok = j.optJSONObject("usage")?.optInt("output_tokens") ?: outTok
                "error" -> throw AiError(AiErrorType.MODEL_ERROR, j.optJSONObject("error")?.optString("message").orEmpty().take(200))
            }
        }
        val calls = blocks.values.filter { it.name.isNotBlank() }.map { AiToolCall(it.id, it.name, parseToolInput(it.args.toString())) }
        if (text.isBlank() && calls.isEmpty()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        return AiResponse(text.toString(), TokenUsage(inTok, outTok), model, calls)
    }

    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
        val json = client.postJson(url, headers(apiKey), body(request), apiKey)
        val (text, calls) = parseAnthropicContent(json.optJSONArray("content"))
        if (text.isBlank() && calls.isEmpty()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        val u = json.optJSONObject("usage")
        return AiResponse(text, TokenUsage(u?.optInt("input_tokens") ?: 0, u?.optInt("output_tokens") ?: 0), json.optString("model", request.model), calls)
    }
}

private class ToolBlock(val id: String, val name: String) { val args = StringBuilder() }

internal fun parseToolInput(raw: String): JSONObject {
    val t = raw.trim()
    if (t.isEmpty()) return JSONObject()
    return try { JSONObject(t) } catch (e: JSONException) { JSONObject().put("__invalid_arguments", t.take(200)) }
}

internal fun anthropicTools(tools: List<AiTool>): JSONArray = JSONArray().also { a ->
    tools.forEach { t -> a.put(JSONObject().put("name", t.name).put("description", t.description).put("input_schema", t.parameters)) }
}

/** system messages are lifted out by the caller; tool results of one turn are merged into a single user message as Anthropic requires. */
internal fun anthropicMessages(all: List<AiMessage>): JSONArray {
    val out = JSONArray()
    var results = JSONArray()
    fun flush() { if (results.length() > 0) { out.put(JSONObject().put("role", "user").put("content", results)); results = JSONArray() } }
    for (m in all) {
        if (m.role == "system") continue
        if (m.role == "tool") {
            results.put(JSONObject().put("type", "tool_result").put("tool_use_id", m.toolCallId.orEmpty()).put("content", m.content))
            continue
        }
        flush()
        if (m.role == "assistant" && m.toolCalls.isNotEmpty()) {
            val blocks = JSONArray()
            if (m.content.isNotBlank()) blocks.put(JSONObject().put("type", "text").put("text", m.content))
            m.toolCalls.forEach { c -> blocks.put(JSONObject().put("type", "tool_use").put("id", c.id).put("name", c.name).put("input", c.arguments)) }
            out.put(JSONObject().put("role", "assistant").put("content", blocks))
        } else out.put(JSONObject().put("role", m.role).put("content", m.content))
    }
    flush()
    return out
}

internal fun parseAnthropicContent(blocks: JSONArray?): Pair<String, List<AiToolCall>> {
    val text = StringBuilder(); val calls = ArrayList<AiToolCall>()
    if (blocks != null) for (i in 0 until blocks.length()) {
        val b = blocks.optJSONObject(i) ?: continue
        when (b.optString("type")) {
            "text" -> text.append(b.optString("text"))
            "tool_use" -> calls += AiToolCall(b.optString("id"), b.optString("name"), b.optJSONObject("input") ?: JSONObject())
        }
    }
    return text.toString() to calls
}
