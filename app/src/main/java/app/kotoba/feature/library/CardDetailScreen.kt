package app.kotoba.feature.library

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Psychology
import androidx.compose.material.icons.filled.SlowMotionVideo
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.R
import app.kotoba.core.ai.AiPrompts
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.StudyRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.data.toSrs
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ConfusionDao
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DeckDao
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.db.ReviewLogDao
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.lang.SpeechService
import app.kotoba.core.model.Category
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.CardDetailRoute
import app.kotoba.core.srs.WeakWordsRadar
import app.kotoba.core.srs.WeaknessReport
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.StatTile
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.formatInterval
import app.kotoba.core.ui.components.masteryLabel
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.CardTermStyle
import app.kotoba.core.ui.theme.MasteryColors
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject
import kotlin.math.roundToInt

/** A sentence in the pager: stored [ContextEntity] or the card's own example. */
data class ContextItem(val entity: ContextEntity?, val sentence: String, val translation: String, val source: ContextSource, val title: String)

data class LearningInfo(val retrievability: Double, val weakness: WeaknessReport)

data class CardDetailUi(
    val card: CardEntity? = null,
    val deck: DeckEntity? = null,
    val contexts: List<ContextItem> = emptyList(),
    val logs: List<ReviewLogEntity> = emptyList(),
    val info: LearningInfo? = null,
    val aiConfigured: Boolean = false,
    val loaded: Boolean = false,
)

@HiltViewModel
class CardDetailViewModel @Inject constructor(
    handle: SavedStateHandle,
    private val repo: CardRepository,
    private val study: StudyRepository,
    private val trash: CardTrash,
    private val ai: AiRepository,
    private val settings: SettingsRepository,
    private val speech: SpeechService,
    private val time: TimeProvider,
    deckDao: DeckDao,
    logDao: ReviewLogDao,
    private val confusionDao: ConfusionDao,
) : ViewModel() {
    val cardId = handle.toRoute<CardDetailRoute>().cardId
    private val card = repo.observeCard(cardId)
    private val logs = logDao.observeForCard(cardId)

    val generating = MutableStateFlow(false)
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages

    private val deck = card.map { it?.deckId }.distinctUntilChanged().flatMapLatest { id -> if (id == null) flowOf(null) else deckDao.observe(id) }

    private val contexts = combine(card, repo.observeContexts(cardId)) { c, list ->
        val items = list.map { ContextItem(it, it.sentence, it.translation, it.source, it.sourceTitle) }
        val ex = c?.example?.trim().orEmpty()
        if (c != null && ex.isNotEmpty() && list.none { it.sentence.trim() == ex }) {
            listOf(ContextItem(null, ex, c.exampleTranslation, sourceOf(c.source), c.sourceTitle)) + items
        } else items
    }

    private val info = combine(card, logs) { c, l -> c to l }.map { (c, l) ->
        c ?: return@map null
        val r = study.fsrs().retrievability(c.toSrs(), time.now())
        LearningInfo(r, WeakWordsRadar.analyze(snapshotOf(c, l.take(30), r, confusionDao.totalForCard(c.id))))
    }

    val ui: StateFlow<CardDetailUi> = combine(card, deck, contexts, combine(logs, info, ::Pair), ai.status) { c, d, ctx, (l, i), st ->
        CardDetailUi(c, d, ctx, l, i, st.configured, loaded = true)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CardDetailUi())

    private fun sourceOf(s: String) = when {
        s.startsWith("book") -> ContextSource.BOOK
        s.startsWith("manga") -> ContextSource.MANGA
        s == "ai" -> ContextSource.AI
        s == "builtin" -> ContextSource.BUILTIN
        s == "manual" -> ContextSource.USER
        else -> ContextSource.IMPORT
    }

    private val lang get() = StudyLanguage.fromCode(ui.value.card?.language)

    fun speak(text: String, slow: Boolean) = viewModelScope.launch {
        speech.rate = settings.current().ttsRate
        speech.speak(text, lang, slow)
    }

    fun toggleFavorite() = viewModelScope.launch { ui.value.card?.let { repo.setFavorite(it.id, !it.favorite) } }
    fun toggleSuspended() = viewModelScope.launch { ui.value.card?.let { repo.setSuspended(it.id, !it.suspended) } }
    fun resetProgress() = viewModelScope.launch { study.resetProgress(cardId) }
    fun delete(onDone: () -> Unit) = viewModelScope.launch { trash.delete(listOf(cardId)); onDone() }

    fun addContext(sentence: String, translation: String) = viewModelScope.launch {
        if (sentence.isBlank()) return@launch
        repo.addContext(ContextEntity(cardId = cardId, sentence = sentence.trim(), translation = translation.trim(), source = ContextSource.USER))
    }

    fun deleteContext(item: ContextItem) = viewModelScope.launch {
        val e = item.entity
        if (e != null) repo.deleteContext(e)
        else ui.value.card?.let { repo.save(it.copy(example = "", exampleTranslation = "")) }
    }

    fun generateExample(errorPrefix: String) = viewModelScope.launch {
        val c = ui.value.card ?: return@launch
        if (generating.value) return@launch
        generating.value = true
        val l = StudyLanguage.fromCode(c.language)
        val native = AiPrompts.nativeName(settings.current().nativeLanguage)
        val existing = ui.value.contexts.joinToString("\n") { "- ${it.sentence}" }
        val prompt = buildString {
            appendLine("Write ONE new, natural example sentence using the word \"${c.term}\" (meaning: ${c.translation}).")
            if (c.level.isNotBlank()) appendLine("Keep the vocabulary around level ${c.level}.")
            if (existing.isNotBlank()) appendLine("It must differ from these:\n$existing")
            appendLine("Answer with exactly two lines and nothing else:")
            appendLine("line 1: the sentence in the target language")
            append("line 2: its translation into $native")
        }
        ai.ask(prompt, system = AiPrompts.languageGuide(l), temperature = 0.9, maxTokens = 300)
            .onSuccess { text ->
                val lines = text.lines().map { cleanLine(it) }.filter { it.isNotEmpty() }
                val sentence = lines.getOrNull(0)
                if (sentence == null) _messages.tryEmit(errorPrefix)
                else repo.addContext(
                    ContextEntity(cardId = c.id, sentence = sentence, translation = lines.getOrNull(1).orEmpty(), source = ContextSource.AI, sourceTitle = "AI"),
                )
            }
            .onFailure { _messages.tryEmit("$errorPrefix: ${it.message.orEmpty()}") }
        generating.value = false
    }

    private fun cleanLine(s: String) = s.trim()
        .removePrefix("line 1:").removePrefix("line 2:").removePrefix("Line 1:").removePrefix("Line 2:")
        .trim().trimStart('-', '*', '1', '2', '.', ')', ' ').trim().trim('"', '“', '”', '「', '」')
}

@Composable
fun CardDetailScreen(
    onBack: () -> Unit,
    onEdit: (Long) -> Unit,
    openDeck: (Long) -> Unit,
    openCategory: (String) -> Unit,
    askTutor: (String) -> Unit,
    vm: CardDetailViewModel = hiltViewModel(),
) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val generating by vm.generating.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    var menu by remember { mutableStateOf(false) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var addSentence by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }

    val card = ui.card
    val aiFailed = stringResource(R.string.library_ai_failed)
    val tutorPrompt = card?.let { stringResource(R.string.library_tutor_prompt, it.term, it.translation) }.orEmpty()

    Scaffold(
        topBar = {
            KotobaTopBar(
                title = "",
                onBack = onBack,
                actions = {
                    if (card != null) {
                        IconButton(onClick = { haptics.tick(); vm.toggleFavorite() }) {
                            Icon(
                                if (card.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                                stringResource(R.string.action_favorite),
                                tint = if (card.favorite) MasteryColors.Learning else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(onClick = { onEdit(card.id) }) { Icon(Icons.Filled.Edit, stringResource(R.string.action_edit)) }
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                        DropdownMenu(menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (card.suspended) R.string.library_unsuspend else R.string.library_suspend)) },
                                onClick = { menu = false; vm.toggleSuspended() },
                            )
                            DropdownMenuItem(text = { Text(stringResource(R.string.library_reset_progress)) }, onClick = { menu = false; confirmReset = true })
                            DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; confirmDelete = true })
                        }
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (card == null) {
            if (ui.loaded) EmptyState("🫥", stringResource(R.string.library_card_missing), "", Modifier.padding(padding))
            else Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        val lang = StudyLanguage.fromCode(card.language)
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp).padding(bottom = 32.dp),
        ) {
            Header(card, lang, vm)
            if (card.suspended) {
                Text(
                    "⏸ " + stringResource(R.string.library_suspended),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 4.dp),
                )
            }
            MetaChips(card, ui.deck, openDeck, openCategory)

            if (card.imageUri.isNotBlank() || card.association.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Column {
                        if (card.imageUri.isNotBlank()) {
                            AsyncImage(
                                model = if (card.imageUri.startsWith("/")) File(card.imageUri) else card.imageUri,
                                contentDescription = card.term,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxWidth().heightIn(max = 220.dp).clip(MaterialTheme.shapes.medium),
                            )
                        }
                        if (card.association.isNotBlank()) {
                            Text("💡 " + card.association, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(14.dp))
                        }
                    }
                }
            }

            WordRelations(card)

            SectionHeader(stringResource(R.string.library_sentences)) {
                Row {
                    IconButton(onClick = { addSentence = true }) { Icon(Icons.Filled.Add, stringResource(R.string.library_add_sentence)) }
                }
            }
            SentencePager(ui.contexts, card, lang, vm)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { vm.generateExample(aiFailed) }, enabled = ui.aiConfigured && !generating, modifier = Modifier.weight(1f)) {
                    if (generating) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.library_generate_example), maxLines = 1)
                }
                OutlinedButton(onClick = { addSentence = true }) { Text(stringResource(R.string.library_add_sentence), maxLines = 1) }
            }
            if (!ui.aiConfigured) {
                Text(stringResource(R.string.ai_not_configured), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 4.dp))
            }

            if (card.notes.isNotBlank()) {
                SectionHeader(stringResource(R.string.library_field_notes))
                Text(card.notes, style = MaterialTheme.typography.bodyMedium)
            }

            LearningSection(card, ui.info, ui.logs)

            Spacer(Modifier.height(16.dp))
            FilledTonalButton(onClick = { askTutor(tutorPrompt) }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Psychology, null); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.library_ask_tutor))
            }
        }
    }

    if (confirmReset) {
        ConfirmDialog(
            stringResource(R.string.library_reset_progress), stringResource(R.string.library_reset_confirm), stringResource(R.string.library_reset),
            onConfirm = vm::resetProgress, onDismiss = { confirmReset = false },
        )
    }
    if (confirmDelete) {
        ConfirmDialog(
            stringResource(R.string.library_delete_card), stringResource(R.string.library_delete_card_confirm), stringResource(R.string.action_delete),
            onConfirm = { vm.delete(onBack) }, onDismiss = { confirmDelete = false },
        )
    }
    if (addSentence) AddSentenceDialog(onDismiss = { addSentence = false }, onAdd = vm::addContext)
}

@Composable
private fun Header(card: CardEntity, lang: StudyLanguage, vm: CardDetailViewModel) {
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.term, style = CardTermStyle, textAlign = TextAlign.Center)
        val reading = listOf(card.reading, card.ipa.takeIf { it.isNotBlank() }?.let { if (it.startsWith("/") || it.startsWith("[")) it else "/$it/" })
            .filter { !it.isNullOrBlank() }.joinToString("  ·  ")
        if (reading.isNotEmpty()) Text(reading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(vertical = 8.dp)) {
            FilledTonalIconButton(onClick = { vm.speak(card.term, false) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.action_listen)) }
            FilledTonalIconButton(onClick = { vm.speak(card.term, true) }) { Icon(Icons.Filled.SlowMotionVideo, stringResource(R.string.library_listen_slow)) }
        }
        Text(card.translation, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        MasteryBadge(card.mastery)
    }
}

@Composable
private fun MetaChips(card: CardEntity, deck: DeckEntity?, openDeck: (Long) -> Unit, openCategory: (String) -> Unit) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (card.partOfSpeech.isNotBlank()) AssistChip(onClick = {}, label = { Text(posLabel(PartOfSpeech.fromKey(card.partOfSpeech))) })
        if (card.level.isNotBlank()) AssistChip(onClick = {}, label = { Text(card.level) })
        if (card.category.isNotBlank()) {
            val c = Category.fromKey(card.category)
            AssistChip(onClick = { openCategory(c.key) }, label = { Text("${c.emoji} ${categoryLabel(c)}") })
        }
        if (deck != null) AssistChip(onClick = { openDeck(deck.id) }, label = { Text("📒 ${deck.name}") })
        if (card.sourceTitle.isNotBlank()) AssistChip(onClick = {}, label = { Text("🔖 ${card.sourceTitle}") })
        splitList(card.tags).forEach { t -> AssistChip(onClick = {}, label = { Text("#$t") }) }
    }
}

@Composable
private fun WordRelations(card: CardEntity) {
    val groups = listOf(
        R.string.library_field_synonyms to splitList(card.synonyms),
        R.string.library_field_antonyms to splitList(card.antonyms),
        R.string.library_field_forms to splitList(card.forms),
    ).filter { it.second.isNotEmpty() }
    groups.forEach { (label, items) ->
        SectionHeader(stringResource(label))
        ChipList(items)
    }
}

@Composable
private fun SentencePager(items: List<ContextItem>, card: CardEntity, lang: StudyLanguage, vm: CardDetailViewModel) {
    if (items.isEmpty()) {
        Text(stringResource(R.string.library_no_sentences), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var hide by rememberSaveable { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf<ContextItem?>(null) }
    val pager = rememberPagerState { items.size }
    val forms = remember(card.forms) { splitList(card.forms) }
    HorizontalPager(pager, pageSpacing = 12.dp, modifier = Modifier.animateContentSize()) { page ->
        val item = items[page]
        Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
            Column(Modifier.padding(16.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        contextSourceEmoji(item.source) + " " + contextSourceLabel(item.source) +
                            item.title.takeIf { it.isNotBlank() && it != "AI" }?.let { " · $it" }.orEmpty(),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f),
                    )
                    Text("${page + 1}/${items.size}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Spacer(Modifier.height(8.dp))
                AnimatedContent(hide, label = "cloze") { h ->
                    val text = if (h) Cloze.mask(item.sentence, card.term, lang.usesSpaces, forms) else item.sentence
                    Text(text, style = MaterialTheme.typography.titleMedium)
                }
                if (item.translation.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(item.translation, style = MaterialTheme.typography.bodyMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    IconButton(onClick = { vm.speak(item.sentence, false) }) { Icon(Icons.AutoMirrored.Filled.VolumeUp, stringResource(R.string.action_listen)) }
                    IconButton(onClick = { hide = !hide }) {
                        Icon(if (hide) Icons.Filled.Visibility else Icons.Filled.VisibilityOff, stringResource(R.string.library_hide_word))
                    }
                    IconButton(onClick = { confirmDelete = item }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                }
            }
        }
    }
    if (items.size > 1) {
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalArrangement = Arrangement.Center) {
            repeat(items.size.coerceAtMost(12)) { i ->
                val active = i == pager.currentPage
                Box(
                    Modifier.padding(3.dp).size(if (active) 8.dp else 6.dp)
                        .background(if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant, CircleShape),
                )
            }
        }
    }
    confirmDelete?.let { item ->
        ConfirmDialog(
            stringResource(R.string.library_delete_sentence), item.sentence, stringResource(R.string.action_delete),
            onConfirm = { vm.deleteContext(item) }, onDismiss = { confirmDelete = null },
        )
    }
}

@Composable
private fun LearningSection(card: CardEntity, info: LearningInfo?, logs: List<ReviewLogEntity>) {
    SectionHeader(stringResource(R.string.library_learning))
    val total = card.correctCount + card.wrongCount
    val accuracy = if (total == 0) "—" else "${(card.correctCount * 100f / total).roundToInt()}%"
    val now = System.currentTimeMillis()
    val due = when {
        card.state == app.kotoba.core.model.SrsState.NEW -> stringResource(R.string.library_due_new)
        card.due <= now -> stringResource(R.string.library_due_now)
        else -> stringResource(R.string.library_due_in, formatInterval(card.due - now))
    }
    val tiles = listOf(
        masteryLabel(card.mastery) to stringResource(R.string.library_stat_mastery),
        srsStateLabel(card.state) to stringResource(R.string.library_stat_state),
        due to stringResource(R.string.library_stat_due),
        (if (card.stability > 0) formatInterval((card.stability * 86_400_000).toLong()) else "—") to stringResource(R.string.library_stat_stability),
        (info?.let { "${(it.retrievability * 100).roundToInt()}%" } ?: "—") to stringResource(R.string.library_stat_retrievability),
        "${card.reps}" to stringResource(R.string.library_stat_reps),
        "${card.lapses}" to stringResource(R.string.library_stat_lapses),
        accuracy to stringResource(R.string.library_stat_accuracy),
        (if (card.avgResponseMs > 0) "%.1fs".format(card.avgResponseMs / 1000f) else "—") to stringResource(R.string.library_stat_response),
    )
    tiles.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { (v, l) -> StatTile(v, l, Modifier.weight(1f)) }
        }
    }
    val weak = info?.weakness
    if (weak != null && (weak.isWeak || weak.reasons.isNotEmpty())) {
        Card(
            Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
        ) {
            Column(Modifier.padding(14.dp)) {
                Text("🎯 " + stringResource(R.string.library_weak_title), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                weak.reasons.forEach { Text("• " + weakReasonLabel(it), color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }
    if (logs.isNotEmpty()) {
        SectionHeader(stringResource(R.string.library_history))
        val fmt = remember { DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT) }
        logs.take(30).forEach { log ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(10.dp).background(if (log.correct) MasteryColors.Mastered else MaterialTheme.colorScheme.error, CircleShape))
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(fmt.format(Date(log.timestamp)), style = MaterialTheme.typography.bodyMedium)
                    Text(
                        ratingLabel(Rating.of(log.rating)) + " · " + "%.1fs".format(log.responseMs / 1000f) +
                            if (log.masteryAfter != log.masteryBefore) " · ${masteryLabel(log.masteryBefore)} → ${masteryLabel(log.masteryAfter)}" else "",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (log.scheduledDays > 0) Text("+" + formatInterval((log.scheduledDays * 86_400_000).toLong()), style = MaterialTheme.typography.labelMedium)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        }
    }
}

@Composable
fun srsStateLabel(s: app.kotoba.core.model.SrsState): String = stringResource(
    when (s) {
        app.kotoba.core.model.SrsState.NEW -> R.string.library_srs_new
        app.kotoba.core.model.SrsState.LEARNING -> R.string.library_srs_learning
        app.kotoba.core.model.SrsState.REVIEW -> R.string.library_srs_review
        app.kotoba.core.model.SrsState.RELEARNING -> R.string.library_srs_relearning
    },
)

@Composable
private fun ratingLabel(r: Rating): String = stringResource(
    when (r) {
        Rating.AGAIN -> R.string.library_rating_again
        Rating.HARD -> R.string.library_rating_hard
        Rating.GOOD -> R.string.library_rating_good
        Rating.EASY -> R.string.library_rating_easy
    },
)

@Composable
private fun AddSentenceDialog(onDismiss: () -> Unit, onAdd: (String, String) -> Unit) {
    var sentence by rememberSaveable { mutableStateOf("") }
    var translation by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_add_sentence)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(sentence, { sentence = it }, label = { Text(stringResource(R.string.library_sentence)) }, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(translation, { translation = it }, label = { Text(stringResource(R.string.library_field_translation)) }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(enabled = sentence.isNotBlank(), onClick = { onAdd(sentence, translation); onDismiss() }) { Text(stringResource(R.string.action_add)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
