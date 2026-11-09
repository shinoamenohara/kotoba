package app.kotoba.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.data.AddFriendResult
import app.kotoba.core.data.FriendCode
import app.kotoba.core.data.FriendSnapshot
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.Leaderboard
import app.kotoba.core.data.LeaderboardEntry
import app.kotoba.core.data.LeagueRules
import app.kotoba.core.data.LeagueStatus
import app.kotoba.core.data.SeasonalEvent
import app.kotoba.core.data.SeasonalEvents
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.SocialRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.AchievementDao
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.DailyActivityDao
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

data class EventProgress(val event: SeasonalEvent, val xp: Int, val badgeUnlocked: Boolean)

data class LeagueUiState(
    val league: LeagueStatus? = null,
    val board: List<LeaderboardEntry> = emptyList(),
    val active: EventProgress? = null,
    val upcoming: SeasonalEvent? = null,
    /** Badge keys `event_<id>_<year>` already earned. */
    val badges: List<String> = emptyList(),
)

@HiltViewModel
class LeagueViewModel @Inject constructor(
    activityDao: DailyActivityDao,
    achievementDao: AchievementDao,
    private val cardDao: CardDao,
    private val social: SocialRepository,
    private val settings: SettingsRepository,
    private val gamification: GamificationRepository,
    private val time: TimeProvider,
) : ViewModel() {
    private val _messages = MutableSharedFlow<AddFriendResult>(extraBufferCapacity = 1)
    val messages: SharedFlow<AddFriendResult> = _messages

    val state: StateFlow<LeagueUiState> = combine(
        activityDao.observeFrom(0),
        achievementDao.observeAll(),
        social.friends,
        social.installId,
        gamification.observeProfile(),
    ) { activity, achievements, friends, id, profile ->
        val today = time.today()
        val league = LeagueRules.status(activity, today)
        val name = settings.current().userName
        val me = LeaderboardEntry(id ?: "", name, league.weekXp, profile.streak, league.tier, isMe = true, stale = false, updatedAt = time.now())
        val badges = achievements.map { it.key }.filter { it.startsWith("event_") }
        val date = LocalDate.ofEpochDay(today)
        val active = SeasonalEvents.active(date)?.let { EventProgress(it, it.xpEarned(activity), it.badgeKey in badges) }
        LeagueUiState(
            league = league,
            board = Leaderboard.build(me, friends, league.weekStart),
            active = active,
            upcoming = if (active == null) SeasonalEvents.upcoming(date) else null,
            badges = badges,
        )
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LeagueUiState())

    init {
        viewModelScope.launch { social.ensureInstallId() }
    }

    /** Builds a fresh code with this week's numbers. */
    suspend fun myCode(): String {
        val s = settings.current()
        val league = state.first { it.league != null }.league!!
        val profile = gamification.observeProfile().first()
        return FriendCode.encode(
            FriendSnapshot(
                id = social.ensureInstallId(),
                name = s.userName.ifBlank { "Kotoba learner" },
                weekStart = league.weekStart,
                weekXp = league.weekXp,
                streak = profile.streak,
                level = profile.level.level,
                tier = league.tier.ordinal,
                mastered = cardDao.countMasteredAllLanguages(),
                languages = s.studyLanguages.map { it.code },
                updatedAt = time.now(),
            ),
        )
    }

    fun addFriend(text: String) = viewModelScope.launch { _messages.emit(social.addFriend(text)) }

    fun removeFriend(id: String) = viewModelScope.launch { social.removeFriend(id) }
}
