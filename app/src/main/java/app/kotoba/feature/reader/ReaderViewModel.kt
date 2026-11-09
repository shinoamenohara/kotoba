package app.kotoba.feature.reader

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.core.data.Achievement
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.db.BookmarkDao
import app.kotoba.core.db.BookmarkEntity
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.ContextDao
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.docs.DocumentRepository
import app.kotoba.core.docs.TextParagraph
import app.kotoba.core.lang.Romanizer
import app.kotoba.core.lang.TextSegmenter
import app.kotoba.core.lang.Token
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.ReaderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class KnownWord(val mastery: Mastery, val reading: String)

data class ReaderUiState(
    val doc: DocumentEntity? = null,
    val paragraphs: List<TextParagraph> = emptyList(),
    val language: StudyLanguage = StudyLanguage.EN,
    val initialParagraph: Int = 0,
    val loading: Boolean = true,
    val error: Boolean = false,
)

data class BookStats(
    val totalWords: Int,
    val uniqueWords: Int,
    val knownUnique: Int,
    val masteredUnique: Int,
) {
    val knownPercent: Int get() = if (uniqueWords == 0) 0 else knownUnique * 100 / uniqueWords
}

/** Pending phrase selection: first word picked, waiting for the last one. */
data class PhraseAnchor(val start: Int, val end: Int)

sealed interface ReaderEvent {
    data object AchievementUnlocked : ReaderEvent
    data class Message(val res: Int) : ReaderEvent
}

@OptIn(FlowPreview::class)
@HiltViewModel
class ReaderViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val repo: DocumentRepository,
    private val segmenter: TextSegmenter,
    private val romanizer: Romanizer,
    private val words: WordStudyHelper,
    private val gamification: GamificationRepository,
    private val bookmarkDao: BookmarkDao,
    cardDao: CardDao,
    contextDao: ContextDao,
    private val prefsStore: ReaderPrefsStore,
) : ViewModel() {
    private val documentId = savedState.toRoute<ReaderRoute>().documentId

    private val _state = MutableStateFlow(ReaderUiState())
    val state: StateFlow<ReaderUiState> = _state
    private var fullText = ""

    val prefs: StateFlow<ReaderPrefs> = prefsStore.prefs

    /** Library words of the document language, keyed by [key]. */
    val known: StateFlow<Map<String, KnownWord>> = _state.map { it.doc?.language }.filterNotNull().distinctUntilChanged()
        .flatMapLatest { code ->
            cardDao.search(code).map { cards ->
                val lang = StudyLanguage.fromCode(code)
                cards.associate { key(it.term.trim(), lang) to KnownWord(it.mastery, it.reading) }
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    val bookmarks: StateFlow<List<BookmarkEntity>> = bookmarkDao.observeForDocument(documentId)
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val addedFromBook: StateFlow<Int> = contextDao.observeCardIdsForDocument(documentId).map { it.size }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _lookup = MutableStateFlow<LookupState?>(null)
    val lookup: StateFlow<LookupState?> = _lookup

    private val _phraseMode = MutableStateFlow(false)
    val phraseMode: StateFlow<Boolean> = _phraseMode
    private val _anchor = MutableStateFlow<PhraseAnchor?>(null)
    val anchor: StateFlow<PhraseAnchor?> = _anchor

    private val _stats = MutableStateFlow<BookStats?>(null)
    val stats: StateFlow<BookStats?> = _stats

    private val _events = Channel<ReaderEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    private val tokenCache = object : LinkedHashMap<Int, List<Token>>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Int, List<Token>>?) = size > 300
    }
    private val readingCache = HashMap<String, String?>()

    private val position = MutableStateFlow<Pair<Int, Float>?>(null)
    private var resumedAt = 0L
    private var lookupJob: Job? = null

    init {
        viewModelScope.launch {
            val doc = repo.get(documentId)
            if (doc == null) { _state.update { it.copy(loading = false, error = true) }; return@launch }
            fullText = repo.readText(doc)
            val paragraphs = withContext(Dispatchers.Default) { app.kotoba.core.docs.DocumentParser.paragraphs(fullText) }
            _state.value = ReaderUiState(
                doc = doc,
                paragraphs = paragraphs,
                language = StudyLanguage.fromCode(doc.language),
                initialParagraph = paragraphIndexFor(paragraphs, doc.position),
                loading = false,
            )
            repo.savePosition(doc.id, doc.position, doc.progress)
            if (gamification.unlock(Achievement.FIRST_BOOK)) _events.send(ReaderEvent.AchievementUnlocked)
        }
        viewModelScope.launch {
            position.filterNotNull().debounce(700).collect { (pos, progress) -> repo.savePosition(documentId, pos, progress) }
        }
    }

    fun key(term: String, lang: StudyLanguage = _state.value.language): String =
        if (lang.usesSpaces) term.lowercase(lang.locale) else term

    fun tokens(p: TextParagraph): List<Token> = synchronized(tokenCache) {
        tokenCache.getOrPut(p.index) { segmenter.tokenize(p.text, _state.value.language) }
    }

    /** Ruby text for a token: library reading, else pinyin/romanization where ICU can do it. */
    fun reading(token: Token): String? {
        if (!token.isWord) return null
        val lang = _state.value.language
        known.value[key(token.text, lang)]?.reading?.takeIf { it.isNotBlank() && it != token.text }?.let { return it }
        if (lang == StudyLanguage.JA || !Romanizer.containsCjk(token.text) && lang != StudyLanguage.KO) return null
        return readingCache.getOrPut(token.text) { romanizer.romanize(token.text, lang)?.takeIf { it != token.text } }
    }

    fun paragraphIndexFor(offset: Int): Int = paragraphIndexFor(_state.value.paragraphs, offset)

    private fun paragraphIndexFor(paragraphs: List<TextParagraph>, offset: Int): Int {
        if (paragraphs.isEmpty()) return 0
        var lo = 0; var hi = paragraphs.lastIndex
        while (lo < hi) {
            val mid = (lo + hi + 1) / 2
            if (paragraphs[mid].start <= offset) lo = mid else hi = mid - 1
        }
        return lo
    }

    fun onVisible(firstIndex: Int, atEnd: Boolean) {
        val ps = _state.value.paragraphs
        val p = ps.getOrNull(firstIndex) ?: return
        val length = fullText.length.coerceAtLeast(1)
        position.value = p.start to if (atEnd) 1f else p.start.toFloat() / length
    }

    // --- word lookup -------------------------------------------------------------------------

    fun onWordTap(p: TextParagraph, token: Token) {
        val start = p.start + token.start
        val end = p.start + token.end
        if (_phraseMode.value) {
            val a = _anchor.value
            if (a == null) { _anchor.value = PhraseAnchor(start, end); return }
            val s = minOf(a.start, start); val e = maxOf(a.end, end)
            _anchor.value = null
            _phraseMode.value = false
            openLookup(fullText.substring(s, e).replace(Regex("\\s+"), " ").trim(), s, e)
        } else {
            openLookup(token.text, start, end)
        }
    }

    /** Long-press starts a phrase at this word. */
    fun onWordLongPress(p: TextParagraph, token: Token) {
        _phraseMode.value = true
        _anchor.value = PhraseAnchor(p.start + token.start, p.start + token.end)
    }

    fun togglePhraseMode() {
        _phraseMode.update { !it }
        _anchor.value = null
    }

    private fun openLookup(term: String, start: Int, end: Int) {
        val lang = _state.value.language
        val p = _state.value.paragraphs.getOrNull(paragraphIndexFor(start))
        val sentence = p?.let { segmenter.sentenceAt(it.text, (start - it.start).coerceIn(0, it.text.length - 1), lang) }.orEmpty()
        _lookup.value = LookupState(term = term, sentence = sentence, start = start, end = end)
        lookupJob?.cancel()
        lookupJob = viewModelScope.launch {
            val r = runCatching { words.lookup(term, lang, sentence) }.getOrNull()
            _lookup.update { it?.takeIf { s -> s.start == start && s.end == end }?.copy(loading = false, result = r) ?: it }
        }
    }

    fun dismissLookup() { lookupJob?.cancel(); _lookup.value = null }

    fun speak(text: String) = viewModelScope.launch { words.speak(text, _state.value.language) }

    fun addWord() {
        val l = _lookup.value ?: return
        val doc = _state.value.doc ?: return
        val r = l.result ?: return
        _lookup.update { it?.copy(busy = true) }
        viewModelScope.launch {
            val id = words.addWord(doc, _state.value.language, r, l.sentence)
            _lookup.update { it?.copy(busy = false, addedCardId = id ?: -1L) }
        }
    }

    fun addContext() {
        val l = _lookup.value ?: return
        val doc = _state.value.doc ?: return
        val card = l.result?.existingCard ?: return
        _lookup.update { it?.copy(busy = true) }
        viewModelScope.launch {
            words.addContext(card.id, doc, l.sentence, l.result.sentenceTranslation)
            _lookup.update { it?.copy(busy = false, contextAdded = true) }
        }
    }

    // --- bookmarks, highlights, notes --------------------------------------------------------

    fun addBookmark(paragraphIndex: Int) = viewModelScope.launch {
        val p = _state.value.paragraphs.getOrNull(paragraphIndex) ?: return@launch
        bookmarkDao.insert(BookmarkEntity(documentId = documentId, position = p.start, quote = p.text.take(160)))
        _events.send(ReaderEvent.Message(app.kotoba.R.string.reader_bookmark_added))
    }

    fun addHighlight(start: Int, end: Int, note: String = "") = viewModelScope.launch {
        if (end <= start || end > fullText.length) return@launch
        bookmarkDao.insert(
            BookmarkEntity(
                documentId = documentId, position = start, length = end - start,
                quote = fullText.substring(start, end).take(500), note = note, isHighlight = true,
            ),
        )
        _events.send(ReaderEvent.Message(if (note.isBlank()) app.kotoba.R.string.reader_highlight_added else app.kotoba.R.string.reader_note_added))
    }

    fun updateNote(b: BookmarkEntity, note: String) = viewModelScope.launch { bookmarkDao.update(b.copy(note = note)) }
    fun deleteBookmark(b: BookmarkEntity) = viewModelScope.launch { bookmarkDao.delete(b) }

    // --- settings, stats, time ---------------------------------------------------------------

    fun updatePrefs(transform: (ReaderPrefs) -> ReaderPrefs) = prefsStore.update(transform)

    fun computeStats() {
        if (_stats.value != null) return
        viewModelScope.launch {
            val lang = _state.value.language
            val freq = withContext(Dispatchers.Default) { segmenter.wordFrequencies(fullText, lang) }
            val k = known.value
            val knownWords = freq.keys.mapNotNull { k[key(it, lang)] }
            _stats.value = BookStats(
                totalWords = freq.values.sum(),
                uniqueWords = freq.size,
                knownUnique = knownWords.size,
                masteredUnique = knownWords.count { it.mastery == Mastery.MASTERED },
            )
        }
    }

    fun onResume() { resumedAt = System.currentTimeMillis() }

    fun onPause() {
        val started = resumedAt
        resumedAt = 0
        val pending = position.value
        viewModelScope.launch(NonCancellable) {
            pending?.let { repo.savePosition(documentId, it.first, it.second) }
            if (started > 0) gamification.addStudyTime(System.currentTimeMillis() - started)
        }
    }
}
