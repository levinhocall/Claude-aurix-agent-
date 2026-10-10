package com.aurix.agent.features.triggers

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
class TriggersViewModel @Inject constructor(private val settings: AgentSettings, private val manager: MissionManager) : ViewModel() {
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
        item { Text("More triggers (time, location, app open) are coming.", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(bottom = 24.dp)) }
    }
}
