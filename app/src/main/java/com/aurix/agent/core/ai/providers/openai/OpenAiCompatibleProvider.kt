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
        val p = JSONObject().put("model", r.model).put("messages", openAiMessages(r.messages))
        if (r.tools.isNotEmpty()) p.put("tools", openAiTools(r.tools))
        if (isOpenAi) p.put("max_completion_tokens", r.maxTokens) // newer OpenAI models reject max_tokens / custom temperature
        else p.put("max_tokens", r.maxTokens).put("temperature", r.temperature)
        if (json && r.tools.isEmpty() && Capabilities.jsonMode(baseUrl, r.model)) p.put("response_format", JSONObject().put("type", "json_object"))
        return p
    }

    private fun mentionsJsonMode(e: AiError) = e.message?.contains("response_format", ignoreCase = true) == true

    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
        val json = try {
            client.postJson(url, headers(apiKey), payload(request, request.json), apiKey)
        } catch (e: AiError) {
            if (request.json && mentionsJsonMode(e)) client.postJson(url, headers(apiKey), payload(request, false), apiKey) else throw e
        }
        val (content, calls) = parseOpenAiMessage(json.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message"))
        if (content.isBlank() && calls.isEmpty()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
        val u = json.optJSONObject("usage")
        return AiResponse(content, TokenUsage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0), json.optString("model", request.model), calls)
    }

    override suspend fun stream(request: AiRequest, apiKey: String, onDelta: suspend (String) -> Unit): AiResponse {
        suspend fun run(withJson: Boolean): AiResponse {
            val p = payload(request, withJson).put("stream", true)
            if (isOpenAi || baseUrl.contains("groq.com") || baseUrl.contains("openrouter.ai")) p.put("stream_options", JSONObject().put("include_usage", true))
            val text = StringBuilder()
            val pending = java.util.TreeMap<Int, PartialCall>()
            var usage = TokenUsage()
            var model = request.model
            client.postSse(url, headers(apiKey), p, apiKey) { d ->
                val j = try { JSONObject(d) } catch (e: JSONException) { return@postSse }
                j.optJSONObject("usage")?.let { u -> usage = TokenUsage(u.optInt("prompt_tokens"), u.optInt("completion_tokens")) }
                j.optString("model").takeIf { it.isNotBlank() }?.let { model = it }
                val d = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")
                val delta = if (d == null || d.isNull("content")) "" else d.optString("content")
                if (delta.isNotEmpty()) { text.append(delta); onDelta(delta) }
                d?.optJSONArray("tool_calls")?.let { arr ->
                    for (i in 0 until arr.length()) {
                        val tc = arr.optJSONObject(i) ?: continue
                        val pc = pending.getOrPut(tc.optInt("index", i)) { PartialCall() }
                        tc.optString("id").takeIf { it.isNotBlank() }?.let { pc.id = it }
                        tc.optJSONObject("function")?.let { fn ->
                            fn.optString("name").takeIf { it.isNotBlank() }?.let { pc.name = it }
                            if (!fn.isNull("arguments")) pc.args.append(fn.optString("arguments"))
                        }
                    }
                }
            }
            val calls = pending.values.filter { it.name.isNotBlank() }.mapIndexed { i, pc -> AiToolCall(pc.id.ifBlank { "call_$i" }, pc.name, parseToolArguments(pc.args.toString())) }
            if (text.isBlank() && calls.isEmpty()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
            return AiResponse(text.toString(), usage, model, calls)
        }
        return try { run(request.json) } catch (e: AiError) { if (request.json && mentionsJsonMode(e)) run(false) else throw e }
    }
}

private class PartialCall { var id = ""; var name = ""; val args = StringBuilder() }

internal fun parseToolArguments(raw: String): JSONObject {
    val t = raw.trim()
    if (t.isEmpty()) return JSONObject()
    return try { JSONObject(t) } catch (e: JSONException) { JSONObject().put("__invalid_arguments", t.take(200)) }
}

internal fun openAiMessages(msgs: List<AiMessage>): JSONArray = JSONArray().also { arr ->
    msgs.forEach { m ->
        val o = JSONObject().put("role", m.role)
        when {
            m.role == "tool" -> o.put("tool_call_id", m.toolCallId.orEmpty()).put("content", m.content)
            m.role == "assistant" && m.toolCalls.isNotEmpty() -> {
                o.put("content", if (m.content.isBlank()) JSONObject.NULL else m.content)
                o.put("tool_calls", JSONArray().also { a ->
                    m.toolCalls.forEach { c -> a.put(JSONObject().put("id", c.id).put("type", "function").put("function", JSONObject().put("name", c.name).put("arguments", c.arguments.toString()))) }
                })
            }
            else -> o.put("content", m.content)
        }
        arr.put(o)
    }
}

internal fun openAiTools(tools: List<AiTool>): JSONArray = JSONArray().also { a ->
    tools.forEach { t -> a.put(JSONObject().put("type", "function").put("function", JSONObject().put("name", t.name).put("description", t.description).put("parameters", t.parameters))) }
}

internal fun parseOpenAiMessage(m: JSONObject?): Pair<String, List<AiToolCall>> {
    if (m == null) return "" to emptyList()
    val content = if (m.isNull("content")) "" else m.optString("content")
    val arr = m.optJSONArray("tool_calls")
    val calls = if (arr == null) emptyList() else (0 until arr.length()).mapNotNull { i ->
        val tc = arr.optJSONObject(i) ?: return@mapNotNull null
        val fn = tc.optJSONObject("function") ?: return@mapNotNull null
        val name = fn.optString("name")
        if (name.isBlank()) null else AiToolCall(tc.optString("id").ifBlank { "call_$i" }, name, parseToolArguments(if (fn.isNull("arguments")) "" else fn.optString("arguments")))
    }
    return content to calls
}
