package app.kotoba.feature.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.AiSettingsRoute
import app.kotoba.core.navigation.DocumentsRoute
import app.kotoba.core.navigation.ImportExportRoute
import app.kotoba.core.navigation.SettingsRoute
import app.kotoba.core.navigation.StorageRoute
import app.kotoba.feature.importexport.ImportExportScreen

fun NavGraphBuilder.settingsGraph(nav: NavController) {
    composable<SettingsRoute> {
        SettingsScreen(
            onBack = { nav.popBackStack() },
            onAiSettings = { nav.navigate(AiSettingsRoute) },
            onImportExport = { nav.navigate(ImportExportRoute) },
            onStorage = { nav.navigate(StorageRoute) },
        )
    }
    composable<ImportExportRoute> {
        ImportExportScreen(onBack = { nav.popBackStack() }, onOpenDocuments = { nav.navigate(DocumentsRoute("notes")) })
    }
    composable<StorageRoute> { StorageScreen(onBack = { nav.popBackStack() }) }
}
