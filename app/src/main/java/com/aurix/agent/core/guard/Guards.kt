package com.aurix.agent.core.guard

import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.security.SecureSettings
import com.aurix.agent.core.tools.RiskLevel
import com.aurix.agent.core.tools.Tool
import com.aurix.agent.core.tools.ToolContext
import com.aurix.agent.core.tools.ToolResult
import org.json.JSONObject
import javax.inject.Inject
import javax.inject.Singleton

enum class WebVerdict { ALLOW, ASK, BLOCK }
data class WebDecision(val verdict: WebVerdict, val reason: String = "")

/** Sentinel: every URL the agent wants to open is judged before it is fetched. Normal sites pass silently (no extra prompts); risky ones are blocked or need approval. */
object Sentinel {
    private val shorteners = setOf("bit.ly", "t.co", "tinyurl.com", "goo.gl", "is.gd", "cutt.ly", "rb.gy", "ow.ly", "shorturl.at", "t.ly")
    private val ipv4 = Regex("^[0-9]{1,3}(\\.[0-9]{1,3}){3}$")

    fun matches(host: String, domain: String): Boolean { val d = domain.trim().lowercase().trimStart('.'); return d.isNotEmpty() && (host == d || host.endsWith(".$d")) }

    fun check(url: String, tainted: Boolean, blocked: Set<String>): WebDecision {
        val uri = try { java.net.URI(url.trim()) } catch (e: Exception) { return WebDecision(WebVerdict.BLOCK, "malformed URL") }
        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return WebDecision(WebVerdict.BLOCK, "only http/https links are allowed")
        val h = uri.host?.lowercase()?.trimEnd('.') ?: return WebDecision(WebVerdict.BLOCK, "no host in URL")
        if (uri.userInfo != null) return WebDecision(WebVerdict.BLOCK, "credentials inside the URL")
        if (h == "localhost" || h.endsWith(".local") || h.endsWith(".internal") || ipv4.matches(h) || h.contains(':') || h.startsWith("[")) return WebDecision(WebVerdict.BLOCK, "local or raw-IP address")
        if (blocked.any { matches(h, it) }) return WebDecision(WebVerdict.BLOCK, "blocked by you")
        if (h in shorteners) return WebDecision(WebVerdict.ASK, "link shortener hides the real destination")
        if (h.split('.').any { it.startsWith("xn--") }) return WebDecision(WebVerdict.ASK, "look-alike (punycode) domain")
        if (scheme == "http") return WebDecision(WebVerdict.ASK, "connection is not encrypted (http)")
        if (tainted) return WebDecision(WebVerdict.ASK, "this task already saw suspicious instructions")
        return WebDecision(WebVerdict.ALLOW)
    }
}

/** Vault: secrets live only in encrypted storage. The model sees {{vault:NAME}}; the real value is substituted at the last moment, only for typing into a screen field. */
object VaultCodec {
    private val token = Regex("\\{\\{vault:([A-Za-z0-9_]{1,32})}}")
    /** Tools allowed to receive a real secret. Anything that sends data out (web, SMS, email, files) is excluded on purpose: that is how a hijacked task would leak it. */
    val SECRET_TOOLS = setOf("SCREEN_TYPE")

    fun normalize(name: String): String? = name.trim().uppercase().replace(' ', '_').takeIf { it.matches(Regex("[A-Z0-9_]{1,32}")) }
    fun tokens(input: JSONObject): List<String> = token.findAll(input.toString()).map { it.groupValues[1].uppercase() }.distinct().toList()
    fun hasToken(input: JSONObject) = token.containsMatchIn(input.toString())

    fun substitute(input: JSONObject, secrets: Map<String, String>): JSONObject =
        JSONObject(token.replace(input.toString()) { m ->
            val v = secrets[m.groupValues[1].uppercase()]
            if (v == null) m.value else JSONObject.quote(v).let { it.substring(1, it.length - 1) }
        })

    /** Anything a tool echoes back is scrubbed so a secret cannot reach the model, the transcript or the logs. */
    fun redact(text: String, secrets: Map<String, String>): String {
        var out = text
        for ((n, v) in secrets.entries.sortedByDescending { it.value.length }) if (v.length >= 4) out = out.replace(v, "{{vault:$n}}")
        return out
    }
}

/** Per-app switches: each group of tools can be turned off by the user. */
object ConnectorPolicy {
    val GROUPS = listOf("Messages & calls", "WhatsApp", "Email", "Web", "Screen control", "Phone storage", "Notifications", "Calendar", "Contacts", "System toggles")
    fun groupOf(tool: String): String? = when {
        tool == "WHATSAPP_MESSAGE" -> "WhatsApp"
        tool == "SEND_SMS" || tool == "CALL_PHONE" -> "Messages & calls"
        tool == "EMAIL_COMPOSE" -> "Email"
        tool == "WEB_SEARCH" || tool == "WEB_BROWSER" -> "Web"
        tool.startsWith("SCREEN_") -> "Screen control"
        tool.startsWith("STORAGE_") || tool == "OCR_IMAGE" -> "Phone storage"
        tool.startsWith("NOTIFICATION") || tool.endsWith("NOTIFICATIONS") -> "Notifications"
        tool.startsWith("CALENDAR_") -> "Calendar"
        tool == "LOOKUP_CONTACT" -> "Contacts"
        tool == "PHONE_TOGGLE" || tool == "SELF_ENABLE_SCREEN_CONTROL" -> "System toggles"
        else -> null
    }
}

@Singleton
class Vault @Inject constructor(private val secure: SecureSettings) {
    private val key = "vault_v1"
    @Synchronized fun all(): Map<String, String> = try {
        val o = JSONObject(secure.getString(key) ?: "{}"); o.keys().asSequence().associateWith { o.getString(it) }
    } catch (e: Exception) { emptyMap() }
    fun names(): List<String> = all().keys.sorted()
    @Synchronized fun put(name: String, value: String): Boolean {
        val n = VaultCodec.normalize(name) ?: return false
        if (value.isBlank() || value.length > 500) return false
        return secure.putStringDurable(key, JSONObject(all().toMutableMap().also { it[n] = value } as Map<*, *>).toString())
    }
    @Synchronized fun delete(name: String): Boolean = secure.putStringDurable(key, JSONObject(all().toMutableMap().also { it.remove(name) } as Map<*, *>).toString())
}

@Singleton
class Guards @Inject constructor(val vault: Vault, private val settings: AgentSettings) {
    /** Name of the switched-off group this tool belongs to, or null if it may run. */
    fun connectorOff(tool: String): String? = ConnectorPolicy.groupOf(tool)?.takeIf { it in settings.connectorsOff() }
    fun web(url: String, tainted: Boolean) = Sentinel.check(url, tainted, settings.sentinelBlocked())
    fun redact(text: String): String = VaultCodec.redact(text, vault.all())
}

class VaultListTool(private val vault: Vault) : Tool {
    override val name = "VAULT_LIST"
    override val description = "List the NAMES of saved secrets (passwords etc). To use one, put {{vault:NAME}} in a SCREEN_TYPE text; the app types the real value. You never see values; never ask the user for them in chat."
    override val inputSchema = "{}"
    override val outputSchema = "secret names"
    override val risk = RiskLevel.LOW
    override val timeoutMs = 5_000L
    override suspend fun execute(input: JSONObject, ctx: ToolContext): ToolResult {
        val n = vault.names()
        return ToolResult.ok(if (n.isEmpty()) "No secrets saved. The user can add them in Settings → Guards." else "Saved secrets: " + n.joinToString(", "))
    }
}
