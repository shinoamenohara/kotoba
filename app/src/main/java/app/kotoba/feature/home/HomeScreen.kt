package app.kotoba.feature.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Radar
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.data.GamificationProfile
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.ProgressRing
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.SpeakButton
import app.kotoba.core.ui.components.questLabel
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings
import java.time.LocalTime

/** Navigation callbacks for the home screen. */
data class HomeActions(
    val startSession: () -> Unit,
    val quickReview: (minutes: Int) -> Unit,
    val weakWords: () -> Unit,
    val openCard: (Long) -> Unit,
    val openDocument: (DocumentEntity) -> Unit,
    val openSettings: () -> Unit,
    val openAchievements: () -> Unit,
    val openLeague: () -> Unit,
    val openLibrary: () -> Unit,
)

@Composable
fun HomeScreen(actions: HomeActions, vm: HomeViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val s = state.settings
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(greeting(s.userName), style = MaterialTheme.typography.titleLarge)
                        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                actions = {
                    LanguageSwitcher(s.activeLanguage, s.studyLanguages, vm::switchLanguage)
                    IconButton(onClick = actions.openSettings) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.action_settings))
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = padding.calculateTopPadding(), bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item { state.profile?.let { ProfileStrip(it, actions.openAchievements) } }
            item { DailyGoalCard(state, actions.startSession) }
            item { QuickReviewRow(s.quickReviewMinutes, actions.quickReview) }
            item { LeagueSummaryCard(actions.openLeague) }
            if (state.counts.weak > 0) item { WeakWordsCard(state.counts.weak, actions.weakWords) }
            state.wordOfDay?.let { w -> item { WordOfDayCard(w, s.showReading, { vm.speak(w) }, { actions.openCard(w.id) }) } }
            state.lastDocument?.let { d -> item { ContinueReadingCard(d) { actions.openDocument(d) } } }
            if (state.counts.total == 0) item { EmptyLibraryCard(actions.openLibrary) }
            state.profile?.let { p -> item { QuestsCard(p) } }
        }
    }
}

@Composable
private fun greeting(name: String): String {
    val hour = remember { LocalTime.now().hour }
    val res = when (hour) {
        in 5..11 -> R.string.home_greeting_morning
        in 12..17 -> R.string.home_greeting_day
        in 18..22 -> R.string.home_greeting_evening
        else -> R.string.home_greeting_night
    }
    val base = stringResource(res)
    return if (name.isBlank()) base else "$base, $name"
}

@Composable
fun LanguageSwitcher(active: StudyLanguage, languages: Set<StudyLanguage>, onSelect: (StudyLanguage) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("${active.flag} ${active.code.uppercase()}") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            languages.sortedBy { it.ordinal }.forEach { l ->
                DropdownMenuItem(text = { Text("${l.flag}  ${l.nativeName}") }, onClick = { open = false; onSelect(l) })
            }
        }
    }
}

@Composable
private fun ProfileStrip(p: GamificationProfile, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("🔥 ${p.streak}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.home_level, p.level.level), style = MaterialTheme.typography.labelLarge)
            LinearProgressIndicator(
                progress = { p.level.progress },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
        }
        Text("${p.level.totalXp} XP", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Icon(Icons.Filled.EmojiEvents, contentDescription = stringResource(R.string.home_achievements), tint = MaterialTheme.colorScheme.tertiary)
    }
}

@Composable
private fun DailyGoalCard(state: HomeUiState, onStart: () -> Unit) {
    val p = state.profile
    val haptics = rememberHaptics()
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                ProgressRing(progress = p?.goalProgress ?: 0f, size = 84.dp, stroke = 10.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${p?.today?.xp ?: 0}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text("/ ${p?.dailyGoal ?: state.settings.dailyXpGoal} XP", style = MaterialTheme.typography.labelSmall)
                    }
                }
                Spacer(Modifier.width(20.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        stringResource(if (p?.today?.goalReached == true) R.string.home_goal_done else R.string.home_daily_goal),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Text(stringResource(R.string.home_due_count, state.counts.due), style = MaterialTheme.typography.bodyMedium)
                    Text(stringResource(R.string.home_new_count, state.counts.newAvailable), style = MaterialTheme.typography.bodyMedium)
                    if (p != null) {
                        Text(
                            stringResource(R.string.home_today_stats, p.today.newWords, p.today.reviews),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            val hasWork = state.counts.due + state.counts.newAvailable > 0
            Button(
                onClick = { haptics.tick(); onStart() },
                modifier = Modifier.fillMaxWidth().height(56.dp),
                contentPadding = ButtonDefaults.ButtonWithIconContentPadding,
            ) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(if (hasWork) R.string.home_start_session else R.string.home_practice_more),
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }
    }
}

@Composable
private fun QuickReviewRow(defaultMinutes: Int, onQuick: (Int) -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
        Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Bolt, contentDescription = null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
            Spacer(Modifier.width(8.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_quick_review), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
                Text(stringResource(R.string.home_quick_review_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
            }
            listOf(1, 3, 5).forEach { m ->
                val primary = m == defaultMinutes
                if (primary) {
                    Button(onClick = { onQuick(m) }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.home_minutes_short, m))
                    }
                } else {
                    FilledTonalButton(onClick = { onQuick(m) }, contentPadding = PaddingValues(horizontal = 12.dp), modifier = Modifier.padding(start = 4.dp)) {
                        Text(stringResource(R.string.home_minutes_short, m))
                    }
                }
            }
        }
    }
}

@Composable
private fun WeakWordsCard(count: Int, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Radar, contentDescription = null, tint = MaterialTheme.colorScheme.onTertiaryContainer)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_weak_title), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
                Text(
                    pluralStringResource(R.plurals.home_weak_words, count, count),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer,
                )
            }
            Text(stringResource(R.string.home_train), color = MaterialTheme.colorScheme.onTertiaryContainer, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
private fun WordOfDayCard(card: CardEntity, showReading: Boolean, onSpeak: () -> Unit, onOpen: () -> Unit) {
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.home_word_of_day), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                MasteryBadge(card.mastery)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(card.term, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    val sub = listOf(card.reading.takeIf { showReading }, card.ipa).filter { !it.isNullOrBlank() }.joinToString("  ·  ")
                    if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                SpeakButton(onSpeak)
            }
            Text(card.translation, style = MaterialTheme.typography.titleMedium)
            if (card.example.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(card.example, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (card.exampleTranslation.isNotBlank()) {
                    Text(card.exampleTranslation, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun ContinueReadingCard(doc: DocumentEntity, onClick: () -> Unit) {
    Card(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (doc.kind == DocumentKind.MANGA) "🎌" else "📖", style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.home_continue_reading), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                Text(doc.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                LinearProgressIndicator(
                    progress = { doc.progress.coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 6.dp).height(4.dp),
                    strokeCap = StrokeCap.Round,
                    drawStopIndicator = {},
                )
            }
            Icon(Icons.AutoMirrored.Filled.MenuBook, contentDescription = null, modifier = Modifier.padding(start = 12.dp).size(20.dp))
        }
    }
}

@Composable
private fun EmptyLibraryCard(onOpen: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.home_empty_title), style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.home_empty_message), style = MaterialTheme.typography.bodyMedium)
            FilledTonalButton(onClick = onOpen) { Text(stringResource(R.string.home_open_library)) }
        }
    }
}

@Composable
private fun QuestsCard(p: GamificationProfile) {
    val animate = LocalUserSettings.current.animations
    Column {
        SectionHeader(stringResource(R.string.home_quests))
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                (listOf(p.dailyChallenge) + p.weeklyQuests).forEach { q ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(if (q.done) "✅" else "🎯", modifier = Modifier.padding(end = 10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(questLabel(q.key), style = MaterialTheme.typography.bodyMedium)
                            LinearProgressIndicator(
                                progress = { (q.progress.toFloat() / q.target).coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp).height(6.dp),
                                strokeCap = StrokeCap.Round,
                                drawStopIndicator = {},
                            )
                        }
                        Text(
                            "${q.progress.coerceAtMost(q.target)}/${q.target}",
                            style = MaterialTheme.typography.labelMedium,
                            modifier = Modifier.padding(start = 10.dp),
                        )
                    }
                }
                AnimatedVisibility(visible = animate && p.today.goalReached) {
                    AssistChip(onClick = {}, label = { Text(stringResource(R.string.home_reward_claimed)) })
                }
            }
        }
    }
}
