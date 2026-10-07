package com.aurix.agent.core.memory

import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject

private class MemorySaveTool(private val repo: MemoryRepository) : Tool {
    override val name = "MEMORY_SAVE"
    override val description = "Remember a useful fact/preference about the user (kind: fact, preference, task, place, emergency)."
    override val inputSchema = """{"text":"I am vegetarian","kind":"fact"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("text")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 8_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val id = repo.save(input.optString("text"), input.optString("kind", "fact").ifBlank { "fact" })
            ?: throw ToolException(ToolErrorType.TOOL_ERROR, if (repo.enabled()) "Nothing to remember" else "Memory is turned off in Settings")
        return ToolResult.ok("Remembered (#$id)")
    }
}

private class MemorySearchTool(private val repo: MemoryRepository) : Tool {
    override val name = "MEMORY_SEARCH"
    override val description = "Look up what is remembered about the user."
    override val inputSchema = """{"query":"food preference"}"""
    override val outputSchema = "matching memories"
    override val required = listOf("query")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 8_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val hits = repo.search(input.optString("query"))
        return ToolResult.ok(if (hits.isEmpty()) "Nothing remembered about that" else hits.joinToString("\n") { "#${it.id} ${it.text}" })
    }
}

private class MemoryForgetTool(private val repo: MemoryRepository) : Tool {
    override val name = "MEMORY_FORGET"
    override val description = "Forget one memory by id, or everything with all=true."
    override val inputSchema = """{"id":3} | {"all":true}"""
    override val outputSchema = "confirmation"
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 8_000L
    override fun riskFor(input: JSONObject) = if (input.optBoolean("all", false)) RiskLevel.HIGH else risk
    override fun describe(input: JSONObject) = if (input.optBoolean("all", false)) "Forget ALL memories" else "Forget memory #${input.optLong("id")}"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        if (input.optBoolean("all", false)) { repo.clear(); return ToolResult.ok("All memories deleted") }
        if (!input.has("id")) throw ToolException(ToolErrorType.INVALID_INPUT, "Give id or all=true")
        repo.delete(input.optLong("id")); return ToolResult.ok("Forgot #${input.optLong("id")}")
    }
}

object MemoryTools {
    fun all(repo: MemoryRepository): List<Tool> = listOf(MemorySaveTool(repo), MemorySearchTool(repo), MemoryForgetTool(repo))
}
