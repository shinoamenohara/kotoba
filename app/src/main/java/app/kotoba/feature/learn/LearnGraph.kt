package app.kotoba.feature.learn

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.LearnRoute
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.core.navigation.WeakWordsRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.learnGraph(nav: NavController) {
    composable<LearnRoute> { PlaceholderScreen("Learn") }
    composable<SessionRoute> { PlaceholderScreen("Session") { nav.popBackStack() } }
    composable<WeakWordsRoute> { PlaceholderScreen("Weak words") { nav.popBackStack() } }
}
