package com.aurix.agent.core.tools

import org.json.JSONObject

private const val MAX_WRITE_CHARS = 200_000

class FileWriteTool(private val ws: Workspace) : Tool {
    override val name = "FILE_WRITE"
    override val description = "Create or overwrite a text file (md, txt, csv, json, html...) in the mission workspace. Use append=true to add to the end (write long content in chunks)."
    override val inputSchema = """{"path":"relative/path.ext","content":"text","append":false}"""
    override val outputSchema = "confirmation with byte size"
    override val required = listOf("path", "content")
    override val permissions = listOf("workspace.write")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 15_000L

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val content = input.optString("content")
        if (content.length > MAX_WRITE_CHARS) throw ToolException(ToolErrorType.INVALID_INPUT, "Content too large; write in chunks with append=true")
        val f = ws.resolve(ctx.missionId, input.optString("path"))
        f.parentFile?.mkdirs()
        if (input.optBoolean("append", false)) f.appendText(content) else f.writeText(content)
        return ToolResult.ok("Wrote ${content.toByteArray().size} bytes to ${ws.relative(ctx.missionId, f)} (total ${f.length()} bytes)", listOf(GeneratedFile(ws.relative(ctx.missionId, f))))
    }
}

class FileReadTool(private val ws: Workspace) : Tool {
    override val name = "FILE_READ"
    override val description = "Read a text file from the mission workspace."
    override val inputSchema = """{"path":"relative/path.ext","max_chars":8000}"""
    override val outputSchema = "file text (possibly truncated)"
    override val required = listOf("path")
    override val permissions = listOf("workspace.read")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 15_000L

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val f = ws.resolve(ctx.missionId, input.optString("path"))
        if (!f.isFile) throw ToolException(ToolErrorType.INVALID_INPUT, "File not found: ${input.optString("path")}")
        if (f.length() > 2_000_000) throw ToolException(ToolErrorType.INVALID_INPUT, "File too large to read")
        val max = input.optInt("max_chars", 8000).coerceIn(100, 20_000)
        val text = f.readText()
        return ToolResult.ok(if (text.length > max) text.take(max) + "\n[truncated, ${text.length - max} more chars]" else text)
    }
}

class FileEditTool(private val ws: Workspace) : Tool {
    override val name = "FILE_EDIT"
    override val description = "Replace exactly one occurrence of `find` with `replace` in a workspace file."
    override val inputSchema = """{"path":"relative/path.ext","find":"exact old text","replace":"new text"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("path", "find", "replace")
    override val permissions = listOf("workspace.write")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 15_000L

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val f = ws.resolve(ctx.missionId, input.optString("path"))
        if (!f.isFile) throw ToolException(ToolErrorType.INVALID_INPUT, "File not found")
        val find = input.optString("find")
        if (find.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "`find` must not be empty")
        val text = f.readText()
        val count = text.split(find).size - 1
        if (count != 1) throw ToolException(ToolErrorType.INVALID_INPUT, "`find` must match exactly once, matched $count times")
        f.writeText(text.replace(find, input.optString("replace")))
        return ToolResult.ok("Edited ${ws.relative(ctx.missionId, f)}", listOf(GeneratedFile(ws.relative(ctx.missionId, f))))
    }
}

class FileListTool(private val ws: Workspace) : Tool {
    override val name = "FILE_LIST"
    override val description = "List files in the mission workspace."
    override val inputSchema = "{}"
    override val outputSchema = "list of relative paths with sizes"
    override val permissions = listOf("workspace.read")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L

    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val base = ws.dir(ctx.missionId)
        val files = base.walkTopDown().filter { it.isFile }.take(200).toList()
        return ToolResult.ok(if (files.isEmpty()) "(workspace is empty)" else files.joinToString("\n") { "${it.relativeTo(base).path} (${it.length()} bytes)" })
    }
}
