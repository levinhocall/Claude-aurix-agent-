package com.aurix.agent.core.tools

import com.aurix.agent.core.approval.QuestionManager
import org.json.JSONObject

class AskUserTool(private val questions: QuestionManager) : Tool {
    override val name = "ASK_USER"
    override val description = "Ask the user a short question when something is ambiguous or missing (e.g. which contact). Optional answer buttons."
    override val inputSchema = """{"question":"Which Mom?","options":["Sharadha Mom","Mom"]}"""
    override val outputSchema = "the user's answer"
    override val required = listOf("question")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 31 * 60_000L
    override fun describe(input: JSONObject) = "Ask: ${input.optString("question")}"

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val opts = input.optJSONArray("options")?.let { a -> (0 until a.length()).map { a.optString(it) }.filter { it.isNotBlank() } }.orEmpty()
        val answer = questions.ask(ctx.missionId, input.optString("question").trim(), opts)
        return ToolResult.ok("User answered: $answer")
    }
}
