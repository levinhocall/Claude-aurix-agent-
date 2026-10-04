package com.aurix.agent.core.tools.device

import java.text.Normalizer

/** Lowercase, strip accents (é->e), map letters that do not decompose (ø->o), keep only letters/digits separated by single spaces. */
fun foldText(s: String): String {
    val sb = StringBuilder()
    for (ch in s.lowercase()) {
        when (ch) {
            'ø' -> sb.append('o'); 'æ' -> sb.append("ae"); 'œ' -> sb.append("oe"); 'ß' -> sb.append("ss")
            'đ' -> sb.append('d'); 'ł' -> sb.append('l'); 'ı' -> sb.append('i'); 'þ' -> sb.append("th")
            else -> sb.append(ch)
        }
    }
    return Normalizer.normalize(sb.toString(), Normalizer.Form.NFD)
        .replace(Regex("\\p{M}+"), "")
        .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
        .trim()
}

/** 100 exact, 80 starts-with, 60 whole word / all words, 40 substring, 0 none. */
fun contactScore(name: String, query: String): Int {
    val n = foldText(name); val q = foldText(query)
    if (q.isEmpty() || n.isEmpty()) return 0
    val words = n.split(' ')
    return when {
        n == q -> 100
        n.startsWith(q) -> 80
        q.split(' ').all { it in words } -> 60
        n.contains(q) -> 40
        else -> 0
    }
}
