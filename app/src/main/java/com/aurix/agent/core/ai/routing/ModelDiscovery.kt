package com.aurix.agent.core.ai.routing

import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.getJson
import okhttp3.OkHttpClient
import javax.inject.Inject
import javax.inject.Singleton

/** Lists the models a key can actually use (also validates the key: 401 => invalid). */
@Singleton
class ModelDiscovery @Inject constructor(private val client: OkHttpClient) {

    /** Throws AiError (AUTH_ERROR etc.) when the key is rejected or the endpoint is unreachable. */
    suspend fun listModels(type: ProviderType, baseUrl: String, secret: String): List<String> {
        val base = baseUrl.trimEnd('/')
        val json = when (type) {
            ProviderType.ANTHROPIC -> client.getJson("$base/v1/models?limit=100", mapOf("x-api-key" to secret, "anthropic-version" to "2023-06-01"), secret)
            ProviderType.OPENAI_COMPATIBLE -> client.getJson("$base/models", mapOf("Authorization" to "Bearer $secret"), secret)
        }
        val arr = json.optJSONArray("data") ?: json.optJSONArray("models") ?: return emptyList()
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i)
            o?.optString("id")?.ifBlank { null } ?: o?.optString("name")?.ifBlank { null }
        }
    }

    suspend fun pick(preset: Preset, secret: String): Pair<ModelPicker.Pick, String?> {
        return try {
            val ids = listModels(preset.type, preset.baseUrl, secret)
            ModelPicker.pick(preset.type, ids, preset.strong, preset.fast) to null
        } catch (e: AiError) {
            if (e.type == com.aurix.agent.core.ai.AiErrorType.AUTH_ERROR) throw e
            ModelPicker.Pick(preset.strong, preset.fast) to "Could not list models (${e.type}); using defaults"
        }
    }
}
