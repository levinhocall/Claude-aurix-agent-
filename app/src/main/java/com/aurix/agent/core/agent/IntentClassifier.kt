package com.aurix.agent.core.agent

import com.aurix.agent.core.tools.device.foldText

enum class TaskCategory { PHONE_COMMAND, QUESTION, CODING, RESEARCH, SCREEN_AUTOMATION, FILES, COMMUNICATION, CREATIVE, OTHER }
enum class Complexity { TRIVIAL, SIMPLE, COMPLEX }
enum class TaskRisk { READ_ONLY, LOW, SENSITIVE, CRITICAL }
enum class Capability { TEXT, REASONING, CODING, VISION, TOOLS, LONG_CONTEXT, WEB }

/** Structured description of what a request needs. Classification never executes anything. */
data class TaskSpec(
    val category: TaskCategory,
    val complexity: Complexity,
    val risk: TaskRisk,
    val capabilities: Set<Capability>,
    val needsLiveData: Boolean,
    val expectedOutput: String,
    val latencyBudgetMs: Long,
    val successCriteria: String,
    val needsClarification: Boolean = false,
)

/** Deterministic rule-based classifier (EN/Hinglish). No model call; uncertain input falls back to a safe OTHER/SIMPLE spec. */
object IntentClassifier {
    private fun has(t: String, vararg w: String) = w.any { Regex("(?<![a-z])$it").containsMatchIn(t) }

    fun classify(text: String): TaskSpec {
        val t = foldText(normalizeHindi(text.lowercase().trim()))
        val local = IntentRouter.route(text)
        val caps = linkedSetOf(Capability.TEXT)
        val words = t.split(' ').count { it.isNotBlank() }

        val critical = has(t, "delete", "hata", "mita", "format", "factory reset", "uninstall", "transfer", "payment", "pay ", "upi", "bank", "password")
        val sensitive = has(t, "send", "bhej", "message", "msg", "sms", "whatsapp", "call", "email", "post", "publish", "share")
        val risk = when {
            local != null -> when (local.tool) {
                "SEND_SMS", "WHATSAPP_MESSAGE", "CALL_PHONE", "EMERGENCY_SOS" -> TaskRisk.SENSITIVE
                "DELETE_FILE", "STORAGE_DELETE" -> TaskRisk.CRITICAL
                else -> TaskRisk.LOW
            }
            critical -> TaskRisk.CRITICAL
            sensitive -> TaskRisk.SENSITIVE
            has(t, "tap", "click", "type ", "scroll", "open", "install", "change", "set ", "turn") -> TaskRisk.LOW
            else -> TaskRisk.READ_ONLY
        }

        val category = when {
            local != null -> if (local.tool.startsWith("SCREEN")) TaskCategory.SCREEN_AUTOMATION else if (local.tool in setOf("SEND_SMS", "WHATSAPP_MESSAGE", "CALL_PHONE")) TaskCategory.COMMUNICATION else TaskCategory.PHONE_COMMAND
            has(t, "code", "kotlin", "python", "function", "bug", "compile", "script", "regex", "sql", "api ") -> TaskCategory.CODING
            has(t, "screen", "tap on", "click on", "scroll", "app me", "app mein") -> TaskCategory.SCREEN_AUTOMATION
            has(t, "file", "folder", "photo", "pdf", "download", "zip", "storage") -> TaskCategory.FILES
            critical.not() && sensitive -> TaskCategory.COMMUNICATION
            has(t, "research", "compare", "find ", "search", "latest", "news", "price", "review", "dhundh", "khoj") -> TaskCategory.RESEARCH
            has(t, "write", "story", "poem", "caption", "essay", "likh") -> TaskCategory.CREATIVE
            t.endsWith("?") || has(t, "what", "why", "how", "kya", "kyun", "kaise", "kaun", "explain", "samjha") -> TaskCategory.QUESTION
            else -> TaskCategory.OTHER
        }
        when (category) {
            TaskCategory.CODING -> { caps += Capability.CODING; caps += Capability.REASONING }
            TaskCategory.RESEARCH -> { caps += Capability.WEB; caps += Capability.TOOLS }
            TaskCategory.SCREEN_AUTOMATION, TaskCategory.PHONE_COMMAND, TaskCategory.FILES, TaskCategory.COMMUNICATION -> caps += Capability.TOOLS
            else -> {}
        }
        if (has(t, "image", "photo mein", "screenshot", "picture", "tasveer", "dekh ke")) caps += Capability.VISION
        if (has(t, "step by step", "analy", "plan", "strategy", "why", "prove", "reason")) caps += Capability.REASONING
        if (words > 120) caps += Capability.LONG_CONTEXT

        val multi = has(t, "and then", "then ", "phir", "uske baad", "aur fir") || t.contains(" and ")
        val complexity = when {
            local != null -> Complexity.TRIVIAL
            words > 40 || multi || category == TaskCategory.RESEARCH || category == TaskCategory.CODING -> Complexity.COMPLEX
            else -> Complexity.SIMPLE
        }
        val live = has(t, "today", "aaj", "now", "abhi", "latest", "news", "weather", "mausam", "price", "score", "current")
        if (live) caps += Capability.WEB
        val output = when (category) {
            TaskCategory.CODING -> "code"; TaskCategory.CREATIVE, TaskCategory.RESEARCH -> "document"
            TaskCategory.PHONE_COMMAND, TaskCategory.SCREEN_AUTOMATION, TaskCategory.COMMUNICATION -> "action confirmation"
            else -> "short answer"
        }
        val latency = when (complexity) { Complexity.TRIVIAL -> 2_000L; Complexity.SIMPLE -> 15_000L; Complexity.COMPLEX -> 120_000L }
        val unclear = words <= 1 && local == null
        return TaskSpec(category, complexity, risk, caps, live, output, latency, "The requested ${if (output == "action confirmation") "action is performed and confirmed" else "$output is delivered"} without invented facts", unclear)
    }
}
