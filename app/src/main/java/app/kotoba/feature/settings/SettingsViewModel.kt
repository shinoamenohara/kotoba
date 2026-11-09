package app.kotoba.feature.settings

import android.app.LocaleManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.LocaleList
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.ai.AiStatus
import app.kotoba.core.data.SeedRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.ThemeMode
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.data.UserSettings
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.importexport.backup.BackupManager
import app.kotoba.feature.importexport.backup.BackupSummary
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface SettingsEvent {
    data class StarterDecks(val cards: Int) : SettingsEvent
    data object BackupDone : SettingsEvent
    data object RestoreDone : SettingsEvent
    data object NoBackupInFolder : SettingsEvent
    data class Error(val message: String) : SettingsEvent
}

data class PendingRestore(val uri: Uri, val summary: BackupSummary)

data class BackupUiState(
    val busy: Boolean = false,
    val folderName: String = "",
    val pendingRestore: PendingRestore? = null,
)

@HiltViewModel
class SettingsViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repo: SettingsRepository,
    private val seed: SeedRepository,
    private val backup: BackupManager,
    private val time: TimeProvider,
    ai: AiRepository,
) : ViewModel() {
    val settings: StateFlow<UserSettings?> = repo.settings.stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val aiStatus: StateFlow<AiStatus?> = ai.status.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _backup = MutableStateFlow(BackupUiState())
    val backupState: StateFlow<BackupUiState> = _backup.asStateFlow()

    private val _installing = MutableStateFlow(false)
    val installing: StateFlow<Boolean> = _installing.asStateFlow()

    private val _events = Channel<SettingsEvent>(Channel.BUFFERED)
    val events = _events.receiveAsFlow()

    val seedLanguages: Set<StudyLanguage> = runCatching { seed.availableLanguages() }.getOrDefault(emptySet())

    init {
        viewModelScope.launch {
            repo.settings.map { it.backupFolderUri }.collect { uri ->
                val name = if (uri.isBlank()) "" else withContext(Dispatchers.IO) { backup.folderName(Uri.parse(uri)) }
                _backup.update { it.copy(folderName = name) }
            }
        }
    }

    private fun launch(block: suspend () -> Unit) = viewModelScope.launch { block() }

    // Profile & languages
    fun setUserName(v: String) = launch { repo.setUserName(v.trim()) }
    fun setNativeLanguage(v: NativeLanguage) = launch { repo.setNativeLanguage(v) }
    fun setActiveLanguage(v: StudyLanguage) = launch { repo.setActiveLanguage(v) }

    fun setStudyLanguages(v: Set<StudyLanguage>) = launch {
        if (v.isEmpty()) return@launch
        repo.setStudyLanguages(v)
        val active = repo.current().activeLanguage
        if (active !in v) repo.setActiveLanguage(v.sortedBy { it.ordinal }.first())
    }

    fun installStarterDecks() = launch {
        if (_installing.value) return@launch
        _installing.value = true
        runCatching {
            val s = repo.current()
            seed.install(s.activeLanguage, s.nativeLanguage)
        }.onSuccess { _events.send(SettingsEvent.StarterDecks(it)) }
            .onFailure { _events.send(SettingsEvent.Error(it.message.orEmpty())) }
        _installing.value = false
    }

    /** "" = follow system. Android 13+ switches the per-app locale; older versions follow the system. */
    fun setInterfaceLanguage(tag: String) = launch {
        repo.setInterfaceLanguage(tag)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java)?.applicationLocales =
                if (tag.isBlank()) LocaleList.getEmptyLocaleList() else LocaleList.forLanguageTags(tag)
        }
    }

    // Learning
    fun setNewPerDay(v: Int) = launch { repo.setNewPerDay(v) }
    fun setMaxReviews(v: Int) = launch { repo.setMaxReviewsPerDay(v) }
    fun setRetention(v: Double) = launch { repo.setDesiredRetention(v) }
    fun setLearningSteps(v: List<Int>) = launch { if (v.isNotEmpty()) repo.setLearningSteps(v) }
    fun setRelearningSteps(v: List<Int>) = launch { if (v.isNotEmpty()) repo.setRelearningSteps(v) }
    fun setDailyXpGoal(v: Int) = launch { repo.setDailyXpGoal(v) }
    fun setQuickMinutes(v: Int) = launch { repo.setQuickReviewMinutes(v) }
    fun setAutoPlay(v: Boolean) = launch { repo.setAutoPlayAudio(v) }
    fun setShowReading(v: Boolean) = launch { repo.setShowReading(v) }
    fun setTtsRate(v: Float) = launch { repo.setTtsRate(v) }

    // Appearance
    fun setTheme(v: ThemeMode) = launch { repo.setTheme(v) }
    fun setDynamicColor(v: Boolean) = launch { repo.setDynamicColor(v) }
    fun setAnimations(v: Boolean) = launch { repo.setAnimations(v) }
    fun setSounds(v: Boolean) = launch { repo.setSounds(v) }
    fun setHaptics(v: Boolean) = launch { repo.setHaptics(v) }

    // Notifications
    fun setReminders(enabled: Boolean, hour: Int, minute: Int) = launch { repo.setReminders(enabled, hour, minute) }

    // Backup & sync
    private fun backupJob(block: suspend () -> Unit) = launch {
        if (_backup.value.busy) return@launch
        _backup.update { it.copy(busy = true) }
        runCatching { block() }.onFailure { _events.send(SettingsEvent.Error(it.message ?: it.javaClass.simpleName)) }
        _backup.update { it.copy(busy = false) }
    }

    fun backupFileName() = backup.defaultFileName()

    fun backupNow(uri: Uri, includeDocuments: Boolean, includeMedia: Boolean) = backupJob {
        backup.backupTo(uri, includeDocuments, includeMedia)
        repo.setLastBackupAt(time.now())
        _events.send(SettingsEvent.BackupDone)
    }

    fun prepareRestore(uri: Uri) = backupJob {
        val summary = backup.summary(uri)
        _backup.update { it.copy(pendingRestore = PendingRestore(uri, summary)) }
    }

    fun cancelRestore() = _backup.update { it.copy(pendingRestore = null) }

    fun confirmRestore() {
        val pending = _backup.value.pendingRestore ?: return
        _backup.update { it.copy(pendingRestore = null) }
        backupJob {
            backup.restore(pending.uri)
            _events.send(SettingsEvent.RestoreDone)
        }
    }

    fun enableAutoBackup(tree: Uri) = backupJob {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        context.contentResolver.takePersistableUriPermission(tree, flags)
        repo.setAutoBackup(true, tree.toString())
        backup.backupToFolder(tree)
        repo.setLastBackupAt(time.now())
        _events.send(SettingsEvent.BackupDone)
    }

    fun disableAutoBackup() = launch {
        val s = repo.current()
        repo.setAutoBackup(false, s.backupFolderUri)
    }

    fun backupToFolderNow() = backupJob {
        val uri = repo.current().backupFolderUri.takeIf { it.isNotBlank() } ?: return@backupJob
        backup.backupToFolder(Uri.parse(uri))
        repo.setLastBackupAt(time.now())
        _events.send(SettingsEvent.BackupDone)
    }

    fun restoreFromFolder() = backupJob {
        val uri = repo.current().backupFolderUri.takeIf { it.isNotBlank() } ?: return@backupJob
        val latest = backup.latestInFolder(Uri.parse(uri))
        if (latest == null) { _events.send(SettingsEvent.NoBackupInFolder); return@backupJob }
        _backup.update { it.copy(pendingRestore = PendingRestore(latest, backup.summary(latest))) }
    }
}
