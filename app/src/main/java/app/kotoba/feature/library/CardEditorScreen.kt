package app.kotoba.feature.library

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.R
import app.kotoba.core.ai.AiCardEnricher
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DeckDao
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.lang.DictionaryService
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.Category
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.CardEditorRoute
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.rememberHaptics
import coil.compose.AsyncImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import javax.inject.Inject

/** Fill helper: only overwrite fields the user left empty. */
private fun String.orFill(v: String?) = ifBlank { v.orEmpty().trim() }

enum class EditorBusy { NONE, DICTIONARY, AI, SAVING }

data class EditorUi(
    val card: CardEntity,
    val language: StudyLanguage,
    val loaded: Boolean = false,
    val duplicate: CardEntity? = null,
    val busy: EditorBusy = EditorBusy.NONE,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class CardEditorViewModel @Inject constructor(
    handle: SavedStateHandle,
    @ApplicationContext private val context: Context,
    private val repo: CardRepository,
    private val settings: SettingsRepository,
    private val dictionary: DictionaryService,
    private val enricher: AiCardEnricher,
    ai: AiRepository,
    deckDao: DeckDao,
) : ViewModel() {
    private val route = handle.toRoute<CardEditorRoute>()
    val isNew = route.cardId <= 0

    val ui = MutableStateFlow(EditorUi(CardEntity(language = StudyLanguage.EN.code, term = "", translation = ""), StudyLanguage.EN))
    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    val messages: SharedFlow<String> = _messages
    private val _saved = MutableSharedFlow<Long>(extraBufferCapacity = 1)
    val saved: SharedFlow<Long> = _saved

    val aiConfigured: StateFlow<Boolean> = ai.status.map { it.configured }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    val decks: StateFlow<List<DeckEntity>> = ui.map { it.language }.distinctUntilChanged()
        .flatMapLatest { deckDao.observeForLanguage(it.code) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        viewModelScope.launch {
            val existing = if (isNew) null else repo.get(route.cardId)
            val lang = existing?.let { StudyLanguage.fromCode(it.language) } ?: settings.current().activeLanguage
            val card = existing ?: CardEntity(
                language = lang.code,
                term = route.term?.trim().orEmpty(),
                translation = "",
                deckId = route.deckId.takeIf { it > 0 },
                category = route.deckId.takeIf { it > 0 }?.let { repo.getDeck(it)?.category }.orEmpty(),
                source = CardSource.MANUAL,
            )
            ui.value = EditorUi(card, lang, loaded = true)
        }
        viewModelScope.launch {
            ui.map { it.card.term.trim() to it.language }.distinctUntilChanged().debounce(300).mapLatest { (term, lang) ->
                if (term.isEmpty()) null else repo.findByTerm(lang, term)?.takeIf { it.id != ui.value.card.id }
            }.collect { dup -> ui.update { it.copy(duplicate = dup) } }
        }
    }

    fun edit(f: (CardEntity) -> CardEntity) = ui.update { it.copy(card = f(it.card)) }

    fun autoFillOffline() = viewModelScope.launch {
        val s = ui.value
        if (s.card.term.isBlank() || s.busy != EditorBusy.NONE) return@launch
        ui.update { it.copy(busy = EditorBusy.DICTIONARY) }
        runCatching { dictionary.lookup(s.card.term, s.language, settings.current().nativeLanguage) }
            .onSuccess { r ->
                val def = r.definitions.firstOrNull()
                edit { c ->
                    c.copy(
                        translation = c.translation.orFill(r.translation),
                        reading = c.reading.orFill(r.reading.takeIf { s.language.hasReading }),
                        ipa = c.ipa.orFill(r.ipa),
                        partOfSpeech = c.partOfSpeech.orFill(r.partOfSpeech.takeIf { it.isNotBlank() }?.let { PartOfSpeech.fromKey(it).key }),
                        synonyms = c.synonyms.orFill(r.synonyms.take(6).joinToString(", ")),
                        antonyms = c.antonyms.orFill(r.antonyms.take(6).joinToString(", ")),
                        example = c.example.orFill(def?.example),
                        notes = c.notes.orFill(r.definitions.take(3).joinToString("\n") { "• ${it.text}" }),
                    )
                }
                if (r.error != null) _messages.tryEmit(r.error)
            }
            .onFailure { _messages.tryEmit(it.message ?: "Lookup failed") }
        ui.update { it.copy(busy = EditorBusy.NONE) }
    }

    fun autoFillAi() = viewModelScope.launch {
        val s = ui.value
        if (s.card.term.isBlank() || s.busy != EditorBusy.NONE) return@launch
        ui.update { it.copy(busy = EditorBusy.AI) }
        enricher.enrich(s.card.term.trim(), s.language, settings.current().nativeLanguage, s.card.example.takeIf { it.isNotBlank() })
            .onSuccess { e ->
                edit { c ->
                    c.copy(
                        translation = c.translation.orFill(e.translation),
                        reading = c.reading.orFill(e.reading.takeIf { s.language.hasReading }),
                        ipa = c.ipa.orFill(e.ipa),
                        partOfSpeech = c.partOfSpeech.orFill(e.partOfSpeech.takeIf { it.isNotBlank() }?.let { PartOfSpeech.fromKey(it).key }),
                        example = c.example.orFill(e.example),
                        exampleTranslation = c.exampleTranslation.orFill(e.exampleTranslation),
                        synonyms = c.synonyms.orFill(e.synonyms),
                        antonyms = c.antonyms.orFill(e.antonyms),
                        forms = c.forms.orFill(e.forms),
                        level = c.level.orFill(e.level.takeIf { it in s.language.levelScale.levels }),
                        category = c.category.orFill(e.category.takeIf { k -> Category.entries.any { it.key == k } }),
                        association = c.association.orFill(e.association),
                    )
                }
            }
            .onFailure { _messages.tryEmit(it.message ?: "AI error") }
        ui.update { it.copy(busy = EditorBusy.NONE) }
    }

    fun attach(uri: Uri, image: Boolean) = viewModelScope.launch {
        val path = runCatching { copyToMedia(uri) }.getOrElse { _messages.tryEmit(it.message ?: "Copy failed"); return@launch }
        edit { if (image) it.copy(imageUri = path) else it.copy(audioUri = path) }
    }

    private suspend fun copyToMedia(uri: Uri): String = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val ext = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri)) ?: "bin"
        val dir = File(context.filesDir, "media/cards").apply { mkdirs() }
        val out = File(dir, "${UUID.randomUUID()}.$ext")
        resolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } ?: error("Cannot open file")
        out.absolutePath
    }

    fun createDeck(deck: DeckEntity) = viewModelScope.launch {
        val id = repo.saveDeck(deck.copy(language = ui.value.language.code))
        edit { it.copy(deckId = id) }
    }

    fun save() = viewModelScope.launch {
        val s = ui.value
        val c = s.card
        if (c.term.isBlank() || c.translation.isBlank() || s.busy != EditorBusy.NONE) return@launch
        ui.update { it.copy(busy = EditorBusy.SAVING) }
        val cleaned = c.copy(
            term = c.term.trim(), translation = c.translation.trim(), reading = c.reading.trim(), ipa = c.ipa.trim(),
            tags = splitList(c.tags).joinToString(", "),
        )
        runCatching { repo.save(cleaned) }
            .onSuccess { _saved.tryEmit(it) }
            .onFailure { _messages.tryEmit(it.message ?: "Save failed") }
        ui.update { it.copy(busy = EditorBusy.NONE) }
    }
}

@Composable
fun CardEditorScreen(onBack: () -> Unit, onSaved: (Long) -> Unit, openCard: (Long) -> Unit, vm: CardEditorViewModel = hiltViewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    val decks by vm.decks.collectAsStateWithLifecycle()
    val aiConfigured by vm.aiConfigured.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    val haptics = rememberHaptics()
    var newDeck by rememberSaveable { mutableStateOf(false) }
    val c = ui.card
    val lang = ui.language

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.saved.collect { haptics.success(); onSaved(it) } }

    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri -> uri?.let { vm.attach(it, image = true) } }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.attach(it, image = false) } }
    val canSave = c.term.isNotBlank() && c.translation.isNotBlank() && ui.busy == EditorBusy.NONE

    Scaffold(
        topBar = {
            KotobaTopBar(
                title = stringResource(if (vm.isNew) R.string.library_add_word else R.string.library_edit_word),
                onBack = onBack,
                actions = { TextButton(onClick = vm::save, enabled = canSave) { Text(stringResource(R.string.action_save)) } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (!ui.loaded) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("${lang.flag} ${lang.nativeName}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Field(c.term, { v -> vm.edit { it.copy(term = v) } }, stringResource(R.string.library_field_term), singleLine = true)

            AnimatedVisibility(ui.duplicate != null) {
                ui.duplicate?.let { d ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Warning, null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
                            Spacer(Modifier.width(8.dp))
                            Text(
                                stringResource(R.string.library_duplicate, d.term, d.translation),
                                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis,
                            )
                            TextButton(onClick = { openCard(d.id) }) { Text(stringResource(R.string.library_open)) }
                        }
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = vm::autoFillOffline, enabled = c.term.isNotBlank() && ui.busy == EditorBusy.NONE, modifier = Modifier.weight(1f)) {
                    if (ui.busy == EditorBusy.DICTIONARY) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.MenuBook, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.library_autofill_dictionary), maxLines = 1)
                }
                if (aiConfigured) {
                    FilledTonalButton(onClick = vm::autoFillAi, enabled = c.term.isNotBlank() && ui.busy == EditorBusy.NONE, modifier = Modifier.weight(1f)) {
                        if (ui.busy == EditorBusy.AI) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.library_autofill_ai), maxLines = 1)
                    }
                }
            }

            Field(c.translation, { v -> vm.edit { it.copy(translation = v) } }, stringResource(R.string.library_field_translation))
            if (lang.hasReading) {
                Field(c.reading, { v -> vm.edit { it.copy(reading = v) } }, readingLabel(lang), singleLine = true)
            }
            if (lang != StudyLanguage.JA && lang != StudyLanguage.ZH) {
                Field(c.ipa, { v -> vm.edit { it.copy(ipa = v) } }, stringResource(R.string.library_reading_ipa), singleLine = true)
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                DropdownField(
                    label = stringResource(R.string.library_field_pos),
                    value = c.partOfSpeech,
                    options = listOf("") + PartOfSpeech.entries.map { it.key },
                    display = { if (it.isEmpty()) "—" else posLabel(PartOfSpeech.fromKey(it)) },
                    onSelect = { v -> vm.edit { it.copy(partOfSpeech = v) } },
                    modifier = Modifier.weight(1f),
                )
                DropdownField(
                    label = stringResource(R.string.library_field_level),
                    value = c.level,
                    options = listOf("") + lang.levelScale.levels,
                    display = { it.ifEmpty { "—" } },
                    onSelect = { v -> vm.edit { it.copy(level = v) } },
                    modifier = Modifier.weight(1f),
                )
            }
            DropdownField(
                label = stringResource(R.string.library_field_category),
                value = c.category,
                options = listOf("") + Category.entries.map { it.key },
                display = { k -> if (k.isEmpty()) stringResource(R.string.library_none) else Category.fromKey(k).let { "${it.emoji}  ${categoryLabel(it)}" } },
                onSelect = { v -> vm.edit { it.copy(category = v) } },
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                DropdownField(
                    label = stringResource(R.string.library_deck),
                    value = c.deckId,
                    options = listOf<Long?>(null) + decks.map { it.id },
                    display = { id -> decks.firstOrNull { it.id == id }?.name ?: stringResource(R.string.library_no_deck) },
                    onSelect = { v -> vm.edit { it.copy(deckId = v) } },
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { newDeck = true }) { Text(stringResource(R.string.library_new_deck)) }
            }

            Field(c.example, { v -> vm.edit { it.copy(example = v) } }, stringResource(R.string.library_field_example))
            Field(c.exampleTranslation, { v -> vm.edit { it.copy(exampleTranslation = v) } }, stringResource(R.string.library_field_example_translation))
            Field(c.synonyms, { v -> vm.edit { it.copy(synonyms = v) } }, stringResource(R.string.library_field_synonyms), hint = stringResource(R.string.library_comma_hint))
            Field(c.antonyms, { v -> vm.edit { it.copy(antonyms = v) } }, stringResource(R.string.library_field_antonyms), hint = stringResource(R.string.library_comma_hint))
            Field(c.forms, { v -> vm.edit { it.copy(forms = v) } }, stringResource(R.string.library_field_forms), hint = stringResource(R.string.library_forms_hint))
            Field(c.association, { v -> vm.edit { it.copy(association = v) } }, stringResource(R.string.library_field_association))

            // Media
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(onClick = { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.Image, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.library_pick_image), maxLines = 1)
                }
                OutlinedButton(onClick = { pickAudio.launch(arrayOf("audio/*")) }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Filled.AudioFile, null, Modifier.size(18.dp)); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.library_pick_audio), maxLines = 1)
                }
            }
            if (c.imageUri.isNotBlank()) {
                Box {
                    AsyncImage(
                        model = if (c.imageUri.startsWith("/")) File(c.imageUri) else c.imageUri,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(180.dp).clip(MaterialTheme.shapes.medium),
                    )
                    IconButton(onClick = { vm.edit { it.copy(imageUri = "") } }, modifier = Modifier.align(Alignment.TopEnd)) {
                        Icon(Icons.Filled.Close, stringResource(R.string.action_delete))
                    }
                }
            }
            if (c.audioUri.isNotBlank()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎵 " + File(c.audioUri).name, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    IconButton(onClick = { vm.edit { it.copy(audioUri = "") } }) { Icon(Icons.Filled.Close, stringResource(R.string.action_delete)) }
                }
            }

            Field(c.tags, { v -> vm.edit { it.copy(tags = v) } }, stringResource(R.string.library_field_tags), hint = stringResource(R.string.library_comma_hint), singleLine = true)
            Field(c.notes, { v -> vm.edit { it.copy(notes = v) } }, stringResource(R.string.library_field_notes), minLines = 3)
            Spacer(Modifier.height(8.dp))
            FilledTonalButton(onClick = vm::save, enabled = canSave, modifier = Modifier.fillMaxWidth()) {
                if (ui.busy == EditorBusy.SAVING) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                else Text(stringResource(R.string.action_save))
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (newDeck) {
        DeckEditDialog(
            initial = DeckEntity(name = "", language = lang.code, color = DeckColors.random(), category = c.category),
            language = lang,
            onDismiss = { newDeck = false },
            onSave = vm::createDeck,
        )
    }
}

@Composable
private fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    singleLine: Boolean = false,
    hint: String? = null,
    minLines: Int = 1,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        supportingText = hint?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = minLines,
        keyboardOptions = KeyboardOptions(imeAction = if (singleLine) ImeAction.Next else ImeAction.Default),
        modifier = Modifier.fillMaxWidth(),
    )
}
