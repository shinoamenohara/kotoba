package app.kotoba.core.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.kotoba.core.data.ThemeMode
import app.kotoba.core.data.UserSettings
import app.kotoba.core.model.Mastery

private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFFC7C4FF)
private val Coral = Color(0xFFFF6B5A)
private val Mint = Color(0xFF14B8A6)

private val LightColors = lightColorScheme(
    primary = Indigo,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE2DFFF),
    onPrimaryContainer = Color(0xFF15106B),
    secondary = Mint,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFCCF5EE),
    onSecondaryContainer = Color(0xFF00201C),
    tertiary = Coral,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFFFDAD5),
    onTertiaryContainer = Color(0xFF410001),
    background = Color(0xFFFBF8FF),
    surface = Color(0xFFFBF8FF),
    surfaceVariant = Color(0xFFE5E1EC),
    surfaceContainer = Color(0xFFF1EDF8),
    surfaceContainerHigh = Color(0xFFEBE7F2),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    onPrimary = Color(0xFF2A2390),
    primaryContainer = Color(0xFF3D35B8),
    onPrimaryContainer = Color(0xFFE2DFFF),
    secondary = Color(0xFF5EDBC9),
    onSecondary = Color(0xFF003731),
    secondaryContainer = Color(0xFF005048),
    onSecondaryContainer = Color(0xFFCCF5EE),
    tertiary = Color(0xFFFFB4A9),
    onTertiary = Color(0xFF680003),
    tertiaryContainer = Color(0xFF8C1D18),
    onTertiaryContainer = Color(0xFFFFDAD5),
    background = Color(0xFF131218),
    surface = Color(0xFF131218),
    surfaceVariant = Color(0xFF47464F),
    surfaceContainer = Color(0xFF1F1E25),
    surfaceContainerHigh = Color(0xFF2A2930),
)

val KotobaShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

private val base = Typography()
val KotobaTypography = base.copy(
    displayMedium = base.displayMedium.copy(fontWeight = FontWeight.Bold),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** Big glyph style for card faces; CJK needs more size to be readable. */
val CardTermStyle = TextStyle(fontSize = 40.sp, fontWeight = FontWeight.Bold, lineHeight = 48.sp)

/** Mastery stage colours, consistent across badges and charts. */
object MasteryColors {
    val New = Color(0xFF9CA3AF)
    val Learning = Color(0xFFF59E0B)
    val Familiar = Color(0xFF3B82F6)
    val Mastered = Color(0xFF10B981)
    fun of(m: Mastery): Color = when (m) {
        Mastery.NEW -> New
        Mastery.LEARNING -> Learning
        Mastery.FAMILIAR -> Familiar
        Mastery.MASTERED -> Mastered
    }
}

val LocalUserSettings = staticCompositionLocalOf { UserSettings() }

@Composable
fun KotobaTheme(settings: UserSettings = UserSettings(), content: @Composable () -> Unit) {
    val dark = when (settings.theme) {
        ThemeMode.SYSTEM -> isSystemInDarkTheme()
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val context = LocalContext.current
    val colors = when {
        settings.dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalUserSettings provides settings) {
        MaterialTheme(colorScheme = colors, typography = KotobaTypography, shapes = KotobaShapes, content = content)
    }
}
