package app.kotoba.core.data

import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.model.StudyLanguage
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.time.LocalDate

class CompetitionTest {
    private val monday = LocalDate.of(2026, 11, 9).toEpochDay() // a Monday

    @Test fun weekStartIsMonday() {
        assertThat(LeagueRules.weekStart(monday)).isEqualTo(monday)
        assertThat(LeagueRules.weekStart(monday + 6)).isEqualTo(monday)
        assertThat(LeagueRules.weekStart(monday + 7)).isEqualTo(monday + 7)
    }

    @Test fun promotesDemotesAndSkipsEmptyWeeks() {
        val activity = listOf(
            DailyActivityEntity(monday - 21, xp = 200), // Bronze → Silver (needs 150)
            DailyActivityEntity(monday - 14, xp = 120), DailyActivityEntity(monday - 13, xp = 120), // 240 ≥ 225 → Gold
            // week monday-7: nothing → below Gold's 100 → back to Silver
            DailyActivityEntity(monday, xp = 30),
        )
        val s = LeagueRules.status(activity, monday + 2)
        assertThat(s.history.map { it.tierAfter }).containsExactly(LeagueTier.SILVER, LeagueTier.GOLD, LeagueTier.SILVER).inOrder()
        assertThat(s.tier).isEqualTo(LeagueTier.SILVER)
        assertThat(s.weekXp).isEqualTo(30)
        assertThat(s.daysLeft).isEqualTo(5)
        assertThat(s.inDemotionZone).isTrue()
    }

    @Test fun bronzeNeverDropsAndDiamondNeverRises() {
        assertThat(LeagueRules.nextTier(LeagueTier.BRONZE, 0)).isEqualTo(LeagueTier.BRONZE)
        assertThat(LeagueRules.nextTier(LeagueTier.DIAMOND, 100_000)).isEqualTo(LeagueTier.DIAMOND)
        assertThat(LeagueRules.status(emptyList(), monday).tier).isEqualTo(LeagueTier.BRONZE)
    }

    @Test fun friendCodeRoundTripsInsideMessage() {
        val snap = FriendSnapshot("abc", "Юки 雪", monday, 420, streak = 12, level = 7, tier = 3, languages = listOf("ja", "zh"))
        val code = FriendCode.encode(snap)
        assertThat(FriendCode.decode("Join me on Kotoba! $code see you")).isEqualTo(snap)
    }

    @Test fun friendCodeRejectsTampering() {
        val code = FriendCode.encode(FriendSnapshot("abc", "Bob", monday, 10))
        val body = code.substringAfter("KTB1-").substringBeforeLast("-")
        val tampered = code.replace(body, body.dropLast(1) + if (body.last() == 'A') 'B' else 'A')
        assertThat(FriendCode.decode(tampered)).isNull()
        assertThat(FriendCode.decode("hello")).isNull()
    }

    @Test fun leaderboardZeroesStaleFriends() {
        val me = LeaderboardEntry("me", "Me", 100, 3, LeagueTier.GOLD, isMe = true, stale = false, updatedAt = 0)
        val board = Leaderboard.build(
            me,
            listOf(
                FriendSnapshot("a", "Ann", monday, 150),
                FriendSnapshot("b", "Ben", monday - 7, 999),
                FriendSnapshot("me", "Duplicate of me", monday, 5000),
            ),
            monday,
        )
        assertThat(board.map { it.id }).containsExactly("a", "me", "b").inOrder()
        assertThat(board.last().stale).isTrue()
        assertThat(board.last().weekXp).isEqualTo(0)
    }

    @Test fun seasonalEventsAndBoost() {
        val ny = SeasonalEvents.active(LocalDate.of(2027, 1, 3))!!
        assertThat(ny.id).isEqualTo("new_year")
        assertThat(ny.badgeKey).isEqualTo("event_new_year_2026")
        val hanami = SeasonalEvents.active(LocalDate.of(2027, 4, 1))!!
        assertThat(hanami.focus).isEqualTo(StudyLanguage.JA)
        assertThat(SeasonalEvents.boostedXp(10, hanami, StudyLanguage.JA)).isEqualTo(20)
        assertThat(SeasonalEvents.boostedXp(10, hanami, StudyLanguage.EN)).isEqualTo(15)
        assertThat(SeasonalEvents.active(LocalDate.of(2026, 11, 9))).isNull()
        assertThat(SeasonalEvents.upcoming(LocalDate.of(2026, 11, 9))!!.id).isEqualTo("new_year")
        assertThat(SeasonalEvents.active(LocalDate.of(2027, 2, 10))!!.id).isEqualTo("spring_festival")
    }

    @Test fun eventXpCountsOnlyItsDays() {
        val e = SeasonalEvents.forYear(2026).first { it.id == "halloween" }
        val days = listOf(
            DailyActivityEntity(LocalDate.of(2026, 10, 23).toEpochDay(), xp = 100),
            DailyActivityEntity(LocalDate.of(2026, 10, 24).toEpochDay(), xp = 40),
            DailyActivityEntity(LocalDate.of(2026, 10, 31).toEpochDay(), xp = 60),
        )
        assertThat(e.xpEarned(days)).isEqualTo(100)
    }

    @Test fun hardWordBonus() {
        val base = app.kotoba.core.srs.XpRules.forAnswer(correct = true, wasNew = false, wasWeak = false, combo = 1)
        val hard = app.kotoba.core.srs.XpRules.forAnswer(correct = true, wasNew = false, wasWeak = false, combo = 1, wasHard = true)
        assertThat(hard - base).isEqualTo(app.kotoba.core.srs.XpRules.HARD_WORD_BONUS)
    }
}
