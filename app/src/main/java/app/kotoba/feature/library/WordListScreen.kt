package app.kotoba.feature.library

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.DriveFileMove
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.R
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DeckDao
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.model.Category
import app.kotoba.core.model.Mastery
import app.kotoba.core.navigation.WordListRoute
import app.kotoba.core.srs.WeakWordsRadar
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.masteryLabel
import app.kotoba.core.ui.components.rememberHaptics
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class WordQuery(
    val text: String = "",
    val mastery: Mastery? = null,
    val deckId: Long? = null,
    val category: String? = null,
    val favorites: Boolean = false,
    val weak: Boolean = false,
    val sort: WordSort = WordSort.RECENT,
) {
    companion object {
        fun from(filter: WordFilter) = when (filter) {
            WordFilter.All -> WordQuery()
            WordFilter.Favorites -> WordQuery(favorites = true)
            WordFilter.Weak -> WordQuery(weak = true, sort = WordSort.WEAK)
            is WordFilter.ByMastery -> WordQuery(mastery = filter.mastery)
            is WordFilter.ByDeck -> WordQuery(deckId = filter.deckId)
            is WordFilter.ByCategory -> WordQuery(category = filter.key)
        }
    }
}

data class WordListUi(val cards: List<CardEntity> = emptyList(), val loaded: Boolean = false)

@OptIn(FlowPreview::class)
@HiltViewModel
class WordListViewModel @Inject constructor(
    handle: SavedStateHandle,
    settings: SettingsRepository,
    private val cardDao: CardDao,
    deckDao: DeckDao,
    private val repo: CardRepository,
    private val trash: CardTrash,
) : ViewModel() {
    val initialFilter = WordFilter.parse(handle.toRoute<WordListRoute>().filter)
    val query = MutableStateFlow(WordQuery.from(initialFilter))
    val selection = MutableStateFlow<Set<Long>>(emptySet())
    private val language = settings.settings.map { it.activeLanguage }.distinctUntilChanged()

    val decks: StateFlow<List<DeckEntity>> = language.flatMapLatest { deckDao.observeForLanguage(it.code) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    // Typing is debounced; chip/sort changes apply immediately.
    private val text = query.map { it.text.trim() }.distinctUntilChanged().debounce(200).onStart { emit(query.value.text.trim()) }

    val ui: StateFlow<WordListUi> = combine(language, query.map { it.copy(text = "") }.distinctUntilChanged(), text.distinctUntilChanged()) { l, q, t -> Triple(l, q, t) }
        .flatMapLatest { (l, q, t) ->
            cardDao.search(
                lang = l.code, query = t, deckId = q.deckId, category = q.category, mastery = q.mastery,
                favoritesOnly = q.favorites, weakThreshold = if (q.weak) WeakWordsRadar.WEAK_THRESHOLD else null, sort = q.sort.key,
            ).map { WordListUi(it, true) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WordListUi())

    fun update(f: (WordQuery) -> WordQuery) = query.update(f)

    fun toggleSelect(id: Long) = selection.update { if (id in it) it - id else it + id }
    fun selectAll() = selection.update { ui.value.cards.map { it.id }.toSet() }
    fun clearSelection() { selection.value = emptySet() }

    fun toggleFavorite(c: CardEntity) = viewModelScope.launch { repo.setFavorite(c.id, !c.favorite) }

    suspend fun delete(ids: Set<Long>): DeletedCards {
        clearSelection()
        return trash.delete(ids)
    }

    fun restore(d: DeletedCards) = viewModelScope.launch { trash.restore(d) }

    fun moveSelected(deckId: Long?) = viewModelScope.launch {
        trash.moveToDeck(selection.value, deckId)
        clearSelection()
    }
}

@Composable
fun WordListScreen(
    onBack: () -> Unit,
    openCard: (Long) -> Unit,
    editCard: (Long) -> Unit,
    vm: WordListViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val q by vm.query.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    var showMove by rememberSaveable { mutableStateOf(false) }
    val selecting = selection.isNotEmpty()
    val deletedMsg = stringResource(R.string.library_deleted)
    val undo = stringResource(R.string.library_undo)

    fun deleteWithUndo(ids: Set<Long>) = scope.launch {
        haptics.success()
        val removed = vm.delete(ids)
        val r = snackbar.showSnackbar(String.format(deletedMsg, removed.cards.size), actionLabel = undo, withDismissAction = true)
        if (r == SnackbarResult.ActionPerformed) vm.restore(removed)
    }

    BackHandler(selecting) { vm.clearSelection() }

    val title = when (val f = vm.initialFilter) {
        WordFilter.All -> stringResource(R.string.library_all_words)
        WordFilter.Favorites -> stringResource(R.string.library_favorites)
        WordFilter.Weak -> stringResource(R.string.library_weak_words)
        is WordFilter.ByMastery -> masteryLabel(f.mastery)
        is WordFilter.ByCategory -> categoryLabel(Category.fromKey(f.key))
        is WordFilter.ByDeck -> decks.firstOrNull { it.id == f.deckId }?.name ?: stringResource(R.string.library_deck)
    }

    Scaffold(
        topBar = {
            if (selecting) {
                TopAppBar(
                    title = { Text(stringResource(R.string.library_selected, selection.size)) },
                    navigationIcon = { IconButton(onClick = vm::clearSelection) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) } },
                    actions = {
                        IconButton(onClick = vm::selectAll) { Icon(Icons.Filled.SelectAll, stringResource(R.string.library_select_all)) }
                        IconButton(onClick = { showMove = true }) { Icon(Icons.AutoMirrored.Filled.DriveFileMove, stringResource(R.string.library_move_to_deck)) }
                        IconButton(onClick = { deleteWithUndo(selection) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                    },
                )
            } else {
                KotobaTopBar(title, onBack = onBack, actions = { SortMenu(q.sort) { s -> vm.update { it.copy(sort = s) } } })
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = q.text,
                onValueChange = { t -> vm.update { it.copy(text = t) } },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                placeholder = { Text(stringResource(R.string.library_search_hint)) },
                leadingIcon = { Icon(Icons.Filled.Search, null) },
                trailingIcon = {
                    if (q.text.isNotEmpty()) IconButton(onClick = { vm.update { it.copy(text = "") } }) { Icon(Icons.Filled.Close, stringResource(R.string.action_close)) }
                },
                singleLine = true,
                shape = MaterialTheme.shapes.extraLarge,
            )
            FilterBar(q, decks, vm::update)
            Text(
                pluralStringResource(R.plurals.library_n_words, ui.cards.size, ui.cards.size),
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
            if (ui.loaded && ui.cards.isEmpty()) {
                EmptyState("🗂️", stringResource(R.string.library_no_words), stringResource(R.string.library_no_words_hint))
            }
            val listState = rememberLazyListState()
            LazyColumn(state = listState, contentPadding = PaddingValues(bottom = 24.dp)) {
                items(ui.cards, key = { it.id }, contentType = { "card" }) { c ->
                    var menu by remember { mutableStateOf(false) }
                    WordRow(
                        card = c,
                        onClick = { if (selecting) vm.toggleSelect(c.id) else openCard(c.id) },
                        onLongClick = { haptics.tick(); vm.toggleSelect(c.id) },
                        selected = c.id in selection,
                        selectionMode = selecting,
                        onToggleFavorite = { haptics.tick(); vm.toggleFavorite(c) },
                        modifier = Modifier.animateItem(),
                    ) {
                        if (!selecting) {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_edit)) }, onClick = { menu = false; editCard(c.id) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; deleteWithUndo(setOf(c.id)) })
                            }
                        }
                    }
                    HorizontalDivider(Modifier.padding(start = 16.dp), color = MaterialTheme.colorScheme.surfaceVariant)
                }
            }
        }
    }

    if (showMove) {
        DeckPickerDialog(decks, onDismiss = { showMove = false }) { vm.moveSelected(it) }
    }
}

@Composable
private fun SortMenu(sort: WordSort, onSort: (WordSort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    IconButton(onClick = { open = true }) { Icon(Icons.AutoMirrored.Filled.Sort, stringResource(R.string.library_sort)) }
    DropdownMenu(open, onDismissRequest = { open = false }) {
        WordSort.entries.forEach { s ->
            DropdownMenuItem(
                text = { Text(sortLabel(s)) },
                leadingIcon = { RadioButton(selected = s == sort, onClick = null) },
                onClick = { onSort(s); open = false },
            )
        }
    }
}

@Composable
fun sortLabel(s: WordSort): String = stringResource(
    when (s) {
        WordSort.RECENT -> R.string.library_sort_recent
        WordSort.ALPHA -> R.string.library_sort_alpha
        WordSort.DUE -> R.string.library_sort_due
        WordSort.WEAK -> R.string.library_sort_weak
    },
)

@Composable
private fun FilterBar(q: WordQuery, decks: List<DeckEntity>, update: ((WordQuery) -> WordQuery) -> Unit) {
    var catMenu by remember { mutableStateOf(false) }
    var deckMenu by remember { mutableStateOf(false) }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item {
            FilterChip(q.favorites, onClick = { update { it.copy(favorites = !it.favorites) } }, label = { Text("⭐ " + stringResource(R.string.library_favorites)) })
        }
        item {
            FilterChip(q.weak, onClick = { update { it.copy(weak = !it.weak) } }, label = { Text("🎯 " + stringResource(R.string.library_weak_words)) })
        }
        items(Mastery.entries) { m ->
            FilterChip(q.mastery == m, onClick = { update { it.copy(mastery = if (it.mastery == m) null else m) } }, label = { Text(masteryLabel(m)) })
        }
        item {
            Row {
                FilterChip(
                    q.category != null,
                    onClick = { if (q.category != null) update { it.copy(category = null) } else catMenu = true },
                    label = { Text(q.category?.let { Category.fromKey(it) }?.let { "${it.emoji} ${categoryLabel(it)}" } ?: stringResource(R.string.library_field_category)) },
                )
                DropdownMenu(catMenu, onDismissRequest = { catMenu = false }) {
                    Category.entries.forEach { c ->
                        DropdownMenuItem(text = { Text("${c.emoji} ${categoryLabel(c)}") }, onClick = { catMenu = false; update { it.copy(category = c.key) } })
                    }
                }
            }
        }
        if (decks.isNotEmpty()) item {
            Row {
                FilterChip(
                    q.deckId != null,
                    onClick = { if (q.deckId != null) update { it.copy(deckId = null) } else deckMenu = true },
                    label = { Text(decks.firstOrNull { it.id == q.deckId }?.name ?: stringResource(R.string.library_deck)) },
                )
                DropdownMenu(deckMenu, onDismissRequest = { deckMenu = false }) {
                    decks.forEach { d ->
                        DropdownMenuItem(text = { Text(d.name) }, onClick = { deckMenu = false; update { it.copy(deckId = d.id) } })
                    }
                }
            }
        }
    }
}

@Composable
fun DeckPickerDialog(decks: List<DeckEntity>, onDismiss: () -> Unit, onPick: (Long?) -> Unit) {
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_move_to_deck)) },
        text = {
            LazyColumn {
                item {
                    DropdownMenuItem(text = { Text(stringResource(R.string.library_no_deck)) }, onClick = { onPick(null); onDismiss() })
                }
                items(decks, key = { it.id }) { d ->
                    DropdownMenuItem(text = { Text(d.name) }, onClick = { onPick(d.id); onDismiss() })
                }
            }
        },
        confirmButton = {},
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
