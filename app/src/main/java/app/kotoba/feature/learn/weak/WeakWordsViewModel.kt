package app.kotoba.feature.learn.weak

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ConfusionDao
import app.kotoba.core.db.ReviewLogDao
import app.kotoba.core.model.SrsState
import app.kotoba.core.srs.LearningSnapshot
import app.kotoba.core.srs.WeakReason
import app.kotoba.core.srs.WeakWordsRadar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
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

data class WeakItem(val card: CardEntity, val reasons: Set<WeakReason>, val score: Double)
data class ConfusedPair(val a: CardEntity, val b: CardEntity, val count: Int)

data class WeakUiState(
    val loading: Boolean = true,
    val items: List<WeakItem> = emptyList(),
    val pairs: List<ConfusedPair> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WeakWordsViewModel @Inject constructor(
    settings: SettingsRepository,
    private val cards: CardDao,
    private val logs: ReviewLogDao,
    private val confusions: ConfusionDao,
) : ViewModel() {
    val state: StateFlow<WeakUiState> = settings.settings
        .map { it.activeLanguage.code }
        .distinctUntilChanged()
        .flatMapLatest { lang ->
            combine(cards.observeWeak(lang, WeakWordsRadar.WEAK_THRESHOLD), confusions.observeTop(30)) { weak, top -> weak to top }
                .mapLatest { (weak, top) ->
                    val items = weak.map { c -> WeakItem(c, reasonsFor(c), c.weakScore) }
                    val ids = top.flatMap { listOf(it.cardId, it.otherCardId) }.distinct()
                    val byId = if (ids.isEmpty()) emptyMap() else cards.getMany(ids).associateBy { it.id }
                    // A↔B and B↔A are the same confusion.
                    val pairs = top.mapNotNull { e ->
                        val a = byId[e.cardId] ?: return@mapNotNull null
                        val b = byId[e.otherCardId] ?: return@mapNotNull null
                        if (a.language != lang) null else ConfusedPair(a, b, e.count)
                    }
                        .groupBy { setOf(it.a.id, it.b.id) }
                        .map { (_, list) -> list.first().copy(count = list.sumOf { it.count }) }
                        .sortedByDescending { it.count }
                        .take(8)
                    WeakUiState(loading = false, items = items, pairs = pairs)
                }
        }
        .flowOn(Dispatchers.IO)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), WeakUiState())

    /** Re-runs the radar on stored stats + recent history to explain why a word is weak. */
    private suspend fun reasonsFor(c: CardEntity): Set<WeakReason> {
        val history = logs.recentForCard(c.id, 30)
        val snapshot = LearningSnapshot(
            state = c.state,
            stability = c.stability,
            reps = c.reps,
            lapses = c.lapses,
            correctCount = c.correctCount,
            wrongCount = c.wrongCount,
            avgResponseMs = c.avgResponseMs,
            retrievability = if (c.state == SrsState.REVIEW) 1.0 else 0.0,
            recentResults = history.map { it.correct },
            correctExerciseTypes = history.filter { it.correct }.map { it.exercise }.toSet(),
            previous = c.mastery,
            confusionCount = confusions.totalForCard(c.id),
        )
        return WeakWordsRadar.analyze(snapshot).reasons
    }
}
