package com.aurix.agent.core.voice

/** Pure helpers for speech: what to bias the recogniser with, how to cut a reply into speakable pieces, how to make text sound right. */
object VoiceBias {
    /** Contact names (and a few fixed words) the recogniser should expect, so "Nazib" is not heard as "nasib". */
    fun build(names: Collection<String>, extra: List<String> = listOf("AURIX", "WhatsApp")): ArrayList<String> {
        val seen = HashSet<String>(); val out = ArrayList<String>()
        for (n in extra + names) {
            val t = n.trim()
            if (t.length !in 2..40 || t.all { it.isDigit() || it == '+' || it == ' ' }) continue
            if (seen.add(t.lowercase())) out += t
            if (out.size >= 100) break
        }
        return out
    }
}

object SpeechChunker {
    private val boundary = Regex("(?<=[.!?।…])\\s+|\\n+")

    /** Sentences, short ones merged forward, long ones cut at a space. The first piece stays short so speech starts early. */
    fun split(text: String, max: Int = 200, minLen: Int = 30): List<String> {
        val raw = text.trim().split(boundary).map { it.trim() }.filter { it.isNotEmpty() }
        val merged = ArrayList<String>()
        for (p in raw) {
            if (merged.isNotEmpty() && merged.last().length < minLen) merged[merged.size - 1] = merged.last() + " " + p else merged += p
        }
        val out = ArrayList<String>()
        for (p in merged) {
            var s = p
            while (s.length > max) {
                val cut = s.lastIndexOf(' ', max).let { if (it < max / 2) max else it }
                out += s.substring(0, cut).trim(); s = s.substring(cut).trim()
            }
            if (s.isNotEmpty()) out += s
        }
        return out
    }
}

/** Text as it should be heard: no markdown/URLs/emoji, phone numbers read digit by digit, symbols spelled out. */
fun speakable(text: String): String {
    var s = spokenText(text)
    s = s.replace(Regex("[\\p{So}\\p{Cs}\\uFE0F\\u200D]"), "")
    s = s.replace(Regex("(?<![\\d])(?:\\+?91[\\s-]?)?(\\d[\\s-]?){9}\\d(?![\\d])")) { m -> m.value.filter { it.isDigit() }.takeLast(10).toCharArray().joinToString(" ") }
    s = s.replace(Regex("₹\\s?([\\d,.]+)")) { "${it.groupValues[1]} rupees" }
    s = s.replace("%", " percent")
    return s.replace(Regex("\\s+"), " ").trim()
}
