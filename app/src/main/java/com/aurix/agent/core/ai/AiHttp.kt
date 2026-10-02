package com.aurix.agent.core.ai

import com.aurix.agent.core.net.awaitResponse
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.net.SocketTimeoutException

internal fun redactSecret(text: String, secret: String): String =
    text.replace(secret, "***").replace(Regex("(sk-|AIza|gsk_)[A-Za-z0-9_\\-]{6,}"), "***")

internal fun mapHttpError(code: Int, retryAfter: String?, body: String, secret: String): AiError {
    val parsed = try { JSONObject(body).optJSONObject("error")?.optString("message") } catch (e: Exception) { null }
    val detail = if (parsed.isNullOrBlank()) body else parsed
    val type = when (code) {
        401, 403 -> AiErrorType.AUTH_ERROR
        429 -> AiErrorType.RATE_LIMIT
        408 -> AiErrorType.TIMEOUT
        400, 404, 422 -> AiErrorType.INVALID_INPUT
        in 500..599 -> AiErrorType.MODEL_ERROR
        else -> AiErrorType.UNKNOWN_ERROR
    }
    return AiError(type, "HTTP $code: ${redactSecret(detail.take(300), secret)}", retryAfter?.toLongOrNull()?.times(1000))
}

/** POST JSON, map every failure to a categorized AiError, never leak the secret. */
internal suspend fun OkHttpClient.postJson(url: String, headers: Map<String, String>, payload: JSONObject, secret: String): JSONObject {
    val req = try {
        Request.Builder().url(url).apply { headers.forEach { (k, v) -> header(k, v) } }
            .post(payload.toString().toRequestBody("application/json".toMediaType())).build()
    } catch (e: IllegalArgumentException) {
        throw AiError(AiErrorType.INVALID_INPUT, "Invalid base URL")
    }
    val resp = try {
        newCall(req).awaitResponse()
    } catch (e: SocketTimeoutException) {
        throw AiError(AiErrorType.TIMEOUT, "Request timed out")
    } catch (e: IOException) {
        throw AiError(AiErrorType.NETWORK_ERROR, "Network error (${e.javaClass.simpleName})")
    }
    return resp.use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw mapHttpError(r.code, r.header("Retry-After"), body, secret)
        try { JSONObject(body) } catch (e: JSONException) { throw AiError(AiErrorType.MODEL_ERROR, "Provider returned a non-JSON response") }
    }
}
