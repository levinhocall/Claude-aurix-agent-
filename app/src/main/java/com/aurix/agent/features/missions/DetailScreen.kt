package com.aurix.agent.features.missions

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.aurix.agent.core.mission.MissionStatus
import com.aurix.agent.core.mission.StepStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DetailScreen(onBack: () -> Unit, vm: DetailViewModel = hiltViewModel()) {
    val mission by vm.mission.collectAsStateWithLifecycle()
    val steps by vm.steps.collectAsStateWithLifecycle()
    val events by vm.events.collectAsStateWithLifecycle()
    val fmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    Scaffold(
        topBar = { TopAppBar(title = { Text("Mission") }, navigationIcon = { TextButton(onClick = onBack) { Text("Back") } }) },
    ) { pad ->
        val m = mission
        if (m == null) {
            Box(Modifier.padding(pad).fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        } else {
            LazyColumn(
                modifier = Modifier.padding(pad).padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                                StatusChip(m.status)
                                Text(elapsedText(m), style = MaterialTheme.typography.labelSmall)
                            }
                            Text(m.objective, fontWeight = FontWeight.Medium)
                            if (m.currentAction.isNotBlank()) Text(m.currentAction, color = MaterialTheme.colorScheme.primary)
                            Text("${m.iterations} model calls · ~${m.tokensUsed} tokens · ${m.recoveries} recoveries",
                                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        when {
                            m.status.isActive() -> {
                                OutlinedButton(onClick = vm::pause) { Text("Pause") }
                                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                            }
                            m.status == MissionStatus.PAUSED -> {
                                Button(onClick = vm::resume) { Text("Resume") }
                                OutlinedButton(onClick = vm::cancel) { Text("Cancel") }
                            }
                        }
                    }
                }
                if (steps.isNotEmpty()) {
                    item { Text("Progress", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                    items(steps, key = { "s-${it.idx}" }) { s ->
                        val icon = when (s.status) {
                            StepStatus.DONE -> "✓"; StepStatus.RUNNING -> "→"; StepStatus.FAILED -> "✗"
                            StepStatus.SKIPPED -> "–"; StepStatus.PENDING -> "○"
                        }
                        Column {
                            Text("$icon  ${s.title}")
                            if (s.status == StepStatus.FAILED && !s.result.isNullOrBlank())
                                Text(s.result.take(200), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                        }
                    }
                }
                if (!m.error.isNullOrBlank()) {
                    item { Text(m.error, color = MaterialTheme.colorScheme.error) }
                }
                if (!m.finalResult.isNullOrBlank()) {
                    item {
                        Text("Result", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
                        SelectionContainer { Text(m.finalResult) }
                    }
                }
                item { Text("Event log", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary) }
                items(events, key = { "e-${it.id}" }) { e ->
                    Text("${fmt.format(Date(e.ts))}  ${e.type}  ${e.detail}", fontFamily = FontFamily.Monospace, fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}
