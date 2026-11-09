package app.kotoba.feature.reader

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.composable
import app.kotoba.core.navigation.DocumentsRoute
import app.kotoba.core.navigation.MangaReaderRoute
import app.kotoba.core.navigation.ReaderRoute
import app.kotoba.core.ui.components.PlaceholderScreen

fun NavGraphBuilder.readerGraph(nav: NavController) {
    composable<DocumentsRoute> { PlaceholderScreen("Documents") { nav.popBackStack() } }
    composable<ReaderRoute> { PlaceholderScreen("Reader") { nav.popBackStack() } }
    composable<MangaReaderRoute> { PlaceholderScreen("Manga") { nav.popBackStack() } }
}
