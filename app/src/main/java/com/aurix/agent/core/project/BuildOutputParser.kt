package com.aurix.agent.core.project

/** Turns a real command's exit code + output into an honest verdict. Exit 0 with zero tests run is UNKNOWN, never PASS. */
data class RunSummary(val result: String, val detail: String) {
    companion object { const val PASS = "PASS"; const val FAIL = "FAIL"; const val UNKNOWN = "UNKNOWN" }
}

object BuildOutputParser {
    private val zeroTests = listOf(Regex("""(?i)no tests ran"""), Regex("""(?i)collected 0 items"""), Regex("""(?i)\bRan 0 tests\b"""), Regex("""(?i)no test files"""), Regex("""(?i)running 0 tests"""))
    private val gradleFailed = Regex("""(\d+) tests? completed, (\d+) failed""")

    fun parse(exit: Int, output: String): RunSummary {
        val kotlinErrors = output.lineSequence().count { it.startsWith("e: ") || Regex("""^.*:\d+:\d+: error:""").containsMatchIn(it) }
        val gf = gradleFailed.find(output)
        val parts = ArrayList<String>()
        gf?.let { parts += "tests=${it.groupValues[1]} failed=${it.groupValues[2]}" }
        if (kotlinErrors > 0) parts += "compile_errors=$kotlinErrors"
        if (output.contains("BUILD FAILED")) parts += "gradle=BUILD FAILED"
        if (exit != 0) return RunSummary(RunSummary.FAIL, (listOf("exit=$exit") + parts).joinToString(" "))
        if (output.contains("BUILD FAILED") || output.lineSequence().any { it.trim().startsWith("FAIL") && !it.contains("0 failed") } && gf != null) return RunSummary(RunSummary.FAIL, parts.joinToString(" "))
        if (zeroTests.any { it.containsMatchIn(output) }) return RunSummary(RunSummary.UNKNOWN, "exit=0 but no tests were executed")
        if (output.contains("BUILD SUCCESSFUL")) parts += "gradle=BUILD SUCCESSFUL"
        Regex("""(\d+) passed""").find(output)?.let { parts += "passed=${it.groupValues[1]}" }
        return RunSummary(RunSummary.PASS, parts.joinToString(" ").ifEmpty { "exit=0" })
    }

    fun tail(output: String, maxLines: Int = 60, maxChars: Int = 5000): String {
        val lines = output.lines()
        val t = lines.takeLast(maxLines).joinToString("\n")
        return if (t.length > maxChars) t.takeLast(maxChars) else t
    }
}
