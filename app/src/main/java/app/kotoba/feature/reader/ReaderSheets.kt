package app.kotoba.feature.reader

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.db.BookmarkEntity
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.LabeledProgress
import app.kotoba.core.ui.components.StatTile
import app.kotoba.core.ui.theme.MasteryColors

@Composable
fun BookmarksSheet(
    bookmarks: List<BookmarkEntity>,
    onDismiss: () -> Unit,
    onJump: (BookmarkEntity) -> Unit,
    onDelete: (BookmarkEntity) -> Unit,
    onEditNote: (BookmarkEntity) -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Text(
            stringResource(R.string.reader_bookmarks),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
        )
        if (bookmarks.isEmpty()) {
            EmptyState("🔖", stringResource(R.string.reader_no_bookmarks_title), stringResource(R.string.reader_no_bookmarks), Modifier.padding(24.dp))
            return@ModalBottomSheet
        }
        LazyColumn(Modifier.heightIn(max = 560.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp)) {
            items(bookmarks, key = { it.id }) { b ->
                ListItem(
                    leadingContent = {
                        Icon(
                            when {
                                b.note.isNotBlank() -> Icons.Filled.EditNote
                                b.isHighlight -> Icons.Filled.Highlight
                                else -> Icons.Filled.Bookmark
                            },
                            null,
                            tint = if (b.isHighlight) Color(0xFFF9A825) else MaterialTheme.colorScheme.primary,
                        )
                    },
                    headlineContent = { Text(b.quote, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                    supportingContent = b.note.takeIf { it.isNotBlank() }?.let { { Text(it, maxLines = 3, overflow = TextOverflow.Ellipsis) } },
                    trailingContent = {
                        Row {
                            if (b.isHighlight) IconButton(onClick = { onEditNote(b) }) { Icon(Icons.Filled.EditNote, stringResource(R.string.reader_note)) }
                            IconButton(onClick = { onDelete(b) }) { Icon(Icons.Filled.Delete, stringResource(R.string.action_delete)) }
                        }
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent),
                    modifier = Modifier.clickable { onJump(b) },
                )
            }
        }
    }
}

@Composable
fun ReaderSettingsSheet(prefs: ReaderPrefs, language: StudyLanguage, onDismiss: () -> Unit, onChange: ((ReaderPrefs) -> ReaderPrefs) -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Text(stringResource(R.string.reader_settings), style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.reader_font_size, prefs.fontSize.toInt()), style = MaterialTheme.typography.labelLarge)
            Slider(value = prefs.fontSize, onValueChange = { v -> onChange { it.copy(fontSize = v) } }, valueRange = 14f..32f, steps = 17)
            Text(stringResource(R.string.reader_line_spacing, "%.1f".format(prefs.lineSpacing)), style = MaterialTheme.typography.labelLarge)
            Slider(value = prefs.lineSpacing, onValueChange = { v -> onChange { it.copy(lineSpacing = v) } }, valueRange = 1.2f..2.2f, steps = 9)
            Text(stringResource(R.string.reader_theme), style = MaterialTheme.typography.labelLarge)
            val themes = ReaderTheme.entries
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                themes.forEachIndexed { i, t ->
                    SegmentedButton(
                        selected = prefs.theme == t,
                        onClick = { onChange { it.copy(theme = t) } },
                        shape = SegmentedButtonDefaults.itemShape(i, themes.size),
                    ) {
                        Text(
                            stringResource(
                                when (t) {
                                    ReaderTheme.AUTO -> R.string.reader_theme_auto
                                    ReaderTheme.LIGHT -> R.string.reader_theme_light
                                    ReaderTheme.SEPIA -> R.string.reader_theme_sepia
                                    ReaderTheme.DARK -> R.string.reader_theme_dark
                                },
                            ),
                        )
                    }
                }
            }
            if (language.hasReading) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.reader_show_readings), style = MaterialTheme.typography.bodyLarge)
                        Text(stringResource(R.string.reader_show_readings_desc, language.readingLabel), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = prefs.showReadings, onCheckedChange = { v -> onChange { it.copy(showReadings = v) } })
                }
            }
        }
    }
}

@Composable
fun BookStatsSheet(stats: BookStats?, addedFromBook: Int, progress: Float, onDismiss: () -> Unit, onGenerateDeck: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 24.dp).padding(bottom = 32.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.reader_stats), style = MaterialTheme.typography.titleLarge)
            if (stats == null) {
                CircularProgressIndicator(Modifier.align(Alignment.CenterHorizontally))
                return@Column
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("%,d".format(stats.totalWords), stringResource(R.string.reader_stat_total), Modifier.weight(1f), emoji = "📄")
                StatTile("%,d".format(stats.uniqueWords), stringResource(R.string.reader_stat_unique), Modifier.weight(1f), emoji = "🔤")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatTile("${stats.knownPercent}%", stringResource(R.string.reader_stat_known), Modifier.weight(1f), emoji = "✅", accent = MasteryColors.Mastered)
                StatTile("$addedFromBook", stringResource(R.string.reader_stat_added), Modifier.weight(1f), emoji = "➕")
            }
            LabeledProgress(stringResource(R.string.reader_stat_known_words), stats.knownUnique, stats.uniqueWords.coerceAtLeast(1), color = MasteryColors.Familiar)
            LabeledProgress(stringResource(R.string.reader_stat_mastered_words), stats.masteredUnique, stats.uniqueWords.coerceAtLeast(1), color = MasteryColors.Mastered)
            LabeledProgress(stringResource(R.string.reader_stat_progress), (progress * 100).toInt(), 100)
            Button(onClick = onGenerateDeck, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.AutoAwesome, null)
                Text("  " + stringResource(R.string.reader_generate_deck))
            }
        }
    }
}

@Composable
fun NoteDialog(initial: String, quote: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var note by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_note)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("“$quote”", style = MaterialTheme.typography.bodySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                OutlinedTextField(note, { note = it }, modifier = Modifier.fillMaxWidth().heightIn(min = 100.dp))
            }
        },
        confirmButton = { TextButton(onClick = { onSave(note) }) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
