package app.kotoba.feature.learn.session

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.db.CardEntity
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.Rating
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.formatInterval
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.CardTermStyle
import app.kotoba.core.ui.theme.LocalUserSettings
import coil.compose.AsyncImage

@Composable
fun FlashcardExercise(
    step: PreparedStep,
    onRate: (Rating) -> Unit,
    onSpeak: (String) -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val settings = LocalUserSettings.current
    val haptics = rememberHaptics()
    val card = step.card
    var flipped by rememberSaveable(step.index) { mutableStateOf(false) }
    val rotation by animateFloatAsState(
        targetValue = if (flipped) 180f else 0f,
        animationSpec = tween(if (settings.animations) 450 else 0),
        label = "flip",
    )
    val density = LocalDensity.current.density

    // The front must not give the answer away: translation→term speaks only after flipping.
    val speakOnFront = step.exercise != ExerciseType.FLASHCARD_TRANSLATION_TO_TERM
    LaunchedEffect(step.index) {
        if (step.exercise == ExerciseType.FLASHCARD_AUDIO_TO_TERM || (settings.autoPlayAudio && speakOnFront)) onSpeak(card.term)
    }
    LaunchedEffect(flipped) {
        if (flipped && settings.autoPlayAudio && !speakOnFront) onSpeak(card.term)
    }

    Column(modifier.fillMaxSize().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Instruction(step.exercise, Modifier.padding(top = 4.dp))
        Box(
            Modifier
                .weight(1f)
                .fillMaxWidth()
                .graphicsLayer {
                    rotationY = rotation
                    cameraDistance = 14f * density
                },
        ) {
            ElevatedCard(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {
                        if (!flipped) haptics.tick()
                        flipped = true
                    },
                shape = RoundedCornerShape(28.dp),
            ) {
                if (rotation <= 90f) {
                    CardFront(step, onSpeak)
                } else {
                    Box(Modifier.fillMaxSize().graphicsLayer { rotationY = 180f }) {
                        CardBack(step, onSpeak, onFavorite, onEdit, onMore)
                    }
                }
            }
        }
        if (!flipped) {
            Button(
                onClick = { haptics.tick(); flipped = true },
                modifier = Modifier.fillMaxWidth().height(56.dp).padding(bottom = 4.dp),
            ) { Text(stringResource(R.string.learn_show_answer)) }
        } else {
            RatingRow(step.intervals, onRate = { r -> if (r == Rating.AGAIN) haptics.error() else haptics.success(); onRate(r) })
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun CardFront(step: PreparedStep, onSpeak: (String) -> Unit) {
    val card = step.card
    val showReading = LocalUserSettings.current.showReading
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        when (step.exercise) {
            ExerciseType.FLASHCARD_TRANSLATION_TO_TERM -> {
                Text(card.translation, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
                if (card.partOfSpeech.isNotBlank()) PosLabel(card.partOfSpeech)
            }
            ExerciseType.FLASHCARD_TERM_TO_CONTEXT -> {
                val sentence = step.sentence
                if (sentence?.cloze != null) {
                    Text(
                        sentenceAnnotated(sentence, MaterialTheme.colorScheme.primary, blank = true),
                        style = MaterialTheme.typography.headlineSmall,
                        textAlign = TextAlign.Center,
                    )
                    if (sentence.translation.isNotBlank()) {
                        Spacer(Modifier.height(12.dp))
                        Text(sentence.translation, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                    }
                } else {
                    TermHeadline(card, showReading = showReading)
                }
            }
            ExerciseType.FLASHCARD_AUDIO_TO_TERM -> {
                FilledTonalIconButton(onClick = { onSpeak(card.term) }, modifier = Modifier.size(96.dp)) {
                    Icon(Icons.AutoMirrored.Rounded.VolumeUp, contentDescription = stringResource(R.string.action_listen), modifier = Modifier.size(48.dp))
                }
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.learn_tap_to_replay), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            else -> {
                TermHeadline(card, showReading = showReading, onSpeak = { onSpeak(card.term) })
                if (card.ipa.isNotBlank()) Text("/${card.ipa.trim('/')}/", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.learn_tap_to_flip), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PosLabel(pos: String) {
    val p = PartOfSpeech.fromKey(pos)
    Text(
        if (p == PartOfSpeech.OTHER) pos else p.key,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.tertiary,
    )
}

@Composable
private fun CardBack(
    step: PreparedStep,
    onSpeak: (String) -> Unit,
    onFavorite: () -> Unit,
    onEdit: () -> Unit,
    onMore: () -> Unit,
) {
    val card = step.card
    val showReading = LocalUserSettings.current.showReading
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            MasteryBadge(card.mastery)
            Spacer(Modifier.weight(1f))
            IconButton(onClick = { onSpeak(card.term) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen)) }
            IconButton(onClick = onFavorite) {
                Icon(
                    if (card.favorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    stringResource(R.string.action_favorite),
                    tint = if (card.favorite) Color(0xFFEF4444) else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onEdit) { Icon(Icons.Rounded.Edit, stringResource(R.string.action_edit)) }
            IconButton(onClick = onMore) { Icon(Icons.Rounded.Info, stringResource(R.string.learn_more_info)) }
        }
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            if (showReading && card.reading.isNotBlank()) {
                Text(card.reading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            Text(card.term, style = CardTermStyle, textAlign = TextAlign.Center)
            val meta = listOfNotNull(
                card.ipa.takeIf { it.isNotBlank() }?.let { "/${it.trim('/')}/" },
                card.partOfSpeech.takeIf { it.isNotBlank() },
                card.level.takeIf { it.isNotBlank() },
            ).joinToString(" · ")
            if (meta.isNotEmpty()) Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(6.dp))
            Text(card.translation, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center)
        }
        if (card.imageUri.isNotBlank()) {
            AsyncImage(
                model = card.imageUri,
                contentDescription = card.term,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxWidth().aspectRatio(16f / 9f).clip(RoundedCornerShape(16.dp)),
            )
        }
        if (card.association.isNotBlank()) Detail("💡", card.association)

        val examples = buildList {
            step.sentence?.let { add(it.text to it.translation) }
            if (card.example.isNotBlank()) add(card.example to card.exampleTranslation)
            step.contexts.forEach { add(it.sentence to it.translation) }
        }.distinctBy { it.first }.take(3)
        if (examples.isNotEmpty()) {
            HorizontalDivider()
            SectionLabel(stringResource(R.string.learn_examples))
            examples.forEach { (text, tr) ->
                Row(verticalAlignment = Alignment.Top) {
                    Column(Modifier.weight(1f)) {
                        Text(text, style = MaterialTheme.typography.bodyLarge)
                        if (tr.isNotBlank()) Text(tr, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    IconButton(onClick = { onSpeak(text) }) { Icon(Icons.AutoMirrored.Rounded.VolumeUp, stringResource(R.string.action_listen), Modifier.size(20.dp)) }
                }
            }
        }
        if (card.synonyms.isNotBlank()) ChipLine(stringResource(R.string.learn_synonyms), card.synonyms)
        if (card.antonyms.isNotBlank()) ChipLine(stringResource(R.string.learn_antonyms), card.antonyms)
        if (card.forms.isNotBlank()) {
            SectionLabel(stringResource(R.string.learn_forms))
            Text(card.forms, style = MaterialTheme.typography.bodyMedium)
        }
        if (card.notes.isNotBlank()) Detail("📝", card.notes)
        val source = card.sourceTitle.ifBlank { "" }
        if (source.isNotBlank()) {
            Text(stringResource(R.string.learn_source, source), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.outline)
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Detail(emoji: String, text: String) {
    Row {
        Text(emoji)
        Spacer(Modifier.size(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ChipLine(label: String, values: String) {
    SectionLabel(label)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        values.split(',', ';', '、', '，').map { it.trim() }.filter { it.isNotEmpty() }.forEach {
            AssistChip(onClick = {}, label = { Text(it) })
        }
    }
}

/** Again / Hard / Good / Easy with the next interval under each label. */
@Composable
fun RatingRow(intervals: Map<Rating, Long>, onRate: (Rating) -> Unit, modifier: Modifier = Modifier) {
    AnimatedVisibility(visible = true, enter = fadeIn() + slideInVertically { it / 2 }) {
        Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Rating.entries.forEach { r ->
                val color = when (r) {
                    Rating.AGAIN -> Color(0xFFEF4444)
                    Rating.HARD -> Color(0xFFF59E0B)
                    Rating.GOOD -> Color(0xFF10B981)
                    Rating.EASY -> Color(0xFF3B82F6)
                }
                Button(
                    onClick = { onRate(r) },
                    modifier = Modifier.weight(1f).height(60.dp),
                    shape = RoundedCornerShape(16.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = color.copy(alpha = 0.16f), contentColor = color),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(4.dp),
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(ratingLabel(r), style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        intervals[r]?.let { Text(formatInterval(it), style = MaterialTheme.typography.labelSmall, maxLines = 1) }
                    }
                }
            }
        }
    }
}

@Composable
fun ratingLabel(r: Rating): String = stringResource(
    when (r) {
        Rating.AGAIN -> R.string.learn_rate_again
        Rating.HARD -> R.string.learn_rate_hard
        Rating.GOOD -> R.string.learn_rate_good
        Rating.EASY -> R.string.learn_rate_easy
    },
)

/** Front-of-card cue for exercises that only need the meaning. */
@Composable
fun MeaningPrompt(card: CardEntity, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(card.translation, style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        if (card.partOfSpeech.isNotBlank()) PosLabel(card.partOfSpeech)
    }
}
