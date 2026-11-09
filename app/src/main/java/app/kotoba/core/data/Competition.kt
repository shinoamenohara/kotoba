package app.kotoba.core.data

import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.model.StudyLanguage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay
import java.util.Base64
import java.util.zip.CRC32
import kotlin.math.roundToInt

/** Weekly leagues. Promotion/demotion is decided by the learner's own weekly XP, so it works fully offline. */
enum class LeagueTier(val key: String, val emoji: String) {
    BRONZE("bronze", "🥉"),
    SILVER("silver", "🥈"),
    GOLD("gold", "🥇"),
    SAPPHIRE("sapphire", "🔷"),
    RUBY("ruby", "♦️"),
    EMERALD("emerald", "💚"),
    AMETHYST("amethyst", "🔮"),
    PEARL("pearl", "⚪"),
    OBSIDIAN("obsidian", "⚫"),
    DIAMOND("diamond", "💎"),
    ;

    /** Weekly XP needed to move up; null for the top tier. */
    val promoteAt: Int? get() = if (this == DIAMOND) null else 150 + 75 * ordinal

    /** A week below this drops the learner one tier (Bronze can't drop). */
    val demoteBelow: Int get() = if (ordinal == 0) 0 else 40 + 30 * ordinal

    companion object {
        fun fromOrdinal(i: Int) = entries.getOrElse(i) { BRONZE }
    }
}

data class WeekResult(val weekStart: Long, val xp: Int, val tierBefore: LeagueTier, val tierAfter: LeagueTier)

data class LeagueStatus(
    val tier: LeagueTier,
    val weekStart: Long,
    val weekXp: Int,
    val daysLeft: Int,
    val history: List<WeekResult>,
) {
    val promotionProgress: Float get() = tier.promoteAt?.let { (weekXp.toFloat() / it).coerceIn(0f, 1f) } ?: 1f
    val inDemotionZone: Boolean get() = weekXp < tier.demoteBelow
}

object LeagueRules {
    /** Monday of the week containing [epochDay]. */
    fun weekStart(epochDay: Long): Long {
        val d = LocalDate.ofEpochDay(epochDay)
        return d.minusDays((d.dayOfWeek.value - DayOfWeek.MONDAY.value).toLong()).toEpochDay()
    }

    /** Replays every finished week since the first activity to find the current tier. */
    fun status(activity: List<DailyActivityEntity>, today: Long): LeagueStatus {
        val currentWeek = weekStart(today)
        val xpByWeek = activity.groupBy { weekStart(it.epochDay) }.mapValues { (_, d) -> d.sumOf { it.xp } }
        val history = mutableListOf<WeekResult>()
        var tier = LeagueTier.BRONZE
        val first = xpByWeek.keys.minOrNull()
        if (first != null) {
            var week = first
            while (week < currentWeek) {
                val xp = xpByWeek[week] ?: 0
                val next = nextTier(tier, xp)
                history += WeekResult(week, xp, tier, next)
                tier = next
                week += 7
            }
        }
        return LeagueStatus(
            tier = tier,
            weekStart = currentWeek,
            weekXp = xpByWeek[currentWeek] ?: 0,
            daysLeft = (currentWeek + 7 - today).toInt(),
            history = history.asReversed().take(12),
        )
    }

    fun nextTier(tier: LeagueTier, weekXp: Int): LeagueTier {
        val up = tier.promoteAt
        return when {
            up != null && weekXp >= up -> LeagueTier.fromOrdinal(tier.ordinal + 1)
            weekXp < tier.demoteBelow -> LeagueTier.fromOrdinal(tier.ordinal - 1)
            else -> tier
        }
    }
}

/** What a learner shares with friends. Short keys keep the code compact enough for chat apps. */
@Serializable
data class FriendSnapshot(
    @SerialName("i") val id: String,
    @SerialName("n") val name: String,
    @SerialName("w") val weekStart: Long,
    @SerialName("x") val weekXp: Int,
    @SerialName("s") val streak: Int = 0,
    @SerialName("l") val level: Int = 1,
    @SerialName("t") val tier: Int = 0,
    @SerialName("m") val mastered: Int = 0,
    @SerialName("g") val languages: List<String> = emptyList(),
    @SerialName("u") val updatedAt: Long = 0,
)

/** `KTB1-<base64url(json)>-<crc32 hex>`: the checksum catches truncated or mistyped pastes. */
object FriendCode {
    private const val PREFIX = "KTB1-"
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = false }

    fun encode(s: FriendSnapshot): String {
        val body = Base64.getUrlEncoder().withoutPadding().encodeToString(json.encodeToString(FriendSnapshot.serializer(), s).toByteArray(Charsets.UTF_8))
        return PREFIX + body + "-" + crc(body)
    }

    /** Finds a code anywhere in [text] (shared messages usually carry extra words). */
    fun decode(text: String): FriendSnapshot? {
        val m = Regex("""KTB1-([A-Za-z0-9_-]+)-([0-9a-f]{8})""").find(text) ?: return null
        val (body, sum) = m.destructured
        if (crc(body) != sum) return null
        return runCatching {
            json.decodeFromString(FriendSnapshot.serializer(), String(Base64.getUrlDecoder().decode(body), Charsets.UTF_8))
        }.getOrNull()?.takeIf { it.id.isNotBlank() }?.let { it.copy(name = it.name.take(40)) }
    }

    private fun crc(body: String) = CRC32().apply { update(body.toByteArray(Charsets.UTF_8)) }.value.toString(16).padStart(8, '0')
}

data class LeaderboardEntry(
    val id: String,
    val name: String,
    val weekXp: Int,
    val streak: Int,
    val tier: LeagueTier,
    val isMe: Boolean,
    /** Friend's snapshot is from an earlier week, so their XP this week is unknown. */
    val stale: Boolean,
    val updatedAt: Long,
)

object Leaderboard {
    fun build(me: LeaderboardEntry, friends: List<FriendSnapshot>, weekStart: Long): List<LeaderboardEntry> {
        val others = friends.filter { it.id != me.id }.map {
            val stale = it.weekStart != weekStart
            LeaderboardEntry(
                id = it.id, name = it.name, weekXp = if (stale) 0 else it.weekXp, streak = it.streak,
                tier = LeagueTier.fromOrdinal(it.tier), isMe = false, stale = stale, updatedAt = it.updatedAt,
            )
        }
        return (others + me).sortedWith(compareByDescending<LeaderboardEntry> { it.weekXp }.thenBy { it.stale }.thenByDescending { it.isMe })
    }
}

/**
 * Calendar events with boosted XP. Events tied to a culture give a bigger boost to that language
 * (Hanami → Japanese, Spring Festival → Chinese, Halloween → English).
 */
data class SeasonalEvent(
    val id: String,
    val emoji: String,
    val start: LocalDate,
    val end: LocalDate,
    val targetXp: Int,
    val focus: StudyLanguage? = null,
) {
    val badgeKey: String get() = "event_${id}_${start.year}"
    operator fun contains(day: LocalDate) = !day.isBefore(start) && !day.isAfter(end)
    fun multiplierFor(language: StudyLanguage?): Double = if (focus != null && focus == language) FOCUS_MULTIPLIER else MULTIPLIER

    fun xpEarned(activity: List<DailyActivityEntity>): Int =
        activity.filter { it.epochDay in start.toEpochDay()..end.toEpochDay() }.sumOf { it.xp }

    companion object {
        const val MULTIPLIER = 1.5
        const val FOCUS_MULTIPLIER = 2.0
    }
}

object SeasonalEvents {
    // Lunar calendar dates don't follow a simple rule; outside this table those events are skipped.
    private val lunarNewYear = mapOf(
        2025 to "2025-01-29", 2026 to "2026-02-17", 2027 to "2027-02-06", 2028 to "2028-01-26",
        2029 to "2029-02-13", 2030 to "2030-02-03", 2031 to "2031-01-23", 2032 to "2032-02-11",
    )
    private val midAutumn = mapOf(
        2025 to "2025-10-06", 2026 to "2026-09-25", 2027 to "2027-09-15", 2028 to "2028-10-03",
        2029 to "2029-09-22", 2030 to "2030-09-12", 2031 to "2031-10-01", 2032 to "2032-09-19",
    )

    fun forYear(year: Int): List<SeasonalEvent> = buildList {
        add(fixed("new_year", "🎆", year, MonthDay.of(12, 26), MonthDay.of(1, 7), 600))
        lunarNewYear[year]?.let { d ->
            val day = LocalDate.parse(d)
            add(SeasonalEvent("spring_festival", "🧧", day.minusDays(3), day.plusDays(10), 700, StudyLanguage.ZH))
        }
        add(fixed("hanami", "🌸", year, MonthDay.of(3, 25), MonthDay.of(4, 10), 700, StudyLanguage.JA))
        add(fixed("tanabata", "🎋", year, MonthDay.of(7, 1), MonthDay.of(7, 7), 350, StudyLanguage.JA))
        add(fixed("summer_reading", "📚", year, MonthDay.of(7, 15), MonthDay.of(8, 15), 1500))
        midAutumn[year]?.let { d ->
            val day = LocalDate.parse(d)
            add(SeasonalEvent("mid_autumn", "🥮", day.minusDays(5), day.plusDays(2), 400, StudyLanguage.ZH))
        }
        add(fixed("halloween", "🎃", year, MonthDay.of(10, 24), MonthDay.of(10, 31), 400, StudyLanguage.EN))
    }

    /** Event running on [day], if any (New Year spans two calendar years). */
    fun active(day: LocalDate): SeasonalEvent? =
        (forYear(day.year - 1) + forYear(day.year)).firstOrNull { day in it }

    /** Next event starting after [day]. */
    fun upcoming(day: LocalDate): SeasonalEvent? =
        (forYear(day.year) + forYear(day.year + 1)).filter { it.start.isAfter(day) }.minByOrNull { it.start }

    /** End-year events (New Year) are keyed by the year they start in. */
    private fun fixed(id: String, emoji: String, year: Int, from: MonthDay, to: MonthDay, target: Int, focus: StudyLanguage? = null): SeasonalEvent {
        val start = from.atYear(year)
        val endYear = if (to.isBefore(from)) year + 1 else year
        return SeasonalEvent(id, emoji, start, to.atYear(endYear), target, focus)
    }

    fun boostedXp(baseXp: Int, event: SeasonalEvent?, language: StudyLanguage?): Int =
        if (event == null || baseXp <= 0) baseXp else (baseXp * event.multiplierFor(language)).roundToInt()
}
