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
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
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
import com.aurix.agent.core.ai.routing.Presets
import com.aurix.agent.core.ai.routing.ProviderEntry
import com.aurix.agent.core.ai.routing.RoutingPolicy
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(onBack: () -> Unit, vm: SettingsViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val status by vm.status.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { while (true) { vm.refreshStatus(); delay(2_000) } }

    Scaffold(topBar = { TopAppBar(title = { Text("Settings") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) }) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { RoutingSection(ui, vm) }
            item { message?.let { Text(it, color = MaterialTheme.colorScheme.tertiary, style = MaterialTheme.typography.bodySmall) } }
            items(ui.providers, key = { it.id }) { p -> ProviderCard(p, status, vm) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Add provider", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                    Presets.all.chunked(3).forEach { row ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            row.forEach { pr -> OutlinedButton(onClick = { vm.addPreset(pr) }, modifier = Modifier.weight(1f)) { Text(pr.name, maxLines = 1) } }
                        }
                    }
                    Text(
                        "Keys are stored encrypted (Android Keystore) and never shown again. Model names are editable — use Test to check them.",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun RoutingSection(ui: SettingsState, vm: SettingsViewModel) {
    var budget by remember(ui.dailyBudget) { mutableStateOf(if (ui.dailyBudget == 0L) "" else ui.dailyBudget.toString()) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Model routing", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        RoutingPolicy.values().forEach { pol ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = ui.policy == pol, onClick = { vm.setPolicy(pol) })
                Column { Text(pol.label, fontWeight = FontWeight.Medium); Text(pol.hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                budget, { budget = it.filter { c -> c.isDigit() } }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text("Daily token budget (blank = unlimited)") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            Button(onClick = { vm.setBudget(budget.toLongOrNull() ?: 0L) }) { Text("Set") }
        }
        Text("Used today: ${ui.todayTokens} tokens", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ProviderCard(p: ProviderEntry, status: Map<String, String>, vm: SettingsViewModel) {
    var name by remember(p) { mutableStateOf(p.name) }
    var url by remember(p) { mutableStateOf(p.baseUrl) }
    var fast by remember(p) { mutableStateOf(p.fastModel) }
    var strong by remember(p) { mutableStateOf(p.strongModel) }
    var newKey by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("${p.name} · ${p.type.label}", fontWeight = FontWeight.Bold)
                Switch(checked = p.enabled, onCheckedChange = { vm.updateProvider(p.copy(enabled = it)) })
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(url, { url = it }, label = { Text("Base URL") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(fast, { fast = it }, label = { Text("Fast model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(strong, { strong = it }, label = { Text("Strong model") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Switch(checked = p.independentKeys, onCheckedChange = { vm.updateProvider(p.copy(independentKeys = it)) })
                Text("Keys are from separate accounts/projects (allows switching key on rate limit)", style = MaterialTheme.typography.bodySmall)
            }
            p.keys.forEach { k ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("${k.label}  ••••${k.secret.takeLast(4)}")
                        Text(status[k.id].orEmpty(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    TextButton(onClick = { vm.removeKey(p.id, k.id) }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    newKey, { newKey = it }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("Add API key") }, visualTransformation = PasswordVisualTransformation(),
                )
                Button(onClick = { vm.addKey(p.id, newKey); newKey = "" }, enabled = newKey.isNotBlank()) { Text("Add") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { vm.updateProvider(p.copy(name = name.trim(), baseUrl = url.trim(), fastModel = fast.trim(), strongModel = strong.trim())) }) { Text("Save") }
                OutlinedButton(onClick = { vm.test(p.id) }) { Text("Test") }
                TextButton(onClick = { vm.deleteProvider(p.id) }) { Text("Delete") }
            }
        }
    }
}
