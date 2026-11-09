package app.kotoba.feature.ai

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.core.ai.AiMessage
import app.kotoba.core.ai.AiPrompts
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.ai.AiRole
import app.kotoba.core.ai.LearnerProfileProvider
import app.kotoba.core.data.Achievement
import app.kotoba.core.data.AnswerInput
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.StudyRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.data.UserSettings
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ChatDao
import app.kotoba.core.db.ChatMessageEntity
import app.kotoba.core.db.ConversationEntity
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.TutorRoute
import app.kotoba.core.srs.WeakWordsRadar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** A word row in the "add words from this reply" preview. */
data class WordPick(val word: ParsedWord, val selected: Boolean = true)

sealed interface QuizState {
    data object Loading : QuizState
    data class Error(val failure: AiFailure) : QuizState
    data class Running(
        val questions: List<QuizQuestion>,
        val index: Int = 0,
        /** The learner's answer to the current question, null while unanswered. */
        val response: String? = null,
        val correct: Int = 0,
        /** Indices of questions linked to an existing card (answers update its SRS state). */
        val linked: Set<Int> = emptySet(),
    ) : QuizState {
        val current: QuizQuestion get() = questions[index]
        val finished: Boolean get() = index >= questions.size
    }
}

sealed interface TutorEvent {
    data class WordsAdded(val added: Int, val duplicates: Int) : TutorEvent
    data object StatsSharingNeeded : TutorEvent
}

data class TutorUiState(
    val configured: Boolean = true,
    val language: StudyLanguage = StudyLanguage.EN,
    val shareStats: Boolean = true,
    val conversations: List<ConversationEntity> = emptyList(),
    val current: ConversationEntity? = null,
    val messages: List<ChatMessageEntity> = emptyList(),
    val sending: Boolean = false,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class TutorViewModel @Inject constructor(
    savedState: SavedStateHandle,
    private val ai: AiRepository,
    private val chat: ChatDao,
    private val cardDao: CardDao,
    private val cards: CardRepository,
    private val study: StudyRepository,
    private val settingsRepo: SettingsRepository,
    private val gamification: GamificationRepository,
    private val profiles: LearnerProfileProvider,
    private val time: TimeProvider,
) : ViewModel() {
    private val route = runCatching { savedState.toRoute<TutorRoute>() }.getOrDefault(TutorRoute())
    private val currentId = MutableStateFlow<Long?>(savedState.get<Long>(KEY_CONVERSATION) ?: route.conversationId.takeIf { it > 0 })
    private val sending = MutableStateFlow(false)
    private val savedStateHandle = savedState

    private val _events = MutableSharedFlow<TutorEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<TutorEvent> = _events

    private val _wordPicks = MutableStateFlow<List<WordPick>?>(null)
    val wordPicks: StateFlow<List<WordPick>?> = _wordPicks.asStateFlow()

    private val _quiz = MutableStateFlow<QuizState?>(null)
    val quiz: StateFlow<QuizState?> = _quiz.asStateFlow()
    private var quizCards: Map<Int, Long> = emptyMap()
    private var questionShownAt = 0L

    private val settings = settingsRepo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, UserSettings())

    private val messages = currentId.flatMapLatest { id -> if (id == null) flowOf(emptyList()) else chat.observeMessages(id) }

    val state: StateFlow<TutorUiState> = combine(
        settingsRepo.settings,
        ai.status,
        chat.observeConversations(),
        combine(currentId, messages, sending) { id, m, s -> Triple(id, m, s) },
    ) { s, status, convs, (id, msgs, isSending) ->
        val forLang = convs.filter { it.language == s.activeLanguage.code }
        TutorUiState(
            configured = status.configured,
            language = s.activeLanguage,
            shareStats = s.ai.shareLearningStats,
            conversations = forLang,
            current = convs.firstOrNull { it.id == id },
            messages = msgs,
            sending = isSending,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), TutorUiState())

    init {
        val prompt = route.prompt
        if (!prompt.isNullOrBlank() && savedState.get<Boolean>(KEY_PROMPT_DONE) != true) {
            savedState[KEY_PROMPT_DONE] = true
            currentId.value = null
            send(prompt)
        }
    }

    fun open(id: Long?) {
        currentId.value = id
        savedStateHandle[KEY_CONVERSATION] = id
    }

    fun newConversation() = open(null)

    fun rename(c: ConversationEntity, title: String) = viewModelScope.launch {
        if (title.isNotBlank()) chat.updateConversation(c.copy(title = title.trim().take(80)))
    }

    fun delete(c: ConversationEntity) = viewModelScope.launch {
        chat.clearMessages(c.id)
        chat.deleteConversation(c)
        if (currentId.value == c.id) open(null)
    }

    fun send(text: String, mode: String = TutorSystemPrompts.MODE_CHAT, title: String? = null) {
        val message = text.trim()
        if (message.isEmpty() || sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                val s = settingsRepo.current()
                val id = currentId.value ?: chat.insertConversation(
                    ConversationEntity(
                        title = title ?: TutorSystemPrompts.titleFrom(message),
                        mode = mode,
                        language = s.activeLanguage.code,
                    ),
                ).also { open(it) }
                chat.insertMessage(ChatMessageEntity(conversationId = id, role = ROLE_USER, content = message, createdAt = time.now()))
                reply(id)
            } finally {
                sending.value = false
            }
        }
    }

    /** Starts a role-play in a fresh conversation. */
    fun startDialogue(scenario: String, opening: String) {
        open(null)
        send(opening, TutorSystemPrompts.dialogueMode(scenario), title = "🎭 $scenario")
    }

    fun retry(error: ChatMessageEntity) {
        if (sending.value) return
        sending.value = true
        viewModelScope.launch {
            try {
                chat.deleteMessage(error.id)
                reply(error.conversationId)
            } finally {
                sending.value = false
            }
        }
    }

    fun requireStats(): Boolean {
        if (settings.value.ai.shareLearningStats) return true
        _events.tryEmit(TutorEvent.StatsSharingNeeded)
        return false
    }

    fun enableStatsSharing() = viewModelScope.launch { settingsRepo.updateAi { it.copy(shareLearningStats = true) } }

    private suspend fun reply(conversationId: Long) {
        val s = settingsRepo.current()
        val conversation = chat.getConversation(conversationId) ?: return
        val lang = StudyLanguage.fromCode(conversation.language)
        val profile = if (s.ai.shareLearningStats) runCatching { profiles.build(lang) }.getOrNull() else null
        val system = TutorSystemPrompts.system(AiPrompts.tutorSystem(lang, s.nativeLanguage, profile), conversation.mode, lang, s.nativeLanguage)
        val history = chat.messages(conversationId)
            .filter { !it.isError && (it.role == ROLE_USER || it.role == ROLE_ASSISTANT) }
            .takeLast(MAX_HISTORY)
            .map { AiMessage(if (it.role == ROLE_USER) AiRole.USER else AiRole.ASSISTANT, it.content) }
            .dropWhile { it.role != AiRole.USER }
        if (history.isEmpty()) return
        ai.complete(history, system)
            .onSuccess { c ->
                chat.insertMessage(ChatMessageEntity(conversationId = conversationId, role = ROLE_ASSISTANT, content = c.text.trim(), createdAt = time.now()))
                gamification.unlock(Achievement.AI_TUTOR)
            }
            .onFailure { e ->
                chat.insertMessage(
                    ChatMessageEntity(
                        conversationId = conversationId, role = ROLE_ASSISTANT, content = AiFailure.of(e).encode(),
                        isError = true, createdAt = time.now(),
                    ),
                )
            }
        chat.updateConversation(conversation.copy(updatedAt = time.now()))
    }

    // --- Add words from a reply ---

    fun pickWords(reply: String) {
        val words = WordListParser.parse(reply)
        _wordPicks.value = words.map { WordPick(it) }
    }

    fun toggleWord(index: Int) = _wordPicks.update { list ->
        list?.mapIndexed { i, p -> if (i == index) p.copy(selected = !p.selected) else p }
    }

    fun setAllWords(selected: Boolean) = _wordPicks.update { list -> list?.map { it.copy(selected = selected) } }

    fun dismissWords() { _wordPicks.value = null }

    fun addPickedWords() {
        val picks = _wordPicks.value?.filter { it.selected }?.map { it.word }.orEmpty()
        _wordPicks.value = null
        if (picks.isEmpty()) return
        viewModelScope.launch {
            val lang = settingsRepo.current().activeLanguage
            val deckId = cards.ensureDeck(lang, TUTOR_DECK, source = CardSource.AI)
            val report = cards.addCards(picks.map { it.toCard(lang, deckId) })
            _events.emit(TutorEvent.WordsAdded(report.added, report.duplicates))
        }
    }

    // --- AI exercises ---

    fun startQuiz() {
        _quiz.value = QuizState.Loading
        viewModelScope.launch {
            val s = settingsRepo.current()
            val lang = s.activeLanguage
            val weak = cardDao.weakCards(lang.code, WeakWordsRadar.WEAK_THRESHOLD, 10)
            val targets = (weak + if (weak.size < 5) cardDao.studiedCards(lang.code, 10 - weak.size) else emptyList()).distinctBy { it.id }
            val level = targets.groupingBy { it.level }.eachCount().filterKeys { it.isNotBlank() }.maxByOrNull { it.value }?.key
            ai.completeJson(QuizBuilder.prompt(lang, s.nativeLanguage, targets.map { it.term }, level), QuizDto.serializer(), temperature = 0.5)
                .onSuccess { dto ->
                    if (_quiz.value != QuizState.Loading) return@onSuccess // closed while loading
                    val questions = QuizBuilder.sanitize(dto)
                    if (questions.isEmpty()) {
                        _quiz.value = QuizState.Error(AiFailure(app.kotoba.core.ai.AiErrorKind.BAD_RESPONSE))
                        return@onSuccess
                    }
                    val byTerm = targets.associateBy { it.term.lowercase() }
                    quizCards = questions.mapIndexedNotNull { i, q ->
                        val key = q.term.trim().lowercase()
                        if (key.isEmpty()) return@mapIndexedNotNull null
                        val card = byTerm[key] ?: cardDao.findByTerm(lang.code, key)
                        card?.let { i to it.id }
                    }.toMap()
                    questionShownAt = time.now()
                    _quiz.value = QuizState.Running(questions, linked = quizCards.keys)
                    gamification.unlock(Achievement.AI_TUTOR)
                }
                .onFailure { if (_quiz.value == QuizState.Loading) _quiz.value = QuizState.Error(AiFailure.of(it)) }
        }
    }

    fun answerQuiz(response: String) {
        val q = _quiz.value as? QuizState.Running ?: return
        if (q.response != null || q.finished) return
        val correct = q.current.isCorrect(response)
        _quiz.value = q.copy(response = response, correct = q.correct + if (correct) 1 else 0)
        val cardId = quizCards[q.index] ?: return
        val elapsed = time.now() - questionShownAt
        viewModelScope.launch {
            runCatching {
                study.recordAnswer(
                    AnswerInput(cardId, if (correct) Rating.GOOD else Rating.AGAIN, q.current.kind.exercise, elapsed, correct = correct),
                )
            }
        }
    }

    fun nextQuestion() {
        val q = _quiz.value as? QuizState.Running ?: return
        questionShownAt = time.now()
        _quiz.value = q.copy(index = q.index + 1, response = null)
    }

    fun closeQuiz() { _quiz.value = null }

    companion object {
        const val ROLE_USER = "user"
        const val ROLE_ASSISTANT = "assistant"
        const val TUTOR_DECK = "🤖 AI Tutor"
        private const val MAX_HISTORY = 30
        private const val KEY_CONVERSATION = "tutor_conversation"
        private const val KEY_PROMPT_DONE = "tutor_prompt_done"
    }
}

private val ipaChars = "ˈˌəɪʊæɑɔʃʒθðŋːɜʌɒɛ".toSet()

/** Maps a parsed reply line to a card; a parenthesised note on non-reading languages is kept as IPA only if it looks like IPA. */
internal fun ParsedWord.toCard(lang: StudyLanguage, deckId: Long): CardEntity {
    val readingIsIpa = !lang.hasReading && reading.any { it in ipaChars }
    return CardEntity(
        deckId = deckId,
        language = lang.code,
        term = term,
        reading = if (lang.hasReading) reading else "",
        ipa = ipa.ifBlank { if (readingIsIpa) reading else "" },
        notes = if (!lang.hasReading && !readingIsIpa) reading else "",
        translation = translation,
        source = CardSource.AI,
        sourceTitle = "AI Tutor",
    )
}
