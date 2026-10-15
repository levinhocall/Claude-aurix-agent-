package com.aurix.agent.core.tools.shell

import android.content.Context
import android.content.pm.PackageManager
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import rikka.shizuku.Shizuku

/** Fixed system toggles. Commands are constants: the model only picks a setting and on/off, never a shell string. */
enum class PhoneToggle(val key: String, private val on: String, private val off: String, val stateCmd: String, val risk: RiskLevel) {
    WIFI("wifi", "svc wifi enable", "svc wifi disable", "settings get global wifi_on", RiskLevel.MEDIUM),
    BLUETOOTH("bluetooth", "svc bluetooth enable", "svc bluetooth disable", "settings get global bluetooth_on", RiskLevel.MEDIUM),
    MOBILE_DATA("mobile_data", "svc data enable", "svc data disable", "settings get global mobile_data", RiskLevel.MEDIUM),
    AIRPLANE("airplane", "cmd connectivity airplane-mode enable", "cmd connectivity airplane-mode disable", "settings get global airplane_mode_on", RiskLevel.HIGH);

    fun command(enable: Boolean) = if (enable) on else off

    companion object {
        fun parse(raw: String?): PhoneToggle? {
            val k = raw?.trim()?.lowercase()?.replace(' ', '_')?.replace("-", "_").orEmpty()
            return when (k) {
                "wifi", "wi_fi", "wlan" -> WIFI
                "bluetooth", "bt" -> BLUETOOTH
                "mobile_data", "data", "cellular" -> MOBILE_DATA
                "airplane", "airplane_mode", "flight", "flight_mode" -> AIRPLANE
                else -> null
            }
        }

        /** `settings get` prints 1/0 (wifi_on can also be 2 while it is switching on). */
        fun readsAsOn(output: String): Boolean? = when (output.trim()) {
            "1", "2", "3" -> true
            "0" -> false
            else -> null
        }

        fun parseState(raw: String?): Boolean? = when (raw?.trim()?.lowercase()) {
            "on", "enable", "enabled", "true", "1" -> true
            "off", "disable", "disabled", "false", "0" -> false
            else -> null
        }
    }
}

/** Builds the new enabled_accessibility_services value without dropping other services. Pure. */
fun mergeAccessibilityService(existing: String?, component: String): String {
    val parts = existing.orEmpty().trim().split(':').map { it.trim() }.filter { it.isNotEmpty() && it != "null" }
    return (parts + component).distinct().joinToString(":")
}

enum class ShizukuState { NOT_RUNNING, NO_PERMISSION, READY }

/** Runs fixed commands as the shell user through Shizuku (the user starts Shizuku once via wireless debugging; no root). */
object ShizukuShell {
    private const val REQUEST_CODE = 4711

    fun state(ctx: Context): ShizukuState = try {
        when {
            !Shizuku.pingBinder() -> ShizukuState.NOT_RUNNING
            Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED -> ShizukuState.READY
            else -> ShizukuState.NO_PERMISSION
        }
    } catch (e: Throwable) { ShizukuState.NOT_RUNNING }

    /** Throws a clear, actionable error unless Shizuku is ready. */
    fun require(ctx: Context) {
        when (state(ctx)) {
            ShizukuState.READY -> return
            ShizukuState.NOT_RUNNING -> throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Shizuku is not running. Install Shizuku, start it (Wireless debugging → Start in the Shizuku app; needed again after a reboot), then retry. Fallback: OPEN_SETTINGS_PANEL.")
            ShizukuState.NO_PERMISSION -> {
                try { Shizuku.requestPermission(REQUEST_CODE) } catch (_: Throwable) {}
                throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Allow AURIX in the Shizuku permission dialog (it was just requested), then retry.")
            }
        }
    }

    /** exit code to combined output. newProcess is private in Shizuku 13+, hence reflection. */
    suspend fun run(cmd: String, timeoutMs: Long = 8_000): Pair<Int, String> = withContext(Dispatchers.IO) {
        withTimeout(timeoutMs) {
            val m = Shizuku::class.java.getDeclaredMethod("newProcess", Array<String>::class.java, Array<String>::class.java, String::class.java).apply { isAccessible = true }
            val p = m.invoke(null, *arrayOf<Any?>(arrayOf("sh", "-c", cmd), null, null)) as Process
            try {
                val out = p.inputStream.bufferedReader().readText() + p.errorStream.bufferedReader().readText()
                p.waitFor() to out.trim()
            } finally { p.destroy() }
        }
    }
}

private class PhoneToggleTool(private val ctx: Context) : Tool {
    override val name = "PHONE_TOGGLE"
    override val description = "Turn Wi-Fi, Bluetooth, mobile data or airplane mode on/off directly and verify the result (needs Shizuku running; otherwise use OPEN_SETTINGS_PANEL)."
    override val inputSchema = """{"setting":"wifi|bluetooth|mobile_data|airplane","state":"on|off"}"""
    override val outputSchema = "verified new state"
    override val required = listOf("setting", "state")
    override val permissions = listOf("shizuku")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 20_000L
    override fun riskFor(input: JSONObject) = PhoneToggle.parse(input.optString("setting"))?.risk ?: risk
    override fun describe(input: JSONObject) = "Turn ${input.optString("setting")} ${input.optString("state")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val t = PhoneToggle.parse(input.optString("setting")) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "setting must be wifi, bluetooth, mobile_data or airplane")
        val want = PhoneToggle.parseState(input.optString("state")) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "state must be on or off")
        ShizukuShell.require(ctx)
        val (code, out) = ShizukuShell.run(t.command(want))
        if (code != 0) throw ToolException(ToolErrorType.TOOL_ERROR, "Command failed (exit $code): ${out.take(200)}")
        var now: Boolean? = null
        repeat(6) { // the system needs a moment; never claim success without reading the state back
            delay(500)
            now = PhoneToggle.readsAsOn(ShizukuShell.run(t.stateCmd).second)
            if (now == want) return ToolResult.ok("${t.key} is now ${if (want) "ON" else "OFF"} [verified]")
        }
        throw ToolException(ToolErrorType.TOOL_ERROR, "${t.key}: command ran but the state reads ${now?.let { if (it) "ON" else "OFF" } ?: "unknown"}, not ${if (want) "ON" else "OFF"}. Do not report success.")
    }
}

/** Re-enables AURIX's own accessibility service (Android drops it after updates / battery kills). */
private class SelfEnableScreenControlTool(private val ctx: Context) : Tool {
    override val name = "SELF_ENABLE_SCREEN_CONTROL"
    override val description = "Turn AURIX's own screen-control (accessibility) service back on without the user opening Settings (needs Shizuku running)."
    override val inputSchema = "{}"
    override val outputSchema = "verified"
    override val permissions = listOf("shizuku")
    override val risk = RiskLevel.HIGH
    override val timeoutMs = 20_000L
    override fun describe(input: JSONObject) = "Re-enable AURIX screen control"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        ShizukuShell.require(ctx)
        val component = "${ctx.packageName}/com.aurix.agent.core.tools.screen.AurixAccessibilityService"
        val current = ShizukuShell.run("settings get secure enabled_accessibility_services").second
        val merged = mergeAccessibilityService(current, component)
        val (c1, o1) = ShizukuShell.run("settings put secure enabled_accessibility_services $merged")
        val (c2, o2) = ShizukuShell.run("settings put secure accessibility_enabled 1")
        if (c1 != 0 || c2 != 0) throw ToolException(ToolErrorType.TOOL_ERROR, "Could not write the setting: ${(o1 + o2).take(200)}")
        repeat(6) {
            delay(500)
            if (com.aurix.agent.core.tools.screen.AurixAccessibilityService.instance != null) return ToolResult.ok("Screen control is connected [verified]")
        }
        throw ToolException(ToolErrorType.TOOL_ERROR, "Setting written but the service did not connect. Ask the user to toggle AURIX in Accessibility settings.")
    }
}

object ShellTools {
    fun all(ctx: Context): List<Tool> = listOf(PhoneToggleTool(ctx), SelfEnableScreenControlTool(ctx))
}
