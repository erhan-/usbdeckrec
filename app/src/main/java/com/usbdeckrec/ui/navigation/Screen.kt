package com.usbdeckrec.ui.navigation

/**
 * Navigation route constants for USB DeckRec.
 */
sealed class Screen(val route: String) {
    data object Recording : Screen("recording")
    data object RecordingsList : Screen("recordings")
    data object Settings : Screen("settings")
}
