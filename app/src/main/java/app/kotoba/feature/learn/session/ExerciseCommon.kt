package app.kotoba.feature.learn.session

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.Lightbulb
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.db.CardEntity
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.ui.components.SpeakButton
import app.kotoba.core.ui.theme.CardTermStyle
import app.kotoba.core.ui.theme.LocalUserSettings
import app.kotoba.feature.learn.logic.Cloze

/** Correct/wrong accents shared by all exercises. */
object AnswerColors {
    val Correct = Color(0xFF10B981)
    val Wrong = Color(0xFFEF4444)
}

@Composable
fun exerciseInstruction(type: ExerciseType): String = stringResource(
    when (type) {
        ExerciseType.FLASHCARD_TERM_TO_TRANSLATION -> R.string.learn_ins_flash_term
        ExerciseType.FLASHCARD_TRANSLATION_TO_TERM -> R.string.learn_ins_flash_translation
        ExerciseType.FLASHCARD_TERM_TO_CONTEXT -> R.string.learn_ins_flash_context
        ExerciseType.FLASHCARD_AUDIO_TO_TERM -> R.string.learn_ins_flash_audio
        ExerciseType.MULTIPLE_CHOICE -> R.string.learn_ins_choice
        ExerciseType.REVERSE_MULTIPLE_CHOICE -> R.string.learn_ins_reverse_choice
        ExerciseType.TYPING -> R.string.learn_ins_typing
        ExerciseType.LISTENING -> R.string.learn_ins_listening
        ExerciseType.SPELLING -> R.string.learn_ins_spelling
        ExerciseType.WORD_SCRAMBLE -> R.string.learn_ins_scramble
        ExerciseType.FILL_BLANK -> R.string.learn_ins_fill
        ExerciseType.SENTENCE_BUILDER -> R.string.learn_ins_builder
        ExerciseType.MATCHING -> R.string.learn_ins_matching
        ExerciseType.CONTEXT_QUIZ -> R.string.learn_ins_context
        ExerciseType.TRANSLATION -> R.string.learn_ins_translation
        ExerciseType.PRONUNCIATION -> R.string.learn_ins_pronunciation
        ExerciseType.QUICK_REVIEW -> R.string.learn_ins_quick
    },
)

@Composable
fun exerciseName(type: ExerciseType): String = stringResource(
    when (type) {
        ExerciseType.FLASHCARD_TERM_TO_TRANSLATION -> R.string.learn_ex_term_translation
        ExerciseType.FLASHCARD_TRANSLATION_TO_TERM -> R.string.learn_ex_translation_term
        ExerciseType.FLASHCARD_TERM_TO_CONTEXT -> R.string.learn_ex_context_card
        ExerciseType.FLASHCARD_AUDIO_TO_TERM -> R.string.learn_ex_audio_card
        ExerciseType.MULTIPLE_CHOICE, ExerciseType.REVERSE_MULTIPLE_CHOICE -> R.string.learn_ex_choice
        ExerciseType.TYPING -> R.string.learn_ex_typing
        ExerciseType.LISTENING -> R.string.learn_ex_listening
        ExerciseType.SPELLING -> R.string.learn_ex_spelling
        ExerciseType.WORD_SCRAMBLE -> R.string.learn_ex_scramble
        ExerciseType.FILL_BLANK -> R.string.learn_ex_fill
        ExerciseType.SENTENCE_BUILDER -> R.string.learn_ex_builder
        ExerciseType.MATCHING -> R.string.learn_ex_matching
        ExerciseType.CONTEXT_QUIZ -> R.string.learn_ex_context_quiz
        ExerciseType.TRANSLATION -> R.string.learn_ex_translation
        ExerciseType.PRONUNCIATION -> R.string.learn_ex_pronunciation
        ExerciseType.QUICK_REVIEW -> R.string.learn_quick_title
    },
)

/** Instruction line above an exercise. */
@Composable
fun Instruction(type: ExerciseType, modifier: Modifier = Modifier) {
    Text(
        exerciseInstruction(type),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier,
    )
}

/** Big term with optional reading line and a speak button. */
@Composable
fun TermHeadline(
    card: CardEntity,
    modifier: Modifier = Modifier,
    showReading: Boolean = LocalUserSettings.current.showReading,
    onSpeak: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (showReading && card.reading.isNotBlank()) {
            Text(card.reading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        }
        Text(card.term, style = CardTermStyle, textAlign = TextAlign.Center)
        if (onSpeak != null) SpeakButton(onClick = onSpeak)
    }
}

/** A sentence with the card's word highlighted (or blanked out). */
fun sentenceAnnotated(sentence: SentenceInfo, highlight: Color, blank: Boolean): AnnotatedString {
    val range = sentence.cloze ?: return AnnotatedString(sentence.text)
    return buildAnnotatedString {
        append(sentence.text.substring(0, range.first))
        withStyle(SpanStyle(color = highlight, fontWeight = FontWeight.Bold, textDecoration = if (blank) null else TextDecoration.Underline)) {
            append(if (blank) Cloze.BLANK else sentence.text.substring(range.first, range.last + 1))
        }
        append(sentence.text.substring(range.last + 1))
    }
}

enum class OptionState { IDLE, CORRECT, WRONG, DIMMED }

/** Large tappable answer option. */
@Composable
fun OptionButton(
    text: String,
    state: OptionState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    sub: String? = null,
    enabled: Boolean = true,
) {
    val border by animateColorAsState(
        when (state) {
            OptionState.CORRECT -> AnswerColors.Correct
            OptionState.WRONG -> AnswerColors.Wrong
            else -> MaterialTheme.colorScheme.outlineVariant
        },
        label = "optBorder",
    )
    val container = when (state) {
        OptionState.CORRECT -> AnswerColors.Correct.copy(alpha = 0.12f)
        OptionState.WRONG -> AnswerColors.Wrong.copy(alpha = 0.12f)
        else -> MaterialTheme.colorScheme.surface
    }
    OutlinedCard(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(if (state == OptionState.IDLE || state == OptionState.DIMMED) 1.dp else 2.dp, border),
        colors = androidx.compose.material3.CardDefaults.outlinedCardColors(
            containerColor = container,
            disabledContainerColor = container,
        ),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                text,
                style = MaterialTheme.typography.titleMedium,
                color = if (state == OptionState.DIMMED) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            )
            if (!sub.isNullOrBlank()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Bottom sheet-like panel shown after an answer. */
@Composable
fun FeedbackPanel(
    feedback: Feedback,
    showReading: Boolean,
    onSpeak: () -> Unit,
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val accent = when {
        feedback.correct && !feedback.close -> AnswerColors.Correct
        feedback.correct -> Color(0xFFF59E0B)
        else -> AnswerColors.Wrong
    }
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = accent.copy(alpha = 0.12f),
        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    ) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    when {
                        feedback.correct && !feedback.close -> Icons.Rounded.CheckCircle
                        feedback.correct -> Icons.Rounded.Lightbulb
                        else -> Icons.Rounded.Error
                    },
                    contentDescription = null,
                    tint = accent,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    stringResource(
                        when {
                            feedback.correct && !feedback.close -> R.string.learn_fb_correct
                            feedback.correct -> R.string.learn_fb_close
                            else -> R.string.learn_fb_wrong
                        },
                    ),
                    style = MaterialTheme.typography.titleLarge,
                    color = accent,
                    modifier = Modifier.weight(1f),
                )
                if (feedback.xp > 0) {
                    Text(stringResource(R.string.learn_xp_plus, feedback.xp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val reading = if (showReading && feedback.card.reading.isNotBlank()) "  ${feedback.card.reading}" else ""
                    Text(feedback.card.term + reading, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(feedback.card.translation, style = MaterialTheme.typography.bodyLarge)
                }
                SpeakButton(onClick = onSpeak)
            }
            if (!feedback.userAnswer.isNullOrBlank() && !(feedback.correct && !feedback.close)) {
                Text(
                    stringResource(R.string.learn_fb_your_answer, feedback.userAnswer),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (!feedback.note.isNullOrBlank()) {
                Text(feedback.note, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(4.dp))
            Button(onClick = onContinue, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text(stringResource(R.string.action_continue))
            }
        }
    }
}
