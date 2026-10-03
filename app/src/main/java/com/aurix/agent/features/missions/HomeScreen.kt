package com.aurix.agent.features.missions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

private val SUGGESTIONS = listOf(
    "Research" to "Search the web for 10 accounting jobs in Mumbai, compare them and save a report as jobs.md",
    "Write" to "Research the pros and cons of social media from several sources and write a summary file with sources",
    "Compute" to "Compare the total cost of a 5-year loan of 800000 at 9% vs 10.5% yearly interest and explain the difference",
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val missions by vm.missions.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshKey() }
    var input by rememberSaveable { mutableStateOf("") }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Text("AURIX", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(20.dp))
                    OutlinedButton(onClick = { scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Text("+  New task") }
                    Text("Recent", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
                    LazyColumn(Modifier.weight(1f)) {
                        items(missions, key = { it.id }) { m ->
                            NavigationDrawerItem(
                                label = { Text(m.objective, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                badge = { Text(statusGlyph(m.status), color = if (m.status.isActive()) cs.primary else cs.onSurfaceVariant) },
                                selected = false,
                                onClick = { scope.launch { drawer.close() }; onOpen(m.id) },
                            )
                        }
                    }
                    NavigationDrawerItem(label = { Text("Settings & API keys") }, selected = false, onClick = { scope.launch { drawer.close() }; onSettings() })
                }
            }
        },
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = { TextButton(onClick = { scope.launch { drawer.open() } }) { Text("☰", fontSize = 22.sp, color = cs.onBackground) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    Composer(input, { input = it }, { vm.submit(input) { id -> input = ""; onOpen(id) } }, "Give me a task")
                }
            },
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                Text("✻", fontSize = 44.sp, color = cs.primary)
                Text("What should I get done?", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(vertical = 12.dp))
                if (!hasKey) {
                    Text(
                        "Add an API key to get started  →", color = cs.onPrimary, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 8.dp).background(cs.primary, RoundedCornerShape(14.dp)).clickable(onClick = onSettings).padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                } else {
                    Column(Modifier.padding(top = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        SUGGESTIONS.forEach { (label, prompt) ->
                            Text(
                                "$label  ·  ${prompt.take(60)}…", color = cs.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.fillMaxWidth().background(cs.surfaceVariant, RoundedCornerShape(14.dp)).clickable { input = prompt }.padding(14.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}
