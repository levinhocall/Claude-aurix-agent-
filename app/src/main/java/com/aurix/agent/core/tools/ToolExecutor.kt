package com.aurix.agent.core.tools

import com.aurix.agent.core.agent.AgentEventType
import com.aurix.agent.core.agent.AgentEvents
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.mission.MissionFileEntity
import com.aurix.agent.core.mission.ToolCallEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Validates, risk-gates, times out, retries, logs and verifies every tool call. */
@Singleton
class ToolExecutor @Inject constructor(
    private val registry: ToolRegistry,
    private val dao: MissionDao,
    private val events: AgentEvents,
    private val workspace: Workspace,
) {
    suspend fun execute(missionId: String, stepIdx: Int, name: String, input: JSONObject, approved: Boolean = false, cloud: Boolean = false): ToolResult {
        val started = System.currentTimeMillis()
        events.emit(missionId, AgentEventType.TOOL_STARTED, "$name ${input.toString().take(200)}")
        val result = run(missionId, name, input, approved, cloud)
        val dur = System.currentTimeMillis() - started
        dao.insertToolCall(
            ToolCallEntity(
                missionId = missionId, stepIdx = stepIdx, tool = name, input = input.toString().take(2000),
                ok = result.ok, errorType = result.errorType?.name, output = result.output.take(1200), durationMs = dur, ts = started,
            )
        )
        val tail = if (result.ok) "" else " ${result.errorType}: ${result.output.take(150)}"
        events.emit(missionId, if (result.ok) AgentEventType.TOOL_COMPLETED else AgentEventType.TOOL_FAILED, "$name ${dur}ms$tail")
        return result
    }

    private suspend fun run(missionId: String, name: String, input: JSONObject, approved: Boolean, cloud: Boolean): ToolResult {
        val tool = registry.get(name)
            ?: return ToolResult.fail(ToolErrorType.INVALID_INPUT, "Unknown tool '$name'. Available: ${registry.names()}")
        val missing = tool.required.filter { !input.has(it) || input.isNull(it) }
        if (missing.isNotEmpty()) return ToolResult.fail(ToolErrorType.INVALID_INPUT, "Missing input field(s): ${missing.joinToString()}. Schema: ${tool.inputSchema}")
        if (tool.riskFor(input).ordinal >= RiskLevel.HIGH.ordinal && !approved) return ToolResult.fail(ToolErrorType.PERMISSION_REQUIRED, "This action needs user approval")

        var attempt = 0
        while (true) {
            attempt++
            val res: ToolResult = try {
                withTimeout(tool.timeoutMs) { tool.execute(input, ToolContext(missionId, cloud)) }
            } catch (e: TimeoutCancellationException) {
                ToolResult.fail(ToolErrorType.TIMEOUT, "Timed out after ${tool.timeoutMs / 1000}s")
            } catch (e: ToolException) {
                ToolResult.fail(e.type, e.message ?: "tool error")
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                ToolResult.fail(ToolErrorType.NETWORK_ERROR, "I/O error (${e.javaClass.simpleName})")
            } catch (e: Exception) {
                ToolResult.fail(ToolErrorType.TOOL_ERROR, "${e.javaClass.simpleName}: ${e.message?.take(200)}")
            }
            val retryable = !res.ok && (res.errorType == ToolErrorType.NETWORK_ERROR || res.errorType == ToolErrorType.TIMEOUT || res.errorType == ToolErrorType.RATE_LIMIT)
            if (retryable && attempt < tool.retry.maxAttempts) {
                delay(tool.retry.backoffMs * attempt)
                continue
            }
            return if (res.ok) verifyArtifacts(missionId, res) else res
        }
    }

    /** A successful tool call is not enough: every produced file must exist, be readable and non-empty. */
    private suspend fun verifyArtifacts(missionId: String, res: ToolResult): ToolResult {
        if (res.files.isEmpty()) return res
        val notes = mutableListOf<String>()
        for (g in res.files) {
            val f = try { workspace.resolve(missionId, g.path) } catch (e: ToolException) { return ToolResult.fail(e.type, "Verification failed: ${e.message}") }
            if (!f.isFile || !f.canRead() || f.length() == 0L)
                return ToolResult.fail(ToolErrorType.TOOL_ERROR, "Verification failed: ${g.path} is missing, unreadable or empty")
            dao.upsertFile(MissionFileEntity(missionId, g.path, f.length(), System.currentTimeMillis(), verified = true))
            notes += "${g.path} (${f.length()} bytes)"
        }
        return res.copy(output = res.output + "\n[verified: ${notes.joinToString()}]")
    }
}
