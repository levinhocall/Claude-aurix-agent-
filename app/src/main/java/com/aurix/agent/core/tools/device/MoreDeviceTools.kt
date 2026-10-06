package com.aurix.agent.core.tools.device

import android.app.ActivityManager
import android.app.KeyguardManager
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import com.aurix.agent.core.tools.screen.AurixAccessibilityService
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.util.Locale
import java.util.TimeZone

private val CC = mapOf("IN" to "91", "US" to "1", "CA" to "1", "GB" to "44", "AE" to "971", "PK" to "92", "BD" to "880", "NP" to "977", "SA" to "966", "AU" to "61", "SG" to "65", "LK" to "94")

/** WhatsApp's wa.me links need digits with country code and no '+'. Returns null when the number cannot be made complete. */
fun normalizeForWhatsApp(number: String, defaultCc: String?): String? {
    val digits = number.filter { it.isDigit() }
    return when {
        number.trim().startsWith("+") && digits.length in 8..15 -> digits
        digits.startsWith("00") && digits.length in 10..17 -> digits.drop(2)
        defaultCc != null && digits.startsWith("0") && digits.length in 9..12 -> defaultCc + digits.drop(1)
        defaultCc != null && digits.length == 10 -> defaultCc + digits
        digits.length in 11..15 -> digits
        else -> null
    }
}

class BrightnessTool(private val ctx: Context) : Tool {
    override val name = "BRIGHTNESS"
    override val description = "Set screen brightness 1-100 (level) or relative (delta). Needs 'Modify system settings' for AURIX."
    override val inputSchema = """{"level":60} | {"delta":20}"""
    override val outputSchema = "new brightness"
    override val permissions = listOf("android.permission.WRITE_SETTINGS")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        if (!Settings.System.canWrite(ctx))
            throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "Brightness needs 'Modify system settings' for AURIX. Open AURIX → Settings → Phone permissions → Modify system settings.")
        val cr = ctx.contentResolver
        val cur = Settings.System.getInt(cr, Settings.System.SCREEN_BRIGHTNESS, 128) * 100 / 255
        val target = when {
            input.has("level") -> input.optInt("level")
            input.has("delta") -> cur + input.optInt("delta")
            else -> throw ToolException(ToolErrorType.INVALID_INPUT, "Give level or delta")
        }
        val lvl = target.coerceIn(1, 100)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS_MODE, Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL)
        Settings.System.putInt(cr, Settings.System.SCREEN_BRIGHTNESS, lvl * 255 / 100)
        return ToolResult.ok("Brightness $lvl%")
    }
}

class VibrateTool(private val ctx: Context) : Tool {
    override val name = "VIBRATE"
    override val description = "Vibrate the phone for a moment (ms 50-3000)."
    override val inputSchema = """{"ms":500}"""
    override val outputSchema = "confirmation"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    @Suppress("DEPRECATION")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val v = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java).defaultVibrator
        else ctx.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        if (!v.hasVibrator()) throw ToolException(ToolErrorType.TOOL_ERROR, "This phone has no vibrator")
        val ms = input.optLong("ms", 500).coerceIn(50, 3000)
        v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
        return ToolResult.ok("Vibrated ${ms} ms")
    }
}

class NetworkStatusTool(private val ctx: Context) : Tool {
    override val name = "NETWORK_STATUS"
    override val description = "Internet connection, Wi-Fi, Bluetooth and airplane-mode state."
    override val inputSchema = "{}"
    override val outputSchema = "status line"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    @Suppress("DEPRECATION")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val via = listOfNotNull(
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) "Wi-Fi" else null,
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) "mobile data" else null,
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) "VPN" else null,
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) == true) "ethernet" else null,
        )
        val online = caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
        val wifiOn = try { (ctx.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager).isWifiEnabled } catch (e: Exception) { null }
        val bt = try { ctx.getSystemService(BluetoothManager::class.java)?.adapter?.isEnabled } catch (e: SecurityException) { null }
        val airplane = Settings.Global.getInt(ctx.contentResolver, Settings.Global.AIRPLANE_MODE_ON, 0) == 1
        fun onOff(b: Boolean?) = when (b) { true -> "on"; false -> "off"; null -> "unknown" }
        return ToolResult.ok("Internet: ${if (online) "connected" else "not connected"} (${via.joinToString().ifEmpty { "no network" }}); Wi-Fi ${onOff(wifiOn)}; Bluetooth ${onOff(bt)}; Airplane mode ${if (airplane) "on" else "off"}")
    }
}

class DeviceInfoTool(private val ctx: Context) : Tool {
    override val name = "DEVICE_INFO"
    override val description = "Phone model, Android version, memory, screen, language, time zone, screen on/locked state."
    override val inputSchema = "{}"
    override val outputSchema = "summary"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val mem = ActivityManager.MemoryInfo().also { (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).getMemoryInfo(it) }
        val dm = ctx.resources.displayMetrics
        val screenOn = (ctx.getSystemService(Context.POWER_SERVICE) as PowerManager).isInteractive
        val locked = (ctx.getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager).isKeyguardLocked
        return ToolResult.ok(
            "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}); RAM ${mem.totalMem / (1024 * 1024)} MB (${mem.availMem / (1024 * 1024)} MB free); " +
                "screen ${dm.widthPixels}x${dm.heightPixels} @${dm.densityDpi}dpi; ${Locale.getDefault().toLanguageTag()}, ${TimeZone.getDefault().id}; screen ${if (screenOn) "on" else "off"}, ${if (locked) "locked" else "unlocked"}",
        )
    }
}

class SettingsPanelTool(private val ctx: Context) : Tool {
    override val name = "OPEN_SETTINGS_PANEL"
    override val description = "Open a system settings panel (wifi/internet, bluetooth, volume, nfc, display, battery, location, sound, accessibility). Android does not let apps toggle Wi-Fi/Bluetooth directly; the user flips the switch."
    override val inputSchema = """{"panel":"bluetooth"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("panel")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 8_000L
    override fun describe(input: JSONObject) = "Open ${input.optString("panel")} settings"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val q = Build.VERSION.SDK_INT >= 29
        val action = when (input.optString("panel").lowercase()) {
            "wifi", "internet", "network" -> if (q) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIFI_SETTINGS
            "bluetooth" -> Settings.ACTION_BLUETOOTH_SETTINGS
            "volume" -> if (q) Settings.Panel.ACTION_VOLUME else Settings.ACTION_SOUND_SETTINGS
            "nfc" -> if (q) Settings.Panel.ACTION_NFC else Settings.ACTION_NFC_SETTINGS
            "display" -> Settings.ACTION_DISPLAY_SETTINGS
            "battery" -> Settings.ACTION_BATTERY_SAVER_SETTINGS
            "location" -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            "sound" -> Settings.ACTION_SOUND_SETTINGS
            "accessibility" -> Settings.ACTION_ACCESSIBILITY_SETTINGS
            else -> throw ToolException(ToolErrorType.INVALID_INPUT, "panel must be wifi, bluetooth, volume, nfc, display, battery, location, sound or accessibility")
        }
        openOtherApp(ctx, Intent(action))
        return ToolResult.ok("Opened ${input.optString("panel")} settings (the user toggles the switch)")
    }
}

class WhatsAppMessageTool(private val ctx: Context) : Tool {
    override val name = "WHATSAPP_MESSAGE"
    override val description = "Open a WhatsApp chat with the message typed; with Screen control on it also taps Send. Always asks the user first. number needs a country code (+91...)."
    override val inputSchema = """{"to":"contact name","number":"+919876543210","text":"message"}"""
    override val outputSchema = "result"
    override val required = listOf("number", "text")
    override val risk = RiskLevel.HIGH
    override val timeoutMs = 30_000L
    override fun describe(input: JSONObject) = "Send WhatsApp message to ${input.optString("to").ifBlank { "(unnamed)" }} (${input.optString("number")}): \"${input.optString("text").take(120)}\""

    private fun pkg(): String? = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { ctx.packageManager.getLaunchIntentForPackage(it) != null }

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val to = input.optString("to").ifBlank { "the contact" }
        val text = input.optString("text")
        if (text.isBlank() || text.length > 1000) throw ToolException(ToolErrorType.INVALID_INPUT, "Text must be 1-1000 characters")
        val num = normalizeForWhatsApp(input.optString("number"), CC[Locale.getDefault().country])
            ?: throw ToolException(ToolErrorType.INVALID_INPUT, "The number needs a country code, e.g. +91…")
        val p = pkg() ?: throw ToolException(ToolErrorType.TOOL_ERROR, "WhatsApp is not installed. Use SEND_SMS instead.")
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/$num?text=" + Uri.encode(text))).setPackage(p)
        openOtherApp(ctx, i)
        val svc = AurixAccessibilityService.instance
            ?: return ToolResult.ok("WhatsApp chat with $to opened with the message typed. Tap Send (turn on Screen control in Settings to let AURIX tap it).")
        for (n in 0 until 16) {
            delay(500)
            if (!svc.foregroundPackage().contains("whatsapp", true)) continue
            val btn = svc.findFirstWithDescription("Send") ?: continue
            val msg = svc.clickNode(btn)
            delay(700)
            return ToolResult.ok("$msg in WhatsApp for $to (delivery not verified)")
        }
        return ToolResult.ok("WhatsApp chat with $to is open and the message is typed, but the Send button was not found. Tap Send manually.")
    }
}

class EmailComposeTool(private val ctx: Context) : Tool {
    override val name = "EMAIL_COMPOSE"
    override val description = "Open the mail app with a draft (to, subject, body). The user reviews and presses Send; AURIX never sends email by itself."
    override val inputSchema = """{"to":"a@b.com","subject":"...","body":"..."}"""
    override val outputSchema = "confirmation"
    override val required = listOf("body")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Draft email to ${input.optString("to").ifBlank { "(no recipient)" }}: ${input.optString("subject").take(80)}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val i = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))
            .putExtra(Intent.EXTRA_EMAIL, arrayOf(input.optString("to")).filter { it.isNotBlank() }.toTypedArray())
            .putExtra(Intent.EXTRA_SUBJECT, input.optString("subject"))
            .putExtra(Intent.EXTRA_TEXT, input.optString("body"))
        openOtherApp(ctx, i)
        return ToolResult.ok("Email draft opened; the user must review and press Send")
    }
}

object MoreDeviceTools {
    fun all(ctx: Context): List<Tool> = listOf(
        BrightnessTool(ctx), VibrateTool(ctx), NetworkStatusTool(ctx), DeviceInfoTool(ctx),
        SettingsPanelTool(ctx), WhatsAppMessageTool(ctx), EmailComposeTool(ctx),
    )
}
