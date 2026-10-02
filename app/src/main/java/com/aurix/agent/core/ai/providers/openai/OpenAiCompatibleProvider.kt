package com.aurix.agent.core.ai.providers.openai

import com.aurix.agent.core.ai.*
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** Works with OpenAI, Groq, Gemini's OpenAI-compat endpoint, OpenRouter, etc. */
class OpenAiCompatibleProvider(
    override val id: String,
    private val baseUrl: String,
    private val client: OkHttpClient,
) : AiProvider {

    override suspend fun complete(request: AiRequest, apiKey: String): AiResponse {
        val httpReq = try {
            val payload = JSONObject()
                .put("model", request.model)
                .put("temperature", request.temperature)
                .put("max_tokens", request.maxTokens)
                .put("messages", JSONArray().also { arr ->
                    request.messages.forEach { arr.put(JSONObject().put("role", it.role).put("content", it.content)) }
                })
            Request.Builder()
                .url(baseUrl.trimEnd('/') + "/chat/completions")
                .header("Authorization", "Bearer $apiKey")
                .post(payload.toString().toRequestBody("application/json".toMediaType()))
                .build()
        } catch (e: IllegalArgumentException) {
            throw AiError(AiErrorType.INVALID_INPUT, "Invalid base URL")
        }

        val response = try {
            client.newCall(httpReq).await()
        } catch (e: SocketTimeoutException) {
            throw AiError(AiErrorType.TIMEOUT, "Request timed out")
        } catch (e: IOException) {
            throw AiError(AiErrorType.NETWORK_ERROR, "Network error (${e.javaClass.simpleName})")
        }

        return response.use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw mapError(resp.code, resp.header("Retry-After"), body, apiKey)
            val json = try { JSONObject(body) } catch (e: JSONException) {
                throw AiError(AiErrorType.MODEL_ERROR, "Provider returned a non-JSON response")
            }
            val content = json.optJSONArray("choices")?.optJSONObject(0)
                ?.optJSONObject("message")?.optString("content").orEmpty()
            if (content.isBlank()) throw AiError(AiErrorType.MODEL_ERROR, "Empty model response")
            val u = json.optJSONObject("usage")
            AiResponse(
                text = content,
                usage = TokenUsage(u?.optInt("prompt_tokens") ?: 0, u?.optInt("completion_tokens") ?: 0),
                model = json.optString("model", request.model),
            )
        }
    }

    private fun mapError(code: Int, retryAfter: String?, body: String, apiKey: String): AiError {
        val parsed = try {
            JSONObject(body).optJSONObject("error")?.optString("message")
        } catch (e: Exception) { null }
        val detail = if (parsed.isNullOrBlank()) body else parsed
        val type = when (code) {
            401, 403 -> AiErrorType.AUTH_ERROR
            429 -> AiErrorType.RATE_LIMIT
            408 -> AiErrorType.TIMEOUT
            400, 404, 422 -> AiErrorType.INVALID_INPUT
            in 500..599 -> AiErrorType.MODEL_ERROR
            else -> AiErrorType.UNKNOWN_ERROR
        }
        return AiError(type, "HTTP $code: ${redact(detail.take(300), apiKey)}", retryAfter?.toLongOrNull()?.times(1000))
    }

    private fun redact(text: String, apiKey: String): String =
        text.replace(apiKey, "***").replace(Regex("sk-[A-Za-z0-9_\\-]{6,}"), "sk-***")
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { cont ->
    cont.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) { if (cont.isActive) cont.resumeWithException(e) }
        override fun onResponse(call: Call, response: Response) { cont.resume(response) }
    })
}
