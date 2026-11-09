package app.kotoba.feature.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.DayCount
import app.kotoba.core.db.KeyMasteryCount
import app.kotoba.core.db.MasteryCount
import app.kotoba.core.model.Category
import app.kotoba.core.model.LevelScale
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.StudyLanguage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class VocabularyMapUiState(
    val loading: Boolean = true,
    val language: StudyLanguage = StudyLanguage.EN,
    val total: Int = 0,
    val mastery: Map<Mastery, Int> = emptyMap(),
    val categories: List<CategoryStat> = emptyList(),
    val weak: Set<Category> = emptySet(),
    val levelScale: LevelScale = LevelScale.CEFR,
    val levels: List<GroupStat> = emptyList(),
    val partsOfSpeech: List<GroupStat> = emptyList(),
    val historyDays: List<Long> = emptyList(),
    val historyStudied: List<Int> = emptyList(),
    val historyMastered: List<Int> = emptyList(),
)

private data class MapRows(
    val mastery: List<MasteryCount>,
    val categories: List<KeyMasteryCount>,
    val levels: List<KeyMasteryCount>,
    val pos: List<KeyMasteryCount>,
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class VocabularyMapViewModel @Inject constructor(
    settings: SettingsRepository,
    private val cardDao: CardDao,
    private val time: TimeProvider,
) : ViewModel() {

    val state: StateFlow<VocabularyMapUiState> = settings.settings.map { it.activeLanguage }.distinctUntilChanged()
        .flatMapLatest { observe(it) }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), VocabularyMapUiState())

    private fun observe(lang: StudyLanguage): Flow<VocabularyMapUiState> {
        val offset = time.zoneOffsetMs()
        val rows = combine(
            cardDao.masteryCounts(lang.code),
            cardDao.categoryMasteryCounts(lang.code),
            cardDao.levelMasteryCounts(lang.code),
            cardDao.posMasteryCounts(lang.code),
            ::MapRows,
        )
        return combine(rows, cardDao.studiedPerDay(lang.code, offset), cardDao.masteredPerDay(lang.code, offset)) { r, studied, mastered ->
            build(lang, r, studied, mastered)
        }
    }

    private fun build(lang: StudyLanguage, r: MapRows, studied: List<DayCount>, mastered: List<DayCount>): VocabularyMapUiState {
        val today = time.today()
        // History spans from the first studied day (at least 30 days) so the chart covers the whole journey.
        val first = studied.minOfOrNull { it.epochDay } ?: today
        val window = DayWindow(minOf(first, today - 29), today)
        val categories = StatsCalculator.categoryStats(r.categories)
        return VocabularyMapUiState(
            loading = false,
            language = lang,
            total = r.mastery.sumOf { it.count },
            mastery = StatsCalculator.masteryMap(r.mastery),
            categories = categories,
            weak = StatsCalculator.weakCategories(categories).map { it.category }.toSet(),
            levelScale = lang.levelScale,
            levels = StatsCalculator.levelDistribution(r.levels, lang.levelScale),
            partsOfSpeech = StatsCalculator.partOfSpeechStats(r.pos),
            historyDays = window.days,
            historyStudied = StatsCalculator.cumulative(studied, window),
            historyMastered = StatsCalculator.cumulative(mastered, window),
        )
    }
}
