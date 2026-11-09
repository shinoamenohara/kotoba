package app.kotoba.feature.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.GamificationProfile
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SeedRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.data.UserSettings
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.CardDao
import app.kotoba.core.db.DocumentDao
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.lang.SpeechService
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.WeakWordsRadar
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeCounts(val due: Int = 0, val newAvailable: Int = 0, val weak: Int = 0, val total: Int = 0)

data class HomeUiState(
    val settings: UserSettings = UserSettings(),
    val profile: GamificationProfile? = null,
    val counts: HomeCounts = HomeCounts(),
    val wordOfDay: CardEntity? = null,
    val lastDocument: DocumentEntity? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val settingsRepository: SettingsRepository,
    gamification: GamificationRepository,
    private val cards: CardRepository,
    cardDao: CardDao,
    documentDao: DocumentDao,
    private val speech: SpeechService,
    time: TimeProvider,
) : ViewModel() {
    private val language = settingsRepository.settings.map { it.activeLanguage }.distinctUntilChanged()

    private val counts = language.flatMapLatest { lang ->
        val now = time.now()
        combine(
            cardDao.countDue(lang.code, now),
            cardDao.countNew(lang.code),
            cardDao.countWeak(lang.code, WeakWordsRadar.WEAK_THRESHOLD),
            cardDao.countAll(lang.code),
            settingsRepository.settings.map { it.newPerDay },
        ) { due, fresh, weak, total, perDay -> HomeCounts(due, minOf(fresh, perDay), weak, total) }
    }

    private val wordOfDay = combine(language, counts.map { it.total }.distinctUntilChanged()) { lang, _ -> lang }
        .mapLatest { cards.wordOfTheDay(it) }

    private val lastDocument = documentDao.observeByKinds(DocumentKind.entries)
        .map { list -> list.firstOrNull { it.lastOpenedAt != null } }

    val state: StateFlow<HomeUiState> = combine(
        settingsRepository.settings,
        gamification.observeProfile(),
        counts,
        wordOfDay,
        lastDocument,
    ) { s, p, c, w, d -> HomeUiState(s, p, c, w, d) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState())

    fun switchLanguage(lang: StudyLanguage) = viewModelScope.launch { settingsRepository.setActiveLanguage(lang) }

    fun speak(card: CardEntity) = viewModelScope.launch { speech.speak(card.term, StudyLanguage.fromCode(card.language)) }
}

data class OnboardingState(
    val step: Int = 0,
    val languages: Set<StudyLanguage> = setOf(StudyLanguage.EN),
    val native: NativeLanguage = NativeLanguage.RU,
    val dailyGoal: Int = 50,
    val name: String = "",
    val installing: Boolean = false,
    val done: Boolean = false,
)

@HiltViewModel
class OnboardingViewModel @Inject constructor(
    private val settings: SettingsRepository,
    private val seed: SeedRepository,
) : ViewModel() {
    private val _state = MutableStateFlow(OnboardingState())
    val state: StateFlow<OnboardingState> = _state.asStateFlow()

    /** Languages that ship with starter decks. */
    val seededLanguages: Set<StudyLanguage> = seed.availableLanguages()

    fun next() = _state.value.let { _state.value = it.copy(step = it.step + 1) }
    fun back() = _state.value.let { _state.value = it.copy(step = (it.step - 1).coerceAtLeast(0)) }

    fun toggleLanguage(lang: StudyLanguage) {
        val cur = _state.value.languages
        val next = if (lang in cur) cur - lang else cur + lang
        if (next.isNotEmpty()) _state.value = _state.value.copy(languages = next)
    }

    fun setNative(n: NativeLanguage) { _state.value = _state.value.copy(native = n) }
    fun setGoal(g: Int) { _state.value = _state.value.copy(dailyGoal = g) }
    fun setName(n: String) { _state.value = _state.value.copy(name = n.take(40)) }

    fun finish() {
        val s = _state.value
        if (s.installing) return
        _state.value = s.copy(installing = true)
        viewModelScope.launch {
            // Featured languages first so the active one is the user's main focus.
            val ordered = s.languages.sortedBy { it.ordinal }
            ordered.forEach { runCatching { seed.install(it, s.native) } }
            settings.setStudyLanguages(ordered.toSet())
            settings.completeOnboarding(s.name.trim(), s.native, LinkedHashSet(ordered), s.dailyGoal)
            _state.value = _state.value.copy(installing = false, done = true)
        }
    }
}
