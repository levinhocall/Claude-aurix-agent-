package com.aurix.agent.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.aurix.agent.features.chrome.AurixChrome
import com.aurix.agent.features.missions.HomeScreen
import com.aurix.agent.features.missions.TranscriptScreen
import com.aurix.agent.features.settings.SettingsScreen
import com.aurix.agent.features.triggers.TriggersScreen
import com.aurix.agent.features.voice.VoiceScreen

private data class Tab(val route: String, val label: String, val glyph: String)
private val TABS = listOf(Tab("home", "Home", "⌂"), Tab("chat", "Chat", "💬"), Tab("triggers", "Triggers", "⚡"), Tab("settings", "Settings", "⚙"))
private val BAR_ROUTES = setOf("home", "triggers", "settings")

@Composable
fun AurixNav(openMissionId: String?, openVoice: Boolean, onConsumed: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    LaunchedEffect(openMissionId, openVoice) {
        if (openVoice) { nav.navigate("voice") { launchSingleTop = true }; onConsumed() }
        else if (openMissionId != null) { nav.navigate("mission/$openMissionId"); onConsumed() }
    }
    val route by nav.currentBackStackEntryAsState()
    val current = route?.destination?.route
    fun go(r: String) = nav.navigate(r) { popUpTo("home") { saveState = true }; launchSingleTop = true; restoreState = true }
    AurixChrome({
        Scaffold(
            containerColor = MaterialTheme.colorScheme.background,
            contentWindowInsets = WindowInsets(0),
            bottomBar = {
                if (current in BAR_ROUTES) NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                    TABS.forEach { t ->
                        NavigationBarItem(
                            selected = current == t.route, onClick = { go(t.route) },
                            icon = { Text(t.glyph) }, label = { Text(t.label) },
                            colors = NavigationBarItemDefaults.colors(selectedTextColor = MaterialTheme.colorScheme.primary, indicatorColor = MaterialTheme.colorScheme.surfaceVariant),
                        )
                    }
                }
            },
        ) { pad ->
            NavHost(nav, startDestination = "home", modifier = Modifier.padding(bottom = pad.calculateBottomPadding())) {
                composable("home") { VoiceScreen(onClose = {}, onOpenMission = { nav.navigate("mission/$it") }, embedded = true) }
                composable("chat") {
                    HomeScreen(
                        onOpen = { nav.navigate("mission/$it") }, onSettings = { go("settings") },
                        onVoice = { go("home") },
                    )
                }
                composable("triggers") { TriggersScreen(onOpenMission = { nav.navigate("mission/$it") }) }
                composable("mission/{id}") {
                    TranscriptScreen(
                        onBack = { nav.popBackStack() }, onOpenMission = { nav.navigate("mission/$it") },
                        onVoice = { go("home") },
                    )
                }
                composable("voice") { VoiceScreen(onClose = { nav.popBackStack() }, onOpenMission = { nav.navigate("mission/$it") }) }
                composable("settings") { SettingsScreen(onBack = { go("home") }) }
            }
        }
    })
}
