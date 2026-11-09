package app.kotoba.core.srs

import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.Rating
import app.kotoba.core.model.SrsState
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FsrsTest {
    private val fsrs = Fsrs(FsrsParams(enableFuzz = false))
    private val day = Fsrs.DAY_MS
    private val t0 = 1_700_000_000_000L

    @Test fun `new card rated good enters learning step`() {
        val r = fsrs.next(SrsCard(), Rating.GOOD, t0)
        assertThat(r.card.state).isEqualTo(SrsState.LEARNING)
        assertThat(r.card.step).isEqualTo(1)
        assertThat(r.intervalMs).isEqualTo(10 * Fsrs.MINUTE_MS)
    }

    @Test fun `easy graduates immediately with multi-day interval`() {
        val r = fsrs.next(SrsCard(), Rating.EASY, t0)
        assertThat(r.card.state).isEqualTo(SrsState.REVIEW)
        assertThat(r.scheduledDays).isAtLeast(7.0)
    }

    @Test fun `intervals grow with successful reviews and shrink after lapse`() {
        var card = fsrs.next(SrsCard(), Rating.GOOD, t0).card
        card = fsrs.next(card, Rating.GOOD, t0 + 10 * Fsrs.MINUTE_MS).card
        assertThat(card.state).isEqualTo(SrsState.REVIEW)
        var now = card.due
        val intervals = mutableListOf<Double>()
        repeat(4) {
            val r = fsrs.next(card, Rating.GOOD, now)
            intervals += r.scheduledDays
            card = r.card
            now = card.due
        }
        assertThat(intervals).isInStrictOrder()
        val before = card.stability
        val lapse = fsrs.next(card, Rating.AGAIN, now)
        assertThat(lapse.card.state).isEqualTo(SrsState.RELEARNING)
        assertThat(lapse.card.lapses).isEqualTo(1)
        assertThat(lapse.card.stability).isLessThan(before)
    }

    @Test fun `rating order yields ordered intervals for review card`() {
        val card = SrsCard(SrsState.REVIEW, stability = 10.0, difficulty = 5.0, due = t0, lastReview = t0 - 10 * day, reps = 5)
        val p = fsrs.preview(card, t0)
        assertThat(p[Rating.AGAIN]!!).isLessThan(p[Rating.HARD]!!)
        assertThat(p[Rating.HARD]!!).isAtMost(p[Rating.GOOD]!!)
        assertThat(p[Rating.GOOD]!!).isLessThan(p[Rating.EASY]!!)
    }

    @Test fun `retrievability equals desired retention at scheduled interval`() {
        val s = 20.0
        val interval = fsrs.nextIntervalDays(s).toDouble()
        assertThat(fsrs.forgettingCurve(interval, s)).isWithin(0.02).of(0.9)
    }
}

class MasteryTest {
    private fun snap(
        state: SrsState = SrsState.REVIEW, stability: Double = 30.0, correct: Int = 8, wrong: Int = 1,
        recent: List<Boolean> = listOf(true, true, true), types: Set<ExerciseType> = setOf(ExerciseType.TYPING, ExerciseType.MULTIPLE_CHOICE),
        previous: Mastery = Mastery.FAMILIAR, avgMs: Long = 2000, r: Double = 0.95, lapses: Int = 0,
    ) = LearningSnapshot(state, stability, correct + wrong, lapses, correct, wrong, avgMs, r, recent, types, previous)

    @Test fun `untouched card is new`() {
        assertThat(MasteryEvaluator.evaluate(snap(state = SrsState.NEW, correct = 0, wrong = 0).copy(reps = 0))).isEqualTo(Mastery.NEW)
    }

    @Test fun `stable accurate varied card is mastered`() {
        assertThat(MasteryEvaluator.evaluate(snap())).isEqualTo(Mastery.MASTERED)
    }

    @Test fun `single exercise type caps at familiar`() {
        assertThat(MasteryEvaluator.evaluate(snap(types = setOf(ExerciseType.TYPING)))).isEqualTo(Mastery.FAMILIAR)
    }

    @Test fun `slow answers cap at familiar`() {
        assertThat(MasteryEvaluator.evaluate(snap(avgMs = 15_000))).isEqualTo(Mastery.FAMILIAR)
    }

    @Test fun `a miss demotes exactly one level`() {
        val m = MasteryEvaluator.evaluate(snap(state = SrsState.RELEARNING, stability = 2.0, recent = listOf(false, true, true), previous = Mastery.MASTERED))
        assertThat(m).isEqualTo(Mastery.FAMILIAR)
    }

    @Test fun `learning card stays learning`() {
        assertThat(MasteryEvaluator.evaluate(snap(state = SrsState.LEARNING, stability = 0.5, correct = 1, wrong = 0, previous = Mastery.NEW)))
            .isEqualTo(Mastery.LEARNING)
    }
}

class WeakRadarTest {
    private fun snap(lapses: Int, correct: Int, wrong: Int, recent: List<Boolean>, avgMs: Long = 2000, confusions: Int = 0) =
        LearningSnapshot(SrsState.REVIEW, 5.0, correct + wrong, lapses, correct, wrong, avgMs, 0.9, recent, emptySet(), Mastery.LEARNING, confusions)

    @Test fun `frequently forgotten word is weak`() {
        val r = WeakWordsRadar.analyze(snap(lapses = 3, correct = 4, wrong = 4, recent = listOf(false, true, false, true, false)))
        assertThat(r.isWeak).isTrue()
        assertThat(r.reasons).containsAtLeast(WeakReason.FORGETS_OFTEN, WeakReason.HIGH_ERROR_RATE, WeakReason.UNSTABLE)
    }

    @Test fun `solid word is not weak`() {
        assertThat(WeakWordsRadar.analyze(snap(lapses = 0, correct = 10, wrong = 0, recent = List(5) { true })).isWeak).isFalse()
    }

    @Test fun `slow and confused word is flagged`() {
        val r = WeakWordsRadar.analyze(snap(lapses = 0, correct = 5, wrong = 1, recent = listOf(true, true), avgMs = 9000, confusions = 3))
        assertThat(r.reasons).containsAtLeast(WeakReason.SLOW, WeakReason.CONFUSED)
        assertThat(r.isWeak).isTrue()
    }
}

class GamificationTest {
    @Test fun `streak counts consecutive days`() {
        assertThat(GamificationRepository.computeStreak(listOf(100, 99, 98, 96), 100)).isEqualTo(3)
        assertThat(GamificationRepository.computeStreak(listOf(99, 98), 100)).isEqualTo(2)
        assertThat(GamificationRepository.computeStreak(listOf(97), 100)).isEqualTo(0)
        assertThat(GamificationRepository.computeStreak(emptyList(), 100)).isEqualTo(0)
    }

    @Test fun `xp levels`() {
        assertThat(XpRules.levelInfo(0).level).isEqualTo(1)
        assertThat(XpRules.levelInfo(100).level).isEqualTo(2)
        assertThat(XpRules.levelInfo(299).level).isEqualTo(2)
        assertThat(XpRules.levelInfo(300).level).isEqualTo(3)
        assertThat(XpRules.forAnswer(correct = true, wasNew = false, wasWeak = true, combo = 5))
            .isEqualTo(XpRules.REVIEW_CORRECT + XpRules.WEAK_WORD_BONUS + XpRules.COMBO_BONUS)
    }
}
