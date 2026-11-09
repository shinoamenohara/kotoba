package app.kotoba.feature.learn.logic

import app.kotoba.core.model.Rating

/** Maps an exercise outcome to an FSRS grade for non-flashcard exercises. */
object RatingPolicy {
    const val SLOW_MS = 10_000L
    /** A typed answer this fast (scaled by length) counts as effortless recall. */
    const val FAST_TYPED_BASE_MS = 2_500L
    const val FAST_TYPED_PER_CHAR_MS = 350L

    fun rate(
        correct: Boolean,
        responseMs: Long,
        typed: Boolean = false,
        answerLength: Int = 0,
        close: Boolean = false,
        usedHint: Boolean = false,
    ): Rating = when {
        !correct -> Rating.AGAIN
        close || usedHint || responseMs > SLOW_MS -> Rating.HARD
        typed && responseMs <= fastTypedThreshold(answerLength) -> Rating.EASY
        else -> Rating.GOOD
    }

    fun fastTypedThreshold(answerLength: Int): Long =
        FAST_TYPED_BASE_MS + FAST_TYPED_PER_CHAR_MS * answerLength.coerceIn(0, 20)
}
