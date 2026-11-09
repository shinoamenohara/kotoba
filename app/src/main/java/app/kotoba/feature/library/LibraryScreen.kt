package app.kotoba.feature.library

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kotoba.R
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DeckDao
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.db.DeckWithStats
import app.kotoba.core.model.Category
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.WeakWordsRadar
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.MasteryBar
import app.kotoba.core.ui.components.ProgressRing
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import androidx.hilt.navigation.compose.hiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class CategoryStat(val category: Category, val counts: Map<Mastery, Int>) {
    val total: Int get() = counts.values.sum()
    val progress: Float get() = masteryProgress(counts)
}

data class LibraryState(
    val language: StudyLanguage = StudyLanguage.EN,
    val languages: List<StudyLanguage> = emptyList(),
    val total: Int = 0,
    val masteryCounts: Map<Mastery, Int> = emptyMap(),
    val weak: Int = 0,
    val favorites: Int = 0,
    val categories: List<CategoryStat> = emptyList(),
    val decks: List<DeckWithStats> = emptyList(),
    val loaded: Boolean = false,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val cards: CardDao,
    private val decks: DeckDao,
    private val repo: CardRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val language = settings.settings.map { it.activeLanguage to it.studyLanguages }

    val state: StateFlow<LibraryState> = language.flatMapLatest { (lang, langs) ->
        val code = lang.code
        val counts = combine(
            cards.countAll(code),
            cards.masteryCounts(code),
            cards.countWeak(code, WeakWordsRadar.WEAK_THRESHOLD),
            cards.search(code, favoritesOnly = true).map { it.size },
        ) { total, mc, weak, fav -> arrayOf(total, mc.associate { it.mastery to it.count }, weak, fav) }
        val categories = cards.categoryMasteryCounts(code).map { rows ->
            rows.groupBy { Category.fromKey(it.key) }
                .map { (c, list) -> CategoryStat(c, list.groupBy { it.mastery }.mapValues { e -> e.value.sumOf { it.count } }) }
                .sortedWith(compareByDescending<CategoryStat> { it.total }.thenBy { it.category.ordinal })
        }
        combine(counts, categories, decks.observeWithStats(code, time.now())) { c, cats, deckList ->
            @Suppress("UNCHECKED_CAST")
            LibraryState(
                language = lang,
                languages = (langs + lang).sortedBy { it.ordinal },
                total = c[0] as Int,
                masteryCounts = c[1] as Map<Mastery, Int>,
                weak = c[2] as Int,
                favorites = c[3] as Int,
                categories = cats,
                decks = deckList,
                loaded = true,
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryState())

    val query = MutableStateFlow("")

    val searchResults: StateFlow<List<CardEntity>> = combine(query.debounce(200), settings.settings.map { it.activeLanguage }) { q, l -> q.trim() to l }
        .flatMapLatest { (q, l) -> if (q.isEmpty()) flowOf(emptyList()) else cards.search(l.code, q).map { it.take(50) } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun setLanguage(l: StudyLanguage) = viewModelScope.launch { settings.setActiveLanguage(l) }

    fun createDeck(deck: DeckEntity) = viewModelScope.launch { repo.saveDeck(deck.copy(language = state.value.language.code)) }
}

/** Callbacks for navigation out of the Library tab. */
class LibraryNav(
    val openWords: (String) -> Unit,
    val openCard: (Long) -> Unit,
    val addWord: () -> Unit,
    val openDeck: (Long) -> Unit,
    val openCategory: (String) -> Unit,
    val openDocuments: (String) -> Unit,
    val openImport: () -> Unit,
    val openGenerator: () -> Unit,
)

@Composable
fun LibraryScreen(nav: LibraryNav, vm: LibraryViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val query by vm.query.collectAsStateWithLifecycle()
    val results by vm.searchResults.collectAsStateWithLifecycle()
    var showNewDeck by rememberSaveable { mutableStateOf(false) }
    val haptics = rememberHaptics()

    Scaffold(
        topBar = { KotobaTopBar(stringResource(R.string.nav_library)) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { haptics.tick(); nav.addWord() },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.library_add_word)) },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
        ) {
            item {
                LanguageSwitcher(state.languages, state.language, vm::setLanguage, Modifier.padding(bottom = 8.dp))
                OutlinedTextField(
                    value = query,
                    onValueChange = { vm.query.value = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.library_search_hint)) },
                    leadingIcon = { Icon(Icons.Filled.Search, null) },
                    trailingIcon = {
                        if (query.isNotEmpty()) IconButton(onClick = { vm.query.value = "" }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
                    },
                    singleLine = true,
                    shape = MaterialTheme.shapes.extraLarge,
                )
            }
            if (query.isNotBlank()) {
                if (results.isEmpty()) {
                    item {
                        EmptyState("🔍", stringResource(R.string.library_no_results), stringResource(R.string.library_no_results_hint)) {
                            TextButton(onClick = nav.addWord) { Text(stringResource(R.string.library_add_word)) }
                        }
                    }
                }
                items(results, key = { "s${it.id}" }) { c -> WordRow(c, onClick = { nav.openCard(c.id) }) }
                return@LazyColumn
            }

            item { Spacer(Modifier.height(12.dp)); OverviewCard(state, nav) }

            item { SectionHeader(stringResource(R.string.library_section_categories)) }
            item {
                if (state.categories.isEmpty() && state.loaded) {
                    Text(stringResource(R.string.library_no_categories), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else {
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        items(state.categories, key = { it.category.key }) { c -> CategoryTile(c) { nav.openCategory(c.category.key) } }
                    }
                }
            }

            item {
                SectionHeader(stringResource(R.string.library_section_decks)) {
                    TextButton(onClick = { showNewDeck = true }) {
                        Icon(Icons.Filled.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(4.dp))
                        Text(stringResource(R.string.library_new_deck))
                    }
                }
            }
            if (state.decks.isEmpty() && state.loaded) {
                item { Text(stringResource(R.string.library_no_decks), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            items(state.decks, key = { "d${it.id}" }) { d -> DeckRow(d) { nav.openDeck(d.id) } }

            item { SectionHeader(stringResource(R.string.library_section_materials)) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    EntryRow("📖", stringResource(R.string.library_books), stringResource(R.string.library_books_hint)) { nav.openDocuments("books") }
                    EntryRow("🗯️", stringResource(R.string.library_manga), stringResource(R.string.library_manga_hint)) { nav.openDocuments("manga") }
                    EntryRow("🗒️", stringResource(R.string.library_notes), stringResource(R.string.library_notes_hint)) { nav.openDocuments("notes") }
                    EntryRow("📥", stringResource(R.string.library_import), stringResource(R.string.library_import_hint)) { nav.openImport() }
                    EntryRow("✨", stringResource(R.string.library_generate), stringResource(R.string.library_generate_hint), highlight = true) { nav.openGenerator() }
                }
            }
        }
    }

    if (showNewDeck) {
        DeckEditDialog(
            initial = DeckEntity(name = "", language = state.language.code, color = DeckColors.random()),
            language = state.language,
            onDismiss = { showNewDeck = false },
            onSave = vm::createDeck,
        )
    }
}

@Composable
private fun OverviewCard(state: LibraryState, nav: LibraryNav) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(16.dp)) {
            Row(Modifier.fillMaxWidth().clickable { nav.openWords("all") }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_all_words), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.library_words_count, state.total),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Icon(Icons.AutoMirrored.Filled.ArrowForward, null)
            }
            AnimatedVisibility(state.total > 0) {
                Column {
                    Spacer(Modifier.height(12.dp))
                    MasteryBar(state.masteryCounts)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Mastery.entries.forEach { m ->
                            val n = state.masteryCounts[m] ?: 0
                            androidx.compose.material3.SuggestionChip(
                                onClick = { nav.openWords(WordFilter.ByMastery(m).encode()) },
                                label = { Text("$n", maxLines = 1) },
                                icon = { Box(Modifier.size(8.dp).background(app.kotoba.core.ui.theme.MasteryColors.of(m), CircleShape)) },
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickTile("⭐", stringResource(R.string.library_favorites), state.favorites, Modifier.weight(1f)) { nav.openWords("favorites") }
                QuickTile("🎯", stringResource(R.string.library_weak_words), state.weak, Modifier.weight(1f)) { nav.openWords("weak") }
            }
        }
    }
}

@Composable
private fun QuickTile(emoji: String, label: String, count: Int, modifier: Modifier, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(label, style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text("$count", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun CategoryTile(stat: CategoryStat, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.width(120.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(12.dp).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            ProgressRing(stat.progress, size = 56.dp, stroke = 6.dp) { Text(stat.category.emoji, style = MaterialTheme.typography.titleLarge) }
            Spacer(Modifier.height(8.dp))
            Text(categoryLabel(stat.category), style = MaterialTheme.typography.labelLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                stringResource(R.string.library_words_count, stat.total),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
fun DeckRow(d: DeckWithStats, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Color(d.color), MaterialTheme.shapes.small), contentAlignment = Alignment.Center) {
                Text(if (d.category.isNotEmpty()) Category.fromKey(d.category).emoji else "📒")
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(d.name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.library_deck_stats, d.total, d.newCount, d.dueCount, d.masteredCount),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (d.dueCount > 0) {
                Box(Modifier.background(MaterialTheme.colorScheme.primary, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp)) {
                    Text("${d.dueCount}", color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
private fun EntryRow(emoji: String, title: String, subtitle: String, highlight: Boolean = false, onClick: () -> Unit) {
    val container = if (highlight) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = container)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (highlight) Icon(Icons.Filled.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
            else Icon(Icons.AutoMirrored.Filled.ArrowForward, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
