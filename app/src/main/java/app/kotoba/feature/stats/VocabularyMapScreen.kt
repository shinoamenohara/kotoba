package app.kotoba.feature.stats

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.model.Mastery
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.LabeledProgress
import app.kotoba.core.ui.components.MasteryBar
import app.kotoba.core.ui.components.ProgressRing
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.masteryLabel
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.MasteryColors
import kotlin.math.roundToInt

@Composable
fun VocabularyMapScreen(
    onBack: () -> Unit,
    onOpenCategory: (String) -> Unit,
    viewModel: VocabularyMapViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    Scaffold(topBar = { KotobaTopBar(stringResource(R.string.stats_vocab_map), onBack = onBack) }) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyVerticalGrid(
            columns = GridCells.Adaptive(156.dp),
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            full { SizeCard(state) }
            full {
                Text(
                    stringResource(R.string.stats_map_categories),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.weak.isNotEmpty()) {
                full {
                    Text(
                        stringResource(R.string.stats_map_weak_hint, state.weak.size),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            items(state.categories, key = { it.category.key }) { c ->
                CategoryTile(c, weak = c.category in state.weak) { haptics.tick(); onOpenCategory(c.category.key) }
            }
            full { LevelsCard(state) }
            if (state.partsOfSpeech.isNotEmpty()) full { PosCard(state) }
            full { HistoryCard(state) }
        }
    }
}

private fun LazyGridScope.full(content: @Composable () -> Unit) {
    item(span = { GridItemSpan(maxLineSpan) }) { content() }
}

@Composable
private fun SizeCard(state: VocabularyMapUiState) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(R.string.stats_map_size),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                    Text(
                        state.total.toString(),
                        style = MaterialTheme.typography.displaySmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                }
                Text(state.language.flag, style = MaterialTheme.typography.displaySmall)
            }
            MasteryBar(state.mastery, height = 14.dp)
            Row(Modifier.fillMaxWidth()) {
                Mastery.entries.forEach { m ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${state.mastery[m] ?: 0}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MasteryColors.of(m))
                        Text(masteryLabel(m), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onPrimaryContainer)
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryTile(c: CategoryStat, weak: Boolean, onClick: () -> Unit) {
    val ringColor = when {
        c.total == 0 -> MaterialTheme.colorScheme.outline
        weak -> MaterialTheme.colorScheme.error
        c.progress >= 0.75f -> MasteryColors.Mastered
        c.progress >= 0.4f -> MasteryColors.Familiar
        else -> MasteryColors.Learning
    }
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(
            containerColor = if (weak) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.35f) else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        border = if (weak) BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.6f)) else null,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            ProgressRing(c.progress, size = 76.dp, stroke = 7.dp, color = ringColor) {
                Text(c.category.emoji, style = MaterialTheme.typography.headlineSmall)
            }
            Text(
                categoryLabel(c.category), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                maxLines = 1, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.Center,
            )
            Text(
                if (c.total == 0) stringResource(R.string.stats_map_empty_category)
                else stringResource(R.string.stats_map_category_counts, c.mastered, c.total),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (c.total > 0) {
                Text("${(c.progress * 100).roundToInt()}%", style = MaterialTheme.typography.labelLarge, color = ringColor, fontWeight = FontWeight.Bold)
            }
            if (weak) {
                Surface(color = MaterialTheme.colorScheme.error, shape = MaterialTheme.shapes.small) {
                    Text(
                        stringResource(R.string.stats_map_needs_attention),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onError,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun LevelsCard(state: VocabularyMapUiState) {
    ChartCard(
        stringResource(R.string.stats_chart_levels, state.levelScale.name),
        subtitle = stringResource(R.string.stats_map_levels_sub),
    ) {
        if (state.levels.all { it.total == 0 }) {
            Text(stringResource(R.string.stats_no_levels), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            state.levels.filter { it.total > 0 || it.key.isNotEmpty() }.forEach { g ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(levelLabel(g.key), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(72.dp))
                        Box(Modifier.weight(1f)) { MasteryBar(g.counts, height = 10.dp) }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "${g.mastered}/${g.total}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.width(64.dp),
                            textAlign = TextAlign.End,
                        )
                    }
                }
            }
            ChartLegend(masteryLegend())
        }
    }
}

@Composable
private fun PosCard(state: VocabularyMapUiState) {
    ChartCard(stringResource(R.string.stats_map_pos)) {
        state.partsOfSpeech.forEach { g ->
            LabeledProgress(
                label = partOfSpeechLabel(g.key),
                value = g.mastered,
                max = g.total,
                color = MasteryColors.Mastered,
            )
            Spacer(Modifier.height(2.dp))
        }
    }
}

@Composable
private fun HistoryCard(state: VocabularyMapUiState) {
    val studied = stringResource(R.string.stats_series_studied)
    val mastered = stringResource(R.string.stats_series_mastered)
    val primary = MaterialTheme.colorScheme.primary
    ChartCard(stringResource(R.string.stats_map_history)) {
        LineChart(
            series = listOf(
                LineSeries(studied, state.historyStudied.map { it.toFloat() }, primary, fill = true),
                LineSeries(mastered, state.historyMastered.map { it.toFloat() }, MasteryColors.Mastered, fill = true),
            ),
            xLabels = state.historyDays.map { shortDay(it, state.historyDays.size) },
        )
        ChartLegend(listOf(studied to primary, mastered to MasteryColors.Mastered))
    }
}
