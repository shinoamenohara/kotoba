package app.kotoba.feature.importexport

import android.content.Intent
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.FileKinds
import app.kotoba.core.IncomingContent
import app.kotoba.core.IncomingContentBus
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.db.DeckDao
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.model.StudyLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ImportStage {
    data object Idle : ImportStage
    data class Loading(val fileName: String) : ImportStage
    data class Preview(val parsed: ParsedImport, val options: ImportOptions) : ImportStage
    data class Importing(val fileName: String) : ImportStage
    data class Done(val report: ImportReport) : ImportStage
    data class Failed(val message: String) : ImportStage
}

data class ExportState(
    val deckId: Long? = null,
    val format: ExportFormat = ExportFormat.CSV,
    val busy: Boolean = false,
)

sealed interface ImportExportEvent {
    data class Share(val intent: Intent) : ImportExportEvent
    data class Exported(val count: Int) : ImportExportEvent
    data class Error(val message: String) : ImportExportEvent
}

@HiltViewModel
class ImportExportViewModel @Inject constructor(
    private val importer: ImportRepository,
    private val exporter: ExportRepository,
    private val settings: SettingsRepository,
    private val incoming: IncomingContentBus,
    deckDao: DeckDao,
) : ViewModel() {
    private val _stage = MutableStateFlow<ImportStage>(ImportStage.Idle)
    val stage: StateFlow<ImportStage> = _stage.asStateFlow()

    private val _export = MutableStateFlow(ExportState())
    val export: StateFlow<ExportState> = _export.asStateFlow()

    private val _events = Channel<ImportExportEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val activeLanguage: StateFlow<StudyLanguage> = settings.settings.map { it.activeLanguage }
        .stateIn(viewModelScope, SharingStarted.Eagerly, StudyLanguage.EN)

    val studyLanguages: StateFlow<List<StudyLanguage>> = settings.settings
        .map { s -> (s.studyLanguages + s.activeLanguage).sortedBy { it.ordinal } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** Decks of the import target language. */
    val targetDecks: StateFlow<List<DeckEntity>> = _stage
        .map { (it as? ImportStage.Preview)?.options?.language }
        .filterNotNull()
        .flatMapLatest { deckDao.observeForLanguage(it.code) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val exportDecks: StateFlow<List<DeckEntity>> = activeLanguage
        .flatMapLatest { deckDao.observeForLanguage(it.code) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        // Files opened with Kotoba (ACTION_VIEW) land here via the navigation host.
        val pending = incoming.pending.value as? IncomingContent.File
        if (pending != null && FileKinds.extension(pending.displayName) in FileKinds.deckExtensions) {
            incoming.clear()
            load(pending.uri, pending.displayName)
        }
    }

    fun onFilePicked(uri: Uri) = load(uri, null)

    private fun load(uri: Uri, name: String?) {
        discardCurrent()
        val fileName = name ?: importer.displayName(uri)
        _stage.value = ImportStage.Loading(fileName)
        viewModelScope.launch {
            runCatching {
                val parsed = importer.parse(uri, fileName)
                val s = settings.current()
                val lang = parsed.suggestedLanguage?.takeIf { it in s.studyLanguages } ?: s.activeLanguage
                val options = ImportOptions(
                    language = lang,
                    deckMode = if (parsed.sourceDecks.isNotEmpty()) DeckMode.SOURCE_DECKS else DeckMode.NEW,
                    newDeckName = fileName.substringBeforeLast('.'),
                    mapping = parsed.groups.associate { it.id to SmartMapping.defaults(it.fields, lang) },
                )
                parsed to options
            }.onSuccess { (parsed, options) ->
                _stage.value = if (parsed.total == 0) {
                    importer.discard(parsed)
                    ImportStage.Failed("")
                } else ImportStage.Preview(parsed, options)
            }.onFailure { _stage.value = ImportStage.Failed(it.message.orEmpty()) }
        }
    }

    fun updateOptions(transform: (ImportOptions) -> ImportOptions) = _stage.update { st ->
        if (st is ImportStage.Preview) st.copy(options = transform(st.options)) else st
    }

    fun setMapping(groupId: Long, fieldIndex: Int, target: FieldTarget) = updateOptions { o ->
        val current = o.mapping[groupId].orEmpty().toMutableList()
        if (fieldIndex !in current.indices) return@updateOptions o
        current[fieldIndex] = target
        o.copy(mapping = o.mapping + (groupId to current))
    }

    fun startImport() {
        val st = _stage.value as? ImportStage.Preview ?: return
        _stage.value = ImportStage.Importing(st.parsed.fileName)
        viewModelScope.launch {
            runCatching { importer.commit(st.parsed, st.options) }
                .onSuccess { _stage.value = ImportStage.Done(it) }
                .onFailure { _stage.value = ImportStage.Failed(it.message.orEmpty()) }
        }
    }

    fun reset() {
        discardCurrent()
        _stage.value = ImportStage.Idle
    }

    private fun discardCurrent() {
        (_stage.value as? ImportStage.Preview)?.parsed?.let(importer::discard)
    }

    // --- Export ---

    fun setExportDeck(id: Long?) = _export.update { it.copy(deckId = id) }
    fun setExportFormat(f: ExportFormat) = _export.update { it.copy(format = f) }

    suspend fun suggestedExportName(): String {
        val e = _export.value
        val lang = activeLanguage.first()
        return exporter.suggestedName(lang, exportDecks.value.firstOrNull { it.id == e.deckId }?.name, e.format)
    }

    fun exportTo(uri: Uri) = runExport { content, _ -> exporter.writeTo(uri, content) }

    fun share() = runExport { content, count ->
        val name = suggestedExportName()
        _events.send(ImportExportEvent.Share(exporter.shareIntent(name, content, _export.value.format)))
        count
    }

    private fun runExport(block: suspend (String, Int) -> Any?) {
        val e = _export.value
        if (e.busy) return
        _export.update { it.copy(busy = true) }
        viewModelScope.launch {
            runCatching {
                val (content, count) = exporter.render(activeLanguage.first(), e.deckId, e.format)
                block(content, count)
                count
            }.onSuccess { _events.send(ImportExportEvent.Exported(it)) }
                .onFailure { _events.send(ImportExportEvent.Error(it.message.orEmpty())) }
            _export.update { it.copy(busy = false) }
        }
    }

    override fun onCleared() {
        discardCurrent()
    }
}
