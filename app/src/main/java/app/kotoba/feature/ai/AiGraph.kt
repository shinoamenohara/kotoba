package app.kotoba.feature.ai

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.AiSettingsRoute
import app.kotoba.core.navigation.GeneratorRoute
import app.kotoba.core.navigation.SessionMode
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.core.navigation.TutorRoute

fun NavGraphBuilder.aiGraph(nav: NavController) {
    composable<TutorRoute> {
        TutorScreen(onOpenSettings = { nav.navigate(AiSettingsRoute) })
    }
    composable<GeneratorRoute> {
        GeneratorScreen(
            onBack = { nav.popBackStack() },
            onStudy = { deckId ->
                nav.navigate(SessionRoute(SessionMode.NEW.name, deckId)) {
                    popUpTo<GeneratorRoute> { inclusive = true }
                }
            },
            onOpenAiSettings = { nav.navigate(AiSettingsRoute) },
        )
    }
    composable<AiSettingsRoute> {
        AiSettingsScreen(onBack = { nav.popBackStack() })
    }
}
