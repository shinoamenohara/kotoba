package app.kotoba

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import app.kotoba.core.IncomingContent
import app.kotoba.core.IncomingContentBus
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.UserSettings
import app.kotoba.core.lang.SpeechService
import app.kotoba.core.navigation.KotobaNavHost
import app.kotoba.core.ui.theme.KotobaTheme
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

@HiltViewModel
class AppViewModel @Inject constructor(settingsRepository: SettingsRepository, speech: SpeechService) : ViewModel() {
    /** null until DataStore delivers the first value (keeps the splash on screen). */
    val settings: StateFlow<UserSettings?> = settingsRepository.settings
        .onEach { speech.rate = it.ttsRate }
        .map<UserSettings, UserSettings?> { it }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
}

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    private val viewModel: AppViewModel by viewModels()
    @Inject lateinit var incoming: IncomingContentBus

    override fun onCreate(savedInstanceState: Bundle?) {
        val splash = installSplashScreen()
        super.onCreate(savedInstanceState)
        splash.setKeepOnScreenCondition { viewModel.settings.value == null }
        enableEdgeToEdge()
        if (savedInstanceState == null) handleIntent(intent)
        setContent {
            val settings by viewModel.settings.collectAsStateWithLifecycle()
            val s = settings ?: return@setContent
            KotobaTheme(s) {
                KotobaNavHost(onboarded = s.onboarded)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        when (intent?.action) {
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT)
                if (!text.isNullOrBlank()) {
                    incoming.post(IncomingContent.Text(text, intent.getStringExtra(Intent.EXTRA_SUBJECT)))
                } else {
                    @Suppress("DEPRECATION")
                    val uri = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
                    uri?.let { incoming.post(IncomingContent.File(it, intent.type, displayName(it))) }
                }
            }
            Intent.ACTION_VIEW -> intent.data?.let { incoming.post(IncomingContent.File(it, intent.type, displayName(it))) }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment
}
