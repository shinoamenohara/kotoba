package app.kotoba.feature.ai

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.AiSettingsRoute
import app.kotoba.core.navigation.GeneratorRoute
import app.kotoba.core.navigation.TutorRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.aiGraph(nav: NavController) {
    composable<TutorRoute> { PlaceholderScreen("Tutor") }
    composable<GeneratorRoute> { PlaceholderScreen("Generator") { nav.popBackStack() } }
    composable<AiSettingsRoute> { PlaceholderScreen("AI settings") { nav.popBackStack() } }
}
