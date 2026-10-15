package com.aurix.agent.core.tools.device

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

object CalendarParse {
    /** "2026-10-20T15:00" or "2026-10-20 15:00" in the phone's time zone -> epoch millis, null if it is not a date. */
    fun parse(raw: String, zone: ZoneId = ZoneId.systemDefault()): Long? = try {
        LocalDateTime.parse(raw.trim().replace(' ', 'T')).atZone(zone).toInstant().toEpochMilli()
    } catch (e: Exception) { null }
}

/** Opens the calendar's own "new event" form prefilled; the user taps Save, so nothing is created behind their back. */
private class CalendarAddTool(private val ctx: Context) : Tool {
    override val name = "CALENDAR_ADD"
    override val description = "Open a prefilled new-event form in the calendar app (the user taps Save). start is local time like 2026-10-20T15:00."
    override val inputSchema = """{"title":"Meeting","start":"2026-10-20T15:00","duration_min":60,"location":"","notes":""}"""
    override val outputSchema = "confirmation"
    override val required = listOf("title", "start")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Add calendar event: ${input.optString("title").take(60)} at ${input.optString("start")}"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val begin = CalendarParse.parse(input.optString("start")) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "start must look like 2026-10-20T15:00")
        val mins = input.optInt("duration_min", 60).coerceIn(5, 24 * 60)
        val i = Intent(Intent.ACTION_INSERT).setData(CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, begin).putExtra(CalendarContract.EXTRA_EVENT_END_TIME, begin + mins * 60_000L)
            .putExtra(CalendarContract.Events.TITLE, input.optString("title")).putExtra(CalendarContract.Events.EVENT_LOCATION, input.optString("location"))
            .putExtra(CalendarContract.Events.DESCRIPTION, input.optString("notes"))
        openOtherApp(ctx, i)
        return ToolResult.ok("Calendar form opened with the event filled in. The user must tap Save (not confirmed until then).")
    }
}

private class CalendarListTool(private val ctx: Context) : Tool {
    override val name = "CALENDAR_LIST"
    override val description = "List calendar events in the next N days (default 7)."
    override val inputSchema = """{"days":7}"""
    override val outputSchema = "events"
    override val permissions = listOf("android.permission.READ_CALENDAR")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Read calendar for the next ${input.optInt("days", 7)} days"
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_CALENDAR) != PackageManager.PERMISSION_GRANTED)
            throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Calendar permission is not granted. Allow it for AURIX in Android app settings, then retry.")
        val days = input.optInt("days", 7).coerceIn(1, 60)
        val now = System.currentTimeMillis()
        val fmt = DateTimeFormatter.ofPattern("EEE d MMM HH:mm")
        val rows = ArrayList<String>()
        CalendarContract.Instances.query(ctx.contentResolver, arrayOf(CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN, CalendarContract.Instances.ALL_DAY), now, now + days * 86_400_000L)?.use { c ->
            while (c.moveToNext() && rows.size < 25) {
                val t = c.getString(0).orEmpty().ifBlank { "(no title)" }
                val whenText = if (c.getInt(2) == 1) "all day" else fmt.format(Instant.ofEpochMilli(c.getLong(1)).atZone(ZoneId.systemDefault()))
                rows += "$whenText  $t"
            }
        }
        return ToolResult.ok(if (rows.isEmpty()) "No events in the next $days days." else rows.sorted().joinToString("\n"))
    }
}

object CalendarTools { fun all(ctx: Context): List<Tool> = listOf(CalendarAddTool(ctx), CalendarListTool(ctx)) }
