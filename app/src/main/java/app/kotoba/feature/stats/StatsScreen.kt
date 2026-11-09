package app.kotoba.feature.stats

import android.content.Context
import android.content.Intent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.hilt.navigation.compose.hiltViewModel
import app.kotoba.R
import app.kotoba.core.db.CardEntity
import app.kotoba.core.model.Mastery
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.MasteryBar
import app.kotoba.core.ui.components.StatTile
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.masteryLabel
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings
import app.kotoba.core.ui.theme.MasteryColors
import kotlin.math.roundToInt

@Composable
fun StatsScreen(
    onOpenMap: () -> Unit,
    onOpenCategory: (String) -> Unit,
    onOpenCard: (Long) -> Unit,
    onOpenWeakWords: () -> Unit,
    viewModel: StatsViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    Scaffold(
        topBar = {
            KotobaTopBar(
                title = stringResource(R.string.stats_title),
                actions = {
                    IconButton(onClick = { haptics.tick(); shareReport(context, state) }, enabled = !state.loading) {
                        Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.stats_share))
                    }
                    IconButton(onClick = { haptics.tick(); onOpenMap() }) {
                        Icon(Icons.Outlined.Map, contentDescription = stringResource(R.string.stats_vocab_map))
                    }
                },
            )
        },
    ) { padding ->
        if (state.loading) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                PeriodSelector(state.period) { haptics.tick(); viewModel.setPeriod(it) }
            }
            item { ReportCard(state, onShare = { haptics.tick(); shareReport(context, state) }) }
            item { OverviewCard(state, onOpenMap) }
            item { GrowthChart(state) }
            item { ActivityChart(state) }
            item { AccuracyChart(state) }
            if (state.categories.isNotEmpty()) item { CategoryProgress(state, onOpenCategory) }
            item { LevelChart(state) }
            item { HardWordsChart(state, onOpenWeakWords) }
            item { ErrorAnalytics(state, onOpenCard) }
            item {
                Text(
                    stringResource(R.string.stats_history),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            if (state.history.isEmpty()) {
                item { EmptyLine(stringResource(R.string.stats_history_empty)) }
            }
            items(state.history, key = { it.epochDay }) { day ->
                HistoryDay(day, state.today, state.terms, onOpenCard)
            }
        }
    }
}

@Composable
private fun PeriodSelector(selected: StatsPeriod, onSelect: (StatsPeriod) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        StatsPeriod.entries.forEachIndexed { i, p ->
            SegmentedButton(
                selected = p == selected,
                onClick = { onSelect(p) },
                shape = SegmentedButtonDefaults.itemShape(i, StatsPeriod.entries.size),
            ) { Text(periodLabel(p)) }
        }
    }
}

@Composable
private fun ReportCard(state: StatsUiState, onShare: () -> Unit) {
    val s = state.summary
    val context = LocalContext.current
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
        Column(Modifier.padding(16.dp).animateContentSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        stringResource(periodReportTitleRes(state.period)),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer,
                    )
                    Text(
                        "${state.language.flag} ${state.language.nativeName}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f),
                    )
                }
                FilledTonalButton(onClick = onShare) {
                    Icon(Icons.Filled.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.stats_share))
                }
            }
            val items = listOf(
                "✨" to (s.newWords.toString() to stringResource(R.string.stats_new_words)),
                "🔁" to (s.reviews.toString() to stringResource(R.string.stats_reviews)),
                "🎯" to (formatPercent(s.accuracy) to stringResource(R.string.stats_accuracy)),
                "⏱️" to (formatDuration(context, s.studyMs) to stringResource(R.string.stats_study_time)),
                "⚡" to (formatSeconds(s.avgResponseMs) to stringResource(R.string.stats_avg_response)),
                "⭐" to ("${s.xp}" to stringResource(R.string.stats_xp_earned)),
                "💔" to (s.forgotten.toString() to stringResource(R.string.stats_forgotten)),
                "💪" to (s.recovered.toString() to stringResource(R.string.stats_recovered)),
            ) + if (state.period != StatsPeriod.DAY) {
                listOf("🏁" to ("${s.goalDays}/${state.period.days}" to stringResource(R.string.stats_goal_days)))
            } else {
                emptyList()
            }
            items.chunked(2).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (emoji, vl) -> ReportMetric(emoji, vl.first, vl.second, Modifier.weight(1f)) }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ReportMetric(emoji: String, value: String, label: String, modifier: Modifier) {
    Surface(modifier, shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = 0.55f)) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(emoji, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(8.dp))
            Column {
                Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun OverviewCard(state: StatsUiState, onOpenMap: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(state.totalWords.toString(), stringResource(R.string.stats_total_words), Modifier.weight(1f), emoji = "📚")
            StatTile(state.mastered.toString(), stringResource(R.string.stats_mastered), Modifier.weight(1f), emoji = "🏆", accent = MasteryColors.Mastered)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StatTile(state.streak.toString(), stringResource(R.string.stats_streak), Modifier.weight(1f), emoji = "🔥", accent = MasteryColors.Learning)
            StatTile(
                stringResource(R.string.stats_level_value, state.level?.level ?: 1),
                stringResource(R.string.stats_total_xp, state.level?.totalXp ?: 0),
                Modifier.weight(1f), emoji = "🎖️",
            )
        }
        state.level?.let { lvl ->
            LinearProgressIndicator(
                progress = { lvl.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }
        ChartCard(stringResource(R.string.stats_word_mastery)) {
            MasteryBar(state.mastery, height = 14.dp)
            Row(Modifier.fillMaxWidth()) {
                Mastery.entries.forEach { m ->
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${state.mastery[m] ?: 0}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MasteryColors.of(m))
                        Text(masteryLabel(m), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            HorizontalDivider()
            Row(Modifier.fillMaxWidth()) {
                NewWordsCell(state.newToday, stringResource(R.string.stats_new_today), Modifier.weight(1f))
                NewWordsCell(state.newWeek, stringResource(R.string.stats_new_week), Modifier.weight(1f))
                NewWordsCell(state.newMonth, stringResource(R.string.stats_new_month), Modifier.weight(1f))
            }
            FilledTonalButton(onClick = onOpenMap, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Outlined.Map, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.stats_open_vocab_map))
            }
        }
    }
}

@Composable
private fun NewWordsCell(value: Int, label: String, modifier: Modifier) {
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("+$value", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun StatsUiState.xLabels() = chartDays.map { shortDay(it, chartDays.size) }

@Composable
private fun GrowthChart(state: StatsUiState) {
    val studied = stringResource(R.string.stats_series_studied)
    val mastered = stringResource(R.string.stats_series_mastered)
    val primary = MaterialTheme.colorScheme.primary
    ChartCard(stringResource(R.string.stats_chart_growth), subtitle = stringResource(R.string.stats_chart_growth_sub)) {
        LineChart(
            series = listOf(
                LineSeries(studied, state.growthStudied.map { it.toFloat() }, primary, fill = true),
                LineSeries(mastered, state.growthMastered.map { it.toFloat() }, MasteryColors.Mastered, fill = true),
            ),
            xLabels = state.xLabels(),
        )
        ChartLegend(listOf(studied to primary, mastered to MasteryColors.Mastered))
    }
}

@Composable
private fun ActivityChart(state: StatsUiState) {
    val reviews = stringResource(R.string.stats_reviews)
    val newWords = stringResource(R.string.stats_new_words)
    val goal = stringResource(R.string.stats_goal_reached)
    val primary = MaterialTheme.colorScheme.primary
    val secondary = MaterialTheme.colorScheme.tertiary
    val bars = state.daily.map {
        BarStack(listOf(it.reviews.toFloat() to primary, it.newWords.toFloat() to secondary), marker = it.goalReached)
    }
    ChartCard(stringResource(R.string.stats_chart_activity)) {
        BarChart(bars, state.xLabels(), markerColor = MasteryColors.Learning)
        ChartLegend(listOf(reviews to primary, newWords to secondary, goal to MasteryColors.Learning))
    }
}

@Composable
private fun AccuracyChart(state: StatsUiState) {
    val avg = state.summary.accuracy
    ChartCard(
        stringResource(R.string.stats_chart_accuracy),
        subtitle = stringResource(R.string.stats_period_accuracy, formatPercent(avg)),
    ) {
        if (state.accuracyTrend.all { it == null }) {
            EmptyLine(stringResource(R.string.stats_no_answers))
        } else {
            LineChart(
                series = listOf(
                    LineSeries(
                        stringResource(R.string.stats_accuracy),
                        state.accuracyTrend.map { it?.times(100f) },
                        accuracyColor(avg ?: 1f),
                    ),
                ),
                xLabels = state.xLabels(),
                fixedMax = 100f,
                yFormat = { "${it.roundToInt()}%" },
            )
        }
    }
}

@Composable
private fun CategoryProgress(state: StatsUiState, onOpenCategory: (String) -> Unit) {
    val max = state.categories.maxOf { it.total }.coerceAtLeast(1)
    val animate = LocalUserSettings.current.animations
    ChartCard(stringResource(R.string.stats_chart_categories), subtitle = stringResource(R.string.stats_chart_categories_sub)) {
        state.categories.forEach { c ->
            var started by remember { mutableStateOf(false) }
            val fraction by animateFloatAsState(
                if (started || !animate) c.total.toFloat() / max else 0f,
                tween(if (animate) 700 else 0),
                label = "category",
            )
            LaunchedEffect(Unit) { started = true }
            Column(
                Modifier.fillMaxWidth().clickable { onOpenCategory(c.category.key) }.padding(vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("${c.category.emoji}  ${categoryLabel(c.category)}", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text(
                        "${c.mastered}/${c.total} · ${(c.progress * 100).roundToInt()}%",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Box(Modifier.fillMaxWidth(fraction.coerceAtLeast(0.02f))) { MasteryBar(c.counts, height = 10.dp) }
            }
        }
        ChartLegend(masteryLegend())
    }
}

@Composable
private fun LevelChart(state: StatsUiState) {
    val bars = state.levels.map { g -> BarStack(Mastery.entries.map { (g.counts[it] ?: 0).toFloat() to MasteryColors.of(it) }) }
    val labels = state.levels.map { levelLabel(it.key) }
    ChartCard(
        stringResource(R.string.stats_chart_levels, state.levelScale.name),
        subtitle = "${state.language.flag} ${state.language.nativeName}",
    ) {
        if (state.levels.all { it.total == 0 }) {
            EmptyLine(stringResource(R.string.stats_no_levels))
        } else {
            BarChart(bars, labels, height = 160.dp)
            ChartLegend(masteryLegend())
        }
    }
}

@Composable
private fun HardWordsChart(state: StatsUiState, onOpenWeakWords: () -> Unit) {
    val forgotten = stringResource(R.string.stats_forgotten)
    val recovered = stringResource(R.string.stats_recovered)
    val error = MaterialTheme.colorScheme.error
    ChartCard(stringResource(R.string.stats_chart_hard), subtitle = stringResource(R.string.stats_chart_hard_sub)) {
        LineChart(
            series = listOf(
                LineSeries(forgotten, state.daily.map { it.forgotten.toFloat() }, error),
                LineSeries(recovered, state.daily.map { it.recovered.toFloat() }, MasteryColors.Mastered),
            ),
            xLabels = state.xLabels(),
            height = 150.dp,
        )
        ChartLegend(listOf(forgotten to error, recovered to MasteryColors.Mastered))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                pluralStringResource(R.plurals.stats_weak_now, state.weakCount, state.weakCount),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            if (state.weakCount > 0) {
                TextButton(onClick = onOpenWeakWords) { Text(stringResource(R.string.stats_practice_weak)) }
            }
        }
    }
}

@Composable
private fun ErrorAnalytics(state: StatsUiState, onOpenCard: (Long) -> Unit) {
    ChartCard(stringResource(R.string.stats_errors), subtitle = stringResource(periodReportTitleRes(state.period))) {
        SubHeader(stringResource(R.string.stats_most_missed))
        if (state.mostMissed.isEmpty()) EmptyLine(stringResource(R.string.stats_no_mistakes))
        state.mostMissed.forEach { m ->
            WordRow(m.card, trailing = stringResource(R.string.stats_missed_of, m.count, m.total), color = MaterialTheme.colorScheme.error) { onOpenCard(m.card.id) }
        }

        SubHeader(stringResource(R.string.stats_confused_pairs))
        if (state.confused.isEmpty()) EmptyLine(stringResource(R.string.stats_no_confusions))
        state.confused.forEach { p ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    p.a.term, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    modifier = Modifier.clickable { onOpenCard(p.a.id) },
                )
                Text("  ⇄  ", color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    p.b.term, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f).clickable { onOpenCard(p.b.id) },
                )
                Text("×${p.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
            }
        }

        SubHeader(stringResource(R.string.stats_by_exercise))
        if (state.byExercise.isEmpty()) EmptyLine(stringResource(R.string.stats_no_answers))
        state.byExercise.forEach { e ->
            val color = accuracyColor(e.accuracy)
            val animate = LocalUserSettings.current.animations
            val p by animateFloatAsState(e.accuracy, tween(if (animate) 700 else 0), label = "exercise")
            Column(Modifier.padding(vertical = 2.dp)) {
                Row {
                    Text(exerciseLabel(e.type), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Text("${formatPercent(e.accuracy)} · ${e.total}", style = MaterialTheme.typography.labelMedium, color = color)
                }
                Spacer(Modifier.height(4.dp))
                LinearProgressIndicator(
                    progress = { p },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = color,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
        }

        SubHeader(stringResource(R.string.stats_slowest))
        if (state.slowest.isEmpty()) EmptyLine(stringResource(R.string.stats_no_answers))
        state.slowest.forEach { s ->
            WordRow(s.card, trailing = formatSeconds(s.avgMs), color = MasteryColors.Learning) { onOpenCard(s.card.id) }
        }
    }
}

@Composable
private fun SubHeader(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
}

@Composable
private fun EmptyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun WordRow(card: CardEntity, trailing: String, color: Color, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable(onClick = onClick).padding(vertical = 6.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(card.term, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                val sub = card.reading.ifBlank { card.ipa }
                if (sub.isNotBlank()) {
                    Spacer(Modifier.width(6.dp))
                    Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Text(card.translation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(trailing, style = MaterialTheme.typography.labelLarge, color = color)
    }
}

@Composable
private fun HistoryDay(day: DaySessions, today: Long, terms: Map<Long, CardEntity>, onOpenCard: (Long) -> Unit) {
    var expanded by rememberSaveable(day.epochDay) { mutableStateOf(false) }
    val acc = StatsCalculator.accuracy(day.correct, day.answers)
    val context = LocalContext.current
    Card(
        modifier = Modifier.fillMaxWidth().animateContentSize(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        onClick = { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(dayTitle(day.epochDay, today), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        stringResource(R.string.stats_history_line, day.answers, day.distinctWords, formatDuration(context, day.activeMs)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(formatPercent(acc), style = MaterialTheme.typography.titleMedium, color = accuracyColor(acc ?: 0f), fontWeight = FontWeight.Bold)
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, contentDescription = null)
            }
            AnimatedVisibility(expanded) {
                Column(Modifier.padding(top = 8.dp)) {
                    day.logs.take(MAX_HISTORY_ROWS).forEach { log ->
                        val card = terms[log.cardId]
                        Row(
                            Modifier.fillMaxWidth().clickable(enabled = card != null) { onOpenCard(log.cardId) }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(if (log.correct) "✓" else "✗", color = if (log.correct) MasteryColors.Mastered else MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                            Spacer(Modifier.width(10.dp))
                            Text(card?.term ?: "—", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(exerciseLabel(log.exercise), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (day.logs.size > MAX_HISTORY_ROWS) {
                        Text(
                            stringResource(R.string.stats_history_more, day.logs.size - MAX_HISTORY_ROWS),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }
}

private const val MAX_HISTORY_ROWS = 40

private fun shareReport(context: Context, state: StatsUiState) {
    val s = state.summary
    val r = context.resources
    val text = buildString {
        appendLine(r.getString(R.string.stats_share_header, r.getString(periodReportTitleRes(state.period)), "${state.language.flag} ${state.language.nativeName}"))
        appendLine()
        appendLine("✨ ${r.getString(R.string.stats_new_words)}: ${s.newWords}")
        appendLine("🔁 ${r.getString(R.string.stats_reviews)}: ${s.reviews}")
        appendLine("🎯 ${r.getString(R.string.stats_accuracy)}: ${formatPercent(s.accuracy)}")
        appendLine("⏱️ ${r.getString(R.string.stats_study_time)}: ${formatDuration(context, s.studyMs)}")
        appendLine("💔 ${r.getString(R.string.stats_forgotten)} / 💪 ${r.getString(R.string.stats_recovered)}: ${s.forgotten} / ${s.recovered}")
        appendLine("🔥 ${r.getString(R.string.stats_streak)}: ${state.streak}")
        appendLine("📚 ${r.getString(R.string.stats_share_vocab, state.totalWords, state.mastered)}")
        state.level?.let { appendLine("🎖️ ${r.getString(R.string.stats_level_value, it.level)} · ${it.totalXp} XP") }
    }.trim()
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
        putExtra(Intent.EXTRA_SUBJECT, r.getString(periodReportTitleRes(state.period)))
    }
    context.startActivity(Intent.createChooser(send, r.getString(R.string.stats_share)))
}
