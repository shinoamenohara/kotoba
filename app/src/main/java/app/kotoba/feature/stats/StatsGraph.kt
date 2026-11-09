package app.kotoba.feature.stats

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.CardDetailRoute
import app.kotoba.core.navigation.CategoryRoute
import app.kotoba.core.navigation.StatsRoute
import app.kotoba.core.navigation.VocabularyMapRoute
import app.kotoba.core.navigation.WeakWordsRoute

fun NavGraphBuilder.statsGraph(nav: NavController) {
    composable<StatsRoute> {
        StatsScreen(
            onOpenMap = { nav.navigate(VocabularyMapRoute) },
            onOpenCategory = { nav.navigate(CategoryRoute(it)) },
            onOpenCard = { nav.navigate(CardDetailRoute(it)) },
            onOpenWeakWords = { nav.navigate(WeakWordsRoute) },
        )
    }
    composable<VocabularyMapRoute> {
        VocabularyMapScreen(
            onBack = { nav.popBackStack() },
            onOpenCategory = { nav.navigate(CategoryRoute(it)) },
        )
    }
}
