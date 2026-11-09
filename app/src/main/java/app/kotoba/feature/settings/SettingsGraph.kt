package app.kotoba.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.ImportExportRoute
import app.kotoba.core.navigation.SettingsRoute
import app.kotoba.core.navigation.StorageRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.settingsGraph(nav: NavController) {
    composable<SettingsRoute> { PlaceholderScreen("Settings") { nav.popBackStack() } }
    composable<ImportExportRoute> { PlaceholderScreen("Import / export") { nav.popBackStack() } }
    composable<StorageRoute> { PlaceholderScreen("Storage") { nav.popBackStack() } }
}
