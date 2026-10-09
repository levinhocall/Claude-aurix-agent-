package com.aurix.agent.core.tools.screen

import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import kotlinx.coroutines.delay
import org.json.JSONObject

internal fun requireService(): AurixAccessibilityService = AurixAccessibilityService.instance
    ?: throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Screen control is not connected. Open AURIX → Settings → Screen control and enable the accessibility service. If it already shows ON, turn AURIX off and on again in Android Accessibility settings (Android sometimes drops the connection after an update or battery kill).")

/** Safety rules for acting on other apps. */
object ScreenGuard {
    const val OWN_PACKAGE = "com.aurix.agent"
    private val blocked = listOf("permissioncontroller", "packageinstaller")
    private val financial = listOf(
        "paisa", "phonepe", "paytm", "bhim", "upi", "bank", "wallet", "authenticator", "bitwarden", "keepass", "lastpass",
        "1password", "passwords", "gpay", "razorpay", "mobikwik", "creditcard", "netbanking",
    )

    fun isSensitive(pkg: String) = financial.any { pkg.contains(it, ignoreCase = true) } || pkg == "com.android.settings"

    /** Money/password apps: CRITICAL (never auto-allowed). Android Settings: HIGH. */
    fun riskFor(pkg: String, base: RiskLevel): RiskLevel = when {
        financial.any { pkg.contains(it, ignoreCase = true) } -> RiskLevel.CRITICAL
        pkg == "com.android.settings" -> RiskLevel.HIGH
        else -> base
    }

    /** Throws if automation must never run in this app. */
    fun check(pkg: String) {
        if (pkg == OWN_PACKAGE) throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Screen control is disabled inside AURIX itself (it could approve its own requests).")
        if (blocked.any { pkg.contains(it, true) }) throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Android permission/install dialogs can only be handled by the user.")
    }
}

private abstract class ScreenTool : Tool {
    override val permissions = listOf("accessibility")
    override val timeoutMs = 30_000L
    protected fun svc() = requireService().also { ScreenGuard.check(it.foregroundPackage()) }
    override fun riskFor(input: JSONObject): RiskLevel =
        ScreenGuard.riskFor(AurixAccessibilityService.instance?.foregroundPackage().orEmpty(), risk)
    protected fun appNote() = AurixAccessibilityService.instance?.foregroundPackage().orEmpty()
    protected suspend fun after(svc: AurixAccessibilityService, msg: String): ToolResult {
        delay(450)
        return ToolResult.ok(msg + "\n" + svc.snapshotText(30))
    }
}

private class ScreenReadTool : ScreenTool() {
    override val name = "SCREEN_READ"
    override val description = "Read what is on screen as a numbered element list ([id] role \"label\" flags c=clickable e=editable s=scrollable)."
    override val inputSchema = "{}"
    override val outputSchema = "app package + elements"
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Read the screen (${appNote()})"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val s = svc()
        return ToolResult.ok(s.snapshot(45).text())
    }
}

private class ScreenTapTool : ScreenTool() {
    override val name = "SCREEN_TAP"
    override val description = "Tap an on-screen element by id (from SCREEN_READ), by visible text, or at x,y. long=true long-presses. Returns the new screen."
    override val inputSchema = """{"id":3} | {"text":"Play"} | {"x":540,"y":1200} (+ "long":true)"""
    override val outputSchema = "result + new screen"
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject): String {
        val svc = AurixAccessibilityService.instance
        val what = when {
            input.has("id") -> svc?.labelFor(input.optInt("id"))?.takeIf { it.isNotEmpty() } ?: "element [${input.optInt("id")}]"
            input.has("text") -> "\"${input.optString("text")}\""
            else -> "(${input.optInt("x")},${input.optInt("y")})"
        }
        return "Tap $what in ${appNote()}"
    }
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val s = svc()
        val long = input.optBoolean("long", false)
        val msg = when {
            input.has("id") -> {
                val id = input.optInt("id")
                val n = s.nodeById(id) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "Element [$id] is gone. Call SCREEN_READ again.")
                s.clickNode(n, long)
            }
            input.has("text") -> {
                val t = input.optString("text")
                val n = s.findByText(t) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "No element with text \"$t\" on screen. Call SCREEN_READ.")
                s.clickNode(n, long)
            }
            input.has("x") && input.has("y") -> {
                if (!s.tap(input.optDouble("x").toFloat(), input.optDouble("y").toFloat(), if (long) 700 else 60)) throw ToolException(ToolErrorType.TOOL_ERROR, "Tap failed")
                "tapped (${input.optInt("x")},${input.optInt("y")})"
            }
            else -> throw ToolException(ToolErrorType.INVALID_INPUT, "Give id, text, or x and y")
        }
        return after(s, msg)
    }
}

private class ScreenTypeTool : ScreenTool() {
    override val name = "SCREEN_TYPE"
    override val description = "Type text into a field (focused one, or id from SCREEN_READ). append=true keeps existing text; submit=true presses Enter after. Never works on password fields."
    override val inputSchema = """{"text":"hello","id":2,"append":false,"submit":false}"""
    override val outputSchema = "result + new screen"
    override val required = listOf("text")
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Type \"${input.optString("text").take(60)}\" in ${appNote()}"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val s = svc()
        var msg = s.typeText(input.optString("text"), if (input.has("id")) input.optInt("id") else null, input.optBoolean("append", false))
        if (input.optBoolean("submit", false)) { delay(200); msg += "; " + s.pressKey("enter") }
        return after(s, msg)
    }
}

private class ScreenScrollTool : ScreenTool() {
    override val name = "SCREEN_SCROLL"
    override val description = "Scroll the current screen: direction down (see further content), up, left or right. Returns the new screen."
    override val inputSchema = """{"direction":"down"}"""
    override val outputSchema = "result + new screen"
    override val required = listOf("direction")
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Scroll ${input.optString("direction")} in ${appNote()}"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val d = input.optString("direction").lowercase()
        if (d !in listOf("up", "down", "left", "right")) throw ToolException(ToolErrorType.INVALID_INPUT, "direction must be up, down, left or right")
        val s = svc()
        return after(s, s.scroll(d))
    }
}

private class ScreenKeyTool : ScreenTool() {
    override val name = "SCREEN_KEY"
    override val description = "Press a system key: back, home, recents, notifications, quick_settings, lock, screenshot, enter."
    override val inputSchema = """{"key":"back"}"""
    override val outputSchema = "result + new screen"
    override val required = listOf("key")
    override val risk = RiskLevel.MEDIUM
    override fun describe(input: JSONObject) = "Press ${input.optString("key")}"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val s = requireService()
        val key = input.optString("key").lowercase()
        if (key != "home" && key != "lock") ScreenGuard.check(s.foregroundPackage())
        return after(s, s.pressKey(key))
    }
}

private class ScreenWaitTool : ScreenTool() {
    override val name = "SCREEN_WAIT"
    override val description = "Wait up to N seconds (max 15) until some text appears on screen (or just wait). Returns the screen."
    override val inputSchema = """{"text":"Play","seconds":8}"""
    override val outputSchema = "new screen"
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 25_000L
    override fun describe(input: JSONObject) = "Wait for screen (${appNote()})"
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val s = requireService()
        val end = System.currentTimeMillis() + input.optInt("seconds", 5).coerceIn(1, 15) * 1000L
        val want = input.optString("text").trim()
        while (System.currentTimeMillis() < end) {
            if (want.isNotEmpty() && s.snapshot(60).lines.any { it.contains(want, true) }) return ToolResult.ok("found \"$want\"\n" + s.snapshotText(30))
            delay(400)
        }
        return ToolResult.ok((if (want.isNotEmpty()) "\"$want\" did not appear" else "waited") + "\n" + s.snapshotText(30))
    }
}

object ScreenTools {
    fun all(): List<Tool> = listOf(ScreenReadTool(), ScreenTapTool(), ScreenTypeTool(), ScreenScrollTool(), ScreenKeyTool(), ScreenWaitTool())
}
