package com.aurix.agent.features.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.ai.AiProviderManager
import com.aurix.agent.core.ai.routing.KeyEntry
import com.aurix.agent.core.ai.routing.KeyPool
import com.aurix.agent.core.ai.routing.Preset
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
    val loaded: Boolean = false,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val store: ProviderStore,
    private val pool: KeyPool,
    private val usage: UsageTracker,
    private val manager: AiProviderManager,
) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsState())
    val ui: StateFlow<SettingsState> = _ui.asStateFlow()
    private val _status = MutableStateFlow<Map<String, String>>(emptyMap())
    val status: StateFlow<Map<String, String>> = _status.asStateFlow()
    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    init { viewModelScope.launch(Dispatchers.IO) { reload() } }

    private fun reload() {
        _ui.value = SettingsState(store.providers(), store.policy(), usage.budget(), usage.today(), loaded = true)
        refreshStatus()
    }

    fun refreshStatus() {
        val m = HashMap<String, String>()
        _ui.value.providers.forEach { p -> p.keys.forEach { m[it.id] = pool.status(it.id) } }
        _status.value = m
        _ui.value = _ui.value.copy(todayTokens = usage.today())
    }

    private fun mutate(block: (List<ProviderEntry>) -> List<ProviderEntry>) {
        viewModelScope.launch(Dispatchers.IO) { store.save(block(store.providers())); reload() }
    }

    fun addPreset(pr: Preset) = mutate {
        it + ProviderEntry(UUID.randomUUID().toString(), pr.type, pr.name, pr.baseUrl, pr.fast, pr.strong)
    }

    fun updateProvider(p: ProviderEntry) {
        if (!p.baseUrl.startsWith("https://")) { _message.value = "Base URL must start with https://"; return }
        _message.value = null
        mutate { list -> list.map { if (it.id == p.id) p else it } }
    }

    fun deleteProvider(id: String) = mutate { list -> list.filter { it.id != id } }

    fun addKey(providerId: String, secret: String) {
        if (secret.isBlank()) return
        mutate { list ->
            list.map { p ->
                if (p.id == providerId) p.copy(keys = p.keys + KeyEntry(UUID.randomUUID().toString(), "Key ${p.keys.size + 1}", secret.trim())) else p
            }
        }
    }

    fun removeKey(providerId: String, keyId: String) = mutate { list ->
        list.map { p -> if (p.id == providerId) p.copy(keys = p.keys.filter { it.id != keyId }) else p }
    }

    fun setPolicy(p: RoutingPolicy) { viewModelScope.launch(Dispatchers.IO) { store.setPolicy(p); reload() } }

    fun setBudget(v: Long) { viewModelScope.launch(Dispatchers.IO) { usage.setBudget(v); reload() } }

    fun test(providerId: String) {
        viewModelScope.launch {
            _message.value = "Testing…"
            _message.value = manager.test(providerId)
            refreshStatus()
        }
    }
}
