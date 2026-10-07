package com.aurix.agent.core.tools.device

import android.annotation.SuppressLint
import android.content.Context
import android.location.LocationManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telecom.TelecomManager
import android.telephony.SmsManager
import com.aurix.agent.core.memory.MemoryRepository
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolErrorType
import com.aurix.agent.core.tools.ToolException
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject

/** Emergency workflow: texts every saved emergency contact the live location, optionally calls the first. CRITICAL: always confirmed. */
class EmergencySosTool(private val ctx: Context, private val memory: MemoryRepository) : Tool {
    override val name = "EMERGENCY_SOS"
    override val description = "Emergency: text all saved emergency contacts your location and optionally call the first. Contacts are saved with 'emergency contact <name> <number>'."
    override val inputSchema = """{"call_first":true}"""
    override val outputSchema = "who was alerted"
    override val permissions = listOf("android.permission.SEND_SMS", "android.permission.ACCESS_FINE_LOCATION")
    override val risk = RiskLevel.CRITICAL
    override val timeoutMs = 40_000L
    override fun describe(input: JSONObject) = "EMERGENCY SOS: text your saved emergency contacts your location" + if (input.optBoolean("call_first", false)) " and call the first one" else ""

    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    override suspend fun execute(input: JSONObject, ctx0: ToolContext): ToolResult {
        requirePermission(ctx, android.Manifest.permission.SEND_SMS, "SMS")
        val people = memory.ofKind("emergency").mapNotNull { m -> normalizePhone(m.text.substringAfterLast(':'))?.let { m.text.substringBeforeLast(':').trim() to it } }.take(5)
        if (people.isEmpty()) throw ToolException(ToolErrorType.INVALID_INPUT, "No emergency contacts saved. Say: \"emergency contact Rahul 9876543210 add karo\".")
        val loc = try {
            val lm = ctx.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (e: SecurityException) { null }
        val where = loc?.let { "My location: https://maps.google.com/?q=${it.latitude},${it.longitude}" } ?: "My location is unavailable."
        val sms = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java) else SmsManager.getDefault()
        val text = "SOS! I need help. $where"
        people.forEach { (_, num) -> sms.sendMultipartTextMessage(num, null, sms.divideMessage(text), null, null) }
        var called = ""
        if (input.optBoolean("call_first", false)) {
            try {
                requirePermission(ctx, android.Manifest.permission.CALL_PHONE, "Phone")
                ctx.getSystemService(TelecomManager::class.java).placeCall(Uri.fromParts("tel", people[0].second, null), Bundle())
                called = "; calling ${people[0].first}"
            } catch (e: ToolException) { called = "; could not call (${e.message})" }
        }
        return ToolResult.ok("SOS texted to ${people.joinToString { it.first }}$called")
    }
}
