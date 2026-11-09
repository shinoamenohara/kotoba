package app.kotoba.feature.library

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.CardDetailRoute
import app.kotoba.core.navigation.CardEditorRoute
import app.kotoba.core.navigation.CategoryRoute
import app.kotoba.core.navigation.DeckRoute
import app.kotoba.core.navigation.LibraryRoute
import app.kotoba.core.navigation.WordListRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.libraryGraph(nav: NavController) {
    composable<LibraryRoute> { PlaceholderScreen("Library") }
    composable<CardDetailRoute> { PlaceholderScreen("Card") { nav.popBackStack() } }
    composable<CardEditorRoute> { PlaceholderScreen("Editor") { nav.popBackStack() } }
    composable<DeckRoute> { PlaceholderScreen("Deck") { nav.popBackStack() } }
    composable<CategoryRoute> { PlaceholderScreen("Category") { nav.popBackStack() } }
    composable<WordListRoute> { PlaceholderScreen("Words") { nav.popBackStack() } }
}
