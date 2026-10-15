package com.aurix.agent.features.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.aurix.agent.core.guard.ConnectorPolicy

/** Per-app switches, web blocklist and the credential vault. Same look as the other Settings sections. */
@Composable
internal fun GuardsSection(ui: SettingsState, vm: SettingsViewModel) {
    val cs = MaterialTheme.colorScheme
    var blocked by remember(ui.guards.blocked) { mutableStateOf(ui.guards.blocked) }
    var vName by remember { mutableStateOf("") }
    var vValue by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("Guards", style = MaterialTheme.typography.titleSmall, color = cs.primary)
        Text("Apps AURIX may use", style = MaterialTheme.typography.bodySmall)
        ConnectorPolicy.GROUPS.forEach { g ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(g, style = MaterialTheme.typography.bodyMedium)
                Switch(checked = g !in ui.guards.off, onCheckedChange = { vm.setConnector(g, it) })
            }
        }
        Text("Blocked websites (comma separated). Risky links (shorteners, http, look-alikes) always ask first.", style = MaterialTheme.typography.bodySmall)
        OutlinedTextField(value = blocked, onValueChange = { blocked = it }, singleLine = false, modifier = Modifier.fillMaxWidth(), label = { Text("e.g. badsite.com, ads.example.org") })
        OutlinedButton(onClick = { vm.saveBlocked(blocked) }, enabled = blocked != ui.guards.blocked) { Text("Save blocked sites") }
        Text("Vault: AURIX types these into password fields. The AI only sees the name, never the value.", style = MaterialTheme.typography.bodySmall)
        ui.guards.vault.forEach { n ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("{{vault:$n}}", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = { vm.vaultDelete(n) }) { Text("Delete") }
            }
        }
        OutlinedTextField(value = vName, onValueChange = { vName = it }, singleLine = true, modifier = Modifier.fillMaxWidth(), label = { Text("Name (e.g. GMAIL)") })
        OutlinedTextField(value = vValue, onValueChange = { vValue = it }, singleLine = true, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth(), label = { Text("Secret value") })
        OutlinedButton(onClick = { vm.vaultPut(vName, vValue); vName = ""; vValue = "" }, enabled = vName.isNotBlank() && vValue.isNotBlank()) { Text("Save to vault") }
    }
}
