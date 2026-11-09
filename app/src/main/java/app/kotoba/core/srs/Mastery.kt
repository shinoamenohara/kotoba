package app.kotoba.core.srs

import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState

/** Inputs for the mastery and weakness evaluators (decoupled from Room for testability). */
data class LearningSnapshot(
    val state: SrsState,
    val stability: Double,
    val reps: Int,
    val lapses: Int,
    val correctCount: Int,
    val wrongCount: Int,
    val avgResponseMs: Long,
    /** Probability of recall right now. */
    val retrievability: Double,
    /** Most recent first. */
    val recentResults: List<Boolean>,
    /** Exercise types the card was answered correctly in. */
    val correctExerciseTypes: Set<ExerciseType>,
    val previous: Mastery,
    val confusionCount: Int = 0,
)

/**
 * Word Mastery: NEW → LEARNING → FAMILIAR → MASTERED, derived from correct/wrong counts,
 * stability (interval between successful reviews), response time, exercise variety and
 * recency. Poor results demote one level at a time.
 */
object MasteryEvaluator {
    const val SLOW_RESPONSE_MS = 10_000L

    fun evaluate(s: LearningSnapshot): Mastery {
        if (s.reps == 0 && s.correctCount + s.wrongCount == 0) return Mastery.NEW
        val total = s.correctCount + s.wrongCount
        val accuracy = if (total == 0) 0.0 else s.correctCount.toDouble() / total
        val recent = s.recentResults.take(5)
        val recentAccuracy = if (recent.isEmpty()) accuracy else recent.count { it }.toDouble() / recent.size
        val inReview = s.state == SrsState.REVIEW

        var level = when {
            inReview && s.stability >= 21 && s.correctCount >= 5 && accuracy >= 0.8 && recentAccuracy >= 0.8 &&
                s.correctExerciseTypes.size >= 2 -> Mastery.MASTERED
            inReview && s.stability >= 3 && s.correctCount >= 3 && accuracy >= 0.6 -> Mastery.FAMILIAR
            else -> Mastery.LEARNING
        }
        if (level == Mastery.MASTERED && s.avgResponseMs > SLOW_RESPONSE_MS) level = Mastery.FAMILIAR
        if (inReview && s.retrievability < 0.7) level = level.demoted()

        val lastWrong = recent.firstOrNull() == false
        if (lastWrong && s.previous.ordinal > Mastery.LEARNING.ordinal) {
            // Gradual regression: a single miss costs at most one level.
            level = s.previous.demoted()
        }
        return level
    }

    private fun Mastery.demoted(): Mastery =
        if (this.ordinal > Mastery.LEARNING.ordinal) Mastery.entries[ordinal - 1] else Mastery.LEARNING
}

enum class WeakReason { FORGETS_OFTEN, HIGH_ERROR_RATE, SLOW, UNSTABLE, CONFUSED }

data class WeaknessReport(val score: Double, val reasons: Set<WeakReason>) {
    val isWeak: Boolean get() = score >= WeakWordsRadar.WEAK_THRESHOLD
}

/** Weak Words Radar: flags words that are forgotten, confused, slow or unstable. */
object WeakWordsRadar {
    const val WEAK_THRESHOLD = 3.0

    fun analyze(s: LearningSnapshot): WeaknessReport {
        val reasons = mutableSetOf<WeakReason>()
        var score = 0.0

        val lapseScore = (s.lapses * 1.5).coerceAtMost(6.0)
        score += lapseScore
        if (s.lapses >= 2) reasons += WeakReason.FORGETS_OFTEN

        val total = s.correctCount + s.wrongCount
        if (total >= 3) {
            val errorRate = s.wrongCount.toDouble() / total
            score += errorRate * 4
            if (errorRate >= 0.4) reasons += WeakReason.HIGH_ERROR_RATE
        }

        val recent = s.recentResults.take(6)
        score += recent.take(5).count { !it } * 0.8

        if (s.avgResponseMs > 8_000) {
            score += 1.5; reasons += WeakReason.SLOW
        } else if (s.avgResponseMs > 5_000) {
            score += 0.75
        }

        val flips = recent.zipWithNext().count { (a, b) -> a != b }
        if (flips >= 3) {
            score += flips * 0.5; reasons += WeakReason.UNSTABLE
        }

        if (s.confusionCount > 0) {
            score += s.confusionCount.coerceAtMost(4) * 0.6
            if (s.confusionCount >= 2) reasons += WeakReason.CONFUSED
        }

        if (s.previous == Mastery.MASTERED && recent.firstOrNull() != false) score *= 0.5
        return WeaknessReport(score, reasons)
    }
}

/** Duolingo-style XP and level curve. */
object XpRules {
    const val NEW_WORD = 10
    const val REVIEW_CORRECT = 5
    const val REVIEW_WRONG = 1
    const val WEAK_WORD_BONUS = 3
    /** Reviews of words FSRS rates as hard (difficulty ≥ [HARD_DIFFICULTY]). */
    const val HARD_WORD_BONUS = 2
    const val HARD_DIFFICULTY = 7.0
    const val COMBO_STEP = 5
    const val COMBO_BONUS = 5
    const val DAILY_GOAL_REWARD = 20

    fun forAnswer(correct: Boolean, wasNew: Boolean, wasWeak: Boolean, combo: Int, wasHard: Boolean = false): Int {
        if (!correct) return REVIEW_WRONG
        var xp = if (wasNew) NEW_WORD else REVIEW_CORRECT
        if (wasWeak) xp += WEAK_WORD_BONUS
        if (wasHard && !wasNew) xp += HARD_WORD_BONUS
        if (combo > 0 && combo % COMBO_STEP == 0) xp += COMBO_BONUS
        return xp
    }

    /** Total XP needed to reach [level] (level 1 = 0 XP, 2 = 100, 3 = 300, 4 = 600, …). */
    fun thresholdFor(level: Int): Int = 50 * level * (level - 1)

    fun levelInfo(totalXp: Int): LevelInfo {
        var level = 1
        while (thresholdFor(level + 1) <= totalXp) level++
        val base = thresholdFor(level)
        val next = thresholdFor(level + 1)
        return LevelInfo(level, totalXp - base, next - base, totalXp)
    }
}

data class LevelInfo(val level: Int, val xpIntoLevel: Int, val xpForLevel: Int, val totalXp: Int) {
    val progress: Float get() = if (xpForLevel == 0) 0f else xpIntoLevel.toFloat() / xpForLevel
}
