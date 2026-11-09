package app.kotoba.feature.learn.logic

import app.kotoba.core.data.QueueMode
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.ExerciseType.CONTEXT_QUIZ
import app.kotoba.core.model.ExerciseType.FILL_BLANK
import app.kotoba.core.model.ExerciseType.FLASHCARD_AUDIO_TO_TERM
import app.kotoba.core.model.ExerciseType.FLASHCARD_TERM_TO_CONTEXT
import app.kotoba.core.model.ExerciseType.FLASHCARD_TERM_TO_TRANSLATION
import app.kotoba.core.model.ExerciseType.FLASHCARD_TRANSLATION_TO_TERM
import app.kotoba.core.model.ExerciseType.LISTENING
import app.kotoba.core.model.ExerciseType.MATCHING
import app.kotoba.core.model.ExerciseType.MULTIPLE_CHOICE
import app.kotoba.core.model.ExerciseType.PRONUNCIATION
import app.kotoba.core.model.ExerciseType.QUICK_REVIEW
import app.kotoba.core.model.ExerciseType.REVERSE_MULTIPLE_CHOICE
import app.kotoba.core.model.ExerciseType.SENTENCE_BUILDER
import app.kotoba.core.model.ExerciseType.SPELLING
import app.kotoba.core.model.ExerciseType.TRANSLATION
import app.kotoba.core.model.ExerciseType.TYPING
import app.kotoba.core.model.ExerciseType.WORD_SCRAMBLE
import app.kotoba.core.model.Mastery
import app.kotoba.core.navigation.SessionMode

/** What a card can support; decides which exercises are possible for it. */
data class CardTraits(
    val mastery: Mastery,
    val hasSentence: Boolean,
    val hasCloze: Boolean,
    val sentenceTokens: Int,
    val termLength: Int,
    /** Enough other cards exist to build answer options. */
    val canOptions: Boolean,
)

object ExercisePlanner {
    const val MIN_BUILDER_TOKENS = 3
    const val MAX_BUILDER_TOKENS = 12
    const val MATCHING_GROUP = 6
    const val MATCHING_MIN = 3

    val flashcardTypes = setOf(
        FLASHCARD_TERM_TO_TRANSLATION, FLASHCARD_TRANSLATION_TO_TERM, FLASHCARD_TERM_TO_CONTEXT, FLASHCARD_AUDIO_TO_TERM,
    )

    fun isFlashcard(t: ExerciseType) = t in flashcardTypes

    fun queueModeFor(mode: SessionMode): QueueMode = when (mode) {
        SessionMode.DAILY -> QueueMode.DAILY
        SessionMode.NEW -> QueueMode.NEW_ONLY
        SessionMode.REVIEW -> QueueMode.REVIEW_ONLY
        SessionMode.WEAK -> QueueMode.WEAK_ONLY
        SessionMode.QUICK -> QueueMode.QUICK
        SessionMode.FLASHCARDS -> QueueMode.DAILY
        SessionMode.EXERCISE, SessionMode.MIXED -> QueueMode.PRACTICE
    }

    /** Second attempt when the primary queue is too small (e.g. exercises before anything was studied). */
    fun fallbackQueueMode(mode: SessionMode): QueueMode? = when (mode) {
        SessionMode.FLASHCARDS, SessionMode.EXERCISE, SessionMode.MIXED -> QueueMode.ALL
        else -> null
    }

    /** Minimum queue size below which [fallbackQueueMode] is tried. */
    fun minQueue(mode: SessionMode, exercise: ExerciseType?): Int = when {
        mode == SessionMode.EXERCISE && exercise == MATCHING -> MATCHING_GROUP
        mode == SessionMode.EXERCISE || mode == SessionMode.MIXED -> 5
        else -> 1
    }

    /** Wrong answers come back later in these modes (learning-step style). */
    fun requeuesMistakes(mode: SessionMode): Boolean = mode != SessionMode.QUICK

    fun exerciseFor(mode: SessionMode, requested: ExerciseType?, traits: CardTraits, rotation: Int): ExerciseType = when (mode) {
        SessionMode.QUICK -> QUICK_REVIEW
        SessionMode.NEW -> FLASHCARD_TERM_TO_TRANSLATION
        SessionMode.REVIEW, SessionMode.FLASHCARDS -> flashcardTemplate(traits, rotation)
        SessionMode.WEAK -> pickFirstAvailable(weakRotation, traits, rotation)
        SessionMode.EXERCISE -> resolve(requested ?: MULTIPLE_CHOICE, traits)
        SessionMode.DAILY, SessionMode.MIXED -> forMixed(traits, rotation)
    }

    fun flashcardTemplate(traits: CardTraits, rotation: Int): ExerciseType {
        val list = when (traits.mastery) {
            Mastery.NEW -> listOf(FLASHCARD_TERM_TO_TRANSLATION)
            Mastery.LEARNING -> listOf(FLASHCARD_TERM_TO_TRANSLATION, FLASHCARD_TRANSLATION_TO_TERM)
            else -> listOf(FLASHCARD_TERM_TO_TRANSLATION, FLASHCARD_TRANSLATION_TO_TERM, FLASHCARD_TERM_TO_CONTEXT, FLASHCARD_AUDIO_TO_TERM)
        }
        return pickFirstAvailable(list, traits, rotation)
    }

    private val learningRotation = listOf(MULTIPLE_CHOICE, LISTENING, REVERSE_MULTIPLE_CHOICE, CONTEXT_QUIZ, WORD_SCRAMBLE)
    private val familiarRotation = listOf(TYPING, FILL_BLANK, SENTENCE_BUILDER, LISTENING, SPELLING, FLASHCARD_TRANSLATION_TO_TERM)
    private val masteredRotation = listOf(TRANSLATION, TYPING, SENTENCE_BUILDER, FLASHCARD_TERM_TO_CONTEXT, PRONUNCIATION, FLASHCARD_AUDIO_TO_TERM)
    private val weakRotation = listOf(TYPING, MULTIPLE_CHOICE, LISTENING, FILL_BLANK, REVERSE_MULTIPLE_CHOICE, SPELLING)

    /** Harder recall formats as a word becomes better known. */
    fun forMixed(traits: CardTraits, rotation: Int): ExerciseType = when (traits.mastery) {
        Mastery.NEW -> FLASHCARD_TERM_TO_TRANSLATION
        Mastery.LEARNING -> pickFirstAvailable(learningRotation, traits, rotation)
        Mastery.FAMILIAR -> pickFirstAvailable(familiarRotation, traits, rotation)
        Mastery.MASTERED -> pickFirstAvailable(masteredRotation, traits, rotation)
    }

    fun isAvailable(t: ExerciseType, traits: CardTraits): Boolean = when (t) {
        MULTIPLE_CHOICE, REVERSE_MULTIPLE_CHOICE, LISTENING -> traits.canOptions
        CONTEXT_QUIZ -> traits.canOptions && traits.hasSentence
        FILL_BLANK -> traits.canOptions && traits.hasCloze
        FLASHCARD_TERM_TO_CONTEXT -> traits.hasCloze
        TRANSLATION -> traits.hasSentence
        SENTENCE_BUILDER -> traits.hasSentence && traits.sentenceTokens in MIN_BUILDER_TOKENS..MAX_BUILDER_TOKENS
        WORD_SCRAMBLE -> traits.termLength in 2..20
        MATCHING -> traits.canOptions
        else -> true
    }

    /** The requested exercise if possible, otherwise the closest format the card supports. */
    fun resolve(requested: ExerciseType, traits: CardTraits): ExerciseType = when {
        isAvailable(requested, traits) -> requested
        requested == FILL_BLANK && traits.canOptions -> MULTIPLE_CHOICE
        requested == SENTENCE_BUILDER && traits.termLength in 2..20 -> WORD_SCRAMBLE
        requested == TRANSLATION || requested == SENTENCE_BUILDER -> TYPING
        traits.canOptions -> MULTIPLE_CHOICE
        requested == FLASHCARD_TERM_TO_CONTEXT -> FLASHCARD_TERM_TO_TRANSLATION
        else -> TYPING
    }

    private fun pickFirstAvailable(list: List<ExerciseType>, traits: CardTraits, rotation: Int): ExerciseType {
        val start = Math.floorMod(rotation, list.size)
        for (i in list.indices) {
            val t = list[(start + i) % list.size]
            if (isAvailable(t, traits)) return t
        }
        return resolve(list[start], traits)
    }
}
