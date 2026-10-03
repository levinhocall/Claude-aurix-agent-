package com.aurix.agent.core.ai.routing

/**
 * Chooses the best "strong" and a cheaper "fast" model from a provider's live model list, so the user never
 * has to type a model name. Falls back to preset defaults when the list is empty or unrecognised.
 */
object ModelPicker {
    data class Pick(val strong: String, val fast: String)

    private val exclude = Regex(
        "embed|moderation|whisper|tts|audio|realtime|transcribe|image|dall|search|guard|safeguard|instruct|distil|computer-use|codex|diffusion|veo|imagen|aqa|gemma|learnlm|robotics|native|chatgpt|-live|deprecated|vision-preview|-ft",
        RegexOption.IGNORE_CASE,
    )
    private val fastWords = Regex("(?<![a-z])(mini|nano)|flash|lite|haiku|instant|small|8b|9b|turbo", RegexOption.IGNORE_CASE)
    private val datedId = Regex("\\d{4}-\\d{2}-\\d{2}|-\\d{8}$|-\\d{4}$")

    fun pick(type: ProviderType, rawIds: List<String>, defStrong: String, defFast: String): Pick {
        val ids0 = rawIds.map { it.removePrefix("models/") }.filter { it.isNotBlank() && !exclude.containsMatchIn(it) }.distinct()
        if (ids0.isEmpty()) return Pick(defStrong, defFast)
        // prefer stable aliases over dated snapshots / previews when both exist
        val undated = ids0.filter { !datedId.containsMatchIn(it) && !it.contains("preview", true) && !it.contains("exp", true) }
        val ids = if (undated.isNotEmpty()) undated else ids0

        if (type == ProviderType.ANTHROPIC) {
            val pool = ids0
            fun family(vararg names: String): String? =
                names.firstNotNullOfOrNull { n -> pool.filter { it.contains(n, true) }.maxWithOrNull(versionComparator) }
            val strong = family("fable", "opus", "sonnet") ?: family("haiku") ?: pool.maxWithOrNull(versionComparator) ?: defStrong
            val fast = family("haiku") ?: family("sonnet") ?: strong
            return Pick(strong, fast)
        }

        val notPro = ids.filter { !it.contains("-pro", true) || it.contains("gemini", true) }
        val candidates = if (notPro.isNotEmpty()) notPro else ids
        val strongPool = candidates.filter { !fastWords.containsMatchIn(it) }
        val proFirst = strongPool.filter { it.contains("pro", true) }
        val strong = (if (proFirst.isNotEmpty()) proFirst else strongPool).maxWithOrNull(versionComparator)
            ?: candidates.maxWithOrNull(versionComparator) ?: defStrong
        val fastPool = candidates.filter { fastWords.containsMatchIn(it) && !it.contains("lite", true) }
            .ifEmpty { candidates.filter { fastWords.containsMatchIn(it) } }
        val fast = fastPool.maxWithOrNull(versionComparator) ?: strong
        return Pick(strong, fast)
    }

    private val versionComparator = Comparator<String> { a, b ->
        val va = versionKey(a); val vb = versionKey(b)
        var i = 0
        while (i < va.size && i < vb.size) {
            if (va[i] != vb[i]) return@Comparator va[i].compareTo(vb[i])
            i++
        }
        va.size.compareTo(vb.size)
    }

    private fun versionKey(id: String): List<Long> =
        Regex("\\d+").findAll(id).map { it.value.toLongOrNull() ?: 0L }.toList()
}
