package com.aurix.agent.core.project

/** Small line diff (LCS) with unified-style hunks. Bounded: huge inputs are summarised instead of diffed. */
object UnifiedDiff {
    private const val MAX_CELLS = 4_000_000L

    fun diff(path: String, old: String?, new: String?, context: Int = 3, maxLines: Int = 200): String {
        val a = old?.lines() ?: emptyList()
        val b = new?.lines() ?: emptyList()
        val header = "--- ${if (old == null) "/dev/null" else "a/$path"}\n+++ ${if (new == null) "/dev/null" else "b/$path"}\n"
        if (old == new) return ""
        if (a.size.toLong() * b.size > MAX_CELLS) return header + "(file too large to diff: ${a.size} -> ${b.size} lines)\n"
        val ops = script(a, b)
        val out = StringBuilder(header)
        var i = 0
        var emitted = 0
        while (i < ops.size) {
            if (ops[i].kind == ' ') { i++; continue }
            val start = maxOf(0, i - context)
            var end = i
            var lastChange = i
            while (end < ops.size && end - lastChange <= context * 2 + 1) { if (ops[end].kind != ' ') lastChange = end; end++ }
            val stop = minOf(ops.size, lastChange + context + 1)
            val slice = ops.subList(start, stop)
            val aStart = slice.firstOrNull { it.kind != '+' }?.aIdx?.plus(1) ?: 0
            val bStart = slice.firstOrNull { it.kind != '-' }?.bIdx?.plus(1) ?: 0
            out.append("@@ -$aStart,${slice.count { it.kind != '+' }} +$bStart,${slice.count { it.kind != '-' }} @@\n")
            for (o in slice) { out.append(o.kind).append(o.text).append('\n'); emitted++ }
            if (emitted >= maxLines) { out.append("… (diff truncated)\n"); break }
            i = stop
        }
        return out.toString()
    }

    private class Op(val kind: Char, val text: String, val aIdx: Int, val bIdx: Int)

    private fun script(a: List<String>, b: List<String>): List<Op> {
        val n = a.size; val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        val ops = ArrayList<Op>(); var i = 0; var j = 0
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { ops += Op(' ', a[i], i, j); i++; j++ }
                dp[i + 1][j] >= dp[i][j + 1] -> { ops += Op('-', a[i], i, j); i++ }
                else -> { ops += Op('+', b[j], i, j); j++ }
            }
        }
        while (i < n) { ops += Op('-', a[i], i, j); i++ }
        while (j < m) { ops += Op('+', b[j], i, j); j++ }
        return ops
    }
}
