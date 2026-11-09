package app.kotoba.feature.learn

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.CardDetailRoute
import app.kotoba.core.navigation.CardEditorRoute
import app.kotoba.core.navigation.LearnRoute
import app.kotoba.core.navigation.SessionMode
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.core.navigation.WeakWordsRoute
import app.kotoba.feature.learn.home.LearnScreen
import app.kotoba.feature.learn.session.SessionScreen
import app.kotoba.feature.learn.weak.WeakWordsScreen

fun NavGraphBuilder.learnGraph(nav: NavController) {
    composable<LearnRoute> {
        LearnScreen(
            onStart = { nav.navigate(it) },
            onWeakWords = { nav.navigate(WeakWordsRoute) },
        )
    }
    composable<SessionRoute> {
        SessionScreen(
            onBack = { nav.popBackStack() },
            onEditCard = { nav.navigate(CardEditorRoute(cardId = it)) },
            onOpenCard = { nav.navigate(CardDetailRoute(it)) },
        )
    }
    composable<WeakWordsRoute> {
        WeakWordsScreen(
            onBack = { nav.popBackStack() },
            onPractice = { nav.navigate(SessionRoute(SessionMode.WEAK.name)) },
            onOpenCard = { nav.navigate(CardDetailRoute(it)) },
        )
    }
}
