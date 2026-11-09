package app.kotoba.feature.home

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.navigation.AchievementsRoute
import app.kotoba.core.navigation.CardDetailRoute
import app.kotoba.core.navigation.HomeRoute
import app.kotoba.core.navigation.LeagueRoute
import app.kotoba.core.navigation.LibraryRoute
import app.kotoba.core.navigation.MangaReaderRoute
import app.kotoba.core.navigation.OnboardingRoute
import app.kotoba.core.navigation.ReaderRoute
import app.kotoba.core.navigation.SessionMode
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.core.navigation.SettingsRoute
import app.kotoba.core.navigation.WeakWordsRoute

fun NavGraphBuilder.homeGraph(nav: NavController) {
    composable<HomeRoute> {
        HomeScreen(
            HomeActions(
                startSession = { nav.navigate(SessionRoute(SessionMode.DAILY.name)) },
                quickReview = { m -> nav.navigate(SessionRoute(SessionMode.QUICK.name, minutes = m)) },
                weakWords = { nav.navigate(WeakWordsRoute) },
                openCard = { nav.navigate(CardDetailRoute(it)) },
                openDocument = { d -> nav.navigate(if (d.kind == DocumentKind.MANGA) MangaReaderRoute(d.id) else ReaderRoute(d.id)) },
                openSettings = { nav.navigate(SettingsRoute) },
                openAchievements = { nav.navigate(AchievementsRoute) },
                openLeague = { nav.navigate(LeagueRoute) },
                openLibrary = {
                    nav.navigate(LibraryRoute) { launchSingleTop = true }
                },
            ),
        )
    }
    composable<OnboardingRoute> {
        OnboardingScreen(onFinished = {
            nav.navigate(HomeRoute) { popUpTo<OnboardingRoute> { inclusive = true } }
        })
    }
    composable<AchievementsRoute> { AchievementsScreen(onBack = { nav.popBackStack() }) }
    composable<LeagueRoute> { LeagueScreen(onBack = { nav.popBackStack() }) }
}
