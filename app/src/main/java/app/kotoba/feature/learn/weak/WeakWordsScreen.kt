package app.kotoba.feature.learn.weak

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.db.CardEntity
import app.kotoba.core.srs.WeakReason
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.theme.LocalUserSettings

@Composable
fun WeakWordsScreen(
    onBack: () -> Unit,
    onPractice: () -> Unit,
    onOpenCard: (Long) -> Unit,
    vm: WeakWordsViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { KotobaTopBar(stringResource(R.string.learn_weak_title), onBack = onBack) },
        bottomBar = {
            if (state.items.isNotEmpty()) {
                Surface(tonalElevation = 3.dp) {
                    Button(onClick = onPractice, modifier = Modifier.fillMaxWidth().padding(16.dp).height(52.dp)) {
                        Icon(Icons.Rounded.PlayArrow, null)
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.learn_weak_practice, state.items.size))
                    }
                }
            }
        },
    ) { padding ->
        when {
            state.loading -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            state.items.isEmpty() && state.pairs.isEmpty() -> Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                EmptyState("💪", stringResource(R.string.learn_empty_weak_title), stringResource(R.string.learn_weak_none))
            }
            else -> LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                item {
                    Text(stringResource(R.string.learn_weak_intro), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (state.pairs.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.learn_weak_confused)) }
                    items(state.pairs, key = { "p${it.a.id}-${it.b.id}" }) { p ->
                        Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f), modifier = Modifier.fillMaxWidth()) {
                            Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                PairSide(p.a, Modifier.weight(1f))
                                Text("⇄", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(horizontal = 8.dp))
                                PairSide(p.b, Modifier.weight(1f))
                                Text("×${p.count}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.error)
                            }
                        }
                    }
                }
                if (state.items.isNotEmpty()) {
                    item { SectionHeader(stringResource(R.string.learn_weak_words, state.items.size)) }
                    items(state.items, key = { it.card.id }) { item -> WeakRow(item) { onOpenCard(item.card.id) } }
                }
            }
        }
    }
}

@Composable
private fun PairSide(card: CardEntity, modifier: Modifier) {
    Column(modifier) {
        Text(card.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(card.translation, style = MaterialTheme.typography.bodySmall, maxLines = 1)
    }
}

@Composable
private fun WeakRow(item: WeakItem, onClick: () -> Unit) {
    val showReading = LocalUserSettings.current.showReading
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(item.card.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        if (showReading && item.card.reading.isNotBlank()) {
                            Spacer(Modifier.width(8.dp))
                            Text(item.card.reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    Text(item.card.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                MasteryBadge(item.card.mastery)
            }
            LinearProgressIndicator(
                progress = { (item.score / 10.0).toFloat().coerceIn(0.05f, 1f) },
                modifier = Modifier.fillMaxWidth().height(6.dp),
                color = MaterialTheme.colorScheme.error,
                strokeCap = StrokeCap.Round,
                drawStopIndicator = {},
            )
            val reasons = item.reasons.ifEmpty { setOf<WeakReason?>(null) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                reasons.forEach { r -> SuggestionChip(onClick = onClick, label = { Text(reasonLabel(r)) }) }
            }
        }
    }
}

@Composable
private fun reasonLabel(r: WeakReason?): String = stringResource(
    when (r) {
        WeakReason.FORGETS_OFTEN -> R.string.learn_reason_forgets
        WeakReason.HIGH_ERROR_RATE -> R.string.learn_reason_errors
        WeakReason.SLOW -> R.string.learn_reason_slow
        WeakReason.UNSTABLE -> R.string.learn_reason_unstable
        WeakReason.CONFUSED -> R.string.learn_reason_confused
        null -> R.string.learn_reason_recent
    },
)
