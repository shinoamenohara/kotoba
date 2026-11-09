package app.kotoba.feature.reader

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.core.IncomingContent
import app.kotoba.core.IncomingContentBus
import app.kotoba.core.data.Achievement
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.docs.DocumentParser
import app.kotoba.core.docs.DocumentRepository
import app.kotoba.core.model.DocumentFormat
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.DocumentsRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class DocumentsTab(val key: String, val kinds: List<DocumentKind>) {
    BOOKS("books", listOf(DocumentKind.BOOK, DocumentKind.ARTICLE)),
    MANGA("manga", listOf(DocumentKind.MANGA)),
    NOTES("notes", listOf(DocumentKind.NOTEBOOKLM, DocumentKind.NOTE)),
    ;

    companion object {
        fun of(key: String?) = entries.firstOrNull { it.key == key } ?: BOOKS
    }
}

data class ImportProgress(val name: String, val fraction: Float?)

sealed interface DocumentsEvent {
    data class Imported(val doc: DocumentEntity) : DocumentsEvent
    data class Failed(val message: String?) : DocumentsEvent
}

@HiltViewModel
class DocumentsViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: DocumentRepository,
    private val incoming: IncomingContentBus,
    private val gamification: GamificationRepository,
) : ViewModel() {
    val initialTab = DocumentsTab.of(runCatching { savedState.toRoute<DocumentsRoute>().tab }.getOrNull())

    val lists: Map<DocumentsTab, StateFlow<List<DocumentEntity>?>> = DocumentsTab.entries.associateWith { tab ->
        repo.observe(tab.kinds).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    }

    private val _progress = MutableStateFlow<ImportProgress?>(null)
    val progress: StateFlow<ImportProgress?> = _progress

    private val _events = Channel<DocumentsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private var importJob: Job? = null

    init {
        incoming.consume<IncomingContent.File>()?.let { f ->
            val format = DocumentParser.formatOf(f.displayName, f.mimeType)
            val kind = if (format == DocumentFormat.IMAGES || format == DocumentFormat.CBZ) DocumentKind.MANGA else null
            importUri(f.uri, f.displayName, kind)
        }
    }

    fun importUri(uri: Uri, displayName: String? = null, kindHint: DocumentKind? = null) = runImport(displayName ?: "") {
        repo.importFromUri(uri, displayName, kindHint) { p -> _progress.value = _progress.value?.copy(fraction = p) }
    }

    fun importImages(uris: List<Uri>) {
        if (uris.isEmpty()) return
        runImport("") { repo.importMangaImages(uris, null) { p -> _progress.value = _progress.value?.copy(fraction = p) } }
    }

    fun importText(title: String, text: String, kind: DocumentKind) = runImport(title) { repo.importText(title, text, kind) }

    private fun runImport(name: String, block: suspend () -> Long) {
        if (importJob?.isActive == true) return
        importJob = viewModelScope.launch {
            _progress.value = ImportProgress(name, null)
            runCatching { block() }
                .onSuccess { id ->
                    gamification.unlock(Achievement.FIRST_IMPORT)
                    repo.get(id)?.let { _events.send(DocumentsEvent.Imported(it)) }
                }
                .onFailure { e -> if (e !is kotlinx.coroutines.CancellationException) _events.send(DocumentsEvent.Failed(e.message)) }
            _progress.value = null
        }
    }

    fun cancelImport() { importJob?.cancel(); _progress.value = null }

    fun delete(doc: DocumentEntity) = viewModelScope.launch { repo.delete(doc) }

    fun setLanguage(doc: DocumentEntity, lang: StudyLanguage) = viewModelScope.launch { repo.setLanguage(doc, lang) }

    fun rename(doc: DocumentEntity, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) repo.update(doc.copy(title = title.trim()))
    }
}
