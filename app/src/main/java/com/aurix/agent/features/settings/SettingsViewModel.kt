package com.aurix.agent.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.ai.AiError
import com.aurix.agent.core.approval.AgentSettings
import com.aurix.agent.core.audit.AuditExporter
import com.aurix.agent.core.approval.PermissionMode
import com.aurix.agent.core.ai.AiErrorType
import com.aurix.agent.core.ai.routing.KeyDetector
import com.aurix.agent.core.ai.routing.KeyEntry
import com.aurix.agent.core.ai.routing.KeyPool
import com.aurix.agent.core.ai.routing.ModelDiscovery
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.ProviderStore
import com.aurix.agent.core.ai.routing.RoutingPolicy
import com.aurix.agent.core.ai.routing.UsageTracker
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.UUID
import javax.inject.Inject

data class SettingsState(
    val providers: List<ProviderEntry> = emptyList(),
    val policy: RoutingPolicy = RoutingPolicy.BALANCED,
    val dailyBudget: Long = 0,
    val todayTokens: Long = 0,
    val busy: Boolean = false,
    val strictApprovals: Boolean = false,
    val userName: String = "",
    val wakeEnabled: Boolean = false,
    val alwaysAllowed: List<String> = emptyList(),
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: ProviderStore,
    private val pool: KeyPool,
    private val usage: UsageTracker,
    private val discovery: ModelDiscovery,
    private val agentSettings: AgentSettings,
    private val auditExporter: AuditExporter,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsState())
    val ui: StateFlow<SettingsState> = _ui.asStateFlow()
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { viewModelScope.launch(Dispatchers.IO) { reload() } }

    private fun reload(busy: Boolean = _ui.value.busy) {
        _ui.value = SettingsState(store.providers(), store.policy(), usage.budget(), usage.today(), busy, agentSettings.mode() == PermissionMode.STRICT, agentSettings.userName(), agentSettings.wakeEnabled(), agentSettings.alwaysAllowed().sorted())
        refreshStatus()
    }

    fun refreshStatus() {
        val m = HashMap<String, String>()
        _ui.value.providers.forEach { p -> p.keys.forEach { m[it.id] = pool.status(it.id) } }
        _status.value = m
        _ui.value = _ui.value.copy(todayTokens = usage.today())
    }

    /** Paste a key -> provider detected, key validated, best models chosen automatically. */
    fun addKey(secretRaw: String, customBaseUrl: String) {
        val secret = secretRaw.trim()
        if (secret.isEmpty() && customBaseUrl.isBlank()) return
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = _ui.value.copy(busy = true)
            _message.value = "Checking key…"
            val preset = KeyDetector.detect(secret, customBaseUrl)
            if (preset == null) {
                _message.value = "Couldn't recognise this. For other providers open Advanced and enter the base URL (https://…). For a local model on your Wi-Fi enter http://<pc-ip>:11434/v1 and leave the key empty."
                _ui.value = _ui.value.copy(busy = false)
                return@launch
            }
            try {
                val (pick, note) = discovery.pick(preset, secret)
                val list = store.providers()
                val existing = list.firstOrNull { it.baseUrl.trimEnd('/') == preset.baseUrl.trimEnd('/') && it.type == preset.type }
                val newKeys = if (preset.local) emptyList() else listOf(KeyEntry(UUID.randomUUID().toString(), "Key ${(existing?.keys?.size ?: 0) + 1}", secret))
                val updated = if (existing != null) {
                    list.map { if (it.id == existing.id) it.copy(fastModel = pick.fast, strongModel = pick.strong, keys = it.keys + newKeys, local = it.local || preset.local) else it }
                } else {
                    list + ProviderEntry(UUID.randomUUID().toString(), preset.type, preset.name, preset.baseUrl, pick.fast, pick.strong, keys = newKeys, local = preset.local)
                }
                store.save(updated)
                _message.value = "✓ ${preset.name} connected — best model: ${pick.strong}" + (note?.let { "\n$it" } ?: "")
            } catch (e: AiError) {
                _message.value = if (e.type == AiErrorType.AUTH_ERROR) "✗ ${preset.name} rejected this key (invalid or no access)." else "✗ ${e.type}: ${e.message}"
            }
            _ui.value = _ui.value.copy(busy = false)
            reload(false)
        }
    }

    fun removeKey(providerId: String, keyId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val list = store.providers().map { p -> if (p.id == providerId) p.copy(keys = p.keys.filter { it.id != keyId }) else p }.filter { it.keys.isNotEmpty() || it.local }
            store.save(list)
            reload()
        }
    }

    /** Re-detect the best models for a provider (e.g. after new models are released) and re-enable its keys. */
    fun recheck(providerId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            val p = store.providers().firstOrNull { it.id == providerId } ?: return@launch
            val secretForCheck = p.keys.firstOrNull()?.secret ?: if (p.local) "" else return@launch
            _message.value = "Re-checking ${p.name}…"
            try {
                p.keys.forEach { pool.reset(it.id) }
                val ids = discovery.listModels(p.type, p.baseUrl, secretForCheck)
                val pick = (if (p.local) com.aurix.agent.core.ai.routing.ModelPicker.pickLocal(ids) else null)
                    ?: com.aurix.agent.core.ai.routing.ModelPicker.pick(p.type, ids, p.strongModel, p.fastModel, p.baseUrl)
                store.save(store.providers().map { if (it.id == p.id) it.copy(strongModel = pick.strong, fastModel = pick.fast) else it })
                _message.value = "✓ ${p.name} OK — best model: ${pick.strong}"
            } catch (e: AiError) {
                _message.value = "✗ ${p.name}: ${e.type} — ${e.message}"
            }
            reload()
        }
    }

    fun removeProvider(providerId: String) {
        viewModelScope.launch(Dispatchers.IO) { store.save(store.providers().filter { it.id != providerId }); reload() }
    }

    fun setSaveCost(on: Boolean) {
        viewModelScope.launch(Dispatchers.IO) { store.setPolicy(if (on) RoutingPolicy.BALANCED else RoutingPolicy.QUALITY); reload() }
    }

    fun setStrict(on: Boolean) { viewModelScope.launch(Dispatchers.IO) { agentSettings.setMode(if (on) PermissionMode.STRICT else PermissionMode.STANDARD); reload() } }

    fun revokeAlways(tool: String) { viewModelScope.launch(Dispatchers.IO) { agentSettings.setAlwaysAllowed(agentSettings.alwaysAllowed() - tool); reload() } }

    fun exportAudit(onReady: (java.io.File) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val f = auditExporter.export()
                kotlinx.coroutines.withContext(Dispatchers.Main) { onReady(f) }
            } catch (e: Exception) { _message.value = "Could not export the audit log: ${e.message?.take(80)}" }
        }
    }

    fun setUserName(n: String) { viewModelScope.launch(Dispatchers.IO) { agentSettings.setUserName(n); reload() } }
    fun setWake(on: Boolean) { viewModelScope.launch(Dispatchers.IO) { agentSettings.setWakeEnabled(on); reload() } }

    fun setBudget(v: Long) { viewModelScope.launch(Dispatchers.IO) { usage.setBudget(v); reload() } }
}
