package com.aurix.agent.core.observability

import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.ModelCallEntity
import kotlinx.coroutines.CancellationException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Removes credentials (and, with [all], personal identifiers) from any text before it is stored, logged or exported.
 * Applied to the event log and tool-call log, so a key that appears in a tool argument or an error body never reaches Room.
 */
object Redactor {
    private const val MASK = "[REDACTED]"
    private val keyShapes = listOf(
        Regex("""sk-ant-[A-Za-z0-9_\-]{12,}"""), Regex("""sk-or-[A-Za-z0-9_\-]{12,}"""), Regex("""sk-[A-Za-z0-9_\-]{16,}"""),
        Regex("""gsk_[A-Za-z0-9]{16,}"""), Regex("""xai-[A-Za-z0-9]{16,}"""), Regex("""AIza[0-9A-Za-z_\-]{30,}"""),
        Regex("""gh[pousr]_[A-Za-z0-9]{20,}"""), Regex("""github_pat_[A-Za-z0-9_]{20,}"""),
        Regex("""eyJ[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{10,}\.[A-Za-z0-9_\-]{5,}"""),
        Regex("""(?i)Bearer\s+[A-Za-z0-9._~+/\-]{12,}=*"""),
    )
    private val assignment = Regex("""(?i)((?:authorization|x-api-key|api[_\- ]?key|access[_\- ]?token|auth[_\- ]?token|client[_\- ]?secret|secret|password|passwd)["']?\s*[:=]\s*["']?)(?:Bearer\s+)?[^\s"',}&]{6,}""")
    private val otp = Regex("""(?i)((?:otp|one[- ]time|verification code|passcode|pin|code)\D{0,14})(\d{4,8})\b""")
    private val card = Regex("""\b(?:\d[ -]?){13,19}\b""")
    private val email = Regex("""\b([A-Za-z0-9._%+\-])[A-Za-z0-9._%+\-]*@([A-Za-z0-9.\-]+\.[A-Za-z]{2,})\b""")

    /** Credentials only: safe to apply to everything that is persisted. */
    fun secrets(s: String): String {
        if (s.isEmpty()) return s
        var out = s
        for (r in keyShapes) out = r.replace(out, MASK)
        out = assignment.replace(out) { it.groupValues[1] + MASK }
        return out
    }

    /** Credentials plus one-time codes, card-like numbers and e-mail addresses: for exports and diagnostics screens. */
    fun all(s: String): String {
        var out = secrets(s)
        out = otp.replace(out) { it.groupValues[1] + "••••" }
        out = card.replace(out) { m -> if (m.value.count { it.isDigit() } in 13..19) "[NUMBER]" else m.value }
        out = email.replace(out) { "${it.groupValues[1]}***@${it.groupValues[2]}" }
        return out
    }
}

/** Rough list prices (USD per 1M tokens, input/output). ESTIMATES only: used when the provider does not report a cost. Unknown models give null, never a guess. */
object CostEstimator {
    private data class Price(val inPerM: Double, val outPerM: Double)
    private val table = listOf(
        "gpt-4o-mini" to Price(0.15, 0.60), "gpt-4o" to Price(2.5, 10.0), "gpt-4.1-mini" to Price(0.4, 1.6), "gpt-4.1" to Price(2.0, 8.0),
        "claude-haiku" to Price(0.8, 4.0), "claude-sonnet" to Price(3.0, 15.0), "claude-opus" to Price(15.0, 75.0),
        "gemini-1.5-flash" to Price(0.075, 0.30), "gemini-2.0-flash" to Price(0.10, 0.40),
    )

    fun estimateUsd(model: String, promptTokens: Int, completionTokens: Int, local: Boolean = false): Double? {
        if (local) return 0.0
        val m = model.lowercase()
        val p = table.firstOrNull { m.contains(it.first) }?.second ?: return null
        return (promptTokens * p.inPerM + completionTokens * p.outPerM) / 1_000_000.0
    }
}

data class ModelModelStats(val model: String, val calls: Int, val failures: Int, val avgMs: Long)
data class DiagnosticsSummary(
    val calls: Int, val failures: Int, val retried: Int,
    val p50Ms: Long, val p95Ms: Long,
    val tokensReported: Long, val tokensEstimated: Long,
    val costEstimateUsd: Double, val callsWithUnknownCost: Int,
    val byModel: List<ModelModelStats>, val topErrors: List<Pair<String, Int>>,
) {
    fun render(): String = buildString {
        if (calls == 0) { append("No model calls recorded yet."); return@buildString }
        append("Calls: $calls (${failures} failed, $retried retried)\n")
        append("Latency: p50 ${p50Ms} ms · p95 ${p95Ms} ms\n")
        append("Tokens: ${tokensReported} provider-reported + ${tokensEstimated} estimated (≈4 chars/token)\n")
        append("Cost: ≈ \$${"%.4f".format(costEstimateUsd)} ESTIMATED from list prices")
        if (callsWithUnknownCost > 0) append(" ($callsWithUnknownCost call(s) on models with no known price are not counted)")
        append("\n")
        byModel.take(6).forEach { append("• ${it.model}: ${it.calls} calls, ${it.failures} failed, avg ${it.avgMs} ms\n") }
        if (topErrors.isNotEmpty()) append("Errors: " + topErrors.joinToString { "${it.first}×${it.second}" })
    }.trim()
}

object Diagnostics {
    private fun pct(sorted: List<Long>, p: Double): Long = if (sorted.isEmpty()) 0 else sorted[((sorted.size - 1) * p).toInt().coerceIn(0, sorted.size - 1)]

    fun summarize(rows: List<ModelCallEntity>): DiagnosticsSummary {
        val lat = rows.map { it.latencyMs }.sorted()
        val reported = rows.filter { it.tokensReported }.sumOf { (it.promptTokens + it.completionTokens).toLong() }
        val estimated = rows.filter { !it.tokensReported }.sumOf { (it.promptTokens + it.completionTokens).toLong() }
        val known = rows.mapNotNull { it.costUsdEstimate }
        return DiagnosticsSummary(
            calls = rows.size, failures = rows.count { !it.ok }, retried = rows.count { it.retried },
            p50Ms = pct(lat, 0.5), p95Ms = pct(lat, 0.95),
            tokensReported = reported, tokensEstimated = estimated,
            costEstimateUsd = known.sum(), callsWithUnknownCost = rows.count { it.costUsdEstimate == null && it.ok },
            byModel = rows.groupBy { it.model.ifBlank { "(unknown)" } }.map { (m, l) -> ModelModelStats(m, l.size, l.count { !it.ok }, l.map { it.latencyMs }.average().toLong()) }.sortedByDescending { it.calls },
            topErrors = rows.mapNotNull { it.errorType }.groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.take(4).map { it.key to it.value },
        )
    }
}

/** Persists one row per model call. Holds no prompt text: only sizes, timings and the routing note. Failures to record never affect the mission. */
@Singleton
class ModelCallRecorder @Inject constructor(private val dao: MissionDao) {
    suspend fun record(
        missionId: String, purpose: String, model: String, latencyMs: Long, error: AiError?,
        reportedPrompt: Int, reportedCompletion: Int, estimatedPromptChars: Int, estimatedCompletionChars: Int,
        routeNote: String, retried: Boolean, local: Boolean = false,
    ) {
        val reported = reportedPrompt + reportedCompletion > 0
        val p = if (reported) reportedPrompt else estimatedPromptChars / 4
        val c = if (reported) reportedCompletion else estimatedCompletionChars / 4
        try {
            dao.insertModelCall(
                ModelCallEntity(
                    missionId = missionId, ts = System.currentTimeMillis(), purpose = purpose.take(24), model = Redactor.secrets(model).take(80),
                    latencyMs = latencyMs, ok = error == null, errorType = error?.type?.name, promptTokens = p, completionTokens = c,
                    tokensReported = reported, costUsdEstimate = if (error == null) CostEstimator.estimateUsd(model, p, c, local) else null,
                    routeNote = Redactor.secrets(routeNote).take(160), retried = retried,
                )
            )
        } catch (e: CancellationException) { throw e } catch (e: Exception) { /* diagnostics are best-effort */ }
    }

    suspend fun summary(sinceMs: Long): DiagnosticsSummary = Diagnostics.summarize(dao.modelCallsSince(sinceMs))
}
