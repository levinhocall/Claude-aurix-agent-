package com.aurix.agent.core.tools.project

import android.content.Context
import com.aurix.agent.core.project.BuildOutputParser
import com.aurix.agent.core.project.CommandCheck
import com.aurix.agent.core.project.CommandPolicy
import com.aurix.agent.core.project.CommandRunner
import com.aurix.agent.core.project.ProjectGuard
import com.aurix.agent.core.project.ProjectService
import com.aurix.agent.core.project.RunSummary
import com.aurix.agent.core.repair.BuildRepair
import com.aurix.agent.core.repair.FailureClassifier
import com.aurix.agent.core.repair.RepairPolicy
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.Workspace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runInterruptible
import org.json.JSONObject
import java.io.File

/** Shared helpers: one ProjectService per mission, rooted at the project the user pointed to. */
class ProjectToolkit(private val context: Context, private val ws: Workspace) {
    private val runner = CommandRunner()
    fun service(missionId: String) = ProjectService(
        state = File(ws.dir(missionId), ".project"),
        forbidden = listOfNotNull(context.filesDir.parentFile),
        allowedInside = listOf(File(context.filesDir, "workspace")),
    )

    /** Read-only git, only when a git binary really exists. Null = unavailable (never guessed). */
    suspend fun git(svc: ProjectService, vararg args: String): String? {
        val argv = listOf("git") + args
        if (CommandPolicy.check(argv.joinToString(" ")) !is CommandCheck.Allowed) return null
        val r = runInterruptible(Dispatchers.IO) { CommandRunner(30_000).run(argv, svc.root()) }
        return if (r.notFound || r.exit != 0) null else r.output
    }

    suspend fun run(svc: ProjectService, argv: List<String>, log: File) = runInterruptible(Dispatchers.IO) { runner.run(argv, svc.root(), log) }
    fun logsDir(missionId: String) = File(File(ws.dir(missionId), ".project"), "logs")

    private fun failFile(missionId: String) = File(File(ws.dir(missionId), ".project"), "fails.txt")
    /** Fingerprints of the consecutive failed runs so far (a passing run resets them). */
    fun failures(missionId: String): List<String> = try { failFile(missionId).takeIf { it.isFile }?.readLines()?.filter { it.isNotBlank() }.orEmpty() } catch (e: Exception) { emptyList() }
    fun noteRun(missionId: String, failedFingerprint: String?) {
        val f = failFile(missionId); f.parentFile?.mkdirs()
        if (failedFingerprint == null) f.delete() else f.appendText(failedFingerprint.replace('\n', ' ') + "\n")
    }
    /** Text of the last failing run's log, or null when the last run did not fail (repairs are scoped to what the failure mentions). */
    fun lastFailureLog(missionId: String): String? {
        if (failures(missionId).isEmpty()) return null
        val last = logsDir(missionId).listFiles()?.maxByOrNull { it.lastModified() } ?: return null
        return try { last.readText().takeLast(20_000) } catch (e: Exception) { null }
    }

    companion object { const val MAX_RUNS = 8 }
}

class ProjectOpenTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_OPEN"
    override val description = "Open a code project/repository folder on this device for inspection and maintenance. Reports the build system and any uncommitted changes (when git is installed). Call first."
    override val inputSchema = """{"path":"/storage/emulated/0/Download/myproject"}"""
    override val outputSchema = "project summary"
    override val required = listOf("path")
    override val permissions = listOf("storage.read")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 60_000L
    override fun describe(input: JSONObject) = "Open project folder ${input.optString("path").take(120)}"

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val svc = kit.service(ctx.missionId)
        val root = svc.open(input.optString("path"))
        val sb = StringBuilder("OPENED ${root.name}\nBuild system: ${svc.detect()}\n")
        val hasGitDir = File(root, ".git").exists()
        val status = if (hasGitDir) kit.git(svc, "status", "--porcelain") else null
        when {
            !hasGitDir -> sb.append("Git: not a git repository. Originals are snapshotted before any edit.\n")
            status == null -> sb.append("Git: repository found but no usable git binary on this device, so uncommitted changes cannot be detected. Originals are snapshotted before any edit; ask the user before touching files they may be editing.\n")
            status.isBlank() -> { svc.setDirty(emptyList()); sb.append("Git: working tree clean.\n") }
            else -> {
                val dirty = status.lines().filter { it.length > 3 }.map { it.substring(3).substringAfter(" -> ").trim().trim('"') }
                svc.setDirty(dirty)
                sb.append("Git: ${dirty.size} file(s) with UNCOMMITTED changes (will not be overwritten without asking): ${dirty.take(15).joinToString()}\n")
            }
        }
        sb.append("Top level:\n").append(svc.tree(".", 1))
        return ToolResult.ok(sb.toString())
    }
}

class ProjectTreeTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_TREE"
    override val description = "List the project's files (bounded; build/.git/node_modules are skipped)."
    override val inputSchema = """{"path":".","depth":2}"""
    override val outputSchema = "tree"
    override val permissions = listOf("storage.read")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 20_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext) = ToolResult.ok(kit.service(ctx.missionId).tree(input.optString("path", "."), input.optInt("depth", 2)))
}

class ProjectReadTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_READ"
    override val description = "Read a project text file with line numbers. Credential files (local.properties, keystores, .env) are refused. A file must be read before it can be edited."
    override val inputSchema = """{"path":"app/src/Main.kt","from":1,"lines":200}"""
    override val outputSchema = "numbered lines"
    override val required = listOf("path")
    override val permissions = listOf("storage.read")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 20_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext) = ToolResult.ok(kit.service(ctx.missionId).read(input.optString("path"), input.optInt("from", 1), input.optInt("lines", 200)))
}

class ProjectSearchTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_SEARCH"
    override val description = "Case-insensitive text search across the project (max 50 hits)."
    override val inputSchema = """{"query":"text to find"}"""
    override val outputSchema = "path:line: text"
    override val required = listOf("query")
    override val permissions = listOf("storage.read")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 30_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext) = ToolResult.ok(kit.service(ctx.missionId).search(input.optString("query")))
}

class ProjectApplyEditTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_APPLY_EDIT"
    override val description = "Targeted edit: replace exact text `old` with `new` in a project file (read it first). Or create a new file with create=true and empty old. Snapshotted, read-back verified, capped (12 files / 40 edits). Never touches secrets."
    override val inputSchema = """{"path":"app/src/Main.kt","old":"exact old text (empty when creating)","new":"replacement text","create":false,"replace_all":false,"allow_dirty":false}"""
    override val outputSchema = "confirmation with READBACK=OK"
    override val required = listOf("path", "new")
    override val permissions = listOf("storage.write")
    override val risk = RiskLevel.HIGH
    override val timeoutMs = 20_000L
    /** Manifest, Gradle, CI and security-related files always ask, even in Jarvis mode. */
    override fun riskFor(input: JSONObject) = if (ProjectGuard.isSensitive(input.optString("path"))) RiskLevel.CRITICAL else RiskLevel.HIGH
    override fun describe(input: JSONObject) = "Edit project file ${input.optString("path").take(100)}: replace ${input.optString("old").take(50).replace('\n', ' ')} → ${input.optString("new").take(50).replace('\n', ' ')}"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val svc = kit.service(ctx.missionId)
        val failing = kit.lastFailureLog(ctx.missionId)
        // after a failed build/test the edit must be a real repair: related to the failure, never weakening security or tests
        RepairPolicy.checkEdit(input.optString("path"), input.optString("old"), input.optString("new"), if (failing != null) svc.changedFiles().toSet() else null, failing.orEmpty())
            ?.let { return ToolResult.fail(ToolErrorType.INVALID_INPUT, "REPAIR_POLICY: $it") }
        return ToolResult.ok(svc.edit(input.optString("path"), input.optString("old"), input.optString("new"), input.optBoolean("create", false), input.optBoolean("replace_all", false), input.optBoolean("allow_dirty", false)))
    }
}

class ProjectRevertTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_REVERT"
    override val description = "Undo this mission's changes to one file by restoring its original (snapshot taken before the first edit)."
    override val inputSchema = """{"path":"app/src/Main.kt"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("path")
    override val permissions = listOf("storage.write")
    override val risk = RiskLevel.HIGH
    override val timeoutMs = 20_000L
    override fun describe(input: JSONObject) = "Revert ${input.optString("path").take(100)} to its original"
    override suspend fun execute(input: JSONObject, ctx: ToolContext) = ToolResult.ok(kit.service(ctx.missionId).revert(input.optString("path")))
}

class ProjectDiffTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_DIFF"
    override val description = "Show the real diff of everything this mission changed, against the originals saved before the first edit (plus git diff --stat when git exists)."
    override val inputSchema = "{}"
    override val outputSchema = "unified diff"
    override val permissions = listOf("storage.read")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 30_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val svc = kit.service(ctx.missionId)
        val stat = kit.git(svc, "diff", "--stat")
        return ToolResult.ok(svc.diff() + (if (stat.isNullOrBlank()) "" else "\n--- git diff --stat ---\n$stat"))
    }
}

class ProjectRunTool(private val kit: ProjectToolkit) : Tool {
    override val name = "PROJECT_RUN"
    override val description = "Run a REAL build/test command in the project (allow-listed: gradlew/gradle test|assemble*, mvn test, npm test, pytest, go test, cargo test, make test; no shell). Returns the real exit code and log. If the toolchain is not installed on this device it says so; never assume success."
    override val inputSchema = """{"command":"./gradlew testDebugUnitTest --stacktrace"}"""
    override val outputSchema = "EXIT=, RESULT=PASS|FAIL|UNKNOWN, log tail"
    override val required = listOf("command")
    override val permissions = listOf("process.exec")
    override val risk = RiskLevel.CRITICAL // project build scripts are arbitrary code: always ask
    override val timeoutMs = 11 * 60_000L
    override fun describe(input: JSONObject) = "Run build/test command: ${input.optString("command").take(140)}"

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val command = input.optString("command")
        val argv = when (val c = CommandPolicy.check(command)) {
            is CommandCheck.Denied -> return ToolResult.fail(ToolErrorType.INVALID_INPUT, "COMMAND_REFUSED: ${c.reason}")
            is CommandCheck.Allowed -> c.argv
        }
        BuildRepair.blockReason(kit.failures(ctx.missionId))?.let { return ToolResult.fail(ToolErrorType.INVALID_INPUT, "REPAIR_LIMIT: $it") }
        val svc = kit.service(ctx.missionId)
        val logs = kit.logsDir(ctx.missionId)
        val n = (logs.listFiles()?.size ?: 0) + 1
        if (n > ProjectToolkit.MAX_RUNS) return ToolResult.fail(ToolErrorType.INVALID_INPUT, "Run budget reached (${ProjectToolkit.MAX_RUNS} runs). Stop and report the current state honestly.")
        val log = File(logs, "run-$n.log")
        val r = kit.run(svc, argv, log)
        return when {
            r.notFound -> ToolResult.fail(ToolErrorType.TOOL_ERROR, "TOOLCHAIN_UNAVAILABLE: '${argv[0]}' is not installed or not executable on this device, so this command cannot run here. Do NOT claim a build or test result. Report that it could not be run.")
            r.timedOut -> ToolResult.fail(ToolErrorType.TIMEOUT, "RUN_TIMED_OUT after 11 minutes. Log tail:\n" + BuildOutputParser.tail(r.output, 30))
            else -> {
                val s = BuildOutputParser.parse(r.exit, r.output)
                kit.noteRun(ctx.missionId, if (s.result == RunSummary.FAIL) FailureClassifier.buildFingerprint(r.output) else if (s.result == RunSummary.PASS) null else "unknown-result")
                ToolResult.ok("EXIT=${r.exit}\nRESULT=${s.result} ${s.detail}\nCOMMAND=${command.take(120)}\nLOG=run-$n.log\n--- last lines ---\n" + BuildOutputParser.tail(r.output))
            }
        }
    }
}

object ProjectTools {
    fun all(ctx: Context, ws: Workspace): List<Tool> {
        val kit = ProjectToolkit(ctx, ws)
        return listOf(ProjectOpenTool(kit), ProjectTreeTool(kit), ProjectReadTool(kit), ProjectSearchTool(kit), ProjectApplyEditTool(kit), ProjectRevertTool(kit), ProjectDiffTool(kit), ProjectRunTool(kit))
    }
}

/** Plan and step guidance for coding/maintenance missions. */
object CodingWorkflow {
    private val trigger = Regex("""(?i)(\bproject\b|\brepo(sitory)?\b|gradle|build|compile|unit test|fix (the )?(bug|error|test)|refactor|codebase)""")
    fun applies(objective: String) = trigger.containsMatchIn(objective) && Regex("""(?i)(code|bug|build|test|gradle|repo|compile|refactor|error|lint)""").containsMatchIn(objective)

    const val PLAN_HINT = """
CODING MISSION (real project on this device): 1) open + inspect [PROJECT_OPEN,PROJECT_TREE,PROJECT_SEARCH,PROJECT_READ]; 2) diagnose the cause from real files/logs; 3) smallest targeted change [PROJECT_READ,PROJECT_APPLY_EDIT]; 4) run the real build/tests [PROJECT_RUN]; 5) inspect the diff [PROJECT_DIFF]. Ask the project path with ASK_USER if the user did not give one. Repairs after a failed run are limited to 3 rounds."""

    const val STEP_RULES = """Coding rules: never claim a build/test passed unless PROJECT_RUN returned RESULT=PASS after your last edit. If PROJECT_RUN reports TOOLCHAIN_UNAVAILABLE or COMMAND_REFUSED, say that plainly and finish the step as blocked; do not simulate output. Edit only what the task needs; never touch secrets, never weaken security settings or tests to get green. Files with uncommitted user changes need ASK_USER first."""
}
