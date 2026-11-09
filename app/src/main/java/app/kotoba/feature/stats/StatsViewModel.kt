package app.kotoba.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.data.GamificationProfile
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ConfusionDao
import app.kotoba.core.db.DailyActivityDao
import app.kotoba.core.db.DailyActivityEntity
import app.kotoba.core.db.DayCount
import app.kotoba.core.db.KeyMasteryCount
import app.kotoba.core.db.MasteryCount
import app.kotoba.core.db.ReviewLogDao
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.model.LevelScale
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.LevelInfo
import app.kotoba.core.srs.WeakWordsRadar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class StatsUiState(
    val loading: Boolean = true,
    val language: StudyLanguage = StudyLanguage.EN,
    val period: StatsPeriod = StatsPeriod.WEEK,
    val today: Long = 0,
    val totalWords: Int = 0,
    val mastery: Map<Mastery, Int> = emptyMap(),
    val dueCount: Int = 0,
    val weakCount: Int = 0,
    val newToday: Int = 0,
    val newWeek: Int = 0,
    val newMonth: Int = 0,
    val summary: PeriodSummary = PeriodSummary(),
    val streak: Int = 0,
    val level: LevelInfo? = null,
    val dailyGoal: Int = 50,
    val chartDays: List<Long> = emptyList(),
    val growthStudied: List<Int> = emptyList(),
    val growthMastered: List<Int> = emptyList(),
    val daily: List<DailyActivityEntity> = emptyList(),
    val accuracyTrend: List<Float?> = emptyList(),
    val categories: List<CategoryStat> = emptyList(),
    val levelScale: LevelScale = LevelScale.CEFR,
    val levels: List<GroupStat> = emptyList(),
    val mostMissed: List<CardCount> = emptyList(),
    val confused: List<ConfusedPair> = emptyList(),
    val byExercise: List<ExerciseAccuracy> = emptyList(),
    val slowest: List<SlowWord> = emptyList(),
    val history: List<DaySessions> = emptyList(),
    /** Terms for cards appearing in [history]. */
    val terms: Map<Long, CardEntity> = emptyMap(),
) {
    val mastered: Int get() = mastery[Mastery.MASTERED] ?: 0
}

private data class VocabData(
    val mastery: List<MasteryCount>,
    val categories: List<KeyMasteryCount>,
    val levels: List<KeyMasteryCount>,
    val studied: List<DayCount>,
    val masteredPerDay: List<DayCount>,
)

private data class Counters(val due: Int, val weak: Int)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    settings: SettingsRepository,
    private val cardDao: CardDao,
    private val logDao: ReviewLogDao,
    private val activityDao: DailyActivityDao,
    private val confusionDao: ConfusionDao,
    private val gamification: GamificationRepository,
    private val time: TimeProvider,
) : ViewModel() {

    private val period = MutableStateFlow(StatsPeriod.WEEK)

    fun setPeriod(p: StatsPeriod) { period.value = p }

    val state: StateFlow<StatsUiState> = settings.settings.map { it.activeLanguage }.distinctUntilChanged()
        .flatMapLatest { lang -> observe(lang) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), StatsUiState())

    private fun observe(lang: StudyLanguage): Flow<StatsUiState> {
        val today = time.today()
        val maxWindow = StatsCalculator.chartWindow(StatsPeriod.MONTH, today)
        val offset = time.zoneOffsetMs()
        val vocab = combine(
            cardDao.masteryCounts(lang.code),
            cardDao.categoryMasteryCounts(lang.code),
            cardDao.levelMasteryCounts(lang.code),
            cardDao.studiedPerDay(lang.code, offset),
            cardDao.masteredPerDay(lang.code, offset),
            ::VocabData,
        )
        val counters = combine(
            cardDao.countDue(lang.code, time.now()),
            cardDao.countWeak(lang.code, WeakWordsRadar.WEAK_THRESHOLD),
            ::Counters,
        )
        // Logs carry no language; keep only those of the active language's cards.
        val logsWithCards: Flow<Pair<List<ReviewLogEntity>, Map<Long, CardEntity>>> =
            logDao.observeSince(time.startOfDay(maxWindow.fromDay)).mapLatest { logs ->
                val cards = cardDao.allForLanguage(lang.code).associateBy { it.id }
                logs.filter { it.cardId in cards } to cards
            }.flowOn(Dispatchers.IO)

        return combine(
            vocab,
            counters,
            combine(activityDao.observeFrom(maxWindow.fromDay), gamification.observeProfile(), ::Pair),
            combine(logsWithCards, confusionDao.observeTop(100), ::Pair),
            period,
        ) { v, c, (activity, profile), (lc, confusions), p ->
            build(lang, today, v, c, activity, profile, lc.first, lc.second, confusions, p)
        }
    }

    private fun build(
        lang: StudyLanguage,
        today: Long,
        v: VocabData,
        c: Counters,
        activity: List<DailyActivityEntity>,
        profile: GamificationProfile,
        logs: List<ReviewLogEntity>,
        cards: Map<Long, CardEntity>,
        confusions: List<app.kotoba.core.db.ConfusionEntity>,
        p: StatsPeriod,
    ): StatsUiState {
        val zone = time.zone()
        val window = StatsCalculator.window(p, today)
        val chart = StatsCalculator.chartWindow(p, today)
        val periodLogs = StatsCalculator.logsIn(logs, window, zone)
        fun studiedIn(w: DayWindow) = v.studied.filter { it.epochDay in w }.sumOf { it.count }
        val history = StatsCalculator.sessionHistory(StatsCalculator.logsIn(logs, chart, zone), zone).take(14)
        return StatsUiState(
            loading = false,
            language = lang,
            period = p,
            today = today,
            totalWords = v.mastery.sumOf { it.count },
            mastery = StatsCalculator.masteryMap(v.mastery),
            dueCount = c.due,
            weakCount = c.weak,
            newToday = studiedIn(StatsCalculator.window(StatsPeriod.DAY, today)),
            newWeek = studiedIn(StatsCalculator.window(StatsPeriod.WEEK, today)),
            newMonth = studiedIn(StatsCalculator.window(StatsPeriod.MONTH, today)),
            summary = StatsCalculator.summarize(activity, logs, window, zone),
            streak = profile.streak,
            level = profile.level,
            dailyGoal = profile.dailyGoal,
            chartDays = chart.days,
            growthStudied = StatsCalculator.cumulative(v.studied, chart),
            growthMastered = StatsCalculator.cumulative(v.masteredPerDay, chart),
            daily = StatsCalculator.dailySeries(activity, chart),
            accuracyTrend = StatsCalculator.accuracyTrend(activity, chart),
            categories = StatsCalculator.categoryStats(v.categories).filter { it.total > 0 }.sortedByDescending { it.total },
            levelScale = lang.levelScale,
            levels = StatsCalculator.levelDistribution(v.levels, lang.levelScale),
            mostMissed = StatsCalculator.mostMissed(periodLogs, cards),
            confused = StatsCalculator.confusedPairs(confusions, cards),
            byExercise = StatsCalculator.accuracyByExercise(periodLogs),
            slowest = StatsCalculator.slowest(periodLogs, cards),
            history = history,
            terms = history.flatMap { d -> d.logs.map { it.cardId } }.distinct().mapNotNull { cards[it] }.associateBy { it.id },
        )
    }
}
