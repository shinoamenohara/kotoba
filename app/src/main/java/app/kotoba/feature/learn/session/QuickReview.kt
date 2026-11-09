package app.kotoba.feature.learn.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToInt

/** Time-boxed review: swipe right = know, left = don't know. Minimal chrome. */
@Composable
fun QuickReviewContent(state: SessionUiState, onAnswer: (Boolean) -> Unit, onSpeak: (String) -> Unit) {
    val step = state.step ?: return
    val settings = LocalUserSettings.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val offset = remember(step.index) { Animatable(0f) }
    var revealed by rememberSaveable(step.index) { mutableStateOf(false) }
    val threshold = with(LocalDensity.current) { 110.dp.toPx() }
    val seconds = (state.quickRemainingMs + 999) / 1000

    LaunchedEffect(step.index) { if (settings.autoPlayAudio) onSpeak(step.card.term) }

    fun answer(know: Boolean) {
        if (know) haptics.success() else haptics.error()
        onAnswer(know)
    }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "%d:%02d".format(seconds / 60, seconds % 60),
                style = MaterialTheme.typography.headlineMedium,
                fontFamily = FontFamily.Monospace,
                color = if (seconds <= 10) AnswerColors.Wrong else MaterialTheme.colorScheme.onSurface,
            )
            Spacer(Modifier.weight(1f))
            Text(
                stringResource(R.string.learn_quick_done, state.answered, state.correct),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LinearProgressIndicator(
            progress = { if (state.quickTotalMs == 0L) 0f else state.quickRemainingMs.toFloat() / state.quickTotalMs },
            modifier = Modifier.fillMaxWidth().height(6.dp),
            strokeCap = StrokeCap.Round,
            drawStopIndicator = {},
        )
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            val drag = offset.value
            val tint = when {
                drag > 0 -> AnswerColors.Correct.copy(alpha = (drag / threshold).coerceIn(0f, 1f) * 0.25f)
                drag < 0 -> AnswerColors.Wrong.copy(alpha = (-drag / threshold).coerceIn(0f, 1f) * 0.25f)
                else -> androidx.compose.ui.graphics.Color.Transparent
            }
            ElevatedCard(
                shape = RoundedCornerShape(28.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(320.dp)
                    .offset { IntOffset(drag.roundToInt(), 0) }
                    .graphicsLayer { rotationZ = drag / 40f }
                    .pointerInput(step.index) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                val v = offset.value
                                scope.launch {
                                    if (abs(v) > threshold) {
                                        offset.animateTo(if (v > 0) threshold * 4 else -threshold * 4)
                                        answer(v > 0)
                                    } else {
                                        offset.animateTo(0f)
                                    }
                                }
                            },
                            onDragCancel = { scope.launch { offset.animateTo(0f) } },
                        ) { change, amount ->
                            change.consume()
                            scope.launch { offset.snapTo(offset.value + amount) }
                        }
                    }
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { revealed = true },
            ) {
                Box(Modifier.fillMaxSize().background(tint), contentAlignment = Alignment.Center) {
                    Column(Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        TermHeadline(step.card)
                        AnimatedVisibility(revealed, enter = fadeIn()) {
                            Text(step.card.translation, style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
                        }
                        if (!revealed) {
                            Text(stringResource(R.string.learn_quick_tap), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(
                onClick = { answer(false) },
                modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AnswerColors.Wrong.copy(alpha = 0.16f), contentColor = AnswerColors.Wrong),
            ) {
                Icon(Icons.Rounded.Close, null)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.learn_quick_dont_know))
            }
            Button(
                onClick = { answer(true) },
                modifier = Modifier.weight(1f).height(64.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AnswerColors.Correct.copy(alpha = 0.16f), contentColor = AnswerColors.Correct),
            ) {
                Icon(Icons.Rounded.Check, null)
                Spacer(Modifier.padding(4.dp))
                Text(stringResource(R.string.learn_quick_know))
            }
        }
    }
}
