package app.kotoba.feature.home

import android.content.Intent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.data.AddFriendResult
import app.kotoba.core.data.LeaderboardEntry
import app.kotoba.core.data.LeagueStatus
import app.kotoba.core.data.LeagueTier
import app.kotoba.core.data.SeasonalEvent
import app.kotoba.core.data.SeasonalEvents
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.rememberHaptics
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date

@Composable
fun LeagueScreen(onBack: () -> Unit, vm: LeagueViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var adding by remember { mutableStateOf(false) }

    val msgAdded = stringResource(R.string.league_friend_added)
    val msgUpdated = stringResource(R.string.league_friend_updated)
    val msgInvalid = stringResource(R.string.league_friend_invalid)
    val msgSelf = stringResource(R.string.league_friend_self)
    LaunchedEffect(Unit) {
        vm.messages.collect { r ->
            if (r == AddFriendResult.ADDED || r == AddFriendResult.UPDATED) haptics.success()
            snackbar.showSnackbar(
                when (r) {
                    AddFriendResult.ADDED -> msgAdded
                    AddFriendResult.UPDATED -> msgUpdated
                    AddFriendResult.INVALID -> msgInvalid
                    AddFriendResult.SELF -> msgSelf
                },
            )
        }
    }

    val shareTitle = stringResource(R.string.league_share_title)
    val shareTemplate = stringResource(R.string.league_share_message)
    val share: () -> Unit = {
        haptics.tick()
        scope.launch {
            val code = vm.myCode()
            val send = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_TEXT, String.format(shareTemplate, code))
            }
            context.startActivity(Intent.createChooser(send, shareTitle))
        }
    }

    Scaffold(
        topBar = { KotobaTopBar(stringResource(R.string.league_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(16.dp, padding.calculateTopPadding() + 8.dp, 16.dp, 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            state.league?.let { l -> item { TierCard(l) } }
            state.active?.let { e -> item { EventCard(e.event, e.xp, e.badgeUnlocked) } }
            state.upcoming?.let { e -> item { UpcomingEventCard(e) } }
            item {
                SectionHeader(stringResource(R.string.league_leaderboard)) {
                    Row {
                        IconButton(onClick = { haptics.tick(); adding = true }) {
                            Icon(Icons.Filled.PersonAdd, contentDescription = stringResource(R.string.league_add_friend))
                        }
                        IconButton(onClick = share) {
                            Icon(Icons.Filled.Share, contentDescription = stringResource(R.string.league_share_code))
                        }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(vertical = 8.dp)) {
                        state.board.forEachIndexed { i, e ->
                            if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp))
                            BoardRow(i + 1, e, onRemove = { vm.removeFriend(e.id) })
                        }
                        if (state.board.size <= 1) {
                            Text(
                                stringResource(R.string.league_no_friends),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(16.dp),
                            )
                            Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Button(onClick = share) { Text(stringResource(R.string.league_share_code)) }
                                FilledTonalButton(onClick = { adding = true }) { Text(stringResource(R.string.league_add_friend)) }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
            if (state.badges.isNotEmpty()) {
                item { SectionHeader(stringResource(R.string.league_event_badges)) }
                item { BadgesRow(state.badges) }
            }
            state.league?.takeIf { it.history.isNotEmpty() }?.let { l ->
                item { SectionHeader(stringResource(R.string.league_history)) }
                itemsIndexed(l.history) { _, w ->
                    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(formatDay(w.weekStart), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${w.xp} XP", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(end = 12.dp))
                        val arrow = when {
                            w.tierAfter.ordinal > w.tierBefore.ordinal -> "⬆️"
                            w.tierAfter.ordinal < w.tierBefore.ordinal -> "⬇️"
                            else -> "➡️"
                        }
                        Text("$arrow ${w.tierAfter.emoji} ${tierName(w.tierAfter)}", style = MaterialTheme.typography.labelLarge)
                    }
                }
            }
            item {
                Text(
                    stringResource(R.string.league_rules_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
    }

    if (adding) AddFriendDialog(onDismiss = { adding = false }, onAdd = { adding = false; vm.addFriend(it) })
}

@Composable
private fun TierCard(l: LeagueStatus) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(l.tier.emoji, style = MaterialTheme.typography.displaySmall)
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.league_tier_name, tierName(l.tier)), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.league_days_left, l.daysLeft), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text("${l.weekXp} XP", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            LinearProgressIndicator(
                progress = { l.promotionProgress },
                modifier = Modifier.fillMaxWidth().height(8.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            val up = l.tier.promoteAt
            Text(
                when {
                    up == null -> stringResource(R.string.league_top_tier)
                    l.weekXp >= up -> stringResource(R.string.league_promotion_secured)
                    else -> stringResource(R.string.league_to_promotion, up - l.weekXp, tierName(LeagueTier.fromOrdinal(l.tier.ordinal + 1)))
                },
                style = MaterialTheme.typography.bodyMedium,
            )
            if (l.inDemotionZone) {
                Text(
                    stringResource(R.string.league_demotion_warning, l.tier.demoteBelow),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EventCard(e: SeasonalEvent, xp: Int, unlocked: Boolean) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("${e.emoji} ${eventName(e.id)}", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(eventDescription(e.id), style = MaterialTheme.typography.bodyMedium)
            Text(
                stringResource(R.string.league_event_until, formatDate(e.end)) + " · " + eventBoostLabel(e),
                style = MaterialTheme.typography.labelMedium,
            )
            LinearProgressIndicator(
                progress = { (xp.toFloat() / e.targetXp).coerceIn(0f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            Text(
                if (unlocked) stringResource(R.string.league_event_badge_earned) else stringResource(R.string.league_event_progress, xp.coerceAtMost(e.targetXp), e.targetXp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun UpcomingEventCard(e: SeasonalEvent) {
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(e.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column {
                Text(stringResource(R.string.league_event_upcoming, eventName(e.id)), style = MaterialTheme.typography.titleSmall)
                Text(stringResource(R.string.league_event_starts, formatDate(e.start)) + " · " + eventBoostLabel(e), style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun eventBoostLabel(e: SeasonalEvent): String {
    val base = stringResource(R.string.league_event_boost, SeasonalEvent.MULTIPLIER.toString())
    val focus = e.focus ?: return base
    return base + " · " + stringResource(R.string.league_event_focus_boost, "${focus.flag} ${focus.nativeName}", SeasonalEvent.FOCUS_MULTIPLIER.toInt().toString())
}

@Composable
private fun BoardRow(rank: Int, e: LeaderboardEntry, onRemove: () -> Unit) {
    val container = if (e.isMe) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else androidx.compose.ui.graphics.Color.Transparent
    Row(
        Modifier.fillMaxWidth().background(container).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            when (rank) { 1 -> "🥇"; 2 -> "🥈"; 3 -> "🥉"; else -> "$rank" },
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.width(36.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (e.isMe) stringResource(R.string.league_you, e.name.ifBlank { "—" }) else e.name,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (e.isMe) FontWeight.Bold else FontWeight.Normal,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                "${e.tier.emoji} ${tierName(e.tier)} · 🔥 ${e.streak}" +
                    if (e.stale) " · " + stringResource(R.string.league_stale, formatMillis(e.updatedAt)) else "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text("${e.weekXp} XP", style = MaterialTheme.typography.titleSmall)
        if (!e.isMe) {
            IconButton(onClick = onRemove) {
                Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.league_remove_friend))
            }
        }
    }
}

@Composable
private fun BadgesRow(keys: List<String>) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            keys.sortedDescending().forEach { key ->
                val id = key.removePrefix("event_").substringBeforeLast("_")
                val year = key.substringAfterLast("_")
                val emoji = SeasonalEvents.forYear(year.toIntOrNull() ?: 2026).firstOrNull { it.id == id }?.emoji ?: "🏅"
                Text("$emoji ${eventName(id)} $year", style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}

@Composable
private fun AddFriendDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    val clipboard = LocalClipboardManager.current
    var text by remember { mutableStateOf(clipboard.getText()?.text?.takeIf { "KTB1-" in it } ?: "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.league_add_friend)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.league_add_friend_hint), style = MaterialTheme.typography.bodyMedium)
                OutlinedTextField(value = text, onValueChange = { text = it }, label = { Text(stringResource(R.string.league_friend_code)) }, maxLines = 4)
            }
        },
        confirmButton = { TextButton(onClick = { onAdd(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.league_add)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(android.R.string.cancel)) } },
    )
}

/** Compact Home entry point: tier, weekly XP and the running event. */
@Composable
fun LeagueSummaryCard(onOpen: () -> Unit, vm: LeagueViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val l = state.league ?: return
    Card(onClick = onOpen, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(l.tier.emoji, style = MaterialTheme.typography.headlineSmall)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.league_tier_name, tierName(l.tier)), style = MaterialTheme.typography.titleSmall)
                val rank = state.board.indexOfFirst { it.isMe } + 1
                Text(
                    stringResource(R.string.league_summary, l.weekXp, l.daysLeft) +
                        if (state.board.size > 1) " · #$rank/${state.board.size}" else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            state.active?.let { e ->
                Text("${e.event.emoji} ×${e.event.multiplierFor(null)}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.tertiary)
            }
        }
    }
}

@Composable
fun tierName(t: LeagueTier): String = stringResource(
    when (t) {
        LeagueTier.BRONZE -> R.string.tier_bronze
        LeagueTier.SILVER -> R.string.tier_silver
        LeagueTier.GOLD -> R.string.tier_gold
        LeagueTier.SAPPHIRE -> R.string.tier_sapphire
        LeagueTier.RUBY -> R.string.tier_ruby
        LeagueTier.EMERALD -> R.string.tier_emerald
        LeagueTier.AMETHYST -> R.string.tier_amethyst
        LeagueTier.PEARL -> R.string.tier_pearl
        LeagueTier.OBSIDIAN -> R.string.tier_obsidian
        LeagueTier.DIAMOND -> R.string.tier_diamond
    },
)

@Composable
private fun eventName(id: String): String = stringResource(
    when (id) {
        "new_year" -> R.string.event_new_year
        "spring_festival" -> R.string.event_spring_festival
        "hanami" -> R.string.event_hanami
        "tanabata" -> R.string.event_tanabata
        "summer_reading" -> R.string.event_summer_reading
        "mid_autumn" -> R.string.event_mid_autumn
        "halloween" -> R.string.event_halloween
        else -> R.string.event_generic
    },
)

@Composable
private fun eventDescription(id: String): String = stringResource(
    when (id) {
        "new_year" -> R.string.event_new_year_desc
        "spring_festival" -> R.string.event_spring_festival_desc
        "hanami" -> R.string.event_hanami_desc
        "tanabata" -> R.string.event_tanabata_desc
        "summer_reading" -> R.string.event_summer_reading_desc
        "mid_autumn" -> R.string.event_mid_autumn_desc
        "halloween" -> R.string.event_halloween_desc
        else -> R.string.event_generic
    },
)

private fun formatDate(d: LocalDate): String = d.format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM))
private fun formatDay(epochDay: Long): String = formatDate(LocalDate.ofEpochDay(epochDay))
private fun formatMillis(ms: Long): String =
    if (ms <= 0) "—" else DateFormat.getDateInstance(DateFormat.SHORT).format(Date(ms))
