package app.kotoba.core.srs

import app.kotoba.core.model.Rating
import app.kotoba.core.model.SrsState
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.random.Random

/** Scheduling-relevant subset of a card. */
data class SrsCard(
    val state: SrsState = SrsState.NEW,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0,
    val lastReview: Long? = null,
    val step: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
)

data class SchedulingResult(val card: SrsCard, val intervalMs: Long) {
    val scheduledDays: Double get() = intervalMs / Fsrs.DAY_MS.toDouble()
}

data class FsrsParams(
    val w: DoubleArray = DEFAULT_W,
    val desiredRetention: Double = 0.9,
    val maximumIntervalDays: Int = 36_500,
    val learningStepsMinutes: List<Int> = listOf(1, 10),
    val relearningStepsMinutes: List<Int> = listOf(10),
    val enableFuzz: Boolean = true,
) {
    init {
        require(w.size == 19) { "FSRS-5 needs 19 weights" }
        require(desiredRetention in 0.7..0.99)
    }

    override fun equals(other: Any?): Boolean = other is FsrsParams &&
        w.contentEquals(other.w) && desiredRetention == other.desiredRetention &&
        maximumIntervalDays == other.maximumIntervalDays && learningStepsMinutes == other.learningStepsMinutes &&
        relearningStepsMinutes == other.relearningStepsMinutes && enableFuzz == other.enableFuzz

    override fun hashCode(): Int = w.contentHashCode() * 31 + desiredRetention.hashCode()

    companion object {
        /** FSRS-5 default parameters. */
        val DEFAULT_W = doubleArrayOf(
            0.40255, 1.18385, 3.173, 15.69105, 7.1949, 0.5345, 1.4604, 0.0046, 1.54575, 0.1192,
            1.01925, 1.9395, 0.11, 0.29605, 2.2698, 0.2315, 2.9898, 0.51655, 0.6621,
        )
    }
}

/**
 * FSRS-5 scheduler (https://github.com/open-spaced-repetition/fsrs4anki/wiki/The-Algorithm)
 * with Anki-style learning / relearning steps.
 */
class Fsrs(val params: FsrsParams = FsrsParams(), private val random: Random = Random.Default) {
    private val w = params.w

    fun retrievability(card: SrsCard, now: Long): Double {
        if (card.state == SrsState.NEW || card.stability <= 0.0) return 0.0
        val last = card.lastReview ?: return 0.0
        val elapsedDays = max(0.0, (now - last) / DAY_MS.toDouble())
        return forgettingCurve(elapsedDays, card.stability)
    }

    fun forgettingCurve(elapsedDays: Double, stability: Double): Double =
        (1.0 + FACTOR * elapsedDays / stability).pow(DECAY)

    /** Previews the interval each rating would produce (for button labels). */
    fun preview(card: SrsCard, now: Long): Map<Rating, Long> =
        Rating.entries.associateWith { next(card, it, now, fuzz = false).intervalMs }

    fun next(card: SrsCard, rating: Rating, now: Long, fuzz: Boolean = params.enableFuzz): SchedulingResult {
        val g = rating.value
        return when (card.state) {
            SrsState.NEW -> {
                val s = initStability(g)
                val d = initDifficulty(g)
                stepOrGraduate(card.copy(stability = s, difficulty = d, step = 0), rating, now, params.learningStepsMinutes, SrsState.LEARNING, fuzz)
            }
            SrsState.LEARNING, SrsState.RELEARNING -> {
                val elapsedDays = card.lastReview?.let { (now - it) / DAY_MS.toDouble() } ?: 0.0
                val s = if (elapsedDays < 1.0) {
                    shortTermStability(card.stability, g)
                } else {
                    val r = forgettingCurve(elapsedDays, card.stability)
                    if (rating == Rating.AGAIN) forgetStability(card.difficulty, card.stability, r)
                    else recallStability(card.difficulty, card.stability, r, rating)
                }
                val d = nextDifficulty(card.difficulty, g)
                val steps = if (card.state == SrsState.LEARNING) params.learningStepsMinutes else params.relearningStepsMinutes
                stepOrGraduate(card.copy(stability = s, difficulty = d), rating, now, steps, card.state, fuzz)
            }
            SrsState.REVIEW -> {
                val elapsedDays = card.lastReview?.let { max(0.0, (now - it) / DAY_MS.toDouble()) } ?: 0.0
                val r = forgettingCurve(elapsedDays, card.stability)
                val d = nextDifficulty(card.difficulty, g)
                if (rating == Rating.AGAIN) {
                    val s = forgetStability(card.difficulty, card.stability, r)
                    val lapsed = card.copy(stability = s, difficulty = d, lapses = card.lapses + 1, step = 0)
                    if (params.relearningStepsMinutes.isEmpty()) {
                        graduate(lapsed, now, fuzz)
                    } else {
                        val ms = params.relearningStepsMinutes[0] * MINUTE_MS
                        SchedulingResult(
                            lapsed.copy(state = SrsState.RELEARNING, due = now + ms, lastReview = now, reps = card.reps + 1),
                            ms,
                        )
                    }
                } else {
                    val s = if (elapsedDays < 1.0) shortTermStability(card.stability, g)
                    else recallStability(card.difficulty, card.stability, r, rating)
                    graduate(card.copy(stability = s, difficulty = d), now, fuzz)
                }
            }
        }
    }

    private fun stepOrGraduate(
        card: SrsCard,
        rating: Rating,
        now: Long,
        steps: List<Int>,
        learningState: SrsState,
        fuzz: Boolean,
    ): SchedulingResult {
        if (steps.isEmpty() || rating == Rating.EASY) return graduate(card, now, fuzz)
        val stepMs: Long
        val nextStep: Int
        when (rating) {
            Rating.AGAIN -> { nextStep = 0; stepMs = steps[0] * MINUTE_MS }
            Rating.HARD -> {
                nextStep = card.step
                val cur = steps[min(card.step, steps.lastIndex)]
                val hardMin = if (card.step == 0 && steps.size > 1) (steps[0] + steps[1]) / 2.0 else cur * 1.5
                stepMs = (hardMin * MINUTE_MS).roundToLong()
            }
            Rating.GOOD -> {
                if (card.step + 1 >= steps.size) return graduate(card, now, fuzz)
                nextStep = card.step + 1
                stepMs = steps[nextStep] * MINUTE_MS
            }
            Rating.EASY -> error("handled above")
        }
        return SchedulingResult(
            card.copy(state = learningState, step = nextStep, due = now + stepMs, lastReview = now, reps = card.reps + 1),
            stepMs,
        )
    }

    private fun graduate(card: SrsCard, now: Long, fuzz: Boolean): SchedulingResult {
        var days = nextIntervalDays(card.stability)
        if (fuzz) days = applyFuzz(days)
        val ms = days * DAY_MS
        return SchedulingResult(
            card.copy(state = SrsState.REVIEW, step = 0, due = now + ms, lastReview = now, reps = card.reps + 1),
            ms,
        )
    }

    fun nextIntervalDays(stability: Double): Long {
        val raw = stability / FACTOR * (params.desiredRetention.pow(1.0 / DECAY) - 1.0)
        return raw.roundToLong().coerceIn(1L, params.maximumIntervalDays.toLong())
    }

    private fun applyFuzz(days: Long): Long {
        if (days < 3) return days
        val delta = max(1L, (days * 0.05).roundToLong())
        return (days + random.nextLong(-delta, delta + 1)).coerceIn(2L, params.maximumIntervalDays.toLong())
    }

    internal fun initStability(g: Int): Double = max(w[g - 1], 0.1)

    internal fun initDifficulty(g: Int): Double = (w[4] - exp(w[5] * (g - 1)) + 1.0).coerceIn(1.0, 10.0)

    internal fun nextDifficulty(d: Double, g: Int): Double {
        val delta = -w[6] * (g - 3)
        val damped = d + delta * (10.0 - d) / 9.0
        val reverted = w[7] * initDifficulty(4) + (1 - w[7]) * damped
        return reverted.coerceIn(1.0, 10.0)
    }

    internal fun recallStability(d: Double, s: Double, r: Double, rating: Rating): Double {
        val hardPenalty = if (rating == Rating.HARD) w[15] else 1.0
        val easyBonus = if (rating == Rating.EASY) w[16] else 1.0
        return s * (1 + exp(w[8]) * (11 - d) * s.pow(-w[9]) * (exp(w[10] * (1 - r)) - 1) * hardPenalty * easyBonus)
    }

    internal fun forgetStability(d: Double, s: Double, r: Double): Double {
        val f = w[11] * d.pow(-w[12]) * ((s + 1).pow(w[13]) - 1) * exp(w[14] * (1 - r))
        return min(f, s).coerceAtLeast(0.1)
    }

    internal fun shortTermStability(s: Double, g: Int): Double =
        (s * exp(w[17] * (g - 3 + w[18]))).coerceAtLeast(0.1)

    companion object {
        const val DECAY = -0.5
        const val FACTOR = 19.0 / 81.0
        const val MINUTE_MS = 60_000L
        const val DAY_MS = 86_400_000L
    }
}
