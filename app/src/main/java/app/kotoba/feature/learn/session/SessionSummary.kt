package app.kotoba.feature.learn.session

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.StatTile
import app.kotoba.core.ui.components.achievementTitle
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings

@Composable
fun SummaryContent(summary: SummaryUi, onContinue: () -> Unit, onDone: () -> Unit) {
    val s = summary.summary
    val haptics = rememberHaptics()
    val animate = LocalUserSettings.current.animations
    val pop = remember { Animatable(if (animate) 0.6f else 1f) }
    LaunchedEffect(Unit) {
        if (summary.goalReached || s.newAchievements.isNotEmpty()) haptics.success()
        pop.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy))
    }
    val accuracy = if (s.answered == 0) 0 else s.correct * 100 / s.answered
    val minutes = (s.durationMs / 60_000).coerceAtLeast(if (s.answered > 0) 1 else 0)

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(Modifier.fillMaxWidth().scale(pop.value), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(if (summary.goalReached) "🎉" else if (accuracy >= 80) "🌟" else "💪", style = MaterialTheme.typography.displayLarge)
                Text(
                    stringResource(if (s.answered == 0) R.string.learn_sum_title_empty else R.string.learn_sum_title),
                    style = MaterialTheme.typography.headlineSmall,
                    textAlign = TextAlign.Center,
                )
                Text(
                    stringResource(R.string.learn_sum_minutes, minutes),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        if (summary.goalReached) {
            item {
                Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.tertiaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🏆", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(stringResource(R.string.learn_sum_goal), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.learn_sum_goal_sub), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(s.answered.toString(), stringResource(R.string.learn_sum_answered), Modifier.weight(1f), emoji = "📝")
                StatTile("$accuracy%", stringResource(R.string.learn_sum_accuracy), Modifier.weight(1f), emoji = "🎯")
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile("+${s.xp}", stringResource(R.string.learn_sum_xp), Modifier.weight(1f), emoji = "⚡")
                StatTile(s.bestCombo.toString(), stringResource(R.string.learn_sum_combo), Modifier.weight(1f), emoji = "🔥")
            }
        }
        if (s.newAchievements.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.learn_sum_achievements)) }
            items(s.newAchievements) { a ->
                Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(a.icon, style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.width(12.dp))
                        Text(achievementTitle(a), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
        if (summary.up.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.learn_sum_up, summary.up.size)) }
            items(summary.up, key = { "u" + it.card.id }) { ChangeRow(it, up = true) }
        }
        if (summary.down.isNotEmpty()) {
            item { SectionHeader(stringResource(R.string.learn_sum_down, summary.down.size)) }
            items(summary.down, key = { "d" + it.card.id }) { ChangeRow(it, up = false) }
        }
        item {
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.learn_sum_more))
                }
                OutlinedButton(onClick = onDone, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                    Text(stringResource(R.string.action_done))
                }
            }
        }
    }
}

@Composable
private fun ChangeRow(change: MasteryChange, up: Boolean) {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(
            if (up) Icons.Rounded.ArrowUpward else Icons.Rounded.ArrowDownward,
            contentDescription = null,
            tint = if (up) Color(0xFF10B981) else Color(0xFFEF4444),
        )
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(change.card.term, style = MaterialTheme.typography.titleMedium)
            Text(change.card.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        MasteryBadge(change.before)
        Text(" → ", style = MaterialTheme.typography.labelLarge)
        MasteryBadge(change.after)
    }
}
