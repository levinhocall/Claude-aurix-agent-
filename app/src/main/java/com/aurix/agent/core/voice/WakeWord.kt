package com.aurix.agent.core.voice

import com.aurix.agent.core.tools.device.foldText

/** Text helpers for voice control (pure, unit-tested). */
object WakeWord {
    private val greet = setOf("hey", "hay", "hi", "hello", "ok", "okay", "hai", "he", "a", "ay", "oh")
    private val aliases = setOf("aurix", "orix", "oryx", "orics", "aurics", "auriks", "oriks", "orex", "aurex", "oreks", "aryx", "oryks", "orrix", "aurick", "orick", "aurik")
    private val glued = Regex("(hey|hay|hi|hello|ok|okay)(aurix|orix|oryx|orics|aurics|aurex|orex|oriks)")

    private fun lev(a: String, b: String): Int {
        val d = Array(a.length + 1) { IntArray(b.length + 1) }
        for (i in 0..a.length) d[i][0] = i
        for (j in 0..b.length) d[0][j] = j
        for (i in 1..a.length) for (j in 1..b.length)
            d[i][j] = minOf(d[i - 1][j] + 1, d[i][j - 1] + 1, d[i - 1][j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
        return d[a.length][b.length]
    }

    fun matches(text: String): Boolean {
        val t = foldText(text).split(' ').filter { it.isNotEmpty() }
        if (t.isEmpty()) return false
        for ((i, w) in t.withIndex()) {
            val nameLike = w in aliases || lev(w, "aurix") <= 1 ||
                (w.length >= 4 && lev(w, "aurix") <= 2 && (w.endsWith("x") || w.endsWith("ix") || w.endsWith("ics") || w.endsWith("iks") || w.endsWith("ex")))
            if (!nameLike) continue
            if (w == "aurix") return true
            if (t.getOrNull(i - 1) in greet || t.getOrNull(i - 2) in greet) return true
            if (i == 0 && t.size <= 2) return true
        }
        return glued.containsMatchIn(t.joinToString(""))
    }
}

/** Markdown/links removed and shortened so text-to-speech reads naturally. */
fun spokenText(s: String): String = s
    .replace(Regex("```[\\s\\S]*?```"), " ")
    .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
    .replace(Regex("https?://\\S+"), " link ")
    .replace(Regex("[*_`#>|]+"), " ")
    .replace(Regex("\\s+"), " ")
    .trim()

private val YES = setOf("yes", "yeah", "yep", "ok", "okay", "allow", "sure", "haan", "han", "ha", "theek", "correct", "approve", "confirm")
private val NO = setOf("no", "nope", "deny", "nahi", "nahin", "na", "mat", "stop", "cancel", "don", "reject")

/** true = yes, false = no, null = unclear. */
fun parseYesNo(text: String): Boolean? {
    val t = foldText(text).split(' ')
    val y = t.any { it in YES }; val n = t.any { it in NO }
    return when { y && !n -> true; n && !y -> false; else -> null }
}

fun isStopPhrase(text: String): Boolean {
    val t = foldText(text)
    return t == "stop" || t == "exit" || t == "close" || listOf("stop listening", "goodbye", "good bye", "bye bye", "band karo", "that s all", "thats all").any { t.contains(it) }
}
