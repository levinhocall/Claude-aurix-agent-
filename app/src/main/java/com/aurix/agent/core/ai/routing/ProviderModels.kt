package com.aurix.agent.core.ai.routing

import org.json.JSONArray
import org.json.JSONObject

enum class ProviderType(val label: String) { OPENAI_COMPATIBLE("OpenAI-compatible"), ANTHROPIC("Anthropic") }
enum class RoutingPolicy(val label: String, val hint: String) {
    QUALITY("Quality", "strongest model for everything"),
    BALANCED("Balanced", "strong for plan/verify, fast for steps (escalates on retry)"),
    ECONOMY("Economy", "fast model for everything"),
}
enum class ModelTier { FAST, STRONG }

data class KeyEntry(val id: String, val label: String, val secret: String)

data class ProviderEntry(
    val id: String,
    val type: ProviderType,
    val name: String,
    val baseUrl: String,
    val fastModel: String,
    val strongModel: String,
    val enabled: Boolean = true,
    /** true = keys belong to separate accounts/projects, so a rate-limited key may fail over to the next key of the same provider. */
    val independentKeys: Boolean = false,
    val keys: List<KeyEntry> = emptyList(),
)

data class Preset(val name: String, val type: ProviderType, val baseUrl: String, val fast: String, val strong: String)

object Presets {
    val all = listOf(
        Preset("OpenAI", ProviderType.OPENAI_COMPATIBLE, "https://api.openai.com/v1", "gpt-4o-mini", "gpt-4o"),
        Preset("Anthropic", ProviderType.ANTHROPIC, "https://api.anthropic.com", "claude-haiku-4-5-20251001", "claude-sonnet-5-5"),
        Preset("Gemini", ProviderType.OPENAI_COMPATIBLE, "https://generativelanguage.googleapis.com/v1beta/openai", "gemini-2.5-flash", "gemini-2.5-pro"),
        Preset("Groq", ProviderType.OPENAI_COMPATIBLE, "https://api.groq.com/openai/v1", "llama-3.1-8b-instant", "llama-3.3-70b-versatile"),
        Preset("OpenRouter", ProviderType.OPENAI_COMPATIBLE, "https://openrouter.ai/api/v1", "openai/gpt-4o-mini", "openai/gpt-4o"),
        Preset("Custom", ProviderType.OPENAI_COMPATIBLE, "https://", "", ""),
    )
}

object ProviderCodec {
    fun encode(list: List<ProviderEntry>): String = JSONArray().also { arr ->
        list.forEach { p ->
            arr.put(
                JSONObject().put("id", p.id).put("type", p.type.name).put("name", p.name).put("baseUrl", p.baseUrl)
                    .put("fast", p.fastModel).put("strong", p.strongModel).put("enabled", p.enabled).put("independent", p.independentKeys)
                    .put("keys", JSONArray().also { ka ->
                        p.keys.forEach { k -> ka.put(JSONObject().put("id", k.id).put("label", k.label).put("secret", k.secret)) }
                    })
            )
        }
    }.toString()

    fun decode(json: String): List<ProviderEntry> {
        val arr = try { JSONArray(json) } catch (e: Exception) { return emptyList() }
        return (0 until arr.length()).mapNotNull { i ->
            val o = arr.optJSONObject(i) ?: return@mapNotNull null
            val ka = o.optJSONArray("keys") ?: JSONArray()
            ProviderEntry(
                id = o.optString("id"),
                type = try { ProviderType.valueOf(o.optString("type")) } catch (e: Exception) { ProviderType.OPENAI_COMPATIBLE },
                name = o.optString("name"), baseUrl = o.optString("baseUrl"),
                fastModel = o.optString("fast"), strongModel = o.optString("strong"),
                enabled = o.optBoolean("enabled", true), independentKeys = o.optBoolean("independent", false),
                keys = (0 until ka.length()).mapNotNull { j ->
                    val k = ka.optJSONObject(j) ?: return@mapNotNull null
                    KeyEntry(k.optString("id"), k.optString("label"), k.optString("secret"))
                },
            )
        }
    }
}
