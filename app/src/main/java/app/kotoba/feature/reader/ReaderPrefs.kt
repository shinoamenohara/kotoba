package app.kotoba.feature.reader

import android.content.Context
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject
import javax.inject.Singleton

enum class ReaderTheme { AUTO, LIGHT, SEPIA, DARK }
enum class MangaDirection { AUTO, RTL, LTR }

data class ReaderPrefs(
    val fontSize: Float = 19f,
    val lineSpacing: Float = 1.6f,
    val theme: ReaderTheme = ReaderTheme.AUTO,
    val showReadings: Boolean = false,
    val mangaDirection: MangaDirection = MangaDirection.AUTO,
    val showOcrBoxes: Boolean = true,
)

/** Reader-only preferences (kept out of the app-wide DataStore). */
@Singleton
class ReaderPrefsStore @Inject constructor(@ApplicationContext context: Context) {
    private val sp = context.getSharedPreferences("reader_prefs", Context.MODE_PRIVATE)
    private val _prefs = MutableStateFlow(load())
    val prefs: StateFlow<ReaderPrefs> = _prefs

    fun update(transform: (ReaderPrefs) -> ReaderPrefs) {
        _prefs.update(transform)
        val p = _prefs.value
        sp.edit()
            .putFloat("font_size", p.fontSize)
            .putFloat("line_spacing", p.lineSpacing)
            .putString("theme", p.theme.name)
            .putBoolean("show_readings", p.showReadings)
            .putString("manga_direction", p.mangaDirection.name)
            .putBoolean("show_ocr_boxes", p.showOcrBoxes)
            .apply()
    }

    private fun load() = ReaderPrefs(
        fontSize = sp.getFloat("font_size", 19f),
        lineSpacing = sp.getFloat("line_spacing", 1.6f),
        theme = runCatching { ReaderTheme.valueOf(sp.getString("theme", null)!!) }.getOrDefault(ReaderTheme.AUTO),
        showReadings = sp.getBoolean("show_readings", false),
        mangaDirection = runCatching { MangaDirection.valueOf(sp.getString("manga_direction", null)!!) }.getOrDefault(MangaDirection.AUTO),
        showOcrBoxes = sp.getBoolean("show_ocr_boxes", true),
    )
}

data class ReaderColors(val background: Color, val text: Color, val secondary: Color, val isDark: Boolean)

@Composable
fun ReaderTheme.colors(): ReaderColors = when (this) {
    ReaderTheme.LIGHT -> ReaderColors(Color(0xFFFFFFFF), Color(0xFF1C1B1F), Color(0xFF6B6B6B), false)
    ReaderTheme.SEPIA -> ReaderColors(Color(0xFFF4ECD8), Color(0xFF4B3A2A), Color(0xFF8A7560), false)
    ReaderTheme.DARK -> ReaderColors(Color(0xFF121212), Color(0xFFE2E2E2), Color(0xFF9E9E9E), true)
    ReaderTheme.AUTO -> MaterialTheme.colorScheme.let {
        ReaderColors(it.background, it.onBackground, it.onSurfaceVariant, it.background.luminanceIsDark())
    }
}

private fun Color.luminanceIsDark(): Boolean = (0.299 * red + 0.587 * green + 0.114 * blue) < 0.5
