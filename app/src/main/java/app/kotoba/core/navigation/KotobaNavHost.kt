package app.kotoba.core.navigation

import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Insights
import androidx.compose.material.icons.outlined.School
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import app.kotoba.R
import app.kotoba.core.FileKinds
import app.kotoba.core.IncomingContent
import app.kotoba.core.IncomingContentBus
import app.kotoba.feature.ai.aiGraph
import app.kotoba.feature.home.homeGraph
import app.kotoba.feature.learn.learnGraph
import app.kotoba.feature.library.libraryGraph
import app.kotoba.feature.reader.readerGraph
import app.kotoba.feature.settings.settingsGraph
import app.kotoba.feature.stats.statsGraph
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.reflect.KClass

private data class TopLevel(val route: Any, val routeClass: KClass<*>, val label: Int, val icon: ImageVector, val selectedIcon: ImageVector)

private val topLevel = listOf(
    TopLevel(HomeRoute, HomeRoute::class, R.string.nav_home, Icons.Outlined.Home, Icons.Filled.Home),
    TopLevel(LearnRoute, LearnRoute::class, R.string.nav_learn, Icons.Outlined.School, Icons.Filled.School),
    TopLevel(LibraryRoute, LibraryRoute::class, R.string.nav_library, Icons.AutoMirrored.Outlined.MenuBook, Icons.AutoMirrored.Filled.MenuBook),
    TopLevel(StatsRoute, StatsRoute::class, R.string.nav_stats, Icons.Outlined.Insights, Icons.Filled.Insights),
    TopLevel(TutorRoute(), TutorRoute::class, R.string.nav_tutor, Icons.Outlined.AutoAwesome, Icons.Filled.AutoAwesome),
)

@HiltViewModel
class NavViewModel @Inject constructor(val incoming: IncomingContentBus) : ViewModel()

@Composable
fun KotobaNavHost(onboarded: Boolean, nav: NavHostController = rememberNavController()) {
    val entry by nav.currentBackStackEntryAsState()
    val destination = entry?.destination
    val showBar = topLevel.any { t -> destination?.hierarchy?.any { it.hasRoute(t.routeClass) } == true }

    // Route shared/opened content to the screen that consumes it.
    val navVm: NavViewModel = hiltViewModel()
    val incoming by navVm.incoming.pending.collectAsStateWithLifecycle()
    LaunchedEffect(incoming, onboarded) {
        if (!onboarded) return@LaunchedEffect
        when (val c = incoming) {
            is IncomingContent.Text -> nav.navigate(GeneratorRoute())
            is IncomingContent.File -> {
                val ext = FileKinds.extension(c.displayName)
                when {
                    ext in FileKinds.deckExtensions -> nav.navigate(ImportExportRoute)
                    ext in FileKinds.imageExtensions || c.mimeType?.startsWith("image/") == true -> nav.navigate(DocumentsRoute("manga"))
                    else -> nav.navigate(DocumentsRoute("books"))
                }
            }
            null -> Unit
        }
    }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    topLevel.forEach { t ->
                        val selected = destination?.hierarchy?.any { it.hasRoute(t.routeClass) } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                nav.navigate(t.route) {
                                    popUpTo(nav.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(if (selected) t.selectedIcon else t.icon, contentDescription = null) },
                            label = { Text(stringResource(t.label), maxLines = 1) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = nav,
            // Fixed at first composition: finishing onboarding navigates instead of swapping the graph.
            startDestination = remember { if (onboarded) HomeRoute else OnboardingRoute },
            modifier = if (showBar) Modifier.padding(bottom = padding.calculateBottomPadding()).consumeWindowInsets(padding) else Modifier,
            enterTransition = { fadeIn() },
            exitTransition = { fadeOut() },
        ) {
            homeGraph(nav)
            learnGraph(nav)
            libraryGraph(nav)
            readerGraph(nav)
            aiGraph(nav)
            statsGraph(nav)
            settingsGraph(nav)
        }
    }
}
