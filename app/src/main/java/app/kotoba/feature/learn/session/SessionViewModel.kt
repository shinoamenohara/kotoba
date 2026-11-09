package app.kotoba.feature.learn.session

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import app.kotoba.core.ai.AiPrompts
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.data.AnswerInput
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.QueueRequest
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.StudyRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextDao
import app.kotoba.core.lang.SpeechService
import app.kotoba.core.lang.TextSegmenter
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.SessionMode
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.feature.learn.logic.AnswerMatcher
import app.kotoba.feature.learn.logic.CardTraits
import app.kotoba.feature.learn.logic.Cloze
import app.kotoba.feature.learn.logic.ExercisePlanner
import app.kotoba.feature.learn.logic.RatingPolicy
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import javax.inject.Inject

private const val QUEUE_LIMIT = 30
private const val QUICK_LIMIT = 200
private const val MAX_REQUEUE = 2
private const val REQUEUE_GAP = 3

private object Keys {
    const val STEPS = "steps"
    const val INDEX = "index"
    const val ANSWERED = "answered"
    const val CORRECT = "correct"
    const val XP = "xp"
    const val COMBO = "combo"
    const val BEST = "best"
    const val STARTED = "started"
    const val QUICK_LEFT = "quickLeft"
    const val GOAL = "goal"
}

@HiltViewModel
class SessionViewModel @Inject constructor(
    private val handle: SavedStateHandle,
    private val study: StudyRepository,
    private val settingsRepo: SettingsRepository,
    private val cardRepo: CardRepository,
    private val cardDao: CardDao,
    private val contextDao: ContextDao,
    private val segmenter: TextSegmenter,
    private val speech: SpeechService,
    private val ai: AiRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val route = handle.toRoute<SessionRoute>()
    val mode: SessionMode = runCatching { SessionMode.valueOf(route.mode) }.getOrDefault(SessionMode.DAILY)
    private val requested: ExerciseType? = route.exercise?.let { runCatching { ExerciseType.valueOf(it) }.getOrNull() }

    private val _state = MutableStateFlow(SessionUiState(mode = mode, requestedExercise = requested))
    val state: StateFlow<SessionUiState> = _state.asStateFlow()

    private var steps = mutableListOf<SessionStep>()
    private val cards = HashMap<Long, CardEntity>()
    private val sentences = HashMap<Long, SentenceInfo?>()
    private val requeued = HashMap<Long, Int>()
    private val changes = LinkedHashMap<Long, MasteryChange>()
    private val writeLock = Mutex()
    private var lang = StudyLanguage.EN
    private var startedAt = 0L
    private var timerJob: Job? = null
    private var burstId = 0L
    private var matchingLastAt = 0L
    private var matchingRecorded = mutableSetOf<Long>()

    init {
        viewModelScope.launch { load(restore = true) }
        viewModelScope.launch { ai.status.collect { s -> _state.update { it.copy(aiAvailable = s.configured) } } }
    }

    // ---------------------------------------------------------------- loading

    private suspend fun load(restore: Boolean) {
        _state.update { SessionUiState(mode = mode, requestedExercise = requested, aiAvailable = it.aiAvailable, loading = true) }
        val s = settingsRepo.current()
        lang = s.activeLanguage
        cards.clear(); sentences.clear(); requeued.clear(); changes.clear()

        val saved = if (restore) handle.get<ArrayList<String>>(Keys.STEPS) else null
        if (saved != null) {
            steps = saved.mapNotNull { SessionStep.decode(it) }.toMutableList()
            cardDao.getMany(steps.flatMap { it.cardIds }.distinct()).forEach { cards[it.id] = it }
            steps.removeAll { st -> st.cardIds.any { it !in cards } }
            startedAt = handle[Keys.STARTED] ?: time.now()
            _state.update {
                it.copy(
                    answered = handle[Keys.ANSWERED] ?: 0, correct = handle[Keys.CORRECT] ?: 0, xp = handle[Keys.XP] ?: 0,
                    combo = handle[Keys.COMBO] ?: 0, bestCombo = handle[Keys.BEST] ?: 0, goalReached = handle[Keys.GOAL] ?: false,
                )
            }
        } else {
            steps = buildSteps(s.quickReviewMinutes).toMutableList()
            startedAt = time.now()
            listOf(Keys.INDEX, Keys.ANSWERED, Keys.CORRECT, Keys.XP, Keys.COMBO, Keys.BEST, Keys.QUICK_LEFT, Keys.GOAL)
                .forEach { handle.remove<Any>(it) }
            handle[Keys.STARTED] = startedAt
            persistSteps()
        }

        if (steps.isEmpty()) {
            _state.update { it.copy(loading = false, empty = true, language = lang) }
            return
        }
        if (mode == SessionMode.QUICK) {
            val total = (route.minutes.takeIf { it > 0 } ?: s.quickReviewMinutes).coerceIn(1, 30) * 60_000L
            val left = handle[Keys.QUICK_LEFT] ?: total
            _state.update { it.copy(quickTotalMs = total, quickRemainingMs = left) }
        }
        val index = (handle[Keys.INDEX] ?: 0).coerceAtMost(steps.size)
        _state.update { it.copy(loading = false, language = lang) }
        showStep(index)
        if (mode == SessionMode.QUICK) startTimer()
    }

    private suspend fun buildSteps(quickMinutes: Int): List<SessionStep> {
        val deckId = route.deckId.takeIf { it > 0 }
        val limit = if (mode == SessionMode.QUICK) QUICK_LIMIT else QUEUE_LIMIT
        var queue = study.buildQueue(QueueRequest(ExercisePlanner.queueModeFor(mode), lang, deckId, route.category, limit))
        val fallback = ExercisePlanner.fallbackQueueMode(mode)
        if (fallback != null && queue.size < ExercisePlanner.minQueue(mode, requested)) {
            val more = study.buildQueue(QueueRequest(fallback, lang, deckId, route.category, limit))
            queue = (queue + more).distinctBy { it.id }.take(limit)
        }
        if (requested != null && mode == SessionMode.EXERCISE && requested != ExerciseType.MATCHING) {
            // Exercises that need a sentence go first to cards that have one.
            val hasSentence = queue.associate { c -> c.id to (sentenceFor(c) != null) }
            queue = queue.sortedByDescending { c -> hasSentence[c.id] == true }
        }
        queue.forEach { cards[it.id] = it }
        val canOptions = cardDao.countAllNow(lang.code) >= 3

        if (mode == SessionMode.EXERCISE && requested == ExerciseType.MATCHING) {
            val chunks = queue.chunked(ExercisePlanner.MATCHING_GROUP)
            return chunks.flatMap { chunk ->
                if (chunk.size >= ExercisePlanner.MATCHING_MIN) listOf(SessionStep(chunk.map { it.id }, ExerciseType.MATCHING))
                else chunk.map { c -> SessionStep(listOf(c.id), ExercisePlanner.resolve(ExerciseType.MULTIPLE_CHOICE, traits(c, canOptions))) }
            }
        }
        // Quick review never needs sentences; skip the per-card lookup and tokenizing for up to 200 cards.
        if (mode == SessionMode.QUICK) return queue.map { SessionStep(listOf(it.id), ExerciseType.QUICK_REVIEW) }
        return queue.mapIndexed { i, c -> SessionStep(listOf(c.id), ExercisePlanner.exerciseFor(mode, requested, traits(c, canOptions), i + c.reps)) }
    }

    private suspend fun traits(c: CardEntity, canOptions: Boolean): CardTraits {
        val sentence = sentenceFor(c)
        return CardTraits(
            mastery = c.mastery,
            hasSentence = sentence != null,
            hasCloze = sentence?.cloze != null,
            sentenceTokens = sentence?.tokens?.size ?: 0,
            termLength = c.term.count { !it.isWhitespace() },
            canOptions = canOptions,
        )
    }

    /** Best example sentence: one where the word can be located, else any. */
    private suspend fun sentenceFor(c: CardEntity): SentenceInfo? {
        if (sentences.containsKey(c.id)) return sentences[c.id]
        val lang = StudyLanguage.fromCode(c.language)
        val candidates = buildList {
            if (c.example.isNotBlank()) add(Triple(c.example, c.exampleTranslation, ""))
            contextDao.forCard(c.id).forEach { add(Triple(it.sentence, it.translation, it.sourceTitle)) }
        }
        val located = candidates.firstNotNullOfOrNull { (text, tr, src) ->
            Cloze.find(text, c.term, c.forms, lang)?.let { SentenceInfo(text, tr, src, it, tokens(text, lang)) }
        }
        val info = located ?: candidates.firstOrNull()?.let { (text, tr, src) -> SentenceInfo(text, tr, src, null, tokens(text, lang)) }
        sentences[c.id] = info
        return info
    }

    private fun tokens(text: String, lang: StudyLanguage): List<String> =
        segmenter.tokenize(text.trim(), lang).filter { it.isWord }.map { it.text.trim() }.filter { it.isNotEmpty() }

    private suspend fun showStep(index: Int) {
        if (index >= steps.size) {
            finish()
            return
        }
        val st = steps[index]
        val prepared = withContext(kotlinx.coroutines.Dispatchers.Default) { prepare(index, st) }
        if (prepared == null) {
            // Card was deleted mid-session.
            steps.removeAt(index)
            persistSteps()
            showStep(index)
            return
        }
        matchingLastAt = time.now()
        matchingRecorded = mutableSetOf()
        _state.update {
            it.copy(
                index = index, stepCount = steps.size, step = prepared, feedback = null,
                translationGrade = TranslationGradeUi(), matchedIds = emptySet(),
            )
        }
    }

    private suspend fun prepare(index: Int, st: SessionStep): PreparedStep? {
        val group = st.cardIds.mapNotNull { cards[it] ?: cardDao.get(it)?.also { c -> cards[c.id] = c } }
        val card = group.firstOrNull() ?: return null
        val needsOptions = st.exercise in setOf(
            ExerciseType.MULTIPLE_CHOICE, ExerciseType.REVERSE_MULTIPLE_CHOICE, ExerciseType.LISTENING,
            ExerciseType.FILL_BLANK, ExerciseType.CONTEXT_QUIZ,
        )
        val options = if (needsOptions) (study.distractors(card, 3) + card).shuffled() else emptyList()
        val sentence = sentenceFor(card)
        val langOfCard = StudyLanguage.fromCode(card.language)
        return PreparedStep(
            index = index,
            exercise = st.exercise,
            card = card,
            group = if (st.exercise == ExerciseType.MATCHING) group else emptyList(),
            options = options,
            sentence = sentence,
            contexts = if (ExercisePlanner.isFlashcard(st.exercise)) contextDao.forCard(card.id) else emptyList(),
            shuffledTokens = sentence?.tokens?.let { shuffleDifferent(it) } ?: emptyList(),
            letters = scrambleLetters(card.term, langOfCard),
            intervals = if (ExercisePlanner.isFlashcard(st.exercise)) study.previewIntervals(card) else emptyMap(),
            shownAt = time.now(),
        )
    }

    private fun scrambleLetters(term: String, lang: StudyLanguage): List<String> {
        val chars = term.filter { !it.isWhitespace() }.map { it.toString() }
        return if (chars.size < 2) chars else shuffleDifferent(chars)
    }

    private fun <T> shuffleDifferent(list: List<T>): List<T> {
        if (list.size < 2 || list.distinct().size < 2) return list
        repeat(10) { val s = list.shuffled(); if (s != list) return s }
        return list.reversed()
    }

    // ---------------------------------------------------------------- answering

    /** Self-graded flashcard rating. */
    fun rate(rating: Rating) {
        val step = _state.value.step ?: return
        if (_state.value.finishing || rateBusy) return
        rateBusy = true
        viewModelScope.launch {
            try {
                record(step.card, step.exercise, rating, rating != Rating.AGAIN, null)
                advance()
            } finally {
                rateBusy = false
            }
        }
    }

    // Guards against double taps / fast swipes recording one card twice.
    private var rateBusy = false

    /** Quick review swipe: right = know, left = don't know. */
    fun quick(know: Boolean) = rate(if (know) Rating.GOOD else Rating.AGAIN)

    /** Result of an exercise; shows feedback until [next] is called. */
    fun submit(attempt: Attempt) {
        val st = _state.value
        val step = st.step ?: return
        if (st.feedback != null || st.finishing) return
        val responseMs = time.now() - step.shownAt
        val rating = attempt.rating ?: RatingPolicy.rate(
            correct = attempt.correct, responseMs = responseMs, typed = attempt.typed,
            answerLength = attempt.answerLength, close = attempt.close, usedHint = attempt.usedHint,
        )
        // Show feedback immediately, persist in the background.
        _state.update { it.copy(feedback = Feedback(attempt.correct, attempt.close, step.card, attempt.userAnswer, attempt.note, 0)) }
        viewModelScope.launch {
            val xp = record(step.card, step.exercise, rating, attempt.correct, attempt.confusedWithCardId, responseMs, stepIndex = step.index)
            _state.update { s -> s.copy(feedback = s.feedback?.copy(xp = xp)) }
        }
    }

    /** Matching board: one tap pair. Returns whether the pair was right. */
    fun matchPair(termCardId: Long, translationCardId: Long): Boolean {
        val step = _state.value.step ?: return false
        val card = step.group.firstOrNull { it.id == termCardId } ?: return false
        val other = step.group.firstOrNull { it.id == translationCardId }
        val right = termCardId == translationCardId || (other != null && other.translation == card.translation)
        val now = time.now()
        val elapsed = now - matchingLastAt
        if (right) {
            matchingLastAt = now
            _state.update { it.copy(matchedIds = it.matchedIds + termCardId + translationCardId) }
        }
        if (termCardId !in matchingRecorded) {
            matchingRecorded += termCardId
            val rating = RatingPolicy.rate(right, elapsed)
            viewModelScope.launch {
                record(card, ExerciseType.MATCHING, rating, right, if (right) null else translationCardId, elapsed, requeue = false)
            }
        }
        if (right && _state.value.matchedIds.size >= step.group.size) {
            viewModelScope.launch {
                delay(450)
                advance()
            }
        }
        return right
    }

    fun next() {
        if (_state.value.feedback == null) return
        viewModelScope.launch { advance() }
    }

    fun skip() {
        viewModelScope.launch { advance() }
    }

    private suspend fun advance() {
        val target = _state.value.index + 1
        handle[Keys.INDEX] = target
        showStep(target)
    }

    private suspend fun record(
        card: CardEntity,
        exercise: ExerciseType,
        rating: Rating,
        correct: Boolean,
        confusedWith: Long?,
        responseMs: Long = time.now() - (_state.value.step?.shownAt ?: time.now()),
        requeue: Boolean = true,
        stepIndex: Int = _state.value.index,
    ): Int = writeLock.withLock {
        // Advance the saved position first so a restored session never re-asks an answered step.
        if (exercise != ExerciseType.MATCHING) handle[Keys.INDEX] = stepIndex + 1
        val combo = if (correct) _state.value.combo + 1 else 0
        val outcome = withContext(NonCancellable) {
            runCatching {
                study.recordAnswer(AnswerInput(card.id, rating, exercise, responseMs, correct, confusedWith, combo))
            }
        }.getOrElse { e ->
            _state.update { it.copy(error = e.message) }
            return@withLock 0
        }
        cards[card.id] = outcome.card
        val prev = changes[card.id]
        changes[card.id] = MasteryChange(outcome.card, prev?.before ?: outcome.masteryBefore, outcome.masteryAfter)

        if (!correct && requeue && ExercisePlanner.requeuesMistakes(mode)) {
            val n = requeued[card.id] ?: 0
            if (n < MAX_REQUEUE) {
                requeued[card.id] = n + 1
                val canOptions = cardDao.countAllNow(lang.code) >= 3
                val again = if (ExercisePlanner.isFlashcard(exercise)) exercise
                else ExercisePlanner.exerciseFor(mode, requested, traits(outcome.card, canOptions), n + 1)
                val at = (stepIndex + 1 + REQUEUE_GAP).coerceAtMost(steps.size)
                steps.add(at, SessionStep(listOf(card.id), again))
                persistSteps()
            }
        }
        _state.update { s ->
            val answered = s.answered + 1
            val right = s.correct + if (correct) 1 else 0
            val best = maxOf(s.bestCombo, combo)
            handle[Keys.ANSWERED] = answered; handle[Keys.CORRECT] = right; handle[Keys.XP] = s.xp + outcome.xpGained
            handle[Keys.COMBO] = combo; handle[Keys.BEST] = best
            if (outcome.dailyGoalJustReached) handle[Keys.GOAL] = true
            s.copy(
                answered = answered, correct = right, xp = s.xp + outcome.xpGained, combo = combo, bestCombo = best,
                stepCount = steps.size,
                xpBurst = XpBurst(++burstId, outcome.xpGained, combo),
                goalReached = s.goalReached || outcome.dailyGoalJustReached,
            )
        }
        outcome.xpGained
    }

    // ---------------------------------------------------------------- translation (AI grading)

    fun gradeTranslation(answer: String) {
        val step = _state.value.step ?: return
        val sentence = step.sentence ?: return
        if (answer.isBlank() || _state.value.translationGrade.loading) return
        _state.update { it.copy(translationGrade = TranslationGradeUi(loading = true)) }
        viewModelScope.launch {
            val native = settingsRepo.current().nativeLanguage
            val prompt = buildString {
                appendLine("Grade a language learner's translation.")
                appendLine("Source (${step.card.language}): ${sentence.text}")
                if (sentence.translation.isNotBlank()) appendLine("Reference translation: ${sentence.translation}")
                appendLine("Target word: ${step.card.term} = ${step.card.translation}")
                appendLine("Learner's translation (${native.code}): $answer")
                appendLine("Accept any faithful, natural translation; ignore punctuation and minor typos.")
                append("Reply with JSON only: {\"score\": 0-100, \"correct\": bool, \"feedback\": \"one short sentence in ")
                append(AiPrompts.nativeName(native))
                append("\", \"corrected\": \"a good translation\"}")
            }
            ai.completeJson(prompt, TranslationGrade.serializer(), system = AiPrompts.languageGuide(lang))
                .onSuccess { g ->
                    // The learner may have skipped ahead while the model was thinking.
                    if (_state.value.step?.index != step.index) return@onSuccess
                    val ok = g.correct || g.score >= 70
                    val rating = when {
                        !ok -> Rating.AGAIN
                        g.score >= 90 -> Rating.GOOD
                        else -> Rating.HARD
                    }
                    val note = listOf(g.feedback, g.corrected).filter { it.isNotBlank() }.joinToString("\n")
                    _state.update { it.copy(translationGrade = TranslationGradeUi()) }
                    submit(Attempt(correct = ok, close = ok && g.score < 90, userAnswer = answer, rating = rating, note = note.ifBlank { null }))
                }
                .onFailure {
                    if (_state.value.step?.index == step.index) _state.update { it.copy(translationGrade = TranslationGradeUi(failed = true)) }
                }
        }
    }

    // ---------------------------------------------------------------- misc actions

    fun speak(text: String = _state.value.step?.card?.term.orEmpty(), slow: Boolean = false) {
        if (text.isBlank()) return
        viewModelScope.launch { speech.speak(text, lang, slow) }
    }

    fun toggleFavorite() {
        val step = _state.value.step ?: return
        val fav = !step.card.favorite
        viewModelScope.launch {
            cardRepo.setFavorite(step.card.id, fav)
            val updated = step.card.copy(favorite = fav)
            cards[updated.id] = updated
            _state.update { s -> s.copy(step = s.step?.takeIf { it.card.id == updated.id }?.copy(card = updated) ?: s.step) }
        }
    }

    fun consumeError() = _state.update { it.copy(error = null) }

    // ---------------------------------------------------------------- timer & finishing

    private fun startTimer() {
        timerJob?.cancel()
        timerJob = viewModelScope.launch {
            var last = time.now()
            while (isActive) {
                delay(200)
                val now = time.now()
                val left = (_state.value.quickRemainingMs - (now - last)).coerceAtLeast(0)
                last = now
                _state.update { it.copy(quickRemainingMs = left) }
                handle[Keys.QUICK_LEFT] = left
                if (left == 0L) {
                    finish()
                    break
                }
            }
        }
    }

    /** Ends the session early (or when the queue is exhausted) and builds the summary. */
    fun finish() {
        if (_state.value.finishing || _state.value.summary != null) return
        _state.update { it.copy(finishing = true) }
        timerJob?.cancel()
        viewModelScope.launch {
            // Wait for in-flight answer writes.
            writeLock.withLock { }
            val s = _state.value
            val summary = runCatching {
                study.finishSession(s.answered, s.correct, s.xp, s.bestCombo, time.now() - startedAt)
            }.getOrElse { app.kotoba.core.data.SessionSummary(s.answered, s.correct, s.xp, s.bestCombo, time.now() - startedAt, emptyList()) }
            val list = changes.values.toList()
            _state.update {
                it.copy(
                    finishing = false,
                    step = null,
                    feedback = null,
                    summary = SummaryUi(
                        summary = summary,
                        up = list.filter { c -> c.after.ordinal > c.before.ordinal },
                        down = list.filter { c -> c.after.ordinal < c.before.ordinal },
                        goalReached = it.goalReached,
                    ),
                )
            }
            handle.remove<ArrayList<String>>(Keys.STEPS)
        }
    }

    /** "Continue" from the summary: a fresh queue in the same mode. */
    fun restart() {
        timerJob?.cancel()
        handle.remove<ArrayList<String>>(Keys.STEPS)
        handle.remove<Long>(Keys.QUICK_LEFT)
        viewModelScope.launch { load(restore = false) }
    }

    private fun persistSteps() {
        handle[Keys.STEPS] = ArrayList(steps.map { it.encode() })
    }

    override fun onCleared() {
        speech.stop()
        super.onCleared()
    }

    companion object {
        /** Helper for exercises comparing a typed answer with a card. */
        fun check(input: String, card: CardEntity) =
            AnswerMatcher.match(input, card.term, card.reading, StudyLanguage.fromCode(card.language))
    }
}

/** Convenience used by previews of the current language. */
internal suspend fun SettingsRepository.activeLanguage(): StudyLanguage = settings.first().activeLanguage
