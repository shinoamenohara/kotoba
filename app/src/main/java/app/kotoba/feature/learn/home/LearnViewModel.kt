package app.kotoba.feature.learn.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.DeckDao
import app.kotoba.core.db.DeckWithStats
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.WeakWordsRadar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class LearnUiState(
    val loading: Boolean = true,
    val language: StudyLanguage = StudyLanguage.EN,
    val due: Int = 0,
    /** New words still available today (capped by the daily limit). */
    val newToday: Int = 0,
    val newTotal: Int = 0,
    val weak: Int = 0,
    val total: Int = 0,
    val quickMinutes: Int = 3,
    val decks: List<DeckWithStats> = emptyList(),
)

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LearnViewModel @Inject constructor(
    settings: SettingsRepository,
    private val cards: CardDao,
    private val decks: DeckDao,
    private val time: TimeProvider,
) : ViewModel() {
    val state: StateFlow<LearnUiState> = settings.settings
        .map { Triple(it.activeLanguage, it.newPerDay, it.quickReviewMinutes) }
        .distinctUntilChanged()
        .flatMapLatest { (lang, newPerDay, quick) ->
            val code = lang.code
            combine(
                cards.countDue(code, time.now()),
                cards.countNew(code),
                cards.countWeak(code, WeakWordsRadar.WEAK_THRESHOLD),
                cards.countAll(code),
                decks.observeWithStats(code, time.now()),
            ) { due, fresh, weak, total, deckList ->
                val introduced = cards.countIntroducedSince(code, time.startOfDay(time.today()))
                LearnUiState(
                    loading = false,
                    language = lang,
                    due = due,
                    newToday = minOf(fresh, (newPerDay - introduced).coerceAtLeast(0)),
                    newTotal = fresh,
                    weak = weak,
                    total = total,
                    quickMinutes = quick,
                    decks = deckList,
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LearnUiState())
}
