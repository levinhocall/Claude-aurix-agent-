package com.aurix.agent.core.tools

import org.json.JSONObject

enum class RiskLevel { LOW, MEDIUM, HIGH }

enum class ToolErrorType {
    NETWORK_ERROR, AUTH_ERROR, RATE_LIMIT, TIMEOUT, TOOL_ERROR, INVALID_INPUT, MODEL_ERROR, PERMISSION_REQUIRED, UNKNOWN_ERROR
}

data class RetryPolicy(val maxAttempts: Int = 1, val backoffMs: Long = 1_000)

data class ToolContext(val missionId: String)

class ToolException(val type: ToolErrorType, message: String) : Exception(message)

/** Path relative to the mission workspace. */
data class GeneratedFile(val path: String)

data class ToolResult(
    val ok: Boolean,
    val output: String,
    val errorType: ToolErrorType? = null,
    val files: List<GeneratedFile> = emptyList(),
) {
    companion object {
        fun ok(output: String, files: List<GeneratedFile> = emptyList()) = ToolResult(true, output, null, files)
        fun fail(type: ToolErrorType, message: String) = ToolResult(false, message, type)
    }
}

interface Tool {
    val name: String
    val description: String
    val inputSchema: String
    val outputSchema: String
    val required: List<String> get() = emptyList()
    val permissions: List<String> get() = emptyList()
    val risk: RiskLevel
    val timeoutMs: Long
    val retry: RetryPolicy get() = RetryPolicy()
    suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult
}
