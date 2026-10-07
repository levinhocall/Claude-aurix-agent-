package com.aurix.agent.core.ai.routing

/** What a provider/model is known to support. Unknown = rely on the prompt only. */
object Capabilities {
    /** response_format=json_object keeps replies valid JSON (the agent protocol is JSON). */
    fun jsonMode(baseUrl: String, model: String): Boolean = when {
        baseUrl.contains("api.openai.com") -> true
        baseUrl.contains("generativelanguage.googleapis.com") -> true
        baseUrl.contains("groq.com") -> !model.contains("gpt-oss", true) && !model.contains("compound", true)
        else -> false
    }
}
