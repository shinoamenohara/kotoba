package app.kotoba.feature.stats

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.StatsRoute
import app.kotoba.core.navigation.VocabularyMapRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.statsGraph(nav: NavController) {
    composable<StatsRoute> { PlaceholderScreen("Stats") }
    composable<VocabularyMapRoute> { PlaceholderScreen("Vocabulary map") { nav.popBackStack() } }
}
