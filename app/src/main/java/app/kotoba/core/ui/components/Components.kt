package app.kotoba.core.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarScrollBehavior
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.model.Mastery
import app.kotoba.core.ui.theme.LocalUserSettings
import app.kotoba.core.ui.theme.MasteryColors

@Composable
fun KotobaTopBar(
    title: String,
    onBack: (() -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    scrollBehavior: TopAppBarScrollBehavior? = null,
) {
    TopAppBar(
        title = { Text(title, maxLines = 1) },
        navigationIcon = {
            if (onBack != null) {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.action_back))
                }
            }
        },
        actions = actions,
        scrollBehavior = scrollBehavior,
    )
}

@Composable
fun SectionHeader(text: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(top = 16.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        action?.invoke()
    }
}

@Composable
fun masteryLabel(m: Mastery): String = stringResource(
    when (m) {
        Mastery.NEW -> R.string.mastery_new
        Mastery.LEARNING -> R.string.mastery_learning
        Mastery.FAMILIAR -> R.string.mastery_familiar
        Mastery.MASTERED -> R.string.mastery_mastered
    },
)

@Composable
fun MasteryBadge(mastery: Mastery, modifier: Modifier = Modifier) {
    val color = MasteryColors.of(mastery)
    Surface(color = color.copy(alpha = 0.16f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).background(color, CircleShape))
            Spacer(Modifier.size(6.dp))
            Text(masteryLabel(mastery), style = MaterialTheme.typography.labelMedium, color = color)
        }
    }
}

@Composable
fun StatTile(value: String, label: String, modifier: Modifier = Modifier, emoji: String? = null, accent: Color = MaterialTheme.colorScheme.primary) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (emoji != null) Text(emoji, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 6.dp))
                Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = accent)
            }
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
fun ProgressRing(
    progress: Float,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    stroke: Dp = 8.dp,
    color: Color = MaterialTheme.colorScheme.primary,
    track: Color = MaterialTheme.colorScheme.surfaceVariant,
    content: @Composable () -> Unit = {},
) {
    val animate = LocalUserSettings.current.animations
    val p by animateFloatAsState(progress.coerceIn(0f, 1f), animationSpec = tween(if (animate) 600 else 0), label = "ring")
    Box(modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val s = stroke.toPx()
            val arcSize = Size(this.size.width - s, this.size.height - s)
            val tl = Offset(s / 2, s / 2)
            drawArc(track, -90f, 360f, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
            drawArc(color, -90f, 360f * p, false, tl, arcSize, style = Stroke(s, cap = StrokeCap.Round))
        }
        content()
    }
}

/** Horizontal stacked bar of mastery stages. */
@Composable
fun MasteryBar(counts: Map<Mastery, Int>, modifier: Modifier = Modifier, height: Dp = 10.dp) {
    val total = counts.values.sum().coerceAtLeast(1)
    Row(modifier.fillMaxWidth().height(height).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))) {
        Mastery.entries.reversed().forEach { m ->
            val c = counts[m] ?: 0
            if (c > 0) Box(Modifier.weight(c.toFloat() / total).fillMaxSize().background(MasteryColors.of(m), RoundedCornerShape(50)))
        }
        val rest = 1f - counts.values.sum().toFloat() / total
        if (rest > 0.001f) Spacer(Modifier.weight(rest))
    }
}

@Composable
fun EmptyState(emoji: String, title: String, message: String, modifier: Modifier = Modifier, action: (@Composable () -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(emoji, style = MaterialTheme.typography.displayMedium)
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        action?.invoke()
    }
}

@Composable
fun SpeakButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val haptic = rememberHaptics()
    IconButton(onClick = { haptic.tick(); onClick() }, modifier = modifier) {
        Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.action_listen))
    }
}

@Composable
fun LabeledProgress(label: String, value: Int, max: Int, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Column(modifier) {
        Row(Modifier.fillMaxWidth()) {
            Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            Text("$value / $max", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { if (max == 0) 0f else (value.toFloat() / max).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().height(8.dp),
            color = color,
            trackColor = MaterialTheme.colorScheme.surfaceVariant,
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
    }
}

/** Haptic feedback that respects the user's setting. */
class Haptics(private val enabled: Boolean, private val feedback: androidx.compose.ui.hapticfeedback.HapticFeedback) {
    fun tick() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.TextHandleMove) }
    fun success() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
    fun error() { if (enabled) feedback.performHapticFeedback(HapticFeedbackType.LongPress) }
}

@Composable
fun rememberHaptics(): Haptics {
    val enabled = LocalUserSettings.current.haptics
    val fb = LocalHapticFeedback.current
    return remember(enabled, fb) { Haptics(enabled, fb) }
}

/** Human-readable interval: "1m", "10m", "3d", "2mo". */
fun formatInterval(ms: Long): String {
    val min = ms / 60_000
    return when {
        min < 60 -> "${min.coerceAtLeast(1)}m"
        min < 60 * 24 -> "${min / 60}h"
        min < 60 * 24 * 30 -> "${min / (60 * 24)}d"
        min < 60 * 24 * 365 -> "${min / (60 * 24 * 30)}mo"
        else -> "%.1fy".format(min / (60.0 * 24 * 365))
    }
}
