package app.kotoba.feature.stats

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.ui.theme.MasteryColors
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun ChartCard(title: String, modifier: Modifier = Modifier, subtitle: String? = null, content: @Composable () -> Unit) {
    Card(
        modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (subtitle != null) {
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            content()
        }
    }
}

fun formatDuration(context: Context, ms: Long): String {
    val totalMin = (ms / 60_000).toInt()
    val h = totalMin / 60
    val m = totalMin % 60
    return when {
        h > 0 -> context.getString(R.string.stats_duration_hm, h, m)
        totalMin > 0 -> context.getString(R.string.stats_duration_m, m)
        ms > 0 -> context.getString(R.string.stats_duration_s, (ms / 1000).toInt())
        else -> context.getString(R.string.stats_duration_m, 0)
    }
}

fun formatSeconds(ms: Long): String = if (ms <= 0) "—" else String.format(Locale.getDefault(), "%.1f s", ms / 1000.0)

fun formatPercent(value: Float?): String = value?.let { "${(it * 100).roundToInt()}%" } ?: "—"

fun shortDay(epochDay: Long, spanDays: Int): String {
    val pattern = if (spanDays <= 7) "EEE" else "d MMM"
    return LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofPattern(pattern, Locale.getDefault()))
}

@Composable
fun dayTitle(epochDay: Long, today: Long): String = when (epochDay) {
    today -> stringResource(R.string.stats_today)
    today - 1 -> stringResource(R.string.stats_yesterday)
    else -> LocalDate.ofEpochDay(epochDay).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.getDefault()))
}

@Composable
fun accuracyColor(value: Float): Color = when {
    value >= 0.85f -> MasteryColors.Mastered
    value >= 0.65f -> MasteryColors.Learning
    else -> MaterialTheme.colorScheme.error
}

@Composable
fun masteryLegend(): List<Pair<String, Color>> = Mastery.entries.map {
    app.kotoba.core.ui.components.masteryLabel(it) to MasteryColors.of(it)
}

@Composable
fun periodLabel(p: StatsPeriod): String = stringResource(
    when (p) {
        StatsPeriod.DAY -> R.string.stats_period_day
        StatsPeriod.WEEK -> R.string.stats_period_week
        StatsPeriod.MONTH -> R.string.stats_period_month
    },
)

fun periodReportTitleRes(p: StatsPeriod): Int = when (p) {
    StatsPeriod.DAY -> R.string.stats_report_day
    StatsPeriod.WEEK -> R.string.stats_report_week
    StatsPeriod.MONTH -> R.string.stats_report_month
}

@Composable
fun levelLabel(key: String): String = key.ifBlank { stringResource(R.string.stats_level_none) }

@Composable
fun exerciseLabel(t: ExerciseType): String = stringResource(
    when (t) {
        ExerciseType.FLASHCARD_TERM_TO_TRANSLATION -> R.string.stats_ex_term_to_translation
        ExerciseType.FLASHCARD_TRANSLATION_TO_TERM -> R.string.stats_ex_translation_to_term
        ExerciseType.FLASHCARD_TERM_TO_CONTEXT -> R.string.stats_ex_term_to_context
        ExerciseType.FLASHCARD_AUDIO_TO_TERM -> R.string.stats_ex_audio_to_term
        ExerciseType.MULTIPLE_CHOICE -> R.string.stats_ex_multiple_choice
        ExerciseType.REVERSE_MULTIPLE_CHOICE -> R.string.stats_ex_reverse_choice
        ExerciseType.TYPING -> R.string.stats_ex_typing
        ExerciseType.LISTENING -> R.string.stats_ex_listening
        ExerciseType.SPELLING -> R.string.stats_ex_spelling
        ExerciseType.WORD_SCRAMBLE -> R.string.stats_ex_scramble
        ExerciseType.FILL_BLANK -> R.string.stats_ex_fill_blank
        ExerciseType.SENTENCE_BUILDER -> R.string.stats_ex_sentence_builder
        ExerciseType.MATCHING -> R.string.stats_ex_matching
        ExerciseType.CONTEXT_QUIZ -> R.string.stats_ex_context_quiz
        ExerciseType.TRANSLATION -> R.string.stats_ex_translation
        ExerciseType.PRONUNCIATION -> R.string.stats_ex_pronunciation
        ExerciseType.QUICK_REVIEW -> R.string.stats_ex_quick_review
    },
)

@Composable
fun partOfSpeechLabel(key: String): String = stringResource(
    when (PartOfSpeech.fromKey(key)) {
        PartOfSpeech.NOUN -> R.string.stats_pos_noun
        PartOfSpeech.VERB -> R.string.stats_pos_verb
        PartOfSpeech.ADJECTIVE -> R.string.stats_pos_adjective
        PartOfSpeech.ADVERB -> R.string.stats_pos_adverb
        PartOfSpeech.PRONOUN -> R.string.stats_pos_pronoun
        PartOfSpeech.PREPOSITION -> R.string.stats_pos_preposition
        PartOfSpeech.CONJUNCTION -> R.string.stats_pos_conjunction
        PartOfSpeech.INTERJECTION -> R.string.stats_pos_interjection
        PartOfSpeech.PARTICLE -> R.string.stats_pos_particle
        PartOfSpeech.COUNTER -> R.string.stats_pos_counter
        PartOfSpeech.PHRASE -> R.string.stats_pos_phrase
        PartOfSpeech.IDIOM -> R.string.stats_pos_idiom
        PartOfSpeech.OTHER -> R.string.stats_pos_other
    },
)
