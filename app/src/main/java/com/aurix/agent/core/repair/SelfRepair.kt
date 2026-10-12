package com.aurix.agent.core.repair

import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.ai.AiErrorType
import com.aurix.agent.core.ai.AiMessage
import com.aurix.agent.core.project.ProjectGuard
import com.aurix.agent.core.tools.ToolErrorType

enum class FailureKind { PROVIDER_TRANSIENT, PROVIDER_AUTH, CONTEXT_OVERFLOW, STRUCTURED_OUTPUT, UNSUPPORTED, TOOL_TRANSIENT, TOOL_INVALID_INPUT, TOOL_PERMISSION, MISSION_LOOP, BUILD_FAILURE, UNKNOWN }
enum class RepairStrategy { RETRY_WITH_BACKOFF, SWITCH_MODEL, COMPACT_CONTEXT, REPROMPT_STRICT, FIX_ARGUMENTS, REPLAN, EDIT_AND_RERUN, ASK_USER, STOP }

/** Hard limits for automatic repair. Everything is bounded: total attempts, attempts per failure kind, build repairs, and identical failures. */
data class RepairBudget(val maxTotal: Int = 8, val maxPerKind: Int = 3, val maxBuildRepairs: Int = 3, val maxSameFingerprint: Int = 2)

data class RepairDecision(val strategy: RepairStrategy, val allowed: Boolean, val reason: String)

class RepairLedger(private val budget: RepairBudget = RepairBudget()) {
    private val perKind = HashMap<FailureKind, Int>()
    private val seen = HashMap<String, Int>()
    var total = 0; private set

    fun attempts(kind: FailureKind) = perKind[kind] ?: 0

    fun strategyFor(kind: FailureKind, attemptNo: Int): RepairStrategy = when (kind) {
        FailureKind.PROVIDER_TRANSIENT, FailureKind.TOOL_TRANSIENT -> RepairStrategy.RETRY_WITH_BACKOFF
        FailureKind.CONTEXT_OVERFLOW -> RepairStrategy.COMPACT_CONTEXT
        FailureKind.STRUCTURED_OUTPUT -> if (attemptNo <= 1) RepairStrategy.REPROMPT_STRICT else RepairStrategy.SWITCH_MODEL
        FailureKind.TOOL_INVALID_INPUT -> RepairStrategy.FIX_ARGUMENTS
        FailureKind.MISSION_LOOP -> RepairStrategy.REPLAN
        FailureKind.BUILD_FAILURE -> RepairStrategy.EDIT_AND_RERUN
        FailureKind.PROVIDER_AUTH, FailureKind.TOOL_PERMISSION -> RepairStrategy.ASK_USER
        FailureKind.UNSUPPORTED, FailureKind.UNKNOWN -> RepairStrategy.STOP
    }

    /** Asks for permission to try one repair. Counts it only when allowed. */
    fun decide(kind: FailureKind, fingerprint: String): RepairDecision {
        val fp = "$kind|$fingerprint"
        if (total >= budget.maxTotal) return RepairDecision(RepairStrategy.STOP, false, "repair budget exhausted (${budget.maxTotal} attempts)")
        if ((seen[fp] ?: 0) >= budget.maxSameFingerprint) return RepairDecision(RepairStrategy.STOP, false, "the same failure keeps repeating; no progress")
        val limit = if (kind == FailureKind.BUILD_FAILURE) budget.maxBuildRepairs else budget.maxPerKind
        if (attempts(kind) >= limit) return RepairDecision(RepairStrategy.STOP, false, "${kind.name.lowercase()} repair limit reached ($limit)")
        val n = attempts(kind) + 1
        val s = strategyFor(kind, n)
        if (s == RepairStrategy.STOP || s == RepairStrategy.ASK_USER) return RepairDecision(s, false, "not automatically repairable")
        perKind[kind] = n; seen[fp] = (seen[fp] ?: 0) + 1; total++
        return RepairDecision(s, true, "${kind.name.lowercase()} attempt $n/$limit")
    }
}

object FailureClassifier {
    fun classify(e: AiError): FailureKind = when (e.type) {
        AiErrorType.CONTEXT_OVERFLOW -> FailureKind.CONTEXT_OVERFLOW
        AiErrorType.INVALID_RESPONSE -> FailureKind.STRUCTURED_OUTPUT
        AiErrorType.UNSUPPORTED_CAPABILITY -> FailureKind.UNSUPPORTED
        AiErrorType.AUTH_ERROR, AiErrorType.CONFIGURATION_ERROR -> FailureKind.PROVIDER_AUTH
        AiErrorType.NETWORK_ERROR, AiErrorType.TIMEOUT, AiErrorType.RATE_LIMIT -> FailureKind.PROVIDER_TRANSIENT
        else -> FailureKind.UNKNOWN
    }

    fun classifyTool(t: ToolErrorType?): FailureKind = when (t) {
        ToolErrorType.INVALID_INPUT -> FailureKind.TOOL_INVALID_INPUT
        ToolErrorType.PERMISSION_REQUIRED -> FailureKind.TOOL_PERMISSION
        ToolErrorType.NETWORK_ERROR, ToolErrorType.TIMEOUT, ToolErrorType.RATE_LIMIT -> FailureKind.TOOL_TRANSIENT
        else -> FailureKind.UNKNOWN
    }

    /** Stable fingerprint of a build/test failure: the first error-looking line, with numbers removed. */
    fun buildFingerprint(output: String): String =
        output.lineSequence().firstOrNull { it.startsWith("e: ") || it.contains("error:", true) || it.contains("FAILED") || it.contains("AssertionError") }
            ?.replace(Regex("""\d+"""), "#")?.trim()?.take(120).orEmpty().ifEmpty { "unknown" }
}

/** Repairs must stay on task: no unrelated files, no secrets, and never weaken security or tests to make a build pass. */
object RepairPolicy {
    private val weakening = listOf(
        Regex("""cleartextTrafficPermitted\s*=\s*"true""""), Regex("""usesCleartextTraffic\s*=\s*"true""""),
        Regex("""android:debuggable\s*=\s*"true""""), Regex("""android:allowBackup\s*=\s*"true""""), Regex("""android:exported\s*=\s*"true""""),
        Regex("""(?i)trustAll|TrustAllCerts|checkServerTrusted\s*\([^)]*\)\s*\{\s*\}"""), Regex("""(?i)hostnameVerifier[^\n]*(true|ALLOW_ALL)"""),
        Regex("""@Ignore\b|@Disabled\b|assumeTrue\s*\(\s*false"""), Regex("""-x\s+test|--no-verify|skipTests|-DskipTests=true"""),
        Regex("""(?i)needsApproval[^\n]*=\s*false|RiskLevel\.LOW\s*//\s*was"""),
    )
    private fun isTestPath(p: String) = p.contains("/test/") || p.contains("/androidTest/") || Regex("""(Test|Tests|Spec)\.\w+$""").containsMatchIn(p) || p.substringAfterLast('/').startsWith("test_")

    /** Returns the reason an edit must be refused, or null when it is acceptable. */
    fun checkEdit(path: String, old: String, new: String, repairScope: Set<String>?, failingOutput: String): String? {
        if (ProjectGuard.isSecret(path)) return "secret/credential files are never modified"
        for (w in weakening) if (w.containsMatchIn(new) && !w.containsMatchIn(old)) return "the change would weaken a security setting or disable checks (${w.pattern.take(40)}); ask the user instead"
        if (isTestPath(path)) {
            if (new.isBlank() && old.isNotBlank()) return "deleting test content to make a build pass is not a repair"
            if (Regex("assert", RegexOption.IGNORE_CASE).findAll(old).count() > Regex("assert", RegexOption.IGNORE_CASE).findAll(new).count()) return "removing assertions to make tests pass is not a repair"
        }
        if (repairScope != null && path !in repairScope) {
            val name = path.substringAfterLast('/')
            if (!failingOutput.contains(name)) return "$path is unrelated to the failure (not previously changed and not mentioned in the failing output)"
        }
        return null
    }
}

/** Shrinks a request that overflowed the model's context while keeping a valid message sequence (tool replies always follow their call). */
object ContextCompactor {
    fun compact(msgs: List<AiMessage>, perMessageChars: Int = 3000, keepTail: Int = 6): List<AiMessage> {
        fun shrink(m: AiMessage): AiMessage {
            if (m.content.length <= perMessageChars) return m
            val half = perMessageChars / 2
            return m.copy(content = m.content.take(half) + "\n…[trimmed]…\n" + m.content.takeLast(half))
        }
        val head = msgs.take(2)
        var tail = msgs.drop(2).takeLast(keepTail)
        while (tail.isNotEmpty() && tail.first().role == "tool") tail = tail.drop(1) // never start with an orphaned tool reply
        return (head + tail).map(::shrink)
    }
}

/** Bounds the edit -> rebuild loop. `failures` = fingerprints of consecutive failed build/test runs (reset by a pass). */
object BuildRepair {
    const val MAX_REPAIR_ROUNDS = 3

    /** Reason a further run must be refused, or null. The first run (0 failures) and the first run after a failure are always allowed up to the round limit. */
    fun blockReason(failures: List<String>, maxRounds: Int = MAX_REPAIR_ROUNDS): String? {
        if (failures.size > maxRounds) return "repair limit reached: $maxRounds repair rounds already failed. Stop, keep the diff, and report the remaining error honestly."
        if (failures.size >= 3 && failures.takeLast(3).toSet().size == 1) return "the same failure repeated 3 times with no progress. Stop and report it instead of trying again."
        return null
    }
}
