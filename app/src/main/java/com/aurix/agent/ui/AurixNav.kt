package com.aurix.agent.ui

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.aurix.agent.features.missions.DetailScreen
import com.aurix.agent.features.missions.HomeScreen
import com.aurix.agent.features.settings.SettingsScreen

@Composable
fun AurixNav() {
    val nav = rememberNavController()
    NavHost(nav, startDestination = "home") {
        composable("home") { HomeScreen(onOpen = { nav.navigate("mission/$it") }, onSettings = { nav.navigate("settings") }) }
        composable("mission/{id}") { DetailScreen(onBack = { nav.popBackStack() }) }
        composable("settings") { SettingsScreen(onBack = { nav.popBackStack() }) }
    }
}
