package app.kotoba.feature.stats

import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ConfusionEntity
import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.db.DayCount
import app.kotoba.core.db.KeyMasteryCount
import app.kotoba.core.db.MasteryCount
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.model.Category
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.LevelScale
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.PartOfSpeech
import java.time.Instant
import java.time.ZoneId

/** Report period. [chartDays] is the span shown by charts for that period. */
enum class StatsPeriod(val days: Int, val chartDays: Int) {
    DAY(1, 7),
    WEEK(7, 28),
    MONTH(30, 90),
}

/** Inclusive range of local epoch days. */
data class DayWindow(val fromDay: Long, val toDay: Long) {
    val size: Int get() = (toDay - fromDay + 1).toInt()
    operator fun contains(day: Long) = day in fromDay..toDay
    val days: List<Long> get() = (fromDay..toDay).toList()
}

data class PeriodSummary(
    val newWords: Int = 0,
    val reviews: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val studyMs: Long = 0,
    val forgotten: Int = 0,
    val recovered: Int = 0,
    val xp: Int = 0,
    val goalDays: Int = 0,
    val activeDays: Int = 0,
    val avgResponseMs: Long = 0,
) {
    /** 0..1, or null when nothing was answered. */
    val accuracy: Float? get() = StatsCalculator.accuracy(correct, correct + wrong)
}

data class CardCount(val card: CardEntity, val count: Int, val total: Int = count)
data class SlowWord(val card: CardEntity, val avgMs: Long, val answers: Int)
data class ConfusedPair(val a: CardEntity, val b: CardEntity, val count: Int)
data class ExerciseAccuracy(val type: ExerciseType, val correct: Int, val total: Int) {
    val accuracy: Float get() = StatsCalculator.accuracy(correct, total) ?: 0f
}
data class DaySessions(
    val epochDay: Long,
    val answers: Int,
    val correct: Int,
    val distinctWords: Int,
    /** Sum of response times — a lower bound of time spent. */
    val activeMs: Long,
    val logs: List<ReviewLogEntity>,
)

data class GroupStat(val key: String, val counts: Map<Mastery, Int>) {
    val total: Int get() = counts.values.sum()
    val mastered: Int get() = counts[Mastery.MASTERED] ?: 0
    /** Weighted progress 0..1: learning ⅓, familiar ⅔, mastered 1. */
    val progress: Float get() = StatsCalculator.weightedProgress(counts)
}

data class CategoryStat(val category: Category, val counts: Map<Mastery, Int>) {
    val total: Int get() = counts.values.sum()
    val mastered: Int get() = counts[Mastery.MASTERED] ?: 0
    val progress: Float get() = StatsCalculator.weightedProgress(counts)
}

/** Pure aggregation for the statistics and vocabulary map screens. */
object StatsCalculator {

    fun window(period: StatsPeriod, today: Long): DayWindow = DayWindow(today - period.days + 1, today)
    fun chartWindow(period: StatsPeriod, today: Long): DayWindow = DayWindow(today - period.chartDays + 1, today)

    fun epochDay(millis: Long, zone: ZoneId): Long = Instant.ofEpochMilli(millis).atZone(zone).toLocalDate().toEpochDay()

    fun accuracy(correct: Int, total: Int): Float? = if (total <= 0) null else correct.toFloat() / total

    fun weightedProgress(counts: Map<Mastery, Int>): Float {
        val total = counts.values.sum()
        if (total == 0) return 0f
        val score = (counts[Mastery.LEARNING] ?: 0) / 3f + (counts[Mastery.FAMILIAR] ?: 0) * 2f / 3f + (counts[Mastery.MASTERED] ?: 0)
        return score / total
    }

    fun masteryMap(rows: List<MasteryCount>): Map<Mastery, Int> =
        Mastery.entries.associateWith { m -> rows.filter { it.mastery == m }.sumOf { it.count } }

    /**
     * Running total for each day of [window], including everything counted before it,
     * so the line starts at the real vocabulary size rather than 0.
     */
    fun cumulative(perDay: List<DayCount>, window: DayWindow): List<Int> {
        var running = perDay.filter { it.epochDay < window.fromDay }.sumOf { it.count }
        val byDay = perDay.groupBy { it.epochDay }.mapValues { (_, v) -> v.sumOf { it.count } }
        return window.days.map { day -> running += byDay[day] ?: 0; running }
    }

    /** One row per day of [window]; missing days are zero-filled. */
    fun dailySeries(activity: List<DailyActivityEntity>, window: DayWindow): List<DailyActivityEntity> {
        val byDay = activity.associateBy { it.epochDay }
        return window.days.map { byDay[it] ?: DailyActivityEntity(it) }
    }

    /** Daily accuracy (0..1); null for days without answers. */
    fun accuracyTrend(activity: List<DailyActivityEntity>, window: DayWindow): List<Float?> =
        dailySeries(activity, window).map { accuracy(it.correct, it.correct + it.wrong) }

    fun summarize(activity: List<DailyActivityEntity>, logs: List<ReviewLogEntity>, window: DayWindow, zone: ZoneId): PeriodSummary {
        val rows = activity.filter { it.epochDay in window }
        val inWindow = logs.filter { epochDay(it.timestamp, zone) in window }
        val avg = inWindow.filter { it.responseMs > 0 }.map { it.responseMs }.let { if (it.isEmpty()) 0L else it.sum() / it.size }
        return PeriodSummary(
            newWords = rows.sumOf { it.newWords },
            reviews = rows.sumOf { it.reviews },
            correct = rows.sumOf { it.correct },
            wrong = rows.sumOf { it.wrong },
            studyMs = rows.sumOf { it.studyMs },
            forgotten = rows.sumOf { it.forgotten },
            recovered = rows.sumOf { it.recovered },
            xp = rows.sumOf { it.xp },
            goalDays = rows.count { it.goalReached },
            activeDays = rows.count { it.reviews > 0 || it.newWords > 0 },
            avgResponseMs = avg,
        )
    }

    fun logsIn(logs: List<ReviewLogEntity>, window: DayWindow, zone: ZoneId): List<ReviewLogEntity> =
        logs.filter { epochDay(it.timestamp, zone) in window }

    fun mostMissed(logs: List<ReviewLogEntity>, cards: Map<Long, CardEntity>, limit: Int = 5): List<CardCount> =
        logs.groupBy { it.cardId }
            .mapNotNull { (id, l) ->
                val wrong = l.count { !it.correct }
                val card = cards[id]
                if (wrong == 0 || card == null) null else CardCount(card, wrong, l.size)
            }
            .sortedWith(compareByDescending<CardCount> { it.count }.thenByDescending { it.count.toFloat() / it.total })
            .take(limit)

    fun slowest(logs: List<ReviewLogEntity>, cards: Map<Long, CardEntity>, limit: Int = 5, minAnswers: Int = 2): List<SlowWord> =
        logs.filter { it.responseMs > 0 }.groupBy { it.cardId }
            .mapNotNull { (id, l) ->
                val card = cards[id]
                if (card == null || l.size < minAnswers) null else SlowWord(card, l.sumOf { it.responseMs } / l.size, l.size)
            }
            .sortedByDescending { it.avgMs }
            .take(limit)

    fun accuracyByExercise(logs: List<ReviewLogEntity>): List<ExerciseAccuracy> =
        logs.groupBy { it.exercise }
            .map { (type, l) -> ExerciseAccuracy(type, l.count { it.correct }, l.size) }
            .sortedBy { it.accuracy }

    /** Collapses (a,b) and (b,a) into one pair; drops pairs outside [cards]. */
    fun confusedPairs(confusions: List<ConfusionEntity>, cards: Map<Long, CardEntity>, limit: Int = 5): List<ConfusedPair> =
        confusions.groupBy { minOf(it.cardId, it.otherCardId) to maxOf(it.cardId, it.otherCardId) }
            .mapNotNull { (ids, rows) ->
                val a = cards[ids.first]
                val b = cards[ids.second]
                if (a == null || b == null || a.id == b.id) null else ConfusedPair(a, b, rows.sumOf { it.count })
            }
            .sortedByDescending { it.count }
            .take(limit)

    /** Review logs grouped by local day, newest first. */
    fun sessionHistory(logs: List<ReviewLogEntity>, zone: ZoneId): List<DaySessions> =
        logs.groupBy { epochDay(it.timestamp, zone) }
            .map { (day, l) ->
                val sorted = l.sortedByDescending { it.timestamp }
                DaySessions(day, l.size, l.count { it.correct }, l.map { it.cardId }.distinct().size, l.sumOf { it.responseMs }, sorted)
            }
            .sortedByDescending { it.epochDay }

    /** Every [Category] (also empty ones) in enum order; unknown or blank keys fall into OTHER. */
    fun categoryStats(rows: List<KeyMasteryCount>): List<CategoryStat> {
        val grouped = rows.groupBy { Category.fromKey(it.key) }
        return Category.entries.map { c ->
            val r = grouped[c].orEmpty()
            CategoryStat(c, Mastery.entries.associateWith { m -> r.filter { it.mastery == m }.sumOf { it.count } })
        }
    }

    /** Categories with enough words whose progress is clearly behind; worst first. */
    fun weakCategories(stats: List<CategoryStat>, minWords: Int = 5, maxProgress: Float = 0.5f, limit: Int = 3): List<CategoryStat> =
        stats.filter { it.total >= minWords && it.progress < maxProgress }
            .sortedWith(compareBy<CategoryStat> { it.progress }.thenByDescending { it.total })
            .take(limit)

    /** One entry per level of [scale] in order, plus a trailing "" entry for unleveled words if any. */
    fun levelDistribution(rows: List<KeyMasteryCount>, scale: LevelScale): List<GroupStat> {
        val norm = rows.groupBy { r -> scale.levels.firstOrNull { it.equals(r.key.trim(), ignoreCase = true) } ?: "" }
        val levels = scale.levels.map { lvl -> group(lvl, norm[lvl].orEmpty()) }
        val other = norm[""].orEmpty()
        return if (other.sumOf { it.count } > 0) levels + group("", other) else levels
    }

    fun partOfSpeechStats(rows: List<KeyMasteryCount>): List<GroupStat> =
        rows.groupBy { PartOfSpeech.fromKey(it.key).key }
            .map { (k, r) -> group(k, r) }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }

    private fun group(key: String, rows: List<KeyMasteryCount>) =
        GroupStat(key, Mastery.entries.associateWith { m -> rows.filter { it.mastery == m }.sumOf { it.count } })
}
