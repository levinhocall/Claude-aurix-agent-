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

@Composable
fun AurixNav(openMissionId: String?, onOpened: () -> Unit) {
    val nav: NavHostController = rememberNavController()
    LaunchedEffect(openMissionId) {
        if (openMissionId != null) { nav.navigate("mission/$openMissionId"); onOpened() }
    }
    NavHost(nav, startDestination = "home") {
        composable("home") {
            HomeScreen(onOpen = { nav.navigate("mission/$it") }, onSettings = { nav.navigate("settings") })
        }
        composable("mission/{id}") {
            TranscriptScreen(onBack = { nav.popBackStack() }, onNewTask = { nav.popBackStack("home", false) })
        }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
