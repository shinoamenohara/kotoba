package app.kotoba.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.core.IncomingContent
import app.kotoba.core.IncomingContentBus
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DocumentDao
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.lang.TextSegmenter
import app.kotoba.core.lang.TranslatorService
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.GeneratorRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.ceil

enum class SourceKind { DOCUMENT, PASTE, SHARED }
enum class GroupBy { TOPIC, DIFFICULTY }

enum class GenStage { SEGMENTING, AI, TRANSLATING }

sealed interface GeneratorStep {
    data object Setup : GeneratorStep
    data class Working(val stage: GenStage, val done: Int = 0, val total: Int = 0) : GeneratorStep
    data class Review(
        val words: List<GeneratedWord>,
        val groupBy: GroupBy = GroupBy.TOPIC,
        val usedAi: Boolean,
        /** Set when AI failed and the list came from the offline fallback (or was cut short). */
        val warning: AiFailure? = null,
        val saving: Boolean = false,
    ) : GeneratorStep
    data class Done(val deckId: Long, val deckName: String, val added: Int, val duplicates: Int) : GeneratorStep
}

data class GeneratorUiState(
    val language: StudyLanguage = StudyLanguage.EN,
    val aiConfigured: Boolean = false,
    val source: SourceKind = SourceKind.PASTE,
    val documents: List<DocumentEntity> = emptyList(),
    val document: DocumentEntity? = null,
    val pasted: String = "",
    val shared: String = "",
    val options: GeneratorOptions = GeneratorOptions(),
    val step: GeneratorStep = GeneratorStep.Setup,
    val error: GeneratorError? = null,
) {
    val hasText: Boolean
        get() = when (source) {
            SourceKind.DOCUMENT -> document != null
            SourceKind.PASTE -> pasted.isNotBlank()
            SourceKind.SHARED -> shared.isNotBlank()
        }
}

enum class GeneratorError { NO_TEXT, NO_WORDS, READ_FAILED }

@HiltViewModel
class GeneratorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val ai: AiRepository,
    private val documentsDao: DocumentDao,
    private val cardDao: CardDao,
    private val cards: CardRepository,
    private val settings: SettingsRepository,
    private val segmenter: TextSegmenter,
    private val translator: TranslatorService,
    incoming: IncomingContentBus,
) : ViewModel() {
    private val route = runCatching { savedState.toRoute<GeneratorRoute>() }.getOrDefault(GeneratorRoute())
    private val _state = MutableStateFlow(GeneratorUiState())
    val state: StateFlow<GeneratorUiState> = _state.asStateFlow()
    private var job: Job? = null

    init {
        incoming.consume<IncomingContent.Text>()?.let { shared ->
            val text = listOfNotNull(shared.subject?.takeIf { it.isNotBlank() }, shared.text).joinToString("\n\n")
            _state.update { it.copy(source = SourceKind.SHARED, shared = text) }
        }
        viewModelScope.launch {
            val s = settings.current()
            _state.update { it.copy(language = s.activeLanguage, aiConfigured = ai.isConfigured(s.ai)) }
        }
        viewModelScope.launch {
            ai.status.collect { st -> _state.update { it.copy(aiConfigured = st.configured) } }
        }
        viewModelScope.launch {
            documentsDao.observeByKinds(listOf(DocumentKind.BOOK, DocumentKind.ARTICLE, DocumentKind.NOTEBOOKLM, DocumentKind.NOTE)).collect { docs ->
                val withText = docs.filter { it.textPath.isNotBlank() }
                _state.update { st ->
                    val preselect = if (st.document == null && route.documentId > 0) withText.firstOrNull { it.id == route.documentId } else null
                    st.copy(
                        documents = withText,
                        document = preselect ?: st.document,
                        source = if (preselect != null) SourceKind.DOCUMENT else st.source,
                        options = if (preselect != null && st.options.deckName.isBlank()) st.options.copy(deckName = "📖 ${preselect.title}") else st.options,
                    )
                }
            }
        }
    }

    fun setSource(kind: SourceKind) = _state.update { it.copy(source = kind, error = null) }
    fun setPasted(text: String) = _state.update { it.copy(pasted = text, error = null) }
    fun selectDocument(doc: DocumentEntity) = _state.update { st ->
        val autoName = st.options.deckName.isBlank() || st.options.deckName.startsWith("📖 ")
        st.copy(
            document = doc,
            source = SourceKind.DOCUMENT,
            error = null,
            options = if (autoName) st.options.copy(deckName = "📖 ${doc.title}") else st.options,
        )
    }

    fun updateOptions(transform: (GeneratorOptions) -> GeneratorOptions) = _state.update { it.copy(options = transform(it.options)) }

    fun togglePos(pos: PartOfSpeech) = updateOptions { o ->
        o.copy(partsOfSpeech = if (pos in o.partsOfSpeech) o.partsOfSpeech - pos else o.partsOfSpeech + pos)
    }

    fun backToSetup() {
        job?.cancel()
        _state.update { it.copy(step = GeneratorStep.Setup) }
    }

    fun generate() {
        val st = _state.value
        if (!st.hasText) { _state.update { it.copy(error = GeneratorError.NO_TEXT) }; return }
        job?.cancel()
        _state.update { it.copy(step = GeneratorStep.Working(GenStage.SEGMENTING), error = null) }
        job = viewModelScope.launch {
            val s = settings.current()
            val lang = s.activeLanguage
            val native = s.nativeLanguage
            val opts = st.options
            val text = withContext(Dispatchers.IO) { runCatching { sourceText(st) }.getOrNull() }
            if (text.isNullOrBlank()) {
                _state.update { it.copy(step = GeneratorStep.Setup, error = GeneratorError.READ_FAILED) }
                return@launch
            }
            val known = withContext(Dispatchers.IO) { cardDao.allTermsLower(lang.code).toHashSet() }
            val useAi = ai.isConfigured(s.ai)
            val candidates = withContext(Dispatchers.Default) {
                val tokens = segmenter.tokenize(text, lang)
                val ranges = segmenter.sentences(text, lang)
                CandidateExtractor.extract(
                    tokens, lang, known, opts.excludeKnown, opts.frequency,
                    limit = if (useAi) (opts.count * 3).coerceIn(30, MAX_AI_CANDIDATES) else opts.count,
                ) { offset -> CandidateExtractor.sentenceFor(text, ranges, offset) }
            }
            if (candidates.isEmpty()) {
                _state.update { it.copy(step = GeneratorStep.Setup, error = GeneratorError.NO_WORDS) }
                return@launch
            }

            var warning: AiFailure? = null
            var words: List<GeneratedWord> = emptyList()
            if (useAi) {
                val (aiWords, failure) = generateWithAi(candidates, lang, native, opts, known)
                words = aiWords
                warning = failure
            }
            val usedAi = words.isNotEmpty()
            if (!usedAi) words = translateOffline(candidates.take(opts.count), lang, native, opts.generateExamples)
            _state.update {
                it.copy(step = GeneratorStep.Review(words.take(opts.count), usedAi = usedAi, warning = warning))
            }
        }
    }

    private suspend fun generateWithAi(
        candidates: List<Candidate>,
        lang: StudyLanguage,
        native: NativeLanguage,
        opts: GeneratorOptions,
        known: Set<String>,
    ): Pair<List<GeneratedWord>, AiFailure?> {
        val chunks = GeneratorPrompts.chunks(candidates)
        val out = ArrayList<GeneratedWord>()
        val seen = HashSet<String>()
        chunks.forEachIndexed { i, chunk ->
            if (out.size >= opts.count) return out to null
            _state.update { it.copy(step = GeneratorStep.Working(GenStage.AI, i, chunks.size)) }
            val remaining = opts.count - out.size
            val wanted = ceil(remaining.toDouble() / (chunks.size - i)).toInt().coerceAtLeast(5).coerceAtMost(remaining + 5)
            val prompt = GeneratorPrompts.prompt(lang, native, opts, chunk, GeneratorPrompts.excerpt(chunk), wanted)
            val maxTokens = (wanted * (if (opts.generateExamples) 160 else 80) + 400).coerceIn(1024, 8192)
            val result = ai.completeJson(prompt, GeneratedWordsDto.serializer(), temperature = 0.3, maxTokens = maxTokens)
            val dto = result.getOrElse { return out to AiFailure.of(it) }
            GeneratorPrompts.toWords(dto.words, chunk, lang, known, opts.excludeKnown, opts.partsOfSpeech, startId = out.size)
                .filter { seen.add(it.term.lowercase()) }
                .forEach { out += it.copy(id = out.size) }
        }
        return out to null
    }

    private suspend fun translateOffline(candidates: List<Candidate>, lang: StudyLanguage, native: NativeLanguage, examples: Boolean): List<GeneratedWord> {
        val translations = HashMap<String, String>()
        if (translator.supports(lang.code) && translator.supports(native.code)) {
            for ((i, c) in candidates.withIndex()) {
                _state.update { it.copy(step = GeneratorStep.Working(GenStage.TRANSLATING, i, candidates.size)) }
                val r = translator.translate(c.term, lang.code, native.code)
                // The first call downloads the model; if that fails, the rest would fail too.
                if (r.isFailure && i == 0) break
                r.getOrNull()?.takeIf { !it.equals(c.term, ignoreCase = true) }?.let { translations[c.term] = it }
            }
        }
        return GeneratorPrompts.offlineWords(candidates, translations, examples)
    }

    private suspend fun sourceText(st: GeneratorUiState): String = when (st.source) {
        SourceKind.PASTE -> st.pasted
        SourceKind.SHARED -> st.shared
        SourceKind.DOCUMENT -> {
            val doc = st.document ?: documentsDao.get(route.documentId)
            val file = doc?.textPath?.takeIf { it.isNotBlank() }?.let(::File)
            if (file == null || !file.exists()) "" else file.bufferedReader().use { r ->
                val buf = CharArray(CandidateExtractor.MAX_TEXT_CHARS)
                val n = r.read(buf)
                if (n <= 0) "" else String(buf, 0, n)
            }
        }
    }.take(CandidateExtractor.MAX_TEXT_CHARS)

    // --- Review ---

    private fun updateReview(transform: (GeneratorStep.Review) -> GeneratorStep.Review) = _state.update { st ->
        val r = st.step as? GeneratorStep.Review ?: return@update st
        st.copy(step = transform(r))
    }

    fun toggle(id: Int) = updateReview { r -> r.copy(words = r.words.map { if (it.id == id) it.copy(selected = !it.selected) else it }) }
    fun selectAll(selected: Boolean) = updateReview { r -> r.copy(words = r.words.map { it.copy(selected = selected) }) }
    fun selectGroup(ids: Set<Int>, selected: Boolean) = updateReview { r ->
        r.copy(words = r.words.map { if (it.id in ids) it.copy(selected = selected) else it })
    }
    fun setGroupBy(g: GroupBy) = updateReview { it.copy(groupBy = g) }
    fun edit(word: GeneratedWord) = updateReview { r -> r.copy(words = r.words.map { if (it.id == word.id) word else it }) }

    fun save() {
        val st = _state.value
        val review = st.step as? GeneratorStep.Review ?: return
        val picked = review.words.filter { it.selected && it.term.isNotBlank() }
        if (picked.isEmpty() || review.saving) return
        updateReview { it.copy(saving = true) }
        viewModelScope.launch {
            val lang = settings.current().activeLanguage
            val doc = st.document.takeIf { st.source == SourceKind.DOCUMENT }
            val name = st.options.deckName.trim().ifBlank { doc?.let { "📖 ${it.title}" } ?: DEFAULT_DECK }
            val deckId = cards.ensureDeck(
                lang, name,
                source = doc?.let { "${CardSource.BOOK}:${it.id}" } ?: CardSource.AI,
                description = doc?.title.orEmpty(),
            )
            val sourceTitle = doc?.title.orEmpty()
            val newCards = picked.map { w ->
                CardEntity(
                    deckId = deckId,
                    language = lang.code,
                    term = w.term.trim(),
                    reading = w.reading,
                    ipa = w.ipa,
                    translation = w.translation.trim(),
                    example = w.example,
                    exampleTranslation = w.exampleTranslation,
                    partOfSpeech = w.partOfSpeech,
                    level = w.level,
                    category = w.category.key,
                    source = CardSource.AI,
                    sourceTitle = sourceTitle,
                )
            }
            val contexts = picked.mapIndexedNotNull { i, w ->
                val list = buildList {
                    if (w.sourceSentence.isNotBlank()) {
                        add(
                            ContextEntity(
                                cardId = 0, sentence = w.sourceSentence,
                                translation = if (w.example.trim() == w.sourceSentence.trim()) w.exampleTranslation else "",
                                source = if (doc != null) ContextSource.BOOK else ContextSource.USER,
                                sourceTitle = sourceTitle, documentId = doc?.id,
                            ),
                        )
                    }
                    if (w.example.isNotBlank() && w.example.trim() != w.sourceSentence.trim()) {
                        add(ContextEntity(cardId = 0, sentence = w.example, translation = w.exampleTranslation, source = ContextSource.AI))
                    }
                }
                if (list.isEmpty()) null else i to list
            }.toMap()
            val report = cards.addCards(newCards, contexts, skipDuplicates = st.options.excludeDuplicates)
            _state.update { it.copy(step = GeneratorStep.Done(deckId, name, report.added, report.duplicates)) }
        }
    }

    companion object {
        private const val MAX_AI_CANDIDATES = 240
        const val DEFAULT_DECK = "✨ Generated deck"
    }
}
