package com.aurix.agent.core.ai.routing

import com.aurix.agent.core.net.isLocalNetworkHost
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Recognises the provider from the API key format so the user only has to paste a key. */
object KeyDetector {
    fun detect(secret: String, customBaseUrl: String = ""): Preset? {
        val k = secret.trim()
        val custom = customBaseUrl.trim()
        if (custom.isNotEmpty()) {
            val u = custom.toHttpUrlOrNull() ?: return null
            val ok = u.scheme == "https" || (u.scheme == "http" && isLocalNetworkHost(u.host))
            if (!ok) return null
            return if (k.isEmpty()) Preset("Local model", ProviderType.OPENAI_COMPATIBLE, custom, "", "", local = true)
            else Preset("Custom", ProviderType.OPENAI_COMPATIBLE, custom, "", "")
        }
        if (k.isEmpty()) return null
        return when {
            k.startsWith("sk-ant-") -> preset("Anthropic")
            k.startsWith("sk-or-") -> preset("OpenRouter")
            k.startsWith("AIza") -> preset("Gemini")
            k.startsWith("gsk_") -> preset("Groq")
            k.startsWith("xai-") -> Preset("xAI", ProviderType.OPENAI_COMPATIBLE, "https://api.x.ai/v1", "grok-3-mini", "grok-4")
            k.startsWith("sk-") -> preset("OpenAI")
            else -> null
        }
    }

    private fun preset(name: String) = Presets.all.first { it.name == name }
}
