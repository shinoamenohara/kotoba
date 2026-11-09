package app.kotoba.feature.reader

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.navigation.DocumentsRoute
import app.kotoba.core.navigation.GeneratorRoute
import app.kotoba.core.navigation.MangaReaderRoute
import app.kotoba.core.navigation.ReaderRoute
import app.kotoba.core.navigation.TutorRoute
import app.kotoba.feature.manga.MangaReaderScreen

fun NavGraphBuilder.readerGraph(nav: NavController) {
    composable<DocumentsRoute> {
        DocumentsScreen(
            onBack = { nav.popBackStack() },
            onOpen = { doc ->
                if (doc.kind == DocumentKind.MANGA) nav.navigate(MangaReaderRoute(doc.id)) else nav.navigate(ReaderRoute(doc.id))
            },
            onGenerateDeck = { nav.navigate(GeneratorRoute(it)) },
        )
    }
    composable<ReaderRoute> {
        ReaderScreen(
            onBack = { nav.popBackStack() },
            onExplainWithAi = { nav.navigate(TutorRoute(prompt = it)) },
            onGenerateDeck = { nav.navigate(GeneratorRoute(it)) },
        )
    }
    composable<MangaReaderRoute> {
        MangaReaderScreen(
            onBack = { nav.popBackStack() },
            onExplainWithAi = { nav.navigate(TutorRoute(prompt = it)) },
        )
    }
}
