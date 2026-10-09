package com.aurix.agent.core.approval

import com.aurix.agent.core.agent.AgentEventType
import com.aurix.agent.core.agent.AgentEvents
import com.aurix.agent.core.mission.MissionDao
import com.aurix.agent.core.security.SecureSettings
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolRegistry
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/** STANDARD: only HIGH-risk actions ask. STRICT: MEDIUM and HIGH ask. LOW never asks. */
/** JARVIS: routine HIGH actions (SMS, calls, WhatsApp) run without asking; CRITICAL (delete, money, SOS) still confirms. */
enum class PermissionMode { STANDARD, STRICT, JARVIS }
enum class Decision { APPROVED, DENIED, TIMED_OUT }

fun needsApproval(risk: RiskLevel, mode: PermissionMode): Boolean = when (risk) {
    RiskLevel.CRITICAL -> true
    RiskLevel.HIGH -> mode != PermissionMode.JARVIS
    RiskLevel.MEDIUM -> mode == PermissionMode.STRICT
    RiskLevel.LOW -> false
}

fun approvalKey(name: String, input: JSONObject): String = Integer.toHexString("$name|$input".hashCode())

@Singleton
class AgentSettings @Inject constructor(private val secure: SecureSettings) {
    @Volatile private var cache: PermissionMode? = null
    fun mode(): PermissionMode = cache ?: (try { PermissionMode.valueOf(secure.getString("permission_mode") ?: "") } catch (e: Exception) { PermissionMode.STANDARD }).also { cache = it }
    fun setMode(m: PermissionMode) { secure.putString("permission_mode", m.name); cache = m }

    fun userName(): String = secure.getString("user_name").orEmpty()
    fun setUserName(n: String) { secure.putString("user_name", n.trim().take(30)) }
    fun memoryEnabled(): Boolean = secure.getString("memory_enabled") != "0"
    fun setMemoryEnabled(on: Boolean) { secure.putString("memory_enabled", if (on) "1" else "0") }
    fun aiSeesNotifications(): Boolean = secure.getString("ai_sees_notifs") == "1"
    fun setAiSeesNotifications(on: Boolean) { secure.putString("ai_sees_notifs", if (on) "1" else "0") }
    fun alwaysAllowed(): Set<String> = secure.getString("always_allow").orEmpty().split(',').filter { it.isNotBlank() }.toSet()
    fun setAlwaysAllowed(s: Set<String>) { secure.putString("always_allow", s.joinToString(",")) }
    fun elevenKey(): String = secure.getString("eleven_key").orEmpty()
    fun setElevenKey(k: String) { secure.putString("eleven_key", k.trim()) }
    fun elevenVoice(): String = secure.getString("eleven_voice")?.takeIf { it.isNotBlank() } ?: "21m00Tcm4TlvDq8ikWAM"
    fun setElevenVoice(v: String) { secure.putString("eleven_voice", v.trim()) }
    fun proactive(): Boolean = secure.getString("proactive") != "0"
    fun setProactive(on: Boolean) { secure.putString("proactive", if (on) "1" else "0") }
    fun carBluetooth(): String = secure.getString("car_bt").orEmpty()
    fun setCarBluetooth(n: String) { secure.putString("car_bt", n.trim().take(40)) }
    fun wakeEnabled(): Boolean = secure.getString("wake_enabled") == "1"
    fun setWakeEnabled(on: Boolean) { secure.putString("wake_enabled", if (on) "1" else "0") }
}

/**
 * Pauses the mission until the user allows or denies a sensitive action. Decisions are persisted as events,
 * so after a process restart an already-approved action is not asked twice and a pending one is shown again.
 */
@Singleton
class ApprovalManager @Inject constructor(
    private val dao: MissionDao,
    private val events: AgentEvents,
    private val settings: AgentSettings,
    private val registry: ToolRegistry,
) {
    private val waiters = ConcurrentHashMap<String, CompletableDeferred<Boolean>>()

    /** HIGH actions the user chose to "always allow" skip the prompt; CRITICAL ones never can. */
    fun needsApproval(tool: Tool, input: JSONObject): Boolean {
        val risk = tool.riskFor(input)
        if (risk == RiskLevel.HIGH && tool.name in settings.alwaysAllowed()) return false
        return needsApproval(risk, settings.mode())
    }

    suspend fun request(missionId: String, tool: Tool, name: String, input: JSONObject, reason: String): Decision {
        val key = approvalKey(name, input)
        decisionFor(missionId, key)?.let { return it }
        val wkey = "$missionId:$key"
        val waiter = CompletableDeferred<Boolean>()
        waiters[wkey] = waiter
        try {
            decisionFor(missionId, key)?.let { return it } // decided between the first check and registering the waiter
            val asked = dao.getApprovalEvents(missionId).any {
                it.type == "APPROVAL_REQUESTED" && (try { JSONObject(it.detail).optString("key") == key } catch (e: Exception) { false })
            }
            if (!asked) {
                events.emit(
                    missionId, AgentEventType.APPROVAL_REQUESTED,
                    JSONObject().put("key", key).put("tool", name).put("summary", tool.describe(input).take(200)).put("reason", reason.take(120)).put("risk", tool.riskFor(input).name).toString(),
                )
            }
            return when (withTimeoutOrNull(TIMEOUT_MS) { waiter.await() }) {
                true -> Decision.APPROVED
                false -> Decision.DENIED
                null -> Decision.TIMED_OUT
            }
        } finally {
            waiters.remove(wkey)
        }
    }

    suspend fun resolve(missionId: String, key: String, allow: Boolean, alwaysTool: String? = null) {
        if (allow && alwaysTool != null) {
            val t = registry.get(alwaysTool)
            if (t != null && t.risk == RiskLevel.HIGH) settings.setAlwaysAllowed(settings.alwaysAllowed() + alwaysTool)
        }
        events.emit(missionId, if (allow) AgentEventType.APPROVAL_GRANTED else AgentEventType.APPROVAL_DENIED, "$key|${if (allow) "allowed" else "denied"}")
        waiters["$missionId:$key"]?.complete(allow)
    }

    private suspend fun decisionFor(missionId: String, key: String): Decision? {
        val d = dao.getApprovalEvents(missionId).lastOrNull {
            (it.type == "APPROVAL_GRANTED" || it.type == "APPROVAL_DENIED") && it.detail.substringBefore('|') == key
        } ?: return null
        return if (d.type == "APPROVAL_GRANTED") Decision.APPROVED else Decision.DENIED
    }

    private companion object { const val TIMEOUT_MS = 30 * 60_000L }
}
