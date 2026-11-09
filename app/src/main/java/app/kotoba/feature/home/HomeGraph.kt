package app.kotoba.feature.home

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.AchievementsRoute
import app.kotoba.core.navigation.HomeRoute
import app.kotoba.core.navigation.OnboardingRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.homeGraph(nav: NavController) {
    composable<HomeRoute> { PlaceholderScreen("Home") }
    composable<OnboardingRoute> { PlaceholderScreen("Onboarding") }
    composable<AchievementsRoute> { PlaceholderScreen("Achievements") { nav.popBackStack() } }
}
