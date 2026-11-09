package app.kotoba.feature.library

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import app.kotoba.core.navigation.CardDetailRoute
import app.kotoba.core.navigation.CardEditorRoute
import app.kotoba.core.navigation.CategoryRoute
import app.kotoba.core.navigation.DeckRoute
import app.kotoba.core.navigation.DocumentsRoute
import app.kotoba.core.navigation.GeneratorRoute
import app.kotoba.core.navigation.ImportExportRoute
import app.kotoba.core.navigation.LibraryRoute
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.core.navigation.TutorRoute
import app.kotoba.core.navigation.WordListRoute

fun NavGraphBuilder.libraryGraph(nav: NavController) {
    composable<LibraryRoute> {
        LibraryScreen(
            LibraryNav(
                openWords = { nav.navigate(WordListRoute(it)) },
                openCard = { nav.navigate(CardDetailRoute(it)) },
                addWord = { nav.navigate(CardEditorRoute()) },
                openDeck = { nav.navigate(DeckRoute(it)) },
                openCategory = { nav.navigate(CategoryRoute(it)) },
                openDocuments = { nav.navigate(DocumentsRoute(it)) },
                openImport = { nav.navigate(ImportExportRoute) },
                openGenerator = { nav.navigate(GeneratorRoute()) },
            ),
        )
    }
    composable<CardDetailRoute> {
        CardDetailScreen(
            onBack = { nav.popBackStack() },
            onEdit = { nav.navigate(CardEditorRoute(cardId = it)) },
            openDeck = { nav.navigate(DeckRoute(it)) },
            openCategory = { nav.navigate(CategoryRoute(it)) },
            askTutor = { nav.navigate(TutorRoute(prompt = it)) },
        )
    }
    composable<CardEditorRoute> { entry ->
        val route = entry.toRoute<CardEditorRoute>()
        CardEditorScreen(
            onBack = { nav.popBackStack() },
            onSaved = { id ->
                // New words open their card; edits return to where the user came from.
                if (route.cardId > 0) nav.popBackStack()
                else nav.navigate(CardDetailRoute(id)) { popUpTo<CardEditorRoute> { inclusive = true } }
            },
            openCard = { nav.navigate(CardDetailRoute(it)) },
        )
    }
    composable<DeckRoute> { entry ->
        val deckId = entry.toRoute<DeckRoute>().deckId
        DeckScreen(
            nav = GroupNav(
                back = { nav.popBackStack() },
                openCard = { nav.navigate(CardDetailRoute(it)) },
                study = { mode -> nav.navigate(SessionRoute(mode, deckId = deckId)) },
                addWord = { nav.navigate(CardEditorRoute(deckId = deckId)) },
                openWords = { nav.navigate(WordListRoute(WordFilter.ByDeck(deckId).encode())) },
            ),
            openExport = { nav.navigate(ImportExportRoute) },
        )
    }
    composable<CategoryRoute> { entry ->
        val key = entry.toRoute<CategoryRoute>().key
        CategoryScreen(
            GroupNav(
                back = { nav.popBackStack() },
                openCard = { nav.navigate(CardDetailRoute(it)) },
                study = { mode -> nav.navigate(SessionRoute(mode, category = key)) },
                addWord = { nav.navigate(CardEditorRoute()) },
                openWords = { nav.navigate(WordListRoute(WordFilter.ByCategory(key).encode())) },
            ),
        )
    }
    composable<WordListRoute> {
        WordListScreen(
            onBack = { nav.popBackStack() },
            openCard = { nav.navigate(CardDetailRoute(it)) },
            editCard = { nav.navigate(CardEditorRoute(cardId = it)) },
        )
    }
}
