package com.aurix.agent.core.project

/**
 * Allow-list for the only commands the coding workflow may run. No shell is ever involved (argv is executed directly),
 * shell metacharacters are rejected outright, and anything destructive, networked or publishing is refused.
 */
sealed interface CommandCheck {
    data class Allowed(val argv: List<String>) : CommandCheck
    data class Denied(val reason: String) : CommandCheck
}

object CommandPolicy {
    private val meta = Regex("""[;&|<>`$(){}\n\r\\]""")
    private val safeArg = Regex("""^[A-Za-z0-9_./:=@^~,+-]+$""")
    private val gradleTask = Regex("""^:?[A-Za-z0-9_.:-]+$""")
    private val gradleDeniedTask = Regex("""(?i)^:?(?:.*:)?(publish|upload|deploy|install|uninstall|clean|wrapper|signing|release|connected|devicecheck).*""")
    private val gradleFlags = setOf("--stacktrace", "--info", "--warn", "--offline", "--no-daemon", "--continue", "--console=plain", "--quiet", "-q", "-i", "-s", "--warning-mode=all", "--warning-mode=summary", "--warning-mode=none", "--no-build-cache")
    private val prop = Regex("""^-[PD][A-Za-z0-9_.]+=[A-Za-z0-9_./:-]*$""")
    private val npmScripts = setOf("build", "test", "lint", "check", "typecheck")
    private val gitSub = setOf("status", "diff", "log", "rev-parse", "branch", "ls-files")
    private val gitFlags = setOf("--porcelain", "-s", "--short", "-b", "--stat", "--name-only", "--numstat", "--no-color", "--oneline", "--cached", "--show-toplevel", "--is-inside-work-tree", "--abbrev-ref", "--show-current", "HEAD", "--")

    fun tokenize(s: String): List<String>? {
        val out = ArrayList<String>(); val cur = StringBuilder(); var q = 0.toChar(); var any = false
        for (c in s) {
            when {
                q != 0.toChar() -> if (c == q) q = 0.toChar() else cur.append(c)
                c == '\'' || c == '"' -> { q = c; any = true }
                c == ' ' || c == '\t' -> if (cur.isNotEmpty() || any) { out += cur.toString(); cur.clear(); any = false }
                else -> cur.append(c)
            }
        }
        if (q != 0.toChar()) return null
        if (cur.isNotEmpty() || any) out += cur.toString()
        return out
    }

    private fun relPath(a: String) = safeArg.matches(a) && !a.startsWith("/") && a.split('/').none { it == ".." }

    fun check(command: String): CommandCheck {
        if (command.length > 300) return CommandCheck.Denied("command too long")
        if (meta.containsMatchIn(command)) return CommandCheck.Denied("shell metacharacters are not allowed (no pipes, chaining, redirects, substitution)")
        val argv = tokenize(command) ?: return CommandCheck.Denied("unbalanced quotes")
        if (argv.isEmpty()) return CommandCheck.Denied("empty command")
        val exe = argv[0].removePrefix("./")
        val args = argv.drop(1)
        val deny = when (exe) {
            "gradlew", "gradle" -> gradle(args)
            "mvn", "mvnw" -> if (args.isNotEmpty() && args.all { it in setOf("test", "compile", "verify", "package", "-q", "-B", "-o") || it == "-DskipTests=false" } && args.any { it in setOf("test", "compile", "verify", "package") }) null else "maven: only test/compile/verify/package are allowed"
            "npm", "pnpm", "yarn" -> if (args == listOf("test") || (args.size == 2 && args[0] == "run" && args[1] in npmScripts)) null else "$exe: only 'test' or 'run <${npmScripts.joinToString("|")}>' is allowed"
            "pytest" -> pyArgs(args)
            "python", "python3" -> if (args.size >= 2 && args[0] == "-m" && args[1] in setOf("pytest", "unittest", "compileall", "py_compile")) pyArgs(args.drop(2)) else "python: only -m pytest|unittest|compileall|py_compile"
            "go" -> if (args.isNotEmpty() && args[0] in setOf("test", "build", "vet") && args.drop(1).all { it == "-v" || it == "-count=1" || relPath(it) || it == "./..." || Regex("""^-run=[A-Za-z0-9_|^$]+$""").matches(it) }) null else "go: only test/build/vet with simple arguments"
            "cargo" -> if (args.isNotEmpty() && args[0] in setOf("test", "build", "check", "clippy") && args.drop(1).all { it in setOf("--release", "--offline", "--quiet", "--all-targets", "--workspace") }) null else "cargo: only test/build/check/clippy with simple flags"
            "make" -> if (args.size == 1 && args[0] in setOf("test", "build", "check", "lint", "all")) null else "make: only test/build/check/lint/all"
            "git" -> git(args)
            else -> "'$exe' is not an allowed build/test command"
        }
        return if (deny == null) CommandCheck.Allowed(listOf(argv[0]) + args) else CommandCheck.Denied(deny)
    }

    private fun gradle(args: List<String>): String? {
        val tasks = ArrayList<String>()
        for (a in args) when {
            a in gradleFlags || prop.matches(a) -> {}
            a.startsWith("-") -> return "gradle flag not allowed: $a"
            gradleTask.matches(a) -> { if (gradleDeniedTask.matches(a)) return "gradle task not allowed: $a"; tasks += a }
            else -> return "invalid gradle argument: $a"
        }
        return if (tasks.isEmpty()) "gradle: name at least one task (e.g. test, assembleDebug)" else null
    }

    private fun pyArgs(args: List<String>): String? {
        var i = 0
        while (i < args.size) {
            val a = args[i]
            when {
                a in setOf("-q", "-x", "-v", "-vv") || Regex("""^--maxfail=\d{1,3}$""").matches(a) -> {}
                a == "-k" && i + 1 < args.size && Regex("""^[A-Za-z0-9_ ]{1,60}$""").matches(args[i + 1]) -> i++
                relPath(a) -> {}
                else -> return "python/pytest argument not allowed: $a"
            }
            i++
        }
        return null
    }

    private fun git(args: List<String>): String? {
        if (args == listOf("--version")) return null
        if (args.isEmpty() || args[0] !in gitSub) return "git: only read-only subcommands (${gitSub.joinToString()}) are allowed"
        var i = 1
        while (i < args.size) {
            val a = args[i]
            when {
                a in gitFlags -> {}
                a == "-n" && i + 1 < args.size && args[i + 1].toIntOrNull() in 1..50 -> i++
                Regex("""^-\d{1,2}$""").matches(a) -> {}
                relPath(a) -> {}
                else -> return "git argument not allowed: $a"
            }
            i++
        }
        return null
    }
}
