package com.aurix.agent.core.tools.device

/**
 * Wrong-recipient protection for tools that message or call a person. A model must never be the source of a phone number:
 * it copies examples from tool schemas (+91 98765 43210) and invents plausible ones. Pure, unit-tested.
 */
object RecipientGuard {
    val COMM_TOOLS = setOf("SEND_SMS", "WHATSAPP_MESSAGE", "CALL_PHONE")

    private val knownFakes = setOf("9876543210", "1234567890", "0123456789", "0987654321", "9999999999", "1111111111", "0000000000", "1212121212", "9876512345", "1234512345")

    fun digits(s: String): String = s.filter { it.isDigit() }
    fun last10(s: String): String = digits(s).takeLast(10)

    /** Example / filler numbers that no real contact has. Needs at least 7 digits to even be a number. */
    fun isPlaceholder(number: String): Boolean {
        val d = last10(number)
        if (d.length < 7) return false
        return d in knownFakes || d.toSet().size <= 2 && d.length >= 8
    }

    /** Did this exact number appear in text the user wrote? (compared on the last 10 digits, ignoring spaces and dashes) */
    fun mentioned(number: String, text: String): Boolean {
        val n = last10(number)
        if (n.length < 7) return false
        val t = digits(text)
        return t.contains(n)
    }

    /** Options shown to the user must not offer a made-up number as a choice. */
    fun cleanOptions(options: List<String>): List<String> = options.filterNot { isPlaceholder(it) }

    enum class Verdict { TRUSTED, RESOLVE }

    /**
     * TRUSTED: the number can be used as is (the user typed it in the request, or typed it as a free answer and it is not a placeholder).
     * RESOLVE: the number (if any) came from the model: find the real one from contacts by name.
     */
    fun verdict(number: String, request: String, answers: String): Verdict = when {
        digits(number).length < 7 -> Verdict.RESOLVE
        mentioned(number, request) -> Verdict.TRUSTED
        mentioned(number, answers) && !isPlaceholder(number) -> Verdict.TRUSTED
        else -> Verdict.RESOLVE
    }
}
