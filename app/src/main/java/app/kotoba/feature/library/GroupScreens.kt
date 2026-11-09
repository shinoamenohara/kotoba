package app.kotoba.feature.library

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
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
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.model.Category
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.CategoryRoute
import app.kotoba.core.navigation.DeckRoute
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.MasteryBar
import app.kotoba.core.ui.components.ProgressRing
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.StatTile
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.masteryLabel
import app.kotoba.core.ui.theme.MasteryColors
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject
import kotlin.math.roundToInt

/** Aggregates for a group of cards (deck or category). */
data class GroupStats(val total: Int, val newCount: Int, val dueCount: Int, val counts: Map<Mastery, Int>) {
    val progress: Float get() = masteryProgress(counts)

    companion object {
        fun of(cards: List<CardEntity>, now: Long) = GroupStats(
            total = cards.size,
            newCount = cards.count { it.state == SrsState.NEW && !it.suspended },
            dueCount = cards.count { it.state != SrsState.NEW && !it.suspended && it.due <= now },
            counts = cards.groupingBy { it.mastery }.eachCount(),
        )
    }
}

data class GroupUi(
    val deck: DeckEntity? = null,
    val cards: List<CardEntity> = emptyList(),
    val stats: GroupStats = GroupStats(0, 0, 0, emptyMap()),
    val language: StudyLanguage = StudyLanguage.EN,
    val loaded: Boolean = false,
)

@HiltViewModel
class DeckViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: CardRepository,
    cardDao: CardDao,
    private val time: TimeProvider,
) : ViewModel() {
    val deckId = handle.toRoute<DeckRoute>().deckId
    private val deck = repo.observeDeck(deckId)

    val ui: StateFlow<GroupUi> = deck.map { it?.language }.distinctUntilChanged().flatMapLatest { lang ->
        if (lang == null) flowOf(emptyList()) else cardDao.search(lang, deckId = deckId, sort = "alpha")
    }.let { cards ->
        combine(deck, cards) { d, list ->
            GroupUi(d, list, GroupStats.of(list, time.now()), StudyLanguage.fromCode(d?.language), loaded = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupUi())

    fun save(d: DeckEntity) = viewModelScope.launch { repo.saveDeck(d) }
    fun delete(deleteCards: Boolean, onDone: () -> Unit) = viewModelScope.launch {
        ui.value.deck?.let { repo.deleteDeck(it, deleteCards) }
        onDone()
    }
}

@HiltViewModel
class CategoryViewModel @Inject constructor(
    handle: SavedStateHandle,
    settings: SettingsRepository,
    cardDao: CardDao,
    private val time: TimeProvider,
) : ViewModel() {
    val category: Category = Category.fromKey(handle.toRoute<CategoryRoute>().key)

    val ui: StateFlow<GroupUi> = settings.settings.map { it.activeLanguage }.distinctUntilChanged().flatMapLatest { lang ->
        cardDao.search(lang.code, category = category.key, sort = "alpha").map { list ->
            GroupUi(null, list, GroupStats.of(list, time.now()), lang, loaded = true)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), GroupUi())
}

class GroupNav(
    val back: () -> Unit,
    val openCard: (Long) -> Unit,
    val study: (mode: String) -> Unit,
    val addWord: () -> Unit,
    val openWords: () -> Unit,
)

@Composable
fun DeckScreen(nav: GroupNav, openExport: () -> Unit, vm: DeckViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    var menu by remember { mutableStateOf(false) }
    var edit by rememberSaveable { mutableStateOf(false) }
    var delete by rememberSaveable { mutableStateOf(false) }
    val deck = ui.deck

    Scaffold(
        topBar = {
            KotobaTopBar(
                title = deck?.name.orEmpty(),
                onBack = nav.back,
                actions = {
                    if (deck != null) {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_edit_deck)) }, onClick = { menu = false; edit = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_export)) }, onClick = { menu = false; openExport() })
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_delete_deck)) }, onClick = { menu = false; delete = true })
                        }
                    }
                },
            )
        },
        floatingActionButton = { FloatingActionButton(onClick = nav.addWord) { Icon(Icons.Filled.Add, stringResource(R.string.library_add_word)) } },
    ) { padding ->
        if (ui.loaded && deck == null) {
            EmptyState("🫥", stringResource(R.string.library_deck_missing), "", Modifier.padding(padding))
            return@Scaffold
        }
        GroupBody(
            padding = padding,
            ui = ui,
            nav = nav,
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        Modifier.size(56.dp).background(Color(deck?.color ?: 0xFF6750A4), MaterialTheme.shapes.medium),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(deck?.category?.takeIf { it.isNotEmpty() }?.let { Category.fromKey(it).emoji } ?: "📒", style = MaterialTheme.typography.headlineSmall)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(deck?.name.orEmpty(), style = MaterialTheme.typography.titleLarge)
                        val meta = listOfNotNull(
                            deck?.level?.takeIf { it.isNotEmpty() },
                            deck?.category?.takeIf { it.isNotEmpty() }?.let { categoryLabel(Category.fromKey(it)) },
                        ).joinToString(" · ")
                        if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
                if (!deck?.description.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(deck?.description.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            flashcards = true,
        )
    }

    if (edit && deck != null) {
        DeckEditDialog(deck, ui.language, onDismiss = { edit = false }, onSave = vm::save)
    }
    if (delete && deck != null) {
        DeleteDeckDialog(deck.name, onDismiss = { delete = false }) { withCards -> vm.delete(withCards, nav.back) }
    }
}

@Composable
fun CategoryScreen(nav: GroupNav, vm: CategoryViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val c = vm.category
    Scaffold(
        topBar = { KotobaTopBar(title = categoryLabel(c), onBack = nav.back) },
        floatingActionButton = { FloatingActionButton(onClick = nav.addWord) { Icon(Icons.Filled.Add, stringResource(R.string.library_add_word)) } },
    ) { padding ->
        GroupBody(
            padding = padding,
            ui = ui,
            nav = nav,
            header = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(c.emoji, style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(categoryLabel(c), style = MaterialTheme.typography.titleLarge)
                        Text("${ui.language.flag} ${ui.language.nativeName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                }
            },
            flashcards = false,
        )
    }
}

@Composable
private fun GroupBody(
    padding: PaddingValues,
    ui: GroupUi,
    nav: GroupNav,
    header: @Composable () -> Unit,
    flashcards: Boolean,
) {
    val s = ui.stats
    LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp)) {
        item { header(); Spacer(Modifier.height(16.dp)) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    ProgressRing(s.progress, size = 84.dp, stroke = 9.dp) {
                        Text("${(s.progress * 100).roundToInt()}%", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Mastery.entries.forEach { m ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(8.dp).background(MasteryColors.of(m), CircleShape))
                                Spacer(Modifier.width(8.dp))
                                Text(masteryLabel(m), style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                                Text("${s.counts[m] ?: 0}", style = MaterialTheme.typography.labelLarge)
                            }
                        }
                    }
                }
                MasteryBar(s.counts, Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp))
            }
        }
        item {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("${s.total}", stringResource(R.string.library_stat_total), Modifier.weight(1f))
                StatTile("${s.newCount}", stringResource(R.string.library_stat_new), Modifier.weight(1f), accent = MasteryColors.New)
                StatTile("${s.dueCount}", stringResource(R.string.library_stat_due_now), Modifier.weight(1f), accent = MasteryColors.Learning)
            }
        }
        item {
            Button(onClick = { nav.study("DAILY") }, enabled = s.total > 0, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.PlayArrow, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.library_study))
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { nav.study("NEW") }, enabled = s.newCount > 0, modifier = Modifier.weight(1f)) {
                    Text(stringResource(R.string.library_learn_new), maxLines = 1)
                }
                if (flashcards) {
                    OutlinedButton(onClick = { nav.study("FLASHCARDS") }, enabled = s.total > 0, modifier = Modifier.weight(1f)) {
                        Text(stringResource(R.string.library_flashcards), maxLines = 1)
                    }
                }
            }
        }
        cardList(ui, nav)
    }
}

private fun LazyListScope.cardList(ui: GroupUi, nav: GroupNav) {
    item {
        SectionHeader(stringResource(R.string.library_words_count, ui.cards.size)) {
            if (ui.cards.isNotEmpty()) TextButton(onClick = nav.openWords) { Text(stringResource(R.string.library_manage)) }
        }
    }
    if (ui.loaded && ui.cards.isEmpty()) {
        item { EmptyState("🌱", stringResource(R.string.library_no_words), stringResource(R.string.library_group_empty_hint)) }
    }
    items(ui.cards, key = { it.id }) { c ->
        WordRow(c, onClick = { nav.openCard(c.id) }, modifier = Modifier.padding(horizontal = 0.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
    }
}

@Composable
private fun DeleteDeckDialog(name: String, onDismiss: () -> Unit, onDelete: (Boolean) -> Unit) {
    var withCards by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_delete_deck)) },
        text = {
            Column {
                Text(stringResource(R.string.library_delete_deck_message, name))
                Spacer(Modifier.height(8.dp))
                listOf(false to R.string.library_keep_cards, true to R.string.library_delete_cards).forEach { (v, label) ->
                    Row(
                        Modifier.fillMaxWidth().selectable(withCards == v, onClick = { withCards = v }).padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(withCards == v, onClick = null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(label))
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onDelete(withCards); onDismiss() }) { Text(stringResource(R.string.action_delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
