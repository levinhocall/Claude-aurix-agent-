package com.aurix.agent.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aurix.agent.features.missions.HomeScreen
import com.aurix.agent.features.missions.TranscriptScreen
import com.aurix.agent.features.settings.SettingsScreen
import com.aurix.agent.features.voice.VoiceScreen

@Composable
fun AurixNav(openMissionId: String?, openVoice: Boolean, onConsumed: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    LaunchedEffect(openMissionId, openVoice) {
        if (openVoice) { nav.navigate("voice") { launchSingleTop = true }; onConsumed() }
        else if (openMissionId != null) { nav.navigate("mission/$openMissionId"); onConsumed() }
    }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(
                onOpen = { nav.navigate("mission/$it") }, onSettings = { nav.navigate("settings") },
                onVoice = { nav.navigate("voice") { launchSingleTop = true } },
            )
        }
        composable("mission/{id}") {
            TranscriptScreen(
                onBack = { nav.popBackStack() }, onOpenMission = { nav.navigate("mission/$it") },
                onVoice = { nav.navigate("voice") { launchSingleTop = true } },
            )
        }
        composable("voice") { VoiceScreen(onClose = { nav.popBackStack() }, onOpenMission = { nav.navigate("mission/$it") }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
