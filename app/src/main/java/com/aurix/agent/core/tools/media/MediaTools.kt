package com.aurix.agent.core.tools.media

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import com.aurix.agent.core.memory.MemoryRepository
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject

private fun launch(ctx: Context, i: Intent): ToolResult = try {
    ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); ToolResult.ok("Opened")
} catch (e: Exception) { ToolResult.fail(ToolErrorType.TOOL_ERROR, "Is phone par koi app nahi mila: ${e.message}") }

class CameraOpenTool(private val ctx: Context) : Tool {
    override val name = "CAMERA_OPEN"
    override val description = "Open the camera app."
    override val inputSchema = "{}"
    override val outputSchema = "status"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L
    override suspend fun execute(input: JSONObject, ctx2: ToolContext) = launch(ctx, Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
}

class PhotosTool(private val ctx: Context) : Tool {
    override val name = "PHOTOS"
    override val description = "Open the gallery / photos."
    override val inputSchema = "{}"
    override val outputSchema = "status"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L
    override suspend fun execute(input: JSONObject, ctx2: ToolContext) =
        launch(ctx, Intent(Intent.ACTION_VIEW).setType("image/*").also { it.data = MediaStore.Images.Media.EXTERNAL_CONTENT_URI })
}

class NearbyPlacesTool(private val ctx: Context) : Tool {
    override val name = "NEARBY_PLACES"
    override val description = "Show nearby places of a kind (cafe, pharmacy, petrol pump) on the map."
    override val inputSchema = """{"query":"pharmacy"}"""
    override val outputSchema = "status"
    override val required = listOf("query")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L
    override suspend fun execute(input: JSONObject, ctx2: ToolContext): ToolResult {
        val q = input.optString("query").trim().take(60)
        if (q.isEmpty()) return ToolResult.fail(ToolErrorType.INVALID_INPUT, "query required")
        return launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(q))))
    }
}

private fun placeKey(n: String): String {
    val k = n.trim().lowercase()
    return when (k) { "ghar" -> "home"; "office" -> "work"; "gaadi", "car" -> "parking"; else -> k }
}

class PlaceSaveTool(private val memory: MemoryRepository) : Tool {
    override val name = "PLACE_SAVE"
    override val description = "Save a named place (home, work, parking) with an address or current location text."
    override val inputSchema = """{"name":"home","address":"..."}"""
    override val outputSchema = "status"
    override val required = listOf("name", "address")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val n = placeKey(input.optString("name")); val a = input.optString("address").trim()
        if (n.isEmpty() || a.isEmpty()) return ToolResult.fail(ToolErrorType.INVALID_INPUT, "name and address required")
        memory.save("$n = $a", "place") ?: return ToolResult.fail(ToolErrorType.TOOL_ERROR, "Memory off hai")
        return ToolResult.ok("Saved place $n")
    }
}

class PlaceGoTool(private val ctx: Context, private val memory: MemoryRepository) : Tool {
    override val name = "PLACE_GO"
    override val description = "Navigate to a saved place (home, work, parking)."
    override val inputSchema = """{"name":"home"}"""
    override val outputSchema = "status"
    override val required = listOf("name")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L
    override suspend fun execute(input: JSONObject, ctx2: ToolContext): ToolResult {
        val n = placeKey(input.optString("name"))
        val hit = memory.ofKind("place").map { it.text }.lastOrNull { it.substringBefore('=').trim() == n }
        val dest = hit?.substringAfter('=')?.trim() ?: n
        return launch(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(dest))))
    }
}

object MediaTools {
    fun all(ctx: Context, memory: MemoryRepository): List<Tool> =
        listOf(CameraOpenTool(ctx), PhotosTool(ctx), NearbyPlacesTool(ctx), PlaceSaveTool(memory), PlaceGoTool(ctx, memory))
}
