package com.aurix.agent.core.ai.routing

/** What we know about a model from its id alone. */
data class ModelCapability(
    /** FAST / STRONG when the id makes it clear, null when unknown (then the user's slot assignment is trusted). */
    val tier: ModelTier?,
    /** false for models that hijack the JSON agent protocol with native tool calls (gpt-oss, compound). */
    val protocolSafe: Boolean,
)

/** Id-based model capability lookup used by [ModelRouter]. Pure, no I/O. Unknown ids are never guessed. */
object ModelCapabilityRegistry {
    private val fastWords = Regex("(?<![a-z])(mini|nano)|flash|lite|haiku|instant|small|8b|9b|turbo", RegexOption.IGNORE_CASE)
    private val strongWords = Regex("opus|sonnet|fable|mythos|(?<![a-z])pro(?![a-z])|70b|72b|405b|reasoner|gpt-4o$|gpt-4\\.1$|gpt-5$", RegexOption.IGNORE_CASE)
    private val unsafe = Regex("gpt-oss|compound", RegexOption.IGNORE_CASE)

    fun describe(modelId: String): ModelCapability {
        val id = modelId.removePrefix("models/").substringAfterLast('/')
        val tier = when {
            id.isBlank() -> null
            fastWords.containsMatchIn(id) -> ModelTier.FAST
            strongWords.containsMatchIn(id) -> ModelTier.STRONG
            else -> null
        }
        return ModelCapability(tier, protocolSafe = !unsafe.containsMatchIn(id))
    }

    fun tierOf(modelId: String): ModelTier? = describe(modelId).tier
}
