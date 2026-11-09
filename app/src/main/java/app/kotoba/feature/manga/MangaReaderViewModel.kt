package app.kotoba.feature.manga

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.R
import app.kotoba.core.data.Achievement
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.docs.DocumentRepository
import app.kotoba.core.lang.TextSegmenter
import app.kotoba.core.lang.Token
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.MangaReaderRoute
import app.kotoba.feature.reader.LookupState
import app.kotoba.feature.reader.MangaDirection
import app.kotoba.feature.reader.ReaderPrefs
import app.kotoba.feature.reader.ReaderPrefsStore
import app.kotoba.feature.reader.WordStudyHelper
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

data class MangaUiState(
    val doc: DocumentEntity? = null,
    val pages: List<File> = emptyList(),
    val language: StudyLanguage = StudyLanguage.JA,
    val initialPage: Int = 0,
    val loading: Boolean = true,
)

/** The speech bubble currently open in the sheet. */
data class BlockSelection(
    val page: Int,
    val block: OcrBlock,
    val translation: String? = null,
    val translating: Boolean = false,
)

/** A word picked for bulk add (can span bubbles and pages). */
data class PickedWord(val page: Int, val blockId: Int, val term: String, val sentence: String)

sealed interface MangaEvent {
    data object AchievementUnlocked : MangaEvent
    data class Message(val res: Int, val arg: Any? = null) : MangaEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class MangaReaderViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: DocumentRepository,
    private val ocrEngine: MangaOcr,
    private val segmenter: TextSegmenter,
    private val words: WordStudyHelper,
    private val gamification: GamificationRepository,
    private val prefsStore: ReaderPrefsStore,
) : ViewModel() {
    private val documentId = savedState.toRoute<MangaReaderRoute>().documentId

    private val _state = MutableStateFlow(MangaUiState())
    val state: StateFlow<MangaUiState> = _state
    val prefs: StateFlow<ReaderPrefs> = prefsStore.prefs

    private val _ocr = MutableStateFlow<Map<Int, OcrPage>>(emptyMap())
    val ocr: StateFlow<Map<Int, OcrPage>> = _ocr
    private val _recognizing = MutableStateFlow<Set<Int>>(emptySet())
    val recognizing: StateFlow<Set<Int>> = _recognizing

    private val _selection = MutableStateFlow<BlockSelection?>(null)
    val selection: StateFlow<BlockSelection?> = _selection
    private val _lookup = MutableStateFlow<LookupState?>(null)
    val lookup: StateFlow<LookupState?> = _lookup
    private val _picks = MutableStateFlow<List<PickedWord>>(emptyList())
    val picks: StateFlow<List<PickedWord>> = _picks
    private val _adding = MutableStateFlow(false)
    val adding: StateFlow<Boolean> = _adding

    private val _events = Channel<MangaEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val page = MutableStateFlow<Int?>(null)
    private var resumedAt = 0L
    private var lookupJob: Job? = null

    init {
        viewModelScope.launch {
            val doc = repo.get(documentId)
            if (doc == null) { _state.update { it.copy(loading = false) }; return@launch }
            val pages = repo.mangaPages(doc)
            val start = doc.position.coerceIn(0, (pages.size - 1).coerceAtLeast(0))
            _state.value = MangaUiState(doc, pages, StudyLanguage.fromCode(doc.language), start, loading = false)
            repo.savePosition(doc.id, start, doc.progress)
            if (gamification.unlock(Achievement.FIRST_MANGA)) _events.send(MangaEvent.AchievementUnlocked)
        }
        viewModelScope.launch {
            page.filterNotNull().debounce(500).collect { p ->
                val n = _state.value.pages.size.coerceAtLeast(1)
                repo.savePosition(documentId, p, (p + 1f) / n)
            }
        }
    }

    /** Right-to-left paging: explicit setting, or automatic for Japanese. */
    fun isRtl(p: ReaderPrefs = prefs.value): Boolean = when (p.mangaDirection) {
        MangaDirection.RTL -> true
        MangaDirection.LTR -> false
        MangaDirection.AUTO -> _state.value.language == StudyLanguage.JA
    }

    fun setDirection(d: MangaDirection) = prefsStore.update { it.copy(mangaDirection = d) }
    fun setShowBoxes(show: Boolean) = prefsStore.update { it.copy(showOcrBoxes = show) }

    fun setLanguage(lang: StudyLanguage) = viewModelScope.launch {
        val doc = _state.value.doc ?: return@launch
        repo.setLanguage(doc, lang)
        _state.update { it.copy(doc = doc.copy(language = lang.code), language = lang) }
    }

    fun onPageShown(index: Int) {
        page.value = index
        for (i in listOf(index, index - 1, index + 1)) loadCached(i)
    }

    private fun loadCached(index: Int) {
        val file = _state.value.pages.getOrNull(index) ?: return
        if (_ocr.value.containsKey(index)) return
        viewModelScope.launch {
            ocrEngine.cached(file)?.let { o -> _ocr.update { it + (index to o) } }
        }
    }

    fun recognize(index: Int) {
        val file = _state.value.pages.getOrNull(index) ?: return
        if (index in _recognizing.value) return
        _recognizing.update { it + index }
        viewModelScope.launch {
            runCatching { ocrEngine.recognize(file, _state.value.language) }
                .onSuccess { o ->
                    _ocr.update { it + (index to o) }
                    if (o.blocks.isEmpty()) _events.send(MangaEvent.Message(R.string.manga_no_text))
                }
                .onFailure { _events.send(MangaEvent.Message(R.string.manga_ocr_failed, it.message)) }
            _recognizing.update { it - index }
        }
    }

    // --- bubble sheet ------------------------------------------------------------------------

    fun openBlock(pageIndex: Int, block: OcrBlock) {
        _selection.value = BlockSelection(pageIndex, block)
        _lookup.value = null
    }

    fun closeBlock() {
        _selection.value = null
        _lookup.value = null
        lookupJob?.cancel()
    }

    fun tokens(text: String): List<Token> = segmenter.tokenize(text, _state.value.language).filter { it.isWord }

    fun saveBlockText(text: String) {
        val sel = _selection.value ?: return
        val file = _state.value.pages.getOrNull(sel.page) ?: return
        val page = _ocr.value[sel.page] ?: return
        val updated = sel.block.copy(text = text.trim(), edited = true)
        val newPage = page.copy(blocks = page.blocks.map { if (it.id == updated.id) updated else it })
        _ocr.update { it + (sel.page to newPage) }
        _selection.value = sel.copy(block = updated, translation = null)
        viewModelScope.launch { ocrEngine.save(file, newPage) }
    }

    fun translateBlock() {
        val sel = _selection.value ?: return
        _selection.value = sel.copy(translating = true)
        viewModelScope.launch {
            val tr = words.translate(sel.block.text, _state.value.language).getOrNull()
            _selection.update { s -> s?.takeIf { it.block.id == sel.block.id }?.copy(translation = tr ?: "—", translating = false) ?: s }
        }
    }

    fun lookupWord(term: String) {
        val sel = _selection.value ?: return
        val sentence = sel.block.text
        _lookup.value = LookupState(term = term, sentence = sentence)
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            val r = runCatching { words.lookup(term, _state.value.language, sentence) }.getOrNull()
            _lookup.update { it?.takeIf { s -> s.term == term }?.copy(loading = false, result = r) ?: it }
        }
    }

    fun closeLookup() { _lookup.value = null }

    fun speak(text: String) = viewModelScope.launch { words.speak(text, _state.value.language) }

    private suspend fun bubbleImage(pageIndex: Int, block: OcrBlock): String {
        val doc = _state.value.doc ?: return ""
        val file = _state.value.pages.getOrNull(pageIndex) ?: return ""
        val dir = repo.documentDir(doc) ?: return ""
        val target = File(dir, "crops/p${pageIndex + 1}_b${block.id}.png")
        return runCatching {
            if (!target.isFile) ocrEngine.crop(file, block.box, target)
            Uri.fromFile(target).toString()
        }.getOrDefault("")
    }

    fun addWord() {
        val l = _lookup.value ?: return
        val r = l.result ?: return
        val sel = _selection.value ?: return
        val doc = _state.value.doc ?: return
        _lookup.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val image = bubbleImage(sel.page, sel.block)
            val id = words.addWord(doc, _state.value.language, r, sel.block.text, image)
            _lookup.update { it?.copy(busy = false, addedCardId = id ?: -1L) }
        }
    }

    fun addContext() {
        val l = _lookup.value ?: return
        val card = l.result?.existingCard ?: return
        val sel = _selection.value ?: return
        val doc = _state.value.doc ?: return
        _lookup.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val image = bubbleImage(sel.page, sel.block)
            words.addContext(card.id, doc, sel.block.text, l.result.sentenceTranslation, image)
            _lookup.update { it?.copy(busy = false, contextAdded = true) }
        }
    }

    // --- bulk pick ---------------------------------------------------------------------------

    fun togglePick(term: String) {
        val sel = _selection.value ?: return
        _picks.update { list ->
            val existing = list.firstOrNull { it.term == term && it.page == sel.page && it.blockId == sel.block.id }
            if (existing != null) list - existing else list + PickedWord(sel.page, sel.block.id, term, sel.block.text)
        }
    }

    fun clearPicks() { _picks.value = emptyList() }

    fun addPicks() {
        val doc = _state.value.doc ?: return
        val picks = _picks.value.distinctBy { it.term.lowercase() }
        if (picks.isEmpty() || _adding.value) return
        _adding.value = true
        viewModelScope.launch {
            val blocks = _ocr.value
            val withImages = picks.map { p ->
                val block = blocks[p.page]?.blocks?.firstOrNull { it.id == p.blockId }
                WordStudyHelper.Pick(p.term, p.sentence, block?.let { bubbleImage(p.page, it) }.orEmpty())
            }
            val report = runCatching { words.addMany(doc, _state.value.language, withImages) }.getOrNull()
            _adding.value = false
            if (report != null) {
                _picks.value = emptyList()
                _events.send(MangaEvent.Message(R.string.manga_bulk_added, report.added))
            }
        }
    }

    fun onResume() { resumedAt = System.currentTimeMillis() }

    fun onPause() {
        val started = resumedAt
        resumedAt = 0
        val p = page.value
        viewModelScope.launch(NonCancellable) {
            if (p != null) repo.savePosition(documentId, p, (p + 1f) / _state.value.pages.size.coerceAtLeast(1))
            if (started > 0) gamification.addStudyTime(System.currentTimeMillis() - started)
        }
    }
}
