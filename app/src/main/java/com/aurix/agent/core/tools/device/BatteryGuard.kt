package com.aurix.agent.core.tools.device

import android.app.usage.UsageStatsManager
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.screen.AurixAccessibilityService
import org.json.JSONObject

/** Known OEM "autostart / background" screens. Component names differ per ROM version, so each is only a candidate. Pure data. */
object OemGuard {
    data class Candidate(val pkg: String, val cls: String)

    private val xiaomi = listOf(Candidate("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity"))
    private val oppoFamily = listOf(
        Candidate("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
        Candidate("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity"),
        Candidate("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity"),
    )
    private val vivoFamily = listOf(
        Candidate("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
        Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
        Candidate("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"),
    )
    private val huawei = listOf(
        Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
        Candidate("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity"),
    )
    private val samsung = listOf(
        Candidate("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity"),
        Candidate("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity"),
    )
    private val asus = listOf(Candidate("com.asus.mobilemanager", "com.asus.mobilemanager.autostart.AutoStartActivity"))
    private val transsion = listOf(Candidate("com.transsion.phonemaster", "com.cyin.himgr.autostart.AutoStartActivity"))

    fun family(manufacturer: String): String = when (manufacturer.trim().lowercase()) {
        "xiaomi", "redmi", "poco" -> "xiaomi"
        "oppo", "realme", "oneplus" -> "oppo"
        "vivo", "iqoo" -> "vivo"
        "huawei", "honor" -> "huawei"
        "samsung" -> "samsung"
        "asus" -> "asus"
        "tecno", "infinix", "itel", "transsion" -> "transsion"
        else -> "generic"
    }

    fun candidates(manufacturer: String): List<Candidate> = when (family(manufacturer)) {
        "xiaomi" -> xiaomi; "oppo" -> oppoFamily; "vivo" -> vivoFamily; "huawei" -> huawei
        "samsung" -> samsung; "asus" -> asus; "transsion" -> transsion
        else -> emptyList()
    }

    /** One line the user/model can act on; OEM settings cannot be read back, so only the battery exemption is verifiable. */
    fun advice(manufacturer: String, ignoringOptimizations: Boolean, bucket: String?, screenControlOn: Boolean, assistantOn: Boolean? = null): String = buildString {
        append("device: ").append(manufacturer.ifBlank { "unknown" }).append(" (").append(family(manufacturer)).append(")\n")
        append("battery optimisation exemption: ").append(if (ignoringOptimizations) "ON" else "OFF -> run BATTERY_GUARD action=battery").append('\n')
        if (bucket != null) append("app standby bucket: ").append(bucket).append('\n')
        append("screen control (accessibility) connected: ").append(if (screenControlOn) "yes" else "NO -> SELF_ENABLE_SCREEN_CONTROL or ask the user to toggle it").append('\n')
        if (assistantOn != null) append("default assistant (power-button/long-press opens AURIX): ").append(if (assistantOn) "yes" else "NO -> run BATTERY_GUARD action=assistant and pick AURIX").append('\n')
        if (family(manufacturer) != "generic") append("autostart/background-run is an OEM setting Android cannot read back: run BATTERY_GUARD action=autostart once and ask the user to enable AURIX there.")
        else append("no OEM-specific autostart screen known for this device; the battery exemption is the main setting.")
    }
}

private class BatteryGuardTool(private val ctx: Context) : Tool {
    override val name = "BATTERY_GUARD"
    override val description = "Keep AURIX alive in the background (voice/wake word/missions). action=status | battery (system dialog: ignore battery optimisation) | autostart (open this phone brand's autostart/background screen) | assistant (open Default digital assistant app settings)."
    override val inputSchema = """{"action":"status|battery|autostart|assistant"}"""
    override val outputSchema = "status or which screen was opened"
    override val permissions = emptyList<String>()
    override val risk = RiskLevel.LOW
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Background-run check: ${input.optString("action", "status")}"

    private fun launch(i: Intent) { ctx.startActivity(i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    private fun ignoring() = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isIgnoringBatteryOptimizations(ctx.packageName)

    private fun bucket(): String? = if (Build.VERSION.SDK_INT >= 28) try {
        when ((ctx.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager).appStandbyBucket) {
            10 -> "ACTIVE"; 20 -> "WORKING_SET"; 30 -> "FREQUENT"; 40 -> "RARE"; 45 -> "RESTRICTED"; else -> "other"
        }
    } catch (e: Exception) { null } else null

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        when (input.optString("action", "status").lowercase()) {
            "status" -> return ToolResult.ok(OemGuard.advice(Build.MANUFACTURER, ignoring(), bucket(), AurixAccessibilityService.instance != null, com.aurix.agent.core.voice.AssistantRole.isDefault(ctx)))
            "battery" -> {
                if (ignoring()) return ToolResult.ok("Already exempt from battery optimisation [verified]")
                try { launch(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))) }
                catch (e: Exception) { try { launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)) } catch (e2: Exception) { throw ToolException(ToolErrorType.TOOL_ERROR, "Could not open battery settings") } }
                return ToolResult.ok("Opened the battery-optimisation dialog. The user must tap Allow; check with BATTERY_GUARD action=status (unconfirmed until then).")
            }
            "assistant" -> {
                try { launch(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS)) } catch (e: Exception) { throw ToolException(ToolErrorType.TOOL_ERROR, "Could not open assistant settings. Open Settings -> Apps -> Default apps -> Digital assistant app.") }
                return ToolResult.ok("Opened Default digital assistant settings. The user must choose AURIX; check with BATTERY_GUARD action=status (unconfirmed until then).")
            }
            "autostart" -> {
                for (c in OemGuard.candidates(Build.MANUFACTURER)) {
                    try { launch(Intent().setComponent(ComponentName(c.pkg, c.cls))); return ToolResult.ok("Opened ${Build.MANUFACTURER} autostart/background screen. Ask the user to enable AURIX there (cannot be verified automatically).") }
                    catch (e: ActivityNotFoundException) { } catch (e: SecurityException) { }
                }
                try { launch(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}"))) }
                catch (e: Exception) { throw ToolException(ToolErrorType.TOOL_ERROR, "Could not open any settings screen") }
                return ToolResult.ok("No known autostart screen for ${Build.MANUFACTURER}; opened AURIX app settings (Battery → Unrestricted, if offered). Unverified.")
            }
            else -> throw ToolException(ToolErrorType.INVALID_INPUT, "action must be status, battery, autostart or assistant")
        }
    }
}

object BatteryGuardTools { fun all(ctx: Context): List<Tool> = listOf(BatteryGuardTool(ctx)) }
