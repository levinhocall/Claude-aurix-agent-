package com.aurix.agent.features.missions

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.aurix.agent.ui.AurixSpark
import kotlinx.coroutines.launch
import java.util.Calendar

private val CATEGORIES = linkedMapOf(
    "✎  Write" to listOf(
        "Research the pros and cons of social media from several sources and write a summary file with sources",
        "Draft a polite email to my manager saying I will be late tomorrow and save it as a note",
    ),
    "⌕  Research" to listOf(
        "Search the web for 10 accounting jobs in Mumbai, compare them and save a report as jobs.md",
        "Compare the latest iPhone and Pixel using at least 3 sources and give me a verdict",
    ),
    "▣  Phone" to listOf(
        "Play Arijit Singh songs on YouTube",
        "Text mom that I will be late",
        "Set an alarm for 6:30 and tell me my battery level",
    ),
    "▤  Files" to listOf(
        "Show the 5 biggest files in my Download folder",
        "Find all PDFs in Download and zip them into one archive",
    ),
)

private fun greeting(name: String): String {
    val h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
    val g = when { h < 12 -> "Good morning"; h < 17 -> "Good afternoon"; else -> "Good evening" }
    return if (name.isBlank()) g else "$g, $name"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(onOpen: (String) -> Unit, onSettings: () -> Unit, onVoice: () -> Unit, vm: HomeViewModel = hiltViewModel()) {
    val missions by vm.missions.collectAsStateWithLifecycle()
    val hasKey by vm.hasKey.collectAsStateWithLifecycle()
    val userName by vm.userName.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.refreshKey() }
    var input by rememberSaveable { mutableStateOf("") }
    var category by remember { mutableStateOf<String?>(null) }
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val cs = MaterialTheme.colorScheme

    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Column(Modifier.fillMaxHeight()) {
                    Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        AurixSpark(26.dp)
                        Text("  AURIX", style = MaterialTheme.typography.titleLarge)
                    }
                    OutlinedButton(onClick = { scope.launch { drawer.close() } }, modifier = Modifier.padding(horizontal = 16.dp).fillMaxWidth()) { Text("+  New chat") }
                    Text("Recents", style = MaterialTheme.typography.titleSmall, color = cs.onSurfaceVariant, modifier = Modifier.padding(start = 20.dp, top = 16.dp, bottom = 4.dp))
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
            containerColor = cs.background,
            topBar = {
                TopAppBar(
                    title = {},
                    navigationIcon = { TextButton(onClick = { scope.launch { drawer.open() } }) { Text("☰", fontSize = 22.sp, color = cs.onBackground) } },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = cs.background),
                )
            },
            bottomBar = {
                Column(Modifier.navigationBarsPadding().imePadding()) {
                    val cat = category
                    if (hasKey && cat != null) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            CATEGORIES[cat].orEmpty().forEach { p ->
                                Text(
                                    p, color = cs.onSurface, style = MaterialTheme.typography.bodyMedium,
                                    modifier = Modifier.fillMaxWidth().background(cs.surface, RoundedCornerShape(14.dp)).clickable { input = p; category = null }.padding(14.dp),
                                )
                            }
                        }
                    } else if (hasKey) {
                        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CATEGORIES.keys.forEach { k ->
                                Text(
                                    k, color = cs.onSurface,
                                    modifier = Modifier.background(cs.surface, RoundedCornerShape(50)).clickable { category = k }.padding(horizontal = 16.dp, vertical = 10.dp),
                                )
                            }
                        }
                    }
                    Composer(input, { input = it }, { vm.submit(input) { id -> input = ""; onOpen(id) } }, "Message AURIX", onVoice = onVoice)
                }
            },
        ) { pad ->
            Column(
                Modifier.padding(pad).fillMaxSize().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
            ) {
                AurixSpark(44.dp)
                Text(
                    greeting(userName), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center,
                    modifier = Modifier.padding(top = 16.dp, bottom = 8.dp),
                )
                if (!hasKey) {
                    Text(
                        "Add an API key to get started  →", color = cs.onPrimary, textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 12.dp).background(cs.primary, RoundedCornerShape(14.dp)).clickable(onClick = onSettings).padding(horizontal = 18.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
