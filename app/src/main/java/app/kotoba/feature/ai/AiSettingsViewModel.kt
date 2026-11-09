package app.kotoba.feature.ai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.ai.AiEndpoint
import app.kotoba.core.ai.AiProvider
import app.kotoba.core.ai.AiRepository
import app.kotoba.core.ai.SecureKeyStore
import app.kotoba.core.data.AiSettings
import app.kotoba.core.data.AiUsage
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

sealed interface ConnectionResult {
    data object Running : ConnectionResult
    data class Ok(val reply: String, val modelCount: Int) : ConnectionResult
    data class Failed(val failure: AiFailure) : ConnectionResult
}

data class AiSettingsUiState(
    val ai: AiSettings = AiSettings(),
    val provider: AiProvider? = null,
    /** Masked form only (e.g. `sk-…9f3a`); the plain key never enters UI state. */
    val maskedKey: String? = null,
    val usageToday: AiUsage = AiUsage(),
    val fetchedModels: List<String> = emptyList(),
    val fetchingModels: Boolean = false,
    val test: ConnectionResult? = null,
)

@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val ai: AiRepository,
    private val keys: SecureKeyStore,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) : ViewModel() {
    val providers: List<AiProvider> get() = ai.providers

    private val local = MutableStateFlow(LocalState())

    private data class LocalState(
        val keyVersion: Int = 0,
        val fetched: Map<String, List<String>> = emptyMap(),
        val fetching: Boolean = false,
        val test: ConnectionResult? = null,
    )

    val state: StateFlow<AiSettingsUiState> = combine(settings.settings, local) { s, l ->
        val p = ai.provider(s.ai.providerId)
        val today = time.today()
        AiSettingsUiState(
            ai = s.ai,
            provider = p,
            maskedKey = if (ai.hasKey(p.id)) ai.maskedKey(p.id) ?: "••••" else null,
            usageToday = s.aiUsage.takeIf { it.epochDay == today } ?: AiUsage(today),
            fetchedModels = l.fetched[p.id].orEmpty(),
            fetchingModels = l.fetching,
            test = l.test,
        )
    }.flowOn(Dispatchers.IO).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AiSettingsUiState())

    fun selectProvider(id: String) = update { if (it.providerId == id) it else it.copy(providerId = id, model = "", baseUrl = "") }
    fun setModel(model: String) = update { it.copy(model = model.trim()) }
    fun setBaseUrl(url: String) = update { it.copy(baseUrl = url.trim()) }
    fun setTemperature(t: Double) = update { it.copy(temperature = (Math.round(t * 10) / 10.0).coerceIn(0.0, 2.0)) }
    fun setMaxTokens(v: Int) = update { it.copy(maxOutputTokens = v.coerceIn(64, 32_000)) }
    fun setDailyTokens(v: Int) = update { it.copy(dailyTokenLimit = v.coerceIn(1_000, 100_000_000)) }
    fun setDailyRequests(v: Int) = update { it.copy(dailyRequestLimit = v.coerceIn(1, 100_000)) }
    fun setShareStats(v: Boolean) = update { it.copy(shareLearningStats = v) }

    private fun update(transform: (AiSettings) -> AiSettings) {
        local.update { it.copy(test = null) }
        viewModelScope.launch { settings.updateAi(transform) }
    }

    /** Only used to redact provider error texts. */
    private fun keyFor(id: String): String? = runCatching { keys.get(id) }.getOrNull()

    fun saveKey(key: String) {
        val trimmed = key.trim()
        if (trimmed.isEmpty()) return
        val id = state.value.ai.providerId
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ai.saveKey(id, trimmed) }
            local.update { it.copy(keyVersion = it.keyVersion + 1, test = null) }
        }
    }

    fun removeKey() {
        val id = state.value.ai.providerId
        viewModelScope.launch {
            withContext(Dispatchers.IO) { ai.removeKey(id) }
            local.update { it.copy(keyVersion = it.keyVersion + 1, test = null) }
        }
    }

    fun fetchModels() {
        val s = state.value
        val p = s.provider ?: return
        local.update { it.copy(fetching = true) }
        viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { p.listModels(AiEndpoint(s.ai.baseUrl.ifBlank { p.defaultBaseUrl }, keys.get(p.id).orEmpty())) }
            }
            local.update { l ->
                l.copy(
                    fetching = false,
                    fetched = result.getOrNull()?.let { l.fetched + (p.id to it.sorted()) } ?: l.fetched,
                    test = result.exceptionOrNull()?.let { ConnectionResult.Failed(AiFailure.of(it, keyFor(p.id))) } ?: l.test,
                )
            }
        }
    }

    fun testConnection() {
        val s = state.value
        val p = s.provider ?: return
        if (p.requiresApiKey && !ai.hasKey(p.id)) {
            local.update { it.copy(test = ConnectionResult.Failed(AiFailure(app.kotoba.core.ai.AiErrorKind.NOT_CONFIGURED))) }
            return
        }
        local.update { it.copy(test = ConnectionResult.Running) }
        viewModelScope.launch {
            val result = ai.testConnection(p.id, "", s.ai.baseUrl, s.ai.model)
            local.update { l ->
                result.fold(
                    onSuccess = { t ->
                        l.copy(
                            test = ConnectionResult.Ok(t.reply, t.models.size),
                            fetched = if (t.models.isNotEmpty()) l.fetched + (p.id to t.models.sorted()) else l.fetched,
                        )
                    },
                    onFailure = { e -> l.copy(test = ConnectionResult.Failed(AiFailure.of(e, keyFor(p.id)))) },
                )
            }
        }
    }
}
