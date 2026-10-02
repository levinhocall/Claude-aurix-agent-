package com.aurix.agent.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.security.SecureSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class SettingsUi(val baseUrl: String = "", val model: String = "", val hasKey: Boolean = false, val loaded: Boolean = false)

@HiltViewModel
class SettingsViewModel @Inject constructor(private val settings: SecureSettings) : ViewModel() {
    private val _ui = MutableStateFlow(SettingsUi())
    val ui: StateFlow<SettingsUi> = _ui.asStateFlow()

    init { reload() }

    private fun reload() {
        viewModelScope.launch(Dispatchers.IO) {
            _ui.value = SettingsUi(settings.baseUrl(), settings.model(), settings.hasKey(), loaded = true)
        }
    }

    /** onResult(null) = saved, otherwise an error message. */
    fun save(baseUrl: String, model: String, key: String, onResult: (String?) -> Unit) {
        if (!baseUrl.trim().startsWith("https://")) { onResult("Base URL must start with https://"); return }
        if (model.isBlank()) { onResult("Model name is required"); return }
        viewModelScope.launch {
            withContext(Dispatchers.IO) { settings.save(baseUrl, model, key) }
            reload()
            onResult(null)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var url by remember(ui.loaded) { mutableStateOf(ui.baseUrl) }
    var model by remember(ui.loaded) { mutableStateOf(ui.model) }
    var key by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        Column(Modifier.padding(pad).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Provider (any OpenAI-compatible endpoint)", style = MaterialTheme.typography.titleSmall)
            OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(model, { model = it }, label = { Text("Model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                key, { key = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text(if (ui.hasKey) "API key (saved — leave blank to keep)" else "API key") },
                visualTransformation = PasswordVisualTransformation(),
            )
            Text(
                "Examples: OpenAI https://api.openai.com/v1 · Groq https://api.groq.com/openai/v1 · Gemini https://generativelanguage.googleapis.com/v1beta/openai. " +
                    "The key is stored encrypted (Android Keystore) and never shown again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(onClick = { vm.save(url, model, key) { err -> status = err ?: "Saved"; if (err == null) key = "" } }, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            status?.let { Text(it, color = if (it == "Saved") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error) }
        }
    }
}
