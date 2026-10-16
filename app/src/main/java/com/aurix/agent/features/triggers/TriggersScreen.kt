package com.aurix.agent.features.triggers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.aurix.agent.core.agent.MissionManager
import com.aurix.agent.core.approval.AgentSettings
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class TriggersViewModel @Inject constructor(
    private val settings: AgentSettings, private val manager: MissionManager,
    private val store: com.aurix.agent.core.proactive.TriggerStore, private val engine: com.aurix.agent.core.proactive.TriggerEngine,
) : ViewModel() {
    fun rules() = store.list()
    /** Error text or null. */
    fun addRule(name: String, e: com.aurix.agent.core.proactive.TriggerEvent, param: String, cmd: String): String? =
        store.add(name, e, param, cmd).also { if (it == null) resched() }
    fun toggleRule(id: String, on: Boolean) { store.setEnabled(id, on); resched() }
    fun deleteRule(id: String) { store.delete(id); resched() }
    private fun resched() { viewModelScope.launch(Dispatchers.IO) { engine.reschedule() } }
    fun proactive() = settings.proactive()
    fun setProactive(on: Boolean) { viewModelScope.launch(Dispatchers.IO) { settings.setProactive(on) } }
    fun car() = settings.carBluetooth()
    fun setCar(n: String) { viewModelScope.launch(Dispatchers.IO) { settings.setCarBluetooth(n) } }
    fun run(cmd: String, onCreated: (String) -> Unit) { viewModelScope.launch { onCreated(manager.create(cmd)) } }
}

private val MODES = listOf(
    "Driving" to "driving mode on", "Work" to "work mode on", "Sleep" to "sleep mode on",
    "Gaming" to "gaming mode on", "Normal" to "normal mode on",
)

@Composable
fun TriggersScreen(onOpenMission: (String) -> Unit, vm: TriggersViewModel = hiltViewModel()) {
    val cs = MaterialTheme.colorScheme
    var proactive by remember { mutableStateOf(vm.proactive()) }
    var car by remember { mutableStateOf(vm.car()) }
    var rules by remember { mutableStateOf(vm.rules()) }
    var newEvent by remember { mutableStateOf(com.aurix.agent.core.proactive.TriggerEvent.CHARGER_ON) }
    var newParam by remember { mutableStateOf("") }
    var newCmd by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var err by remember { mutableStateOf<String?>(null) }
    LazyColumn(Modifier.fillMaxSize().background(cs.background).statusBarsPadding().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { Text("Triggers", style = MaterialTheme.typography.headlineMedium, color = cs.onBackground, modifier = Modifier.padding(top = 16.dp)) }
        item { Text("Smart modes (tap to switch)", style = MaterialTheme.typography.titleSmall, color = cs.primary) }
        items(MODES) { (label, cmd) ->
            Row(
                Modifier.fillMaxWidth().background(cs.surface, RoundedCornerShape(14.dp)).clickable { vm.run(cmd, onOpenMission) }.padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) { Text("$label mode", color = cs.onSurface, modifier = Modifier.weight(1f)); Text("›", color = cs.onSurfaceVariant) }
        }
        item { Text("Automatic triggers", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 8.dp)) }
        item {
            Column(Modifier.fillMaxWidth().background(cs.surface, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Car Bluetooth connects → driving mode\nLow battery → suggestion", color = cs.onSurface, modifier = Modifier.weight(1f))
                    Switch(checked = proactive, onCheckedChange = { proactive = it; vm.setProactive(it) })
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(car, { car = it }, singleLine = true, modifier = Modifier.weight(1f), label = { Text("Car Bluetooth name") })
                    OutlinedButton(onClick = { vm.setCar(car) }) { Text("Save") }
                }
            }
        }
        item { Text("My triggers", style = MaterialTheme.typography.titleSmall, color = cs.primary, modifier = Modifier.padding(top = 8.dp)) }
        if (rules.isEmpty()) item { Text("No triggers yet. Add one below.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall) }
        items(rules, key = { it.id }) { r ->
            Row(Modifier.fillMaxWidth().background(cs.surface, RoundedCornerShape(14.dp)).padding(start = 16.dp, top = 8.dp, bottom = 8.dp, end = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(r.name, color = cs.onSurface)
                    Text(com.aurix.agent.core.proactive.TriggerLogic.describe(r), color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
                    if (r.fired > 0) Text("Ran ${r.fired}x · last " + java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale.getDefault()).format(java.util.Date(r.lastFired)), color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
                }
                Switch(checked = r.enabled, onCheckedChange = { vm.toggleRule(r.id, it); rules = vm.rules() })
                OutlinedButton(onClick = { vm.deleteRule(r.id); rules = vm.rules() }, modifier = Modifier.padding(start = 6.dp)) { Text("✕") }
            }
        }
        item {
            Column(Modifier.fillMaxWidth().background(cs.surface, RoundedCornerShape(14.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("New trigger — when…", color = cs.onSurface)
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    com.aurix.agent.core.proactive.TriggerEvent.values().forEach { e ->
                        if (e == newEvent) Button(onClick = { }) { Text(e.label) } else OutlinedButton(onClick = { newEvent = e; err = null }) { Text(e.label) }
                    }
                }
                newEvent.paramHint?.let { h -> OutlinedTextField(newParam, { newParam = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text(h) }) }
                OutlinedTextField(newCmd, { newCmd = it }, modifier = Modifier.fillMaxWidth(), label = { Text("…do this (e.g. \"battery saver on karo\")") })
                OutlinedTextField(newName, { newName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name (optional)") })
                err?.let { Text(it, color = cs.error, style = MaterialTheme.typography.bodySmall) }
                OutlinedButton(onClick = {
                    err = vm.addRule(newName, newEvent, newParam, newCmd)
                    if (err == null) { newName = ""; newParam = ""; newCmd = ""; rules = vm.rules() }
                }) { Text("Add trigger") }
                Text("Runs like a normal mission; risky actions still ask you first. Charger triggers work while the app is alive; time triggers run once a day at that time (Android may delay a few minutes).", color = cs.onSurfaceVariant, style = MaterialTheme.typography.labelSmall)
            }
        }
        item { Text("Location and app-open triggers are coming.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}
