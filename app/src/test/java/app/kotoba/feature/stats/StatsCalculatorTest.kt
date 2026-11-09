package app.kotoba.feature.stats

import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ConfusionEntity
import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.db.DayCount
import app.kotoba.core.db.KeyMasteryCount
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.model.Category
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.LevelScale
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate
import java.time.ZoneOffset

class StatsCalculatorTest {
    private val zone = ZoneOffset.UTC
    private val today = LocalDate.of(2026, 11, 9).toEpochDay()
    private fun at(day: Long, hour: Int = 12) = (day * 86_400L + hour * 3600L) * 1000L

    private fun log(cardId: Long, day: Long, correct: Boolean, ms: Long = 1000, ex: ExerciseType = ExerciseType.TYPING, hour: Int = 12) =
        ReviewLogEntity(
            cardId = cardId, timestamp = at(day, hour), rating = if (correct) 3 else 1, correct = correct, exercise = ex,
            responseMs = ms, stateBefore = SrsState.REVIEW, masteryBefore = Mastery.LEARNING, masteryAfter = Mastery.LEARNING,
            stabilityAfter = 1.0, scheduledDays = 1.0,
        )

    private fun card(id: Long, term: String = "w$id") = CardEntity(id = id, language = "en", term = term, translation = "t")

    @Test fun `period windows end today and have the right length`() {
        assertThat(StatsCalculator.window(StatsPeriod.DAY, today)).isEqualTo(DayWindow(today, today))
        val week = StatsCalculator.window(StatsPeriod.WEEK, today)
        assertThat(week.fromDay).isEqualTo(today - 6)
        assertThat(week.size).isEqualTo(7)
        assertThat(StatsCalculator.window(StatsPeriod.MONTH, today).size).isEqualTo(30)
        assertThat(StatsCalculator.chartWindow(StatsPeriod.MONTH, today).size).isEqualTo(90)
    }

    @Test fun `cumulative series includes history before the window and fills gaps`() {
        val perDay = listOf(DayCount(today - 10, 5), DayCount(today - 2, 3), DayCount(today, 1))
        val w = DayWindow(today - 3, today)
        assertThat(StatsCalculator.cumulative(perDay, w)).containsExactly(5, 8, 8, 9).inOrder()
        assertThat(StatsCalculator.cumulative(emptyList(), w)).containsExactly(0, 0, 0, 0)
    }

    @Test fun `accuracy handles zero and trend leaves gaps for idle days`() {
        assertThat(StatsCalculator.accuracy(0, 0)).isNull()
        assertThat(StatsCalculator.accuracy(3, 4)).isEqualTo(0.75f)
        val activity = listOf(DailyActivityEntity(today - 1, correct = 9, wrong = 1))
        assertThat(StatsCalculator.accuracyTrend(activity, DayWindow(today - 2, today)))
            .containsExactly(null, 0.9f, null).inOrder()
    }

    @Test fun `summary sums only rows inside the window`() {
        val activity = listOf(
            DailyActivityEntity(today - 8, newWords = 100, reviews = 100),
            DailyActivityEntity(today - 1, newWords = 2, reviews = 10, correct = 8, wrong = 2, studyMs = 60_000, forgotten = 1, recovered = 2, goalReached = true),
            DailyActivityEntity(today, newWords = 3, reviews = 5, correct = 5, wrong = 0),
        )
        val logs = listOf(log(1, today, true, 1000), log(1, today, false, 3000), log(2, today - 9, true, 99_000))
        val s = StatsCalculator.summarize(activity, logs, StatsCalculator.window(StatsPeriod.WEEK, today), zone)
        assertThat(s.newWords).isEqualTo(5)
        assertThat(s.reviews).isEqualTo(15)
        assertThat(s.accuracy).isWithin(1e-4f).of(13f / 15f)
        assertThat(s.goalDays).isEqualTo(1)
        assertThat(s.activeDays).isEqualTo(2)
        assertThat(s.forgotten).isEqualTo(1)
        assertThat(s.recovered).isEqualTo(2)
        assertThat(s.avgResponseMs).isEqualTo(2000)
    }

    @Test fun `error analytics rank missed, slow, exercise and confused words`() {
        val cards = (1L..3L).associateWith { card(it) }
        val logs = listOf(
            log(1, today, false, 5000), log(1, today, false, 5000), log(1, today, true, 5000, ExerciseType.LISTENING),
            log(2, today, false, 1000), log(2, today, true, 1000),
            log(3, today, true, 500),
        )
        val missed = StatsCalculator.mostMissed(logs, cards)
        assertThat(missed.map { it.card.id }).containsExactly(1L, 2L).inOrder()
        assertThat(missed.first().count).isEqualTo(2)
        assertThat(StatsCalculator.slowest(logs, cards).map { it.card.id }).containsExactly(1L, 2L).inOrder()
        val byEx = StatsCalculator.accuracyByExercise(logs)
        assertThat(byEx.first().type).isEqualTo(ExerciseType.TYPING)
        assertThat(byEx.first { it.type == ExerciseType.LISTENING }.accuracy).isEqualTo(1f)

        val pairs = StatsCalculator.confusedPairs(
            listOf(ConfusionEntity(1, 2, 3, 0), ConfusionEntity(2, 1, 2, 0), ConfusionEntity(3, 99, 9, 0)),
            cards,
        )
        assertThat(pairs).hasSize(1)
        assertThat(pairs.single().count).isEqualTo(5)
    }

    @Test fun `session history groups logs by local day newest first`() {
        val logs = listOf(log(1, today - 1, true), log(2, today, true), log(2, today, false, hour = 13), log(3, today, true, hour = 23))
        val h = StatsCalculator.sessionHistory(logs, zone)
        assertThat(h.map { it.epochDay }).containsExactly(today, today - 1).inOrder()
        assertThat(h.first().answers).isEqualTo(3)
        assertThat(h.first().distinctWords).isEqualTo(2)
        assertThat(h.first().correct).isEqualTo(2)
        // A time zone east of UTC moves the 23:00 UTC answer into the next local day.
        assertThat(StatsCalculator.sessionHistory(logs, ZoneOffset.ofHours(3)).first().epochDay).isEqualTo(today + 1)
    }

    @Test fun `category stats cover every category and weak ones are the least progressed`() {
        val rows = listOf(
            KeyMasteryCount("food", Mastery.NEW, 8), KeyMasteryCount("food", Mastery.LEARNING, 2),
            KeyMasteryCount("travel", Mastery.MASTERED, 10),
            KeyMasteryCount("business", Mastery.NEW, 2),
            KeyMasteryCount("", Mastery.FAMILIAR, 6), KeyMasteryCount("unknown", Mastery.NEW, 3),
        )
        val stats = StatsCalculator.categoryStats(rows)
        assertThat(stats.map { it.category }).containsExactlyElementsIn(Category.entries).inOrder()
        assertThat(stats.first { it.category == Category.OTHER }.total).isEqualTo(9)
        assertThat(stats.first { it.category == Category.TRAVEL }.progress).isEqualTo(1f)
        val weak = StatsCalculator.weakCategories(stats)
        // business is excluded (too few words), travel is fully mastered.
        assertThat(weak.map { it.category }).containsExactly(Category.FOOD, Category.OTHER).inOrder()
    }

    @Test fun `level distribution follows the language scale and keeps unleveled words`() {
        val rows = listOf(
            KeyMasteryCount("n5", Mastery.MASTERED, 4), KeyMasteryCount("N3", Mastery.NEW, 2), KeyMasteryCount("", Mastery.NEW, 1),
        )
        val d = StatsCalculator.levelDistribution(rows, LevelScale.JLPT)
        assertThat(d.map { it.key }).containsExactly("N5", "N4", "N3", "N2", "N1", "").inOrder()
        assertThat(d.first().mastered).isEqualTo(4)
        assertThat(StatsCalculator.levelDistribution(rows.take(2), LevelScale.JLPT)).hasSize(5)
    }
}
