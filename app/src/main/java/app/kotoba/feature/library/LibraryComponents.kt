package app.kotoba.feature.library

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.model.Category
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.srs.WeakReason
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.MasteryColors

/** Flag chips for the learner's study languages. */
@Composable
fun LanguageSwitcher(languages: List<StudyLanguage>, active: StudyLanguage, onSelect: (StudyLanguage) -> Unit, modifier: Modifier = Modifier) {
    if (languages.size <= 1) return
    val haptics = rememberHaptics()
    FlowRow(modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        languages.forEach { l ->
            FilterChip(
                selected = l == active,
                onClick = { haptics.tick(); onSelect(l) },
                label = { Text("${l.flag}  ${l.nativeName}") },
            )
        }
    }
}

/** One word in a list. Long-press enters selection mode in [WordListScreen]. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun WordRow(
    card: CardEntity,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    selected: Boolean = false,
    selectionMode: Boolean = false,
    onLongClick: (() -> Unit)? = null,
    onToggleFavorite: (() -> Unit)? = null,
    trailing: @Composable () -> Unit = {},
) {
    val bg = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent
    Row(
        modifier
            .fillMaxWidth()
            .background(bg)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (selectionMode) {
            Icon(
                if (selected) Icons.Filled.CheckCircle else Icons.Filled.Check,
                contentDescription = null,
                tint = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                modifier = Modifier.padding(end = 12.dp).size(22.dp),
            )
        }
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    card.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
                )
                val reading = card.reading.ifBlank { card.ipa }
                if (reading.isNotBlank()) {
                    Spacer(Modifier.width(8.dp))
                    Text(reading, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            Text(card.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Spacer(Modifier.width(8.dp))
        MasteryBadge(card.mastery)
        if (onToggleFavorite != null && !selectionMode) {
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (card.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                    contentDescription = stringResource(R.string.action_favorite),
                    tint = if (card.favorite) MasteryColors.Learning else MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        trailing()
    }
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = { onConfirm(); onDismiss() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun ColorPicker(selected: Long, onSelect: (Long) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        DeckColors.forEach { c ->
            val isSel = c == selected
            Box(
                Modifier
                    .size(32.dp)
                    .background(Color(c), CircleShape)
                    .then(if (isSel) Modifier.border(3.dp, MaterialTheme.colorScheme.onSurface, CircleShape) else Modifier)
                    .clickable { onSelect(c) },
                contentAlignment = Alignment.Center,
            ) { if (isSel) Icon(Icons.Filled.Check, null, tint = Color.White, modifier = Modifier.size(18.dp)) }
        }
    }
}

/** Generic dropdown field. */
@Composable
fun <T> DropdownField(
    label: String,
    value: T,
    options: List<T>,
    display: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = display(value),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
            singleLine = true,
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o ->
                DropdownMenuItem(text = { Text(display(o)) }, onClick = { onSelect(o); open = false })
            }
        }
    }
}

/** Create or edit a deck (personal dictionary). */
@Composable
fun DeckEditDialog(
    initial: DeckEntity,
    language: StudyLanguage,
    onDismiss: () -> Unit,
    onSave: (DeckEntity) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial.name) }
    var description by rememberSaveable { mutableStateOf(initial.description) }
    var color by rememberSaveable { mutableStateOf(initial.color) }
    var category by rememberSaveable { mutableStateOf(initial.category) }
    var level by rememberSaveable { mutableStateOf(initial.level) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(if (initial.id == 0L) R.string.library_new_deck else R.string.library_edit_deck)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    name, { name = it }, label = { Text(stringResource(R.string.library_deck_name)) }, singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    description, { description = it }, label = { Text(stringResource(R.string.library_deck_description)) },
                    modifier = Modifier.fillMaxWidth(), maxLines = 3,
                )
                ColorPicker(color) { color = it }
                DropdownField(
                    label = stringResource(R.string.library_field_category),
                    value = category,
                    options = listOf("") + Category.entries.map { it.key },
                    display = { k -> if (k.isEmpty()) stringResource(R.string.library_none) else Category.fromKey(k).let { "${it.emoji} ${categoryLabel(it)}" } },
                    onSelect = { category = it },
                )
                DropdownField(
                    label = stringResource(R.string.library_field_level),
                    value = level,
                    options = listOf("") + language.levelScale.levels,
                    display = { it.ifEmpty { stringResource(R.string.library_none) } },
                    onSelect = { level = it },
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank(),
                onClick = {
                    onSave(initial.copy(name = name.trim(), description = description.trim(), color = color, category = category, level = level))
                    onDismiss()
                },
            ) { Text(stringResource(R.string.action_save)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
fun contextSourceLabel(s: ContextSource): String = stringResource(
    when (s) {
        ContextSource.BOOK -> R.string.library_source_book
        ContextSource.MANGA -> R.string.library_source_manga
        ContextSource.BUILTIN -> R.string.library_source_builtin
        ContextSource.AI -> R.string.library_source_ai
        ContextSource.USER -> R.string.library_source_user
        ContextSource.IMPORT -> R.string.library_source_import
    },
)

fun contextSourceEmoji(s: ContextSource): String = when (s) {
    ContextSource.BOOK -> "📖"
    ContextSource.MANGA -> "💬"
    ContextSource.BUILTIN -> "📚"
    ContextSource.AI -> "✨"
    ContextSource.USER -> "✍️"
    ContextSource.IMPORT -> "📥"
}

@Composable
fun weakReasonLabel(r: WeakReason): String = stringResource(
    when (r) {
        WeakReason.FORGETS_OFTEN -> R.string.library_weak_forgets
        WeakReason.HIGH_ERROR_RATE -> R.string.library_weak_errors
        WeakReason.SLOW -> R.string.library_weak_slow
        WeakReason.UNSTABLE -> R.string.library_weak_unstable
        WeakReason.CONFUSED -> R.string.library_weak_confused
    },
)

@Composable
fun posLabel(p: PartOfSpeech): String = stringResource(
    when (p) {
        PartOfSpeech.NOUN -> R.string.library_pos_noun
        PartOfSpeech.VERB -> R.string.library_pos_verb
        PartOfSpeech.ADJECTIVE -> R.string.library_pos_adjective
        PartOfSpeech.ADVERB -> R.string.library_pos_adverb
        PartOfSpeech.PRONOUN -> R.string.library_pos_pronoun
        PartOfSpeech.PREPOSITION -> R.string.library_pos_preposition
        PartOfSpeech.CONJUNCTION -> R.string.library_pos_conjunction
        PartOfSpeech.INTERJECTION -> R.string.library_pos_interjection
        PartOfSpeech.PARTICLE -> R.string.library_pos_particle
        PartOfSpeech.COUNTER -> R.string.library_pos_counter
        PartOfSpeech.PHRASE -> R.string.library_pos_phrase
        PartOfSpeech.IDIOM -> R.string.library_pos_idiom
        PartOfSpeech.OTHER -> R.string.library_pos_other
    },
)

/** Localised reading-line label: Kana / Pinyin / Romanization / IPA. */
@Composable
fun readingLabel(lang: StudyLanguage): String = stringResource(
    when (lang) {
        StudyLanguage.JA -> R.string.library_reading_kana
        StudyLanguage.ZH -> R.string.library_reading_pinyin
        StudyLanguage.KO -> R.string.library_reading_romanization
        else -> R.string.library_reading_ipa
    },
)

/** Wrap-around chips for synonyms/antonyms/forms. */
@Composable
fun ChipList(items: List<String>, onClick: ((String) -> Unit)? = null) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        items.forEach { s ->
            androidx.compose.material3.AssistChip(onClick = { onClick?.invoke(s) }, label = { Text(s) })
        }
    }
}
