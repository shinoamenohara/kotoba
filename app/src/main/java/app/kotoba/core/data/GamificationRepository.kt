package app.kotoba.core.data

import app.kotoba.core.db.AchievementEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.srs.LevelInfo
import app.kotoba.core.srs.XpRules
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Achievement catalogue. Titles/descriptions live in strings as `achievement_<key>_title/_desc`. */
enum class Achievement(val key: String, val icon: String, val target: Int) {
    FIRST_WORD("first_word", "🌱", 1),
    WORDS_100("words_100", "💯", 100),
    WORDS_500("words_500", "📗", 500),
    MASTERED_100("mastered_100", "⭐", 100),
    MASTERED_1000("mastered_1000", "🏆", 1000),
    STREAK_7("streak_7", "🔥", 7),
    STREAK_30("streak_30", "🌋", 30),
    STREAK_100("streak_100", "☄️", 100),
    REVIEWS_100("reviews_100", "🔁", 100),
    REVIEWS_1000("reviews_1000", "♾️", 1000),
    COMBO_25("combo_25", "⚡", 25),
    CATEGORY_MASTERED("category_mastered", "🗺️", 1),
    DECK_COMPLETED("deck_completed", "🎯", 1),
    FIRST_BOOK("first_book", "📖", 1),
    FIRST_MANGA("first_manga", "🎌", 1),
    FIRST_IMPORT("first_import", "📦", 1),
    AI_TUTOR("ai_tutor", "🤖", 1),
    POLYGLOT("polyglot", "🌍", 3),
    ;

    companion object {
        fun fromKey(key: String) = entries.firstOrNull { it.key == key }
    }
}

data class Quest(val key: String, val progress: Int, val target: Int, val xpReward: Int) {
    val done: Boolean get() = progress >= target
}

data class GamificationProfile(
    val level: LevelInfo,
    val streak: Int,
    val today: DailyActivityEntity,
    val dailyGoal: Int,
    val unlocked: Map<Achievement, Long>,
    val weeklyQuests: List<Quest>,
    val dailyChallenge: Quest,
) {
    val goalProgress: Float get() = (today.xp.toFloat() / dailyGoal).coerceIn(0f, 1f)
}

@Singleton
class GamificationRepository @Inject constructor(
    private val db: KotobaDatabase,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    private val activity = db.dailyActivityDao()
    private val achievements = db.achievementDao()

    fun observeProfile(): Flow<GamificationProfile> {
        val today = time.today()
        return combine(
            activity.observeTotalXp(),
            activity.observeActiveDaysDesc(),
            activity.observeFrom(today - 6),
            achievements.observeAll(),
            settings.settings,
        ) { xp, activeDays, week, unlocked, s ->
            val todayRow = week.firstOrNull { it.epochDay == today } ?: DailyActivityEntity(today)
            GamificationProfile(
                level = XpRules.levelInfo(xp),
                streak = computeStreak(activeDays, today),
                today = todayRow,
                dailyGoal = s.dailyXpGoal,
                unlocked = unlocked.mapNotNull { a -> Achievement.fromKey(a.key)?.let { it to a.unlockedAt } }.toMap(),
                weeklyQuests = weeklyQuests(week),
                dailyChallenge = Quest("daily_combo", todayRow.bestCombo, DAILY_COMBO_TARGET, 15),
            )
        }
    }

    suspend fun currentStreak(): Int = computeStreak(activity.activeDaysDesc(), time.today())

    suspend fun addStudyTime(ms: Long) {
        val day = time.today()
        val row = activity.get(day) ?: DailyActivityEntity(day)
        activity.upsert(row.copy(studyMs = row.studyMs + ms.coerceIn(0, 4 * 3_600_000L)))
    }

    /** Unlocks an event achievement (first book, first import…). Returns true if newly unlocked. */
    suspend fun unlock(a: Achievement): Boolean = achievements.insert(AchievementEntity(a.key, time.now())) != -1L

    /** Re-evaluates progress achievements; returns the ones unlocked by this call. */
    suspend fun checkProgressAchievements(bestComboToday: Int): List<Achievement> {
        val already = achievements.all().map { it.key }.toSet()
        val cards = db.cardDao()
        val studied = cards.countStudiedAllLanguages()
        val mastered = cards.countMasteredAllLanguages()
        val reviews = db.reviewLogDao().countAll()
        val streak = currentStreak()
        val categories = cards.categoryMasteryCountsAll().groupBy { it.key }
        val categoryDone = categories.any { (key, rows) ->
            key.isNotBlank() && rows.sumOf { it.count } >= 10 && rows.all { it.mastery == app.kotoba.core.model.Mastery.MASTERED }
        }
        val candidates = buildList {
            if (studied >= 1) add(Achievement.FIRST_WORD)
            if (studied >= 100) add(Achievement.WORDS_100)
            if (studied >= 500) add(Achievement.WORDS_500)
            if (mastered >= 100) add(Achievement.MASTERED_100)
            if (mastered >= 1000) add(Achievement.MASTERED_1000)
            if (streak >= 7) add(Achievement.STREAK_7)
            if (streak >= 30) add(Achievement.STREAK_30)
            if (streak >= 100) add(Achievement.STREAK_100)
            if (reviews >= 100) add(Achievement.REVIEWS_100)
            if (reviews >= 1000) add(Achievement.REVIEWS_1000)
            if (bestComboToday >= 25) add(Achievement.COMBO_25)
            if (categoryDone) add(Achievement.CATEGORY_MASTERED)
        }
        val deckDone = db.deckDao().all().any { deck ->
            val c = cards.forDeck(deck.id)
            c.size >= 10 && c.all { it.mastery == app.kotoba.core.model.Mastery.MASTERED }
        }
        val all = if (deckDone) candidates + Achievement.DECK_COMPLETED else candidates
        val polyglot = cards.all().filter { it.firstStudiedAt != null }.map { it.language }.distinct().size >= 3
        val result = (if (polyglot) all + Achievement.POLYGLOT else all).filter { it.key !in already }
        result.forEach { unlock(it) }
        return result
    }

    /** Unlocks the running seasonal event's badge once its XP target is reached. */
    suspend fun checkEventBadge(): SeasonalEvent? {
        val event = SeasonalEvents.active(java.time.LocalDate.ofEpochDay(time.today())) ?: return null
        if (event.xpEarned(activity.all()) < event.targetXp) return null
        return event.takeIf { achievements.insert(AchievementEntity(event.badgeKey, time.now())) != -1L }
    }

    private fun weeklyQuests(week: List<DailyActivityEntity>): List<Quest> = listOf(
        Quest("weekly_reviews", week.sumOf { it.reviews }, 200, 50),
        Quest("weekly_new_words", week.sumOf { it.newWords }, 30, 50),
        Quest("weekly_goal_days", week.count { it.goalReached }, 5, 75),
        Quest("weekly_minutes", (week.sumOf { it.studyMs } / 60_000L).toInt(), 60, 50),
    )

    companion object {
        const val DAILY_COMBO_TARGET = 15

        /** Consecutive active days ending today, or yesterday if today has no activity yet. */
        fun computeStreak(activeDaysDesc: List<Long>, today: Long): Int {
            if (activeDaysDesc.isEmpty()) return 0
            var expected = if (activeDaysDesc.first() == today) today else today - 1
            var streak = 0
            for (day in activeDaysDesc) {
                if (day > expected) continue
                if (day == expected) { streak++; expected-- } else break
            }
            return streak
        }
    }
}
