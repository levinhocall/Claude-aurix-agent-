package com.aurix.agent.core.tools.device

import android.annotation.SuppressLint
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.LocationManager
import android.media.AudioManager
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.provider.AlarmClock
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telecom.TelecomManager
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject

private fun norm(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

/** Digits with optional leading +, 6-15 long; null if it does not look like a phone number. */
fun normalizePhone(raw: String): String? {
    val s = raw.trim().replace(Regex("[\\s\\-().]"), "")
    return if (Regex("^\\+?[0-9]{6,15}$").matches(s)) s else null
}

private fun requirePermission(ctx: Context, permission: String, label: String) {
    if (ContextCompat.checkSelfPermission(ctx, permission) != PackageManager.PERMISSION_GRANTED)
        throw ToolException(ToolErrorType.PERMISSION_REQUIRED, "$label permission is not granted. Open AURIX → Settings → Phone permissions and grant it.")
}

private fun startOtherApp(ctx: Context, intent: Intent) {
    if (!AppState.inForeground && !Settings.canDrawOverlays(ctx))
        throw ToolException(
            ToolErrorType.PERMISSION_REQUIRED,
            "Android blocks starting other apps from the background. Keep AURIX open on screen, or enable 'Display over other apps' for AURIX (Settings → Phone permissions).",
        )
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { ctx.startActivity(intent) } catch (e: ActivityNotFoundException) {
        throw ToolException(ToolErrorType.TOOL_ERROR, "No installed app can handle this request")
    }
}

private fun tryStart(ctx: Context, intent: Intent): Boolean = try { startOtherApp(ctx, intent); true } catch (e: ToolException) { if (e.type == ToolErrorType.PERMISSION_REQUIRED) throw e else false }

class OpenAppTool(private val ctx: Context) : Tool {
    override val name = "OPEN_APP"
    override val description = "Open an installed app by name (e.g. YouTube, WhatsApp, Chrome). Returns the app that was launched."
    override val inputSchema = """{"name":"app name"}"""
    override val outputSchema = "launched app name"
    override val required = listOf("name")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Open app: ${input.optString("name")}"

    @Suppress("DEPRECATION")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val q = norm(input.optString("name"))
        if (q.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "name is empty")
        val pm = ctx.packageManager
        val apps = pm.queryIntentActivities(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER), 0)
            .map { it to it.loadLabel(pm).toString() }
        val match = apps.firstOrNull { norm(it.second) == q } ?: apps.firstOrNull { norm(it.second).contains(q) }
            ?: apps.firstOrNull { norm(it.second).length > 2 && q.contains(norm(it.second)) }
            ?: throw ToolException(ToolErrorType.INVALID_INPUT, "No installed app matches '${input.optString("name")}'. Some installed apps: ${apps.take(20).joinToString { it.second }}")
        val intent = pm.getLaunchIntentForPackage(match.first.activityInfo.packageName)
            ?: throw ToolException(ToolErrorType.TOOL_ERROR, "${match.second} has no launchable screen")
        startOtherApp(ctx, intent)
        return ToolResult.ok("Launched ${match.second} (the request was delivered to Android)")
    }
}

class OpenUrlTool(private val ctx: Context) : Tool {
    override val name = "OPEN_URL"
    override val description = "Open a web link in the user's browser or the matching app (e.g. a YouTube results URL opens the YouTube app)."
    override val inputSchema = """{"url":"https://..."}"""
    override val outputSchema = "confirmation"
    override val required = listOf("url")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Open link: ${input.optString("url")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val url = input.optString("url").trim()
        if (!(url.startsWith("https://") || url.startsWith("http://"))) throw ToolException(ToolErrorType.INVALID_INPUT, "Only http/https links")
        startOtherApp(ctx, Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        return ToolResult.ok("Opened $url (request delivered)")
    }
}

class PlayMusicTool(private val ctx: Context) : Tool {
    override val name = "PLAY_MUSIC"
    override val description = "Ask the phone's music app to search and play a song/artist. Falls back to a YouTube search. OK means the request was delivered, not that audio is playing."
    override val inputSchema = """{"query":"song or artist"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("query")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Play music: ${input.optString("query")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val q = input.optString("query").trim()
        if (q.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "query is empty")
        val play = Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
            .putExtra(SearchManager.QUERY, q)
            .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
        if (tryStart(ctx, play)) return ToolResult.ok("Asked the music app to play \"$q\" (request delivered; playback not confirmed)")
        val yt = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + Uri.encode(q)))
        startOtherApp(ctx, yt)
        return ToolResult.ok("No music app handled it; opened a YouTube search for \"$q\" (user must tap a result)")
    }
}

class SetAlarmTool(private val ctx: Context) : Tool {
    override val name = "SET_ALARM"
    override val description = "Set an alarm at a clock time (24h)."
    override val inputSchema = """{"hour":7,"minute":30,"label":"optional"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("hour", "minute")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Set alarm %02d:%02d %s".format(input.optInt("hour"), input.optInt("minute"), input.optString("label"))

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val h = input.optInt("hour", -1); val m = input.optInt("minute", -1)
        if (h !in 0..23 || m !in 0..59) throw ToolException(ToolErrorType.INVALID_INPUT, "hour 0-23 and minute 0-59 required")
        val i = Intent(AlarmClock.ACTION_SET_ALARM).putExtra(AlarmClock.EXTRA_HOUR, h).putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        startOtherApp(ctx, i)
        return ToolResult.ok("Alarm request sent for %02d:%02d".format(h, m))
    }
}

class SetTimerTool(private val ctx: Context) : Tool {
    override val name = "SET_TIMER"
    override val description = "Start a countdown timer."
    override val inputSchema = """{"seconds":300,"label":"optional"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("seconds")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Start ${input.optInt("seconds")}s timer ${input.optString("label")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val s = input.optInt("seconds", -1)
        if (s !in 1..86_400) throw ToolException(ToolErrorType.INVALID_INPUT, "seconds must be 1..86400")
        startOtherApp(ctx, Intent(AlarmClock.ACTION_SET_TIMER).putExtra(AlarmClock.EXTRA_LENGTH, s).putExtra(AlarmClock.EXTRA_MESSAGE, input.optString("label")).putExtra(AlarmClock.EXTRA_SKIP_UI, true))
        return ToolResult.ok("Timer request sent for ${s}s")
    }
}

class FlashlightTool(private val ctx: Context) : Tool {
    override val name = "FLASHLIGHT"
    override val description = "Turn the flashlight on or off."
    override val inputSchema = """{"on":true}"""
    override val outputSchema = "confirmation"
    override val required = listOf("on")
    override val risk = RiskLevel.LOW
    override val timeoutMs = 8_000L

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val cm = ctx.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val id = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true }
            ?: throw ToolException(ToolErrorType.TOOL_ERROR, "This device has no flashlight")
        val on = input.optBoolean("on", true)
        cm.setTorchMode(id, on)
        return ToolResult.ok("Flashlight ${if (on) "on" else "off"}")
    }
}

class BatteryTool(private val ctx: Context) : Tool {
    override val name = "BATTERY_INFO"
    override val description = "Battery level and charging status."
    override val inputSchema = "{}"
    override val outputSchema = "level, charging state"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val i = ctx.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: throw ToolException(ToolErrorType.TOOL_ERROR, "Battery info unavailable")
        val level = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val charging = st == BatteryManager.BATTERY_STATUS_CHARGING || st == BatteryManager.BATTERY_STATUS_FULL
        return ToolResult.ok("Battery $level%, ${if (charging) "charging" else "not charging"}, temp ${i.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) / 10.0}°C")
    }
}

class VolumeTool(private val ctx: Context) : Tool {
    override val name = "SET_VOLUME"
    override val description = "Set media volume 0-100 (omit level to just read it)."
    override val inputSchema = """{"level":50}"""
    override val outputSchema = "current volume"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        if (input.has("level")) {
            val lv = input.optInt("level").coerceIn(0, 100)
            am.setStreamVolume(AudioManager.STREAM_MUSIC, lv * max / 100, 0)
        }
        return ToolResult.ok("Media volume ${am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max.coerceAtLeast(1)}%")
    }
}

class MediaControlTool(private val ctx: Context) : Tool {
    override val name = "MEDIA_CONTROL"
    override val description = "Send a media key to the active player: play, pause, play_pause, next, previous."
    override val inputSchema = """{"action":"next"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("action")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 5_000L
    override fun describe(input: JSONObject) = "Media: ${input.optString("action")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val code = when (input.optString("action").lowercase()) {
            "play" -> KeyEvent.KEYCODE_MEDIA_PLAY; "pause" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "play_pause", "toggle" -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
            "next" -> KeyEvent.KEYCODE_MEDIA_NEXT; "previous", "prev" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> throw ToolException(ToolErrorType.INVALID_INPUT, "action must be play, pause, play_pause, next or previous")
        }
        val am = ctx.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ToolResult.ok("Sent media key ${input.optString("action")} (player response not confirmed)")
    }
}

class ClipboardTool(private val ctx: Context) : Tool {
    override val name = "CLIPBOARD_SET"
    override val description = "Copy text to the clipboard."
    override val inputSchema = """{"text":"..."}"""
    override val outputSchema = "confirmation"
    override val required = listOf("text")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 5_000L
    override fun describe(input: JSONObject) = "Copy to clipboard: ${input.optString("text").take(80)}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val cb = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        cb.setPrimaryClip(android.content.ClipData.newPlainText("AURIX", input.optString("text")))
        return ToolResult.ok("Copied ${input.optString("text").length} characters")
    }
}

class SendSmsTool(private val ctx: Context) : Tool {
    override val name = "SEND_SMS"
    override val description = "Send a text message (SMS) to a phone number. Always asks the user for approval first."
    override val inputSchema = """{"number":"+919876543210","text":"message"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("number", "text")
    override val permissions = listOf("android.permission.SEND_SMS")
    override val risk = RiskLevel.HIGH
    override val timeoutMs = 15_000L
    override fun describe(input: JSONObject) = "Send SMS to ${input.optString("number")}: \"${input.optString("text").take(120)}\""

    @Suppress("DEPRECATION")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val number = normalizePhone(input.optString("number")) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "Invalid phone number")
        val text = input.optString("text")
        if (text.isBlank() || text.length > 1000) throw ToolException(ToolErrorType.INVALID_INPUT, "Text must be 1-1000 characters")
        requirePermission(ctx, android.Manifest.permission.SEND_SMS, "SMS")
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        sms.sendMultipartTextMessage(number, null, sms.divideMessage(text), null, null)
        return ToolResult.ok("SMS handed to the phone's messaging stack for $number (delivery not confirmed)")
    }
}

class CallPhoneTool(private val ctx: Context) : Tool {
    override val name = "CALL_PHONE"
    override val description = "Place a phone call to a number (use LOOKUP_CONTACT first for names). Always asks the user for approval first."
    override val inputSchema = """{"number":"+919876543210"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("number")
    override val permissions = listOf("android.permission.CALL_PHONE")
    override val risk = RiskLevel.HIGH
    override val timeoutMs = 15_000L
    override fun describe(input: JSONObject) = "Call ${input.optString("number")}"

    @SuppressLint("MissingPermission")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val number = normalizePhone(input.optString("number")) ?: throw ToolException(ToolErrorType.INVALID_INPUT, "Invalid phone number")
        requirePermission(ctx, android.Manifest.permission.CALL_PHONE, "Phone")
        ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", number, null), Bundle())
        return ToolResult.ok("Call to $number requested")
    }
}

class LookupContactTool(private val ctx: Context) : Tool {
    override val name = "LOOKUP_CONTACT"
    override val description = "Find phone numbers of saved contacts by name."
    override val inputSchema = """{"name":"Rahul"}"""
    override val outputSchema = "matching contacts with numbers"
    override val required = listOf("name")
    override val permissions = listOf("android.permission.READ_CONTACTS")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Look up contact: ${input.optString("name")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        requirePermission(ctx, android.Manifest.permission.READ_CONTACTS, "Contacts")
        val q = input.optString("name").trim()
        if (q.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "name is empty")
        val rows = mutableListOf<String>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME, ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} LIKE ?", arrayOf("%$q%"), null,
        )?.use { c -> while (c.moveToNext() && rows.size < 8) rows += "${c.getString(0)}: ${c.getString(1)}" }
        return if (rows.isEmpty()) ToolResult.ok("No contact matches '$q'") else ToolResult.ok(rows.distinct().joinToString("\n"))
    }
}

class LocationTool(private val ctx: Context) : Tool {
    override val name = "MY_LOCATION"
    override val description = "Last known device location (latitude, longitude, age)."
    override val inputSchema = "{}"
    override val outputSchema = "coordinates and a maps link"
    override val permissions = listOf("android.permission.ACCESS_FINE_LOCATION")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L

    @SuppressLint("MissingPermission")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        requirePermission(ctx, android.Manifest.permission.ACCESS_FINE_LOCATION, "Location")
        val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val best = lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
            ?: throw ToolException(ToolErrorType.TOOL_ERROR, "No recent location fix. Open a maps app once so the phone gets a fix.")
        val ageMin = (System.currentTimeMillis() - best.time) / 60_000
        return ToolResult.ok("lat ${best.latitude}, lon ${best.longitude}, accuracy ${best.accuracy.toInt()} m, ${ageMin} min old\nhttps://maps.google.com/?q=${best.latitude},${best.longitude}")
    }
}

class NavigateTool(private val ctx: Context) : Tool {
    override val name = "NAVIGATE"
    override val description = "Start turn-by-turn navigation to a place in the maps app."
    override val inputSchema = """{"destination":"place or address"}"""
    override val outputSchema = "confirmation"
    override val required = listOf("destination")
    override val risk = RiskLevel.MEDIUM
    override val timeoutMs = 10_000L
    override fun describe(input: JSONObject) = "Navigate to ${input.optString("destination")}"

    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        val d = input.optString("destination").trim()
        if (d.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "destination is empty")
        val nav = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=" + Uri.encode(d)))
        if (!tryStart(ctx, nav)) startOtherApp(ctx, Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=" + Uri.encode(d))))
        return ToolResult.ok("Navigation to \"$d\" requested")
    }
}

object DeviceTools {
    fun all(ctx: Context): List<Tool> = listOf(
        OpenAppTool(ctx), OpenUrlTool(ctx), PlayMusicTool(ctx), SetAlarmTool(ctx), SetTimerTool(ctx), FlashlightTool(ctx),
        BatteryTool(ctx), VolumeTool(ctx), MediaControlTool(ctx), ClipboardTool(ctx), SendSmsTool(ctx), CallPhoneTool(ctx),
        LookupContactTool(ctx), LocationTool(ctx), NavigateTool(ctx),
    )
}
