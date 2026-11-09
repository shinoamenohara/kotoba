package app.kotoba.feature.learn.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings
import kotlinx.coroutines.delay

/** Tap a word, then its translation. Every first pick per word is recorded via the view model. */
@Composable
fun MatchingExercise(
    step: PreparedStep,
    matchedIds: Set<Long>,
    onPair: (termCardId: Long, translationCardId: Long) -> Boolean,
    onSpeak: (String) -> Unit,
) {
    val haptics = rememberHaptics()
    val showReading = LocalUserSettings.current.showReading
    val left = remember(step.index) { step.group.shuffled() }
    val right = remember(step.index) { step.group.shuffled() }
    var selectedLeft by rememberSaveable(step.index) { mutableStateOf<Long?>(null) }
    var selectedRight by rememberSaveable(step.index) { mutableStateOf<Long?>(null) }
    var wrong by remember(step.index) { mutableStateOf<Pair<Long, Long>?>(null) }

    LaunchedEffect(wrong) {
        if (wrong != null) {
            delay(600)
            wrong = null
        }
    }

    fun tryPair() {
        val l = selectedLeft ?: return
        val r = selectedRight ?: return
        val ok = onPair(l, r)
        if (ok) haptics.success() else {
            haptics.error()
            wrong = l to r
        }
        selectedLeft = null
        selectedRight = null
    }

    Column(
        Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Instruction(step.exercise)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                left.forEach { c ->
                    MatchTile(
                        text = c.term,
                        sub = if (showReading) c.reading else "",
                        selected = selectedLeft == c.id,
                        matched = c.id in matchedIds,
                        wrong = wrong?.first == c.id,
                    ) {
                        onSpeak(c.term)
                        selectedLeft = c.id
                        tryPair()
                    }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                right.forEach { c ->
                    MatchTile(
                        text = c.translation,
                        sub = "",
                        selected = selectedRight == c.id,
                        matched = c.id in matchedIds,
                        wrong = wrong?.second == c.id,
                    ) {
                        selectedRight = c.id
                        tryPair()
                    }
                }
            }
        }
    }
}

@Composable
private fun MatchTile(text: String, sub: String, selected: Boolean, matched: Boolean, wrong: Boolean, onClick: () -> Unit) {
    val border by animateColorAsState(
        when {
            wrong -> AnswerColors.Wrong
            matched -> AnswerColors.Correct
            selected -> MaterialTheme.colorScheme.primary
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "matchBorder",
    )
    val alpha by animateFloatAsState(if (matched) 0.35f else 1f, label = "matchAlpha")
    OutlinedCard(
        onClick = onClick,
        enabled = !matched,
        modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).alpha(alpha),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (selected || wrong) 2.dp else 1.dp, border),
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (sub.isNotBlank()) Text(sub, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(text, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        }
    }
}
