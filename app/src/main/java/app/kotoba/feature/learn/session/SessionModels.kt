package app.kotoba.feature.learn.session

import app.kotoba.core.data.SessionSummary
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.SessionMode
import kotlinx.serialization.Serializable

/** One screen of a session: usually one card, several for matching. */
data class SessionStep(val cardIds: List<Long>, val exercise: ExerciseType) {
    fun encode(): String = cardIds.joinToString(",") + ":" + exercise.name

    companion object {
        fun decode(s: String): SessionStep? = runCatching {
            val (ids, ex) = s.split(':', limit = 2)
            SessionStep(ids.split(',').map { it.toLong() }, ExerciseType.valueOf(ex))
        }.getOrNull()
    }
}

/** Example sentence used by context-based exercises. [cloze] is the word's position. */
data class SentenceInfo(
    val text: String,
    val translation: String,
    val sourceTitle: String,
    val cloze: IntRange?,
    /** Word tokens in order (for the sentence builder). */
    val tokens: List<String>,
)

/** Everything a step needs to render, resolved off the main thread. */
data class PreparedStep(
    val index: Int,
    val exercise: ExerciseType,
    val card: CardEntity,
    val group: List<CardEntity> = emptyList(),
    val options: List<CardEntity> = emptyList(),
    val sentence: SentenceInfo? = null,
    val contexts: List<ContextEntity> = emptyList(),
    val shuffledTokens: List<String> = emptyList(),
    val letters: List<String> = emptyList(),
    val intervals: Map<Rating, Long> = emptyMap(),
    val shownAt: Long = System.currentTimeMillis(),
)

/** What an exercise reports; the ViewModel turns it into a rating. */
data class Attempt(
    val correct: Boolean,
    val close: Boolean = false,
    val usedHint: Boolean = false,
    val typed: Boolean = false,
    val answerLength: Int = 0,
    val confusedWithCardId: Long? = null,
    val userAnswer: String? = null,
    /** Explicit rating (self-assessment / AI grading), bypasses [app.kotoba.feature.learn.logic.RatingPolicy]. */
    val rating: Rating? = null,
    val note: String? = null,
)

data class Feedback(
    val correct: Boolean,
    val close: Boolean,
    val card: CardEntity,
    val userAnswer: String?,
    val note: String?,
    val xp: Int,
)

data class XpBurst(val id: Long, val xp: Int, val combo: Int)

data class MasteryChange(val card: CardEntity, val before: Mastery, val after: Mastery)

data class SummaryUi(
    val summary: SessionSummary,
    val up: List<MasteryChange>,
    val down: List<MasteryChange>,
    val goalReached: Boolean,
)

data class TranslationGradeUi(val loading: Boolean = false, val failed: Boolean = false)

data class SessionUiState(
    val mode: SessionMode = SessionMode.DAILY,
    val requestedExercise: ExerciseType? = null,
    val language: StudyLanguage = StudyLanguage.EN,
    val loading: Boolean = true,
    val empty: Boolean = false,
    val stepCount: Int = 0,
    val index: Int = 0,
    val step: PreparedStep? = null,
    val feedback: Feedback? = null,
    val answered: Int = 0,
    val correct: Int = 0,
    val xp: Int = 0,
    val combo: Int = 0,
    val bestCombo: Int = 0,
    val xpBurst: XpBurst? = null,
    val goalReached: Boolean = false,
    val finishing: Boolean = false,
    val summary: SummaryUi? = null,
    val quickTotalMs: Long = 0,
    val quickRemainingMs: Long = 0,
    val aiAvailable: Boolean = false,
    val translationGrade: TranslationGradeUi = TranslationGradeUi(),
    /** Cards answered in the current matching board. */
    val matchedIds: Set<Long> = emptySet(),
    val error: String? = null,
) {
    val progress: Float get() = if (stepCount == 0) 0f else (index.toFloat() / stepCount).coerceIn(0f, 1f)
}

@Serializable
data class TranslationGrade(
    val score: Int = 0,
    val correct: Boolean = false,
    val feedback: String = "",
    val corrected: String = "",
)
