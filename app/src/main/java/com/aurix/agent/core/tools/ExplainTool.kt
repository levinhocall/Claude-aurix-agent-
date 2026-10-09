package com.aurix.agent.core.tools

import com.aurix.agent.core.mission.MissionDao
import org.json.JSONObject

class ExplainLastTool(private val dao: MissionDao) : Tool {
    override val name = "EXPLAIN_LAST"
    override val description = "Explain why the most recent failed or stuck task did not work."
    override val inputSchema = "{}"
    override val outputSchema = "plain explanation"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val m = dao.lastProblemMission(ctx.missionId) ?: return ToolResult.ok("Koi failed task nahi mila.")
        val c = dao.lastFailedToolCall(m.id)
        val sb = StringBuilder("Last problem: \"${m.objective.take(80)}\" (${m.status}).")
        m.error?.takeIf { it.isNotBlank() }?.let { sb.append(" Error: ${it.take(200)}.") }
        if (c != null) sb.append(" Tool ${c.tool} failed${c.errorType?.let { " ($it)" } ?: ""}: ${c.output.take(200)}")
        sb.append(" Dobara bolo 'retry' ya doosre tareeke se batao.")
        return ToolResult.ok(sb.toString())
    }
}
