package com.usbdeckrec.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.usbdeckrec.ui.recording.RecordingScreen
import com.usbdeckrec.ui.recordings.RecordingsListScreen
import com.usbdeckrec.ui.settings.SettingsScreen

@Composable
fun NavGraph() {
    val navController = rememberNavController()

    NavHost(
        navController = navController,
        startDestination = Screen.Recording.route
    ) {
        composable(Screen.Recording.route) {
            RecordingScreen(
                onNavigateToRecordings = {
                    navController.navigate(Screen.RecordingsList.route)
                },
                onNavigateToSettings = {
                    navController.navigate(Screen.Settings.route)
                }
            )
        }
        composable(Screen.RecordingsList.route) {
            RecordingsListScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
        composable(Screen.Settings.route) {
            SettingsScreen(
                onNavigateBack = {
                    navController.popBackStack()
                }
            )
        }
    }
}
