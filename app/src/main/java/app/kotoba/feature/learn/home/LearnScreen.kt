package app.kotoba.feature.learn.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.db.DeckWithStats
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.navigation.SessionMode
import app.kotoba.core.navigation.SessionRoute
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.feature.learn.session.exerciseName

/** Exercise grid entries: type + emoji. */
private val exerciseGrid = listOf(
    ExerciseType.FLASHCARD_TERM_TO_TRANSLATION to "🃏",
    ExerciseType.FLASHCARD_TRANSLATION_TO_TERM to "🔄",
    ExerciseType.MULTIPLE_CHOICE to "🔘",
    ExerciseType.TYPING to "⌨️",
    ExerciseType.LISTENING to "🎧",
    ExerciseType.SPELLING to "🔤",
    ExerciseType.WORD_SCRAMBLE to "🧩",
    ExerciseType.FILL_BLANK to "✏️",
    ExerciseType.SENTENCE_BUILDER to "🧱",
    ExerciseType.MATCHING to "🔗",
    ExerciseType.CONTEXT_QUIZ to "🔍",
    ExerciseType.TRANSLATION to "🌐",
    ExerciseType.PRONUNCIATION to "🎤",
)

@Composable
fun LearnScreen(
    onStart: (SessionRoute) -> Unit,
    onWeakWords: () -> Unit,
    vm: LearnViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    val start: (SessionRoute) -> Unit = { haptics.tick(); onStart(it) }

    Scaffold(topBar = { KotobaTopBar(stringResource(R.string.learn_title)) }) { padding ->
        if (!state.loading && state.total == 0) {
            Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState("📭", stringResource(R.string.learn_no_words_title), stringResource(R.string.learn_no_words_msg))
            }
            return@Scaffold
        }
        LazyColumn(
            Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { DailyHero(state) { start(SessionRoute(SessionMode.DAILY.name)) } }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile("🌱", stringResource(R.string.learn_tile_new), state.newTotal.toString(), Modifier.weight(1f)) {
                        start(SessionRoute(SessionMode.NEW.name))
                    }
                    ModeTile("🔁", stringResource(R.string.learn_tile_review), state.due.toString(), Modifier.weight(1f)) {
                        start(SessionRoute(SessionMode.REVIEW.name))
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ModeTile("🃏", stringResource(R.string.learn_tile_flashcards), null, Modifier.weight(1f)) {
                        start(SessionRoute(SessionMode.FLASHCARDS.name))
                    }
                    ModeTile("🎯", stringResource(R.string.learn_tile_weak), state.weak.toString(), Modifier.weight(1f), onClick = onWeakWords)
                }
            }
            item { SectionHeader(stringResource(R.string.learn_quick_title)) }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(1, 3, 5).forEach { m ->
                        val default = m == state.quickMinutes
                        FilledTonalButton(
                            onClick = { start(SessionRoute(SessionMode.QUICK.name, minutes = m)) },
                            modifier = Modifier.weight(1f).height(56.dp),
                            colors = if (default) ButtonDefaults.filledTonalButtonColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                            else ButtonDefaults.filledTonalButtonColors(),
                        ) { Text(stringResource(R.string.learn_quick_minutes, m), fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            item {
                OutlinedCard(
                    onClick = { start(SessionRoute(SessionMode.MIXED.name)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("🎲", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.width(14.dp))
                        Column(Modifier.weight(1f)) {
                            Text(stringResource(R.string.learn_mode_mixed), style = MaterialTheme.typography.titleMedium)
                            Text(stringResource(R.string.learn_mixed_sub), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Icon(Icons.Rounded.PlayArrow, null, tint = MaterialTheme.colorScheme.primary)
                    }
                }
            }
            item { SectionHeader(stringResource(R.string.learn_exercises)) }
            items(exerciseGrid.chunked(3)) { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    row.forEach { (type, emoji) ->
                        ExerciseTile(emoji, exerciseName(type), Modifier.weight(1f)) {
                            start(SessionRoute(SessionMode.EXERCISE.name, exercise = type.name))
                        }
                    }
                    repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (state.decks.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.learn_decks)) }
                items(state.decks, key = { it.id }) { deck ->
                    DeckRow(deck) { start(SessionRoute(SessionMode.DAILY.name, deckId = deck.id)) }
                }
            }
        }
    }
}

@Composable
private fun DailyHero(state: LearnUiState, onStart: () -> Unit) {
    val primary = MaterialTheme.colorScheme.primary
    val tertiary = MaterialTheme.colorScheme.tertiary
    val nothing = state.due == 0 && state.newToday == 0
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp)) {
        Column(
            Modifier
                .fillMaxWidth()
                .background(Brush.linearGradient(listOf(primary, tertiary)))
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(stringResource(R.string.learn_daily_title), style = MaterialTheme.typography.titleLarge, color = Color.White, fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                HeroCount(state.due, stringResource(R.string.learn_due))
                HeroCount(state.newToday, stringResource(R.string.learn_new))
            }
            Text(
                stringResource(if (nothing) R.string.learn_daily_done else R.string.learn_daily_sub),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Button(
                onClick = onStart,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = primary),
            ) {
                Icon(Icons.Rounded.PlayArrow, null)
                Spacer(Modifier.width(6.dp))
                Text(stringResource(R.string.learn_daily_start), fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun HeroCount(value: Int, label: String) {
    Column {
        Text(value.toString(), style = MaterialTheme.typography.headlineMedium, color = Color.White, fontWeight = FontWeight.Bold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = Color.White.copy(alpha = 0.85f))
    }
}

@Composable
private fun ModeTile(emoji: String, title: String, count: String?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = modifier.heightIn(min = 96.dp),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerHigh),
    ) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(emoji, style = MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.weight(1f))
                if (count != null) {
                    Text(count, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(title, style = MaterialTheme.typography.titleSmall)
        }
    }
}

@Composable
private fun ExerciseTile(emoji: String, title: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = modifier.heightIn(min = 92.dp), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.fillMaxWidth().padding(10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.height(6.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}

@Composable
private fun DeckRow(deck: DeckWithStats, onStudy: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(12.dp).background(Color(deck.color), CircleShape))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(deck.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    stringResource(R.string.learn_deck_stats, deck.total, deck.dueCount, deck.newCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
                LinearProgressIndicator(
                    progress = { if (deck.total == 0) 0f else deck.masteredCount.toFloat() / deck.total },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            Spacer(Modifier.width(12.dp))
            FilledTonalButton(onClick = onStudy, enabled = deck.total > 0) { Text(stringResource(R.string.learn_study)) }
        }
    }
}
