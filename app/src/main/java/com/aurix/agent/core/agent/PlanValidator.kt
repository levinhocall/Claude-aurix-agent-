package com.aurix.agent.core.agent

data class ValidatedPlan(val steps: List<String>, val notes: List<String>)

/**
 * Plans come from a model, so they are checked before anything runs: bounded size and length, no duplicate steps,
 * and tool hints that name tools which do not exist are removed (they would silently hide the right tools from the step).
 */
object PlanValidator {
    const val MAX_STEPS = 8
    const val MAX_TITLE = 240
    private val hint = Regex("\\s*\\[([A-Z][A-Z0-9_ ,]*)]\\s*$")

    fun validate(titles: List<String>, knownTools: Set<String>, maxSteps: Int = MAX_STEPS): ValidatedPlan? {
        val notes = ArrayList<String>()
        val seen = HashSet<String>()
        val out = ArrayList<String>()
        for (raw in titles) {
            var t = raw.trim().replace(Regex("\\s+"), " ")
            if (t.isEmpty()) continue
            val m = hint.find(t)
            if (m != null) {
                val names = m.groupValues[1].split(',').map { it.trim() }.filter { it.isNotEmpty() }
                val good = names.filter { it in knownTools }
                if (good.size != names.size) notes += "dropped unknown tool(s): " + names.filter { it !in knownTools }.joinToString()
                val body = t.removeRange(m.range).trim().take(MAX_TITLE)
                t = if (good.isEmpty()) body else "$body [${good.joinToString(",")}]"
            } else t = t.take(MAX_TITLE)
            if (t.isBlank()) continue
            val key = cleanTitle(t).lowercase()
            if (!seen.add(key)) { notes += "dropped duplicate step"; continue }
            out += t
        }
        if (out.size > maxSteps) { notes += "trimmed ${out.size} steps to $maxSteps"; while (out.size > maxSteps) out.removeAt(out.size - 1) }
        return if (out.isEmpty()) null else ValidatedPlan(out, notes)
    }
}
