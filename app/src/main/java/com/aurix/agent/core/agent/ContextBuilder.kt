package com.aurix.agent.core.agent

/**
 * Builds a prompt that fits a token budget. Sections are kept by priority (0 = must keep); a trimmable section is cut
 * (keeping its start) before anything is dropped, and lower-priority sections are dropped first. Token counts are a
 * conservative estimate (about 3.2 characters per token) with a safety margin, because no tokenizer is available on-device.
 */
object ContextBuilder {
    data class Section(val text: String, val priority: Int, val minChars: Int = 0, val label: String = "")

    fun estimateTokens(s: String): Int = (s.length / 3.2 + 1).toInt()

    /** Safe input budget for a model with [contextTokens] of context: leave room for the answer and a 15% margin. */
    fun inputBudget(contextTokens: Int, reserveForOutput: Int): Int = ((contextTokens - reserveForOutput) * 0.85).toInt().coerceAtLeast(500)

    fun build(sections: List<Section>, budgetTokens: Int): String {
        val budgetChars = (budgetTokens * 3.2).toInt()
        val live = sections.filter { it.text.isNotBlank() }.toMutableList()
        fun total() = live.sumOf { it.text.length + 1 }
        // 1) trim from the lowest-priority section upward
        val top = live.maxOfOrNull { it.priority } ?: 0
        for (prio in top downTo 1) {
            var i = 0
            while (total() > budgetChars && i < live.size) {
                val sct = live[i]
                if (sct.priority == prio && sct.text.length > sct.minChars) {
                    val over = total() - budgetChars
                    val keep = (sct.text.length - over).coerceAtLeast(sct.minChars)
                    live[i] = sct.copy(text = sct.text.take(keep).trimEnd() + if (keep < sct.text.length) " …(trimmed)" else "")
                }
                i++
            }
            // 2) still too big: drop the section entirely if it has no minimum
            var j = live.size - 1
            while (total() > budgetChars && j >= 0) {
                if (live[j].priority == prio && live[j].minChars == 0) live.removeAt(j)
                j--
            }
        }
        return live.joinToString("\n") { it.text }
    }
}
