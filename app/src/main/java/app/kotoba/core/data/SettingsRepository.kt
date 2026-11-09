package app.kotoba.core.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.FsrsParams
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AiSettings(
    /** One of [app.kotoba.core.ai.AiProviders] ids. */
    val providerId: String = "openai",
    val model: String = "",
    /** Override for OpenAI-compatible endpoints (Ollama, LM Studio, proxies). */
    val baseUrl: String = "",
    val temperature: Double = 0.7,
    val maxOutputTokens: Int = 1024,
    val dailyTokenLimit: Int = 200_000,
    val dailyRequestLimit: Int = 300,
    /** Let the tutor see learning statistics (weak words, accuracy…). */
    val shareLearningStats: Boolean = true,
)

data class AiUsage(val epochDay: Long = 0, val tokens: Int = 0, val requests: Int = 0)

data class UserSettings(
    val onboarded: Boolean = false,
    val userName: String = "",
    val nativeLanguage: NativeLanguage = NativeLanguage.RU,
    val activeLanguage: StudyLanguage = StudyLanguage.EN,
    val studyLanguages: Set<StudyLanguage> = setOf(StudyLanguage.EN),
    /** "" = follow system, otherwise BCP-47 tag ("ru", "en"). */
    val interfaceLanguage: String = "",
    // Learning
    val newPerDay: Int = 15,
    val maxReviewsPerDay: Int = 200,
    val desiredRetention: Double = 0.9,
    val learningSteps: List<Int> = listOf(1, 10),
    val relearningSteps: List<Int> = listOf(10),
    val dailyXpGoal: Int = 50,
    val quickReviewMinutes: Int = 3,
    val autoPlayAudio: Boolean = true,
    val showReading: Boolean = true,
    val ttsRate: Float = 1.0f,
    // Appearance & feedback
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val dynamicColor: Boolean = true,
    val haptics: Boolean = true,
    val sounds: Boolean = true,
    val animations: Boolean = true,
    // Notifications
    val remindersEnabled: Boolean = true,
    val reminderHour: Int = 19,
    val reminderMinute: Int = 0,
    // Backup
    val autoBackupEnabled: Boolean = false,
    /** SAF tree URI chosen for automatic backups (can be a cloud provider such as Drive). */
    val backupFolderUri: String = "",
    val lastBackupAt: Long = 0,
    // AI
    val ai: AiSettings = AiSettings(),
    val aiUsage: AiUsage = AiUsage(),
) {
    fun fsrsParams(): FsrsParams = FsrsParams(
        desiredRetention = desiredRetention.coerceIn(0.7, 0.99),
        learningStepsMinutes = learningSteps,
        relearningStepsMinutes = relearningSteps,
    )
}

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

@Singleton
class SettingsRepository @Inject constructor(@ApplicationContext context: Context) {
    private val store = context.dataStore

    private object K {
        val onboarded = booleanPreferencesKey("onboarded")
        val userName = stringPreferencesKey("user_name")
        val nativeLanguage = stringPreferencesKey("native_language")
        val activeLanguage = stringPreferencesKey("active_language")
        val studyLanguages = stringSetPreferencesKey("study_languages")
        val interfaceLanguage = stringPreferencesKey("interface_language")
        val newPerDay = intPreferencesKey("new_per_day")
        val maxReviews = intPreferencesKey("max_reviews")
        val retention = doublePreferencesKey("desired_retention")
        val learningSteps = stringPreferencesKey("learning_steps")
        val relearningSteps = stringPreferencesKey("relearning_steps")
        val dailyXpGoal = intPreferencesKey("daily_xp_goal")
        val quickMinutes = intPreferencesKey("quick_minutes")
        val autoPlay = booleanPreferencesKey("auto_play_audio")
        val showReading = booleanPreferencesKey("show_reading")
        val ttsRate = floatPreferencesKey("tts_rate")
        val theme = stringPreferencesKey("theme")
        val dynamicColor = booleanPreferencesKey("dynamic_color")
        val haptics = booleanPreferencesKey("haptics")
        val sounds = booleanPreferencesKey("sounds")
        val animations = booleanPreferencesKey("animations")
        val reminders = booleanPreferencesKey("reminders")
        val reminderHour = intPreferencesKey("reminder_hour")
        val reminderMinute = intPreferencesKey("reminder_minute")
        val autoBackup = booleanPreferencesKey("auto_backup")
        val backupFolder = stringPreferencesKey("backup_folder")
        val lastBackup = longPreferencesKey("last_backup")
        val aiProvider = stringPreferencesKey("ai_provider")
        val aiModel = stringPreferencesKey("ai_model")
        val aiBaseUrl = stringPreferencesKey("ai_base_url")
        val aiTemperature = doublePreferencesKey("ai_temperature")
        val aiMaxTokens = intPreferencesKey("ai_max_tokens")
        val aiDailyTokens = intPreferencesKey("ai_daily_tokens")
        val aiDailyRequests = intPreferencesKey("ai_daily_requests")
        val aiShareStats = booleanPreferencesKey("ai_share_stats")
        val aiUsageDay = longPreferencesKey("ai_usage_day")
        val aiUsageTokens = intPreferencesKey("ai_usage_tokens")
        val aiUsageRequests = intPreferencesKey("ai_usage_requests")
    }

    val settings: Flow<UserSettings> = store.data.map { p ->
        val d = UserSettings()
        UserSettings(
            onboarded = p[K.onboarded] ?: d.onboarded,
            userName = p[K.userName] ?: d.userName,
            nativeLanguage = NativeLanguage.fromCode(p[K.nativeLanguage]),
            activeLanguage = StudyLanguage.fromCode(p[K.activeLanguage] ?: d.activeLanguage.code),
            studyLanguages = p[K.studyLanguages]?.map { StudyLanguage.fromCode(it) }?.toSet()?.ifEmpty { null } ?: d.studyLanguages,
            interfaceLanguage = p[K.interfaceLanguage] ?: d.interfaceLanguage,
            newPerDay = p[K.newPerDay] ?: d.newPerDay,
            maxReviewsPerDay = p[K.maxReviews] ?: d.maxReviewsPerDay,
            desiredRetention = p[K.retention] ?: d.desiredRetention,
            learningSteps = parseSteps(p[K.learningSteps]) ?: d.learningSteps,
            relearningSteps = parseSteps(p[K.relearningSteps]) ?: d.relearningSteps,
            dailyXpGoal = p[K.dailyXpGoal] ?: d.dailyXpGoal,
            quickReviewMinutes = p[K.quickMinutes] ?: d.quickReviewMinutes,
            autoPlayAudio = p[K.autoPlay] ?: d.autoPlayAudio,
            showReading = p[K.showReading] ?: d.showReading,
            ttsRate = p[K.ttsRate] ?: d.ttsRate,
            theme = p[K.theme]?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() } ?: d.theme,
            dynamicColor = p[K.dynamicColor] ?: d.dynamicColor,
            haptics = p[K.haptics] ?: d.haptics,
            sounds = p[K.sounds] ?: d.sounds,
            animations = p[K.animations] ?: d.animations,
            remindersEnabled = p[K.reminders] ?: d.remindersEnabled,
            reminderHour = p[K.reminderHour] ?: d.reminderHour,
            reminderMinute = p[K.reminderMinute] ?: d.reminderMinute,
            autoBackupEnabled = p[K.autoBackup] ?: d.autoBackupEnabled,
            backupFolderUri = p[K.backupFolder] ?: d.backupFolderUri,
            lastBackupAt = p[K.lastBackup] ?: d.lastBackupAt,
            ai = AiSettings(
                providerId = p[K.aiProvider] ?: d.ai.providerId,
                model = p[K.aiModel] ?: d.ai.model,
                baseUrl = p[K.aiBaseUrl] ?: d.ai.baseUrl,
                temperature = p[K.aiTemperature] ?: d.ai.temperature,
                maxOutputTokens = p[K.aiMaxTokens] ?: d.ai.maxOutputTokens,
                dailyTokenLimit = p[K.aiDailyTokens] ?: d.ai.dailyTokenLimit,
                dailyRequestLimit = p[K.aiDailyRequests] ?: d.ai.dailyRequestLimit,
                shareLearningStats = p[K.aiShareStats] ?: d.ai.shareLearningStats,
            ),
            aiUsage = AiUsage(p[K.aiUsageDay] ?: 0, p[K.aiUsageTokens] ?: 0, p[K.aiUsageRequests] ?: 0),
        )
    }

    suspend fun current(): UserSettings = settings.first()

    suspend fun completeOnboarding(name: String, native: NativeLanguage, languages: Set<StudyLanguage>, dailyGoal: Int) {
        store.edit {
            it[K.onboarded] = true
            it[K.userName] = name
            it[K.nativeLanguage] = native.code
            it[K.studyLanguages] = languages.map { l -> l.code }.toSet()
            it[K.activeLanguage] = languages.first().code
            it[K.dailyXpGoal] = dailyGoal
        }
    }

    suspend fun setActiveLanguage(lang: StudyLanguage) = store.edit {
        it[K.activeLanguage] = lang.code
        val set = (it[K.studyLanguages] ?: emptySet()) + lang.code
        it[K.studyLanguages] = set
    }

    suspend fun setStudyLanguages(langs: Set<StudyLanguage>) = store.edit {
        if (langs.isNotEmpty()) it[K.studyLanguages] = langs.map { l -> l.code }.toSet()
    }

    suspend fun setUserName(v: String) = store.edit { it[K.userName] = v }
    suspend fun setNativeLanguage(v: NativeLanguage) = store.edit { it[K.nativeLanguage] = v.code }
    suspend fun setInterfaceLanguage(tag: String) = store.edit { it[K.interfaceLanguage] = tag }
    suspend fun setNewPerDay(v: Int) = store.edit { it[K.newPerDay] = v.coerceIn(0, 200) }
    suspend fun setMaxReviewsPerDay(v: Int) = store.edit { it[K.maxReviews] = v.coerceIn(10, 2000) }
    suspend fun setDesiredRetention(v: Double) = store.edit { it[K.retention] = v.coerceIn(0.7, 0.99) }
    suspend fun setLearningSteps(v: List<Int>) = store.edit { it[K.learningSteps] = v.joinToString(",") }
    suspend fun setRelearningSteps(v: List<Int>) = store.edit { it[K.relearningSteps] = v.joinToString(",") }
    suspend fun setDailyXpGoal(v: Int) = store.edit { it[K.dailyXpGoal] = v.coerceIn(10, 1000) }
    suspend fun setQuickReviewMinutes(v: Int) = store.edit { it[K.quickMinutes] = v }
    suspend fun setAutoPlayAudio(v: Boolean) = store.edit { it[K.autoPlay] = v }
    suspend fun setShowReading(v: Boolean) = store.edit { it[K.showReading] = v }
    suspend fun setTtsRate(v: Float) = store.edit { it[K.ttsRate] = v.coerceIn(0.5f, 2f) }
    suspend fun setTheme(v: ThemeMode) = store.edit { it[K.theme] = v.name }
    suspend fun setDynamicColor(v: Boolean) = store.edit { it[K.dynamicColor] = v }
    suspend fun setHaptics(v: Boolean) = store.edit { it[K.haptics] = v }
    suspend fun setSounds(v: Boolean) = store.edit { it[K.sounds] = v }
    suspend fun setAnimations(v: Boolean) = store.edit { it[K.animations] = v }
    suspend fun setReminders(enabled: Boolean, hour: Int, minute: Int) = store.edit {
        it[K.reminders] = enabled; it[K.reminderHour] = hour; it[K.reminderMinute] = minute
    }
    suspend fun setAutoBackup(enabled: Boolean, folderUri: String) = store.edit {
        it[K.autoBackup] = enabled; it[K.backupFolder] = folderUri
    }
    suspend fun setLastBackupAt(v: Long) = store.edit { it[K.lastBackup] = v }

    suspend fun updateAi(transform: (AiSettings) -> AiSettings) {
        val next = transform(current().ai)
        store.edit {
            it[K.aiProvider] = next.providerId
            it[K.aiModel] = next.model
            it[K.aiBaseUrl] = next.baseUrl
            it[K.aiTemperature] = next.temperature
            it[K.aiMaxTokens] = next.maxOutputTokens
            it[K.aiDailyTokens] = next.dailyTokenLimit
            it[K.aiDailyRequests] = next.dailyRequestLimit
            it[K.aiShareStats] = next.shareLearningStats
        }
    }

    /** Adds usage for [epochDay], resetting the counters when the day changes. */
    suspend fun addAiUsage(epochDay: Long, tokens: Int) = store.edit {
        val sameDay = it[K.aiUsageDay] == epochDay
        it[K.aiUsageDay] = epochDay
        it[K.aiUsageTokens] = (if (sameDay) it[K.aiUsageTokens] ?: 0 else 0) + tokens
        it[K.aiUsageRequests] = (if (sameDay) it[K.aiUsageRequests] ?: 0 else 0) + 1
    }

    private fun parseSteps(raw: String?): List<Int>? =
        raw?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.filter { it > 0 }
}
