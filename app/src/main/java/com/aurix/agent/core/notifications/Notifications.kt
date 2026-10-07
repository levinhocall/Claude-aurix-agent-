package com.aurix.agent.core.notifications

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject

data class NotifItem(val pkg: String, val app: String, val title: String, val text: String, val time: Long, val otp: String?, val missedCall: Boolean)

/** In-memory only (never written to disk): the last 100 notifications seen while access is enabled. */
object NotificationStore {
    private val items = ArrayDeque<NotifItem>()
    @Synchronized fun add(i: NotifItem) {
        items.removeAll { it.pkg == i.pkg && it.title == i.title && it.text == i.text }
        items.addFirst(i)
        while (items.size > 100) items.removeLast()
    }
    @Synchronized fun recent(limit: Int, app: String?, missedOnly: Boolean): List<NotifItem> =
        items.filter { (!missedOnly || it.missedCall) && (app.isNullOrBlank() || it.app.contains(app, true) || it.pkg.contains(app, true)) }.take(limit)
    @Synchronized fun latestOtp(maxAgeMs: Long): NotifItem? = items.firstOrNull { it.otp != null && System.currentTimeMillis() - it.time <= maxAgeMs }
}

fun extractOtp(text: String): String? {
    if (!Regex("otp|one.?time|verification|verify|code|passcode|pin|कोड", RegexOption.IGNORE_CASE).containsMatchIn(text)) return null
    return Regex("(?<!\\d)(\\d{4,8})(?!\\d)").find(text)?.groupValues?.get(1)
}

class AurixNotificationListener : NotificationListenerService() {
    override fun onListenerConnected() { instance = this }
    override fun onListenerDisconnected() { if (instance === this) instance = null }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return
        val app = try { packageManager.getApplicationLabel(packageManager.getApplicationInfo(sbn.packageName, 0)).toString() } catch (e: Exception) { sbn.packageName }
        NotificationStore.add(NotifItem(sbn.packageName, app, title.take(80), text.take(300), sbn.postTime, extractOtp("$title $text"), n.category == Notification.CATEGORY_MISSED_CALL))
    }

    companion object { @Volatile var instance: AurixNotificationListener? = null }
}

private fun listener(): AurixNotificationListener = AurixNotificationListener.instance
    ?: throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Notification access is off. Open AURIX → Settings → Notification access and enable it.")

private fun ago(t: Long): String { val m = (System.currentTimeMillis() - t) / 60_000; return if (m < 1) "now" else if (m < 60) "${m}m ago" else "${m / 60}h ago" }

private class NotificationsListTool(private val settings: AgentSettings) : Tool {
    override val name = "NOTIFICATIONS_LIST"
    override val description = "Read recent notifications (optionally one app, or kind=missed_call). Text is hidden from cloud AI unless allowed in Settings."
    override val inputSchema = """{"limit":8,"app":"","kind":"missed_call"}"""
    override val outputSchema = "notifications"
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 8_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        listener()
        val items = NotificationStore.recent(input.optInt("limit", 8).coerceIn(1, 20), input.optString("app").ifBlank { null }, input.optString("kind") == "missed_call")
        if (items.isEmpty()) return ToolResult.ok(if (input.optString("kind") == "missed_call") "No missed calls seen" else "No recent notifications")
        if (ctx.cloud && !settings.aiSeesNotifications())
            return ToolResult.ok("${items.size} recent notifications from ${items.map { it.app }.distinct().take(5).joinToString()}. (Text is hidden from AI models; allow it in Settings → Notification access.)")
        return ToolResult.ok(items.joinToString("\n") { "${it.app} — ${it.title}${if (it.text.isNotBlank()) ": ${it.text.take(120)}" else ""} (${ago(it.time)})" })
    }
}

private class NotificationOtpTool(private val settings: AgentSettings) : Tool {
    override val name = "NOTIFICATION_OTP"
    override val description = "The latest OTP/verification code from notifications (last 10 minutes), only when asked."
    override val inputSchema = "{}"
    override val outputSchema = "code and sender"
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 8_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        listener()
        val o = NotificationStore.latestOtp(10 * 60_000L) ?: return ToolResult.ok("No OTP in the last 10 minutes")
        if (ctx.cloud && !settings.aiSeesNotifications()) return ToolResult.ok("An OTP from ${o.app} arrived ${ago(o.time)} (hidden from AI models; allow in Settings → Notification access).")
        return ToolResult.ok("OTP ${o.otp} from ${o.app} (${ago(o.time)})")
    }
}

private class NotificationsClearTool : Tool {
    override val name = "NOTIFICATIONS_CLEAR"
    override val description = "Dismiss all notifications."
    override val inputSchema = "{}"
    override val outputSchema = "confirmation"
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 8_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult { listener().cancelAllNotifications(); return ToolResult.ok("Notifications cleared") }
}

object NotificationTools {
    fun all(settings: AgentSettings): List<Tool> = listOf(NotificationsListTool(settings), NotificationOtpTool(settings), NotificationsClearTool())
}
