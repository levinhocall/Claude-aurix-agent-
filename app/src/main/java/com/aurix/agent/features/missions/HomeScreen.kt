package com.aurix.agent.features.missions

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurix.agent.core.mission.MissionEntity
import com.aurix.agent.core.mission.MissionStatus

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val missions by vm.missions.collectAsStateWithLifecycle()
    var input by rememberSaveable { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("AURIX", fontWeight = FontWeight.Bold, letterSpacing = 4.sp) },
                actions = { TextButton(onClick = onSettings) { Text("Settings") } },
            )
        },
    ) { pad ->
        LazyColumn(
            modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = input, onValueChange = { input = it }, minLines = 3,
                        label = { Text("What should I accomplish?") }, modifier = Modifier.fillMaxWidth(),
                    )
                    Button(
                        onClick = { vm.submit(input) { id -> input = ""; onOpen(id) } },
                        enabled = input.isNotBlank(), modifier = Modifier.fillMaxWidth(),
                    ) { Text("Start mission") }
                }
            }
            missionSection("Active", missions.filter { it.status.isActive() }, onOpen)
            missionSection("Waiting for approval", missions.filter { it.status == MissionStatus.WAITING_FOR_APPROVAL }, onOpen)
            missionSection("Paused", missions.filter { it.status == MissionStatus.PAUSED }, onOpen)
            missionSection("History", missions.filter { it.status.isTerminal() }, onOpen)
        }
    }
}

private fun LazyListScope.missionSection(title: String, list: List<MissionEntity>, onOpen: (String) -> Unit) {
    if (list.isEmpty()) return
    item(key = "h-$title") { Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
    items(list, key = { it.id }) { MissionCard(it) { onOpen(it.id) } }
}

@Composable
private fun MissionCard(m: MissionEntity, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                StatusChip(m.status)
                Text(elapsedText(m), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(m.objective, maxLines = 2, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            if (m.currentAction.isNotBlank()) {
                Text(m.currentAction, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (m.totalSteps > 0) {
                val shown = if (m.status.isTerminal()) m.currentStep else minOf(m.currentStep + 1, m.totalSteps)
                LinearProgressIndicator(progress = { m.currentStep.toFloat() / m.totalSteps }, modifier = Modifier.fillMaxWidth())
                Text("Step $shown/${m.totalSteps}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
