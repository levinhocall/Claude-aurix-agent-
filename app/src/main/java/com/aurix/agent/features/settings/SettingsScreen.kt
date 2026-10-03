package com.aurix.agent.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.RoutingPolicy
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    var key by remember { mutableStateOf("") }
    var custom by remember { mutableStateOf("") }
    var advanced by remember { mutableStateOf(false) }
    var budget by remember(ui.dailyBudget) { mutableStateOf(if (ui.dailyBudget == 0L) "" else ui.dailyBudget.toString()) }
    LaunchedEffect(Unit) { while (true) { vm.refreshStatus(); delay(2_000) } }
    val cs = MaterialTheme.colorScheme

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("API keys") },
                navigationIcon = { TextButton(onClick = onBack) { Text("‹", color = cs.onBackground) } },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
            )
        },
    ) { pad ->
        LazyColumn(Modifier.padding(pad).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Text(
                    "Paste a key from OpenAI, Anthropic, Gemini, Groq, OpenRouter or xAI. AURIX detects the provider, checks the key and picks the best available model automatically.",
                    style = MaterialTheme.typography.bodyMedium, color = cs.onSurfaceVariant,
                )
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        key, { key = it }, singleLine = true, modifier = Modifier.weight(1f),
                        label = { Text("Paste API key") }, visualTransformation = PasswordVisualTransformation(),
                    )
                    Button(onClick = { vm.addKey(key, custom); key = "" }, enabled = key.isNotBlank() && !ui.busy) { Text(if (ui.busy) "…" else "Add") }
                }
            }
            item { message?.let { Text(it, color = cs.tertiary, style = MaterialTheme.typography.bodySmall) } }
            items(ui.providers, key = { it.id }) { p -> ProviderBlock(p, status, vm) }
            item { TextButton(onClick = { advanced = !advanced }) { Text(if (advanced) "Advanced ▾" else "Advanced ▸") } }
            if (advanced) {
                item {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedTextField(
                            custom, { custom = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                            label = { Text("Custom base URL (only for other OpenAI-compatible providers)") },
                        )
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Switch(checked = ui.policy == RoutingPolicy.BALANCED, onCheckedChange = { vm.setSaveCost(it) })
                            Text("Save cost: use the faster model for simple steps", style = MaterialTheme.typography.bodySmall)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                budget, { budget = it.filter { c -> c.isDigit() } }, singleLine = true, modifier = Modifier.weight(1f),
                                label = { Text("Daily token budget (blank = unlimited)") },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            )
                            OutlinedButton(onClick = { vm.setBudget(budget.toLongOrNull() ?: 0L) }) { Text("Set") }
                        }
                        Text("Used today: ${ui.todayTokens} tokens", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProviderBlock(p: ProviderEntry, status: Map<String, String>, vm: SettingsViewModel) {
    val cs = MaterialTheme.colorScheme
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = cs.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.name, fontWeight = FontWeight.Bold)
            Text("Auto-selected · best: ${p.strongModel} · fast: ${p.fastModel}", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            p.keys.forEach { k ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${k.label}  ••••${k.secret.takeLast(4)}")
                        Text(status[k.id].orEmpty(), style = MaterialTheme.typography.labelSmall, color = cs.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.removeKey(p.id, k.id) }) { Text("Remove") }
                }
            }
            OutlinedButton(onClick = { vm.recheck(p.id) }) { Text("Re-check models") }
        }
    }
}
