package app.kotoba.feature.importexport

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.StatTile
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.feature.importexport.anki.AnkiFields
import kotlinx.coroutines.launch

@Composable
fun ImportExportScreen(
    onBack: () -> Unit,
    onOpenDocuments: () -> Unit,
    vm: ImportExportViewModel = hiltViewModel(),
) {
    val stage by vm.stage.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val languages by vm.studyLanguages.collectAsStateWithLifecycle()
    val targetDecks by vm.targetDecks.collectAsStateWithLifecycle()
    val exportDecks by vm.exportDecks.collectAsStateWithLifecycle()
    val activeLanguage by vm.activeLanguage.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(0) }
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is ImportExportEvent.Share -> context.startActivity(
                    android.content.Intent.createChooser(e.intent, context.getString(R.string.import_share_title)),
                )
                is ImportExportEvent.Exported -> {
                    haptics.success()
                    snackbar.showSnackbar(context.resources.getQuantityString(R.plurals.import_exported_words, e.count, e.count))
                }
                is ImportExportEvent.Error -> snackbar.showSnackbar(context.getString(R.string.import_error_generic, e.message))
            }
        }
    }
    LaunchedEffect(stage) { if (stage is ImportStage.Done) haptics.success() }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::onFilePicked) }
    val saver = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument(export.format.mime)) { uri ->
        uri?.let(vm::exportTo)
    }

    Scaffold(
        topBar = { KotobaTopBar(stringResource(R.string.import_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(stringResource(R.string.import_tab_import)) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(stringResource(R.string.import_tab_export)) })
            }
            AnimatedContent(targetState = tab, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "tab") { t ->
                if (t == 0) {
                    ImportTab(
                        stage = stage, languages = languages, decks = targetDecks,
                        onPick = { picker.launch(arrayOf("*/*")) },
                        onOpenDocuments = onOpenDocuments, vm = vm, onDone = onBack,
                    )
                } else {
                    ExportTab(
                        state = export, language = activeLanguage, decks = exportDecks,
                        onDeck = vm::setExportDeck, onFormat = vm::setExportFormat,
                        onSave = { scope.launch { saver.launch(vm.suggestedExportName()) } },
                        onShare = vm::share,
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------- Import

@Composable
private fun ImportTab(
    stage: ImportStage,
    languages: List<StudyLanguage>,
    decks: List<DeckEntity>,
    onPick: () -> Unit,
    onOpenDocuments: () -> Unit,
    vm: ImportExportViewModel,
    onDone: () -> Unit,
) {
    AnimatedContent(targetState = stage, contentKey = { it::class }, transitionSpec = { fadeIn() togetherWith fadeOut() }, label = "stage") { st ->
        when (st) {
            ImportStage.Idle -> ImportIdle(onPick, onOpenDocuments)
            is ImportStage.Loading -> Busy(stringResource(R.string.import_reading, st.fileName))
            is ImportStage.Importing -> Busy(stringResource(R.string.import_importing, st.fileName))
            is ImportStage.Preview -> ImportPreview(st, languages, decks, vm)
            is ImportStage.Done -> ImportDone(st.report, onAnother = vm::reset, onDone = onDone)
            is ImportStage.Failed -> EmptyState(
                emoji = "😕",
                title = stringResource(R.string.import_failed_title),
                message = st.message.ifBlank { stringResource(R.string.import_failed_empty) },
                modifier = Modifier.fillMaxSize(),
            ) { Button(onClick = vm::reset) { Text(stringResource(R.string.action_retry)) } }
        }
    }
}

@Composable
private fun Busy(text: String) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center)
    }
}

@Composable
private fun ImportIdle(onPick: () -> Unit, onOpenDocuments: () -> Unit) {
    LazyColumn(contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                Column(Modifier.fillMaxWidth().padding(20.dp)) {
                    Text("📦", style = MaterialTheme.typography.displaySmall)
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.import_hero_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.import_hero_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(16.dp))
                    Button(onClick = onPick) {
                        Icon(Icons.Default.FileOpen, null)
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(R.string.import_choose_file))
                    }
                }
            }
        }
        item { Text(stringResource(R.string.import_supported), style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(top = 8.dp)) }
        val formats = listOf(
            Triple("🃏", R.string.import_format_anki, R.string.import_format_anki_desc),
            Triple("📊", R.string.import_format_csv, R.string.import_format_csv_desc),
            Triple("🧾", R.string.import_format_json, R.string.import_format_json_desc),
            Triple("📖", R.string.import_format_dict, R.string.import_format_dict_desc),
        )
        items(formats) { (emoji, title, desc) ->
            OutlinedCard {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(emoji, style = MaterialTheme.typography.headlineSmall)
                    Spacer(Modifier.width(16.dp))
                    Column {
                        Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        item {
            Card(onClick = onOpenDocuments, modifier = Modifier.padding(top = 8.dp)) {
                Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.AutoMirrored.Filled.Notes, null, tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(16.dp))
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.import_notebooklm), style = MaterialTheme.typography.titleSmall)
                        Text(stringResource(R.string.import_notebooklm_desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}

@Composable
private fun kindLabel(kind: ImportKind): String = stringResource(
    when (kind) {
        ImportKind.ANKI -> R.string.import_format_anki
        ImportKind.CSV -> R.string.import_format_csv
        ImportKind.DICTIONARY -> R.string.import_format_dict
        ImportKind.JSON -> R.string.import_format_json
        ImportKind.KOTOBA -> R.string.import_format_kotoba
    },
)

@Composable
fun fieldTargetLabel(t: FieldTarget): String = stringResource(
    when (t) {
        FieldTarget.TERM -> R.string.import_target_term
        FieldTarget.TRANSLATION -> R.string.import_target_translation
        FieldTarget.READING -> R.string.import_target_reading
        FieldTarget.IPA -> R.string.import_target_ipa
        FieldTarget.EXAMPLE -> R.string.import_target_example
        FieldTarget.EXAMPLE_TRANSLATION -> R.string.import_target_example_translation
        FieldTarget.NOTES -> R.string.import_target_notes
        FieldTarget.PART_OF_SPEECH -> R.string.import_target_pos
        FieldTarget.TAGS -> R.string.import_target_tags
        FieldTarget.AUDIO -> R.string.import_target_audio
        FieldTarget.IMAGE -> R.string.import_target_image
        FieldTarget.IGNORE -> R.string.import_target_ignore
    },
)

@Composable
private fun ImportPreview(st: ImportStage.Preview, languages: List<StudyLanguage>, decks: List<DeckEntity>, vm: ImportExportViewModel) {
    val parsed = st.parsed
    val o = st.options
    val hasTerm = parsed.kind == ImportKind.KOTOBA || parsed.groups.any { g -> o.mapping[g.id].orEmpty().contains(FieldTarget.TERM) }
    val deckValid = when (o.deckMode) {
        DeckMode.EXISTING -> o.existingDeckId != null
        else -> true
    }
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Card {
                    Column(Modifier.fillMaxWidth().padding(16.dp)) {
                        Text(parsed.fileName, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Text(kindLabel(parsed.kind), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.height(12.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            StatTile(parsed.total.toString(), stringResource(R.string.import_stat_records), Modifier.weight(1f))
                            if (parsed.sourceDecks.isNotEmpty()) StatTile(parsed.sourceDecks.size.toString(), stringResource(R.string.import_stat_decks), Modifier.weight(1f))
                            if (parsed.reviewCount > 0) StatTile(parsed.reviewCount.toString(), stringResource(R.string.import_stat_reviewed), Modifier.weight(1f))
                            if (parsed.media.isNotEmpty()) StatTile(parsed.media.size.toString(), stringResource(R.string.import_stat_media), Modifier.weight(1f))
                        }
                        parsed.warnings.forEach {
                            Text("⚠ $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
            item {
                Section(stringResource(R.string.import_target_language)) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        languages.forEach { l ->
                            FilterChip(
                                selected = l == o.language,
                                onClick = {
                                    vm.updateOptions { opt ->
                                        opt.copy(language = l, existingDeckId = null, mapping = parsed.groups.associate { it.id to SmartMapping.defaults(it.fields, l) })
                                    }
                                },
                                label = { Text("${l.flag} ${l.nativeName}") },
                            )
                        }
                    }
                }
            }
            item { DeckChooser(parsed, o, decks, vm) }
            item {
                Section(stringResource(R.string.import_options)) {
                    SwitchRow(stringResource(R.string.import_skip_duplicates), stringResource(R.string.import_skip_duplicates_desc), o.skipDuplicates) { v ->
                        vm.updateOptions { it.copy(skipDuplicates = v) }
                    }
                    if (parsed.reviewCount > 0) {
                        SwitchRow(stringResource(R.string.import_progress), stringResource(R.string.import_progress_desc), o.importProgress) { v ->
                            vm.updateOptions { it.copy(importProgress = v) }
                        }
                    }
                }
            }
            if (parsed.kind != ImportKind.KOTOBA) {
                item { Text(stringResource(R.string.import_mapping), style = MaterialTheme.typography.titleMedium) }
                items(parsed.groups, key = { it.id }) { g ->
                    MappingCard(g, o.mapping[g.id].orEmpty(), html = parsed.kind == ImportKind.ANKI) { i, t -> vm.setMapping(g.id, i, t) }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = vm::reset, modifier = Modifier.weight(1f)) { Text(stringResource(R.string.action_cancel)) }
            Button(onClick = vm::startImport, enabled = hasTerm && deckValid, modifier = Modifier.weight(2f)) {
                Text(pluralStringResource(R.plurals.import_import_n, parsed.total, parsed.total))
            }
        }
        if (!hasTerm) {
            Text(
                stringResource(R.string.import_need_term), color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            )
        }
    }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun SwitchRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(role = Role.Switch) { onChange(!checked) }.padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

@Composable
private fun RadioRow(selected: Boolean, text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().selectable(selected = selected, enabled = enabled, role = Role.RadioButton, onClick = onClick).padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = null, enabled = enabled)
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge, color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun DeckChooser(parsed: ParsedImport, o: ImportOptions, decks: List<DeckEntity>, vm: ImportExportViewModel) {
    Section(stringResource(R.string.import_deck)) {
        if (parsed.sourceDecks.isNotEmpty()) {
            RadioRow(o.deckMode == DeckMode.SOURCE_DECKS, pluralStringResource(R.plurals.import_keep_decks, parsed.sourceDecks.size, parsed.sourceDecks.size)) {
                vm.updateOptions { it.copy(deckMode = DeckMode.SOURCE_DECKS) }
            }
        }
        RadioRow(o.deckMode == DeckMode.NEW, stringResource(R.string.import_new_deck)) { vm.updateOptions { it.copy(deckMode = DeckMode.NEW) } }
        if (o.deckMode == DeckMode.NEW) {
            OutlinedTextField(
                value = o.newDeckName, onValueChange = { v -> vm.updateOptions { it.copy(newDeckName = v) } },
                label = { Text(stringResource(R.string.import_deck_name)) }, singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(start = 40.dp),
            )
        }
        RadioRow(o.deckMode == DeckMode.EXISTING, stringResource(R.string.import_existing_deck), enabled = decks.isNotEmpty()) {
            vm.updateOptions { it.copy(deckMode = DeckMode.EXISTING, existingDeckId = it.existingDeckId ?: decks.firstOrNull()?.id) }
        }
        if (o.deckMode == DeckMode.EXISTING && decks.isNotEmpty()) {
            Dropdown(
                value = decks.firstOrNull { it.id == o.existingDeckId }?.name.orEmpty(),
                options = decks.map { it.name },
                modifier = Modifier.padding(start = 40.dp),
            ) { i -> vm.updateOptions { it.copy(existingDeckId = decks[i].id) } }
        }
    }
}

@Composable
private fun Dropdown(value: String, options: List<String>, modifier: Modifier = Modifier, label: String? = null, onSelect: (Int) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            label = label?.let { { Text(it) } },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEachIndexed { i, s ->
                DropdownMenuItem(text = { Text(s) }, onClick = { expanded = false; onSelect(i) })
            }
        }
    }
}

@Composable
private fun MappingCard(group: FieldGroup, targets: List<FieldTarget>, html: Boolean, onChange: (Int, FieldTarget) -> Unit) {
    OutlinedCard {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(group.name, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(pluralStringResource(R.plurals.import_n_records, group.count, group.count), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            group.fields.forEachIndexed { i, field ->
                val sample = group.samples.asSequence().map { it.getOrElse(i) { "" } }.firstOrNull { it.isNotBlank() }.orEmpty()
                val shown = remember(sample, html) {
                    if (!html) sample else {
                        val media = (AnkiFields.sounds(sample).map { "🔊 $it" } + AnkiFields.images(sample).map { "🖼 $it" }).joinToString(" ")
                        AnkiFields.plainText(sample).ifBlank { media }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f).padding(end = 8.dp)) {
                        Text(field, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (shown.isNotBlank()) {
                            Text(shown.replace('\n', ' '), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    val current = targets.getOrElse(i) { FieldTarget.IGNORE }
                    val labels = FieldTarget.entries.map { fieldTargetLabel(it) }
                    Dropdown(value = fieldTargetLabel(current), options = labels, modifier = Modifier.width(170.dp)) { idx ->
                        onChange(i, FieldTarget.entries[idx])
                    }
                }
            }
        }
    }
}

@Composable
private fun ImportDone(report: ImportReport, onAnother: () -> Unit, onDone: () -> Unit) {
    var showErrors by remember { mutableStateOf(false) }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text(if (report.added > 0) "🎉" else "🤔", style = MaterialTheme.typography.displayMedium) }
        item {
            Text(
                stringResource(if (report.added > 0) R.string.import_done_title else R.string.import_done_nothing),
                style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center,
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatTile(report.added.toString(), stringResource(R.string.import_report_added), Modifier.weight(1f), emoji = "✅")
                StatTile(report.duplicates.toString(), stringResource(R.string.import_report_duplicates), Modifier.weight(1f), emoji = "♻️", accent = MaterialTheme.colorScheme.tertiary)
                StatTile(report.errors.toString(), stringResource(R.string.import_report_errors), Modifier.weight(1f), emoji = "⚠️", accent = MaterialTheme.colorScheme.error)
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (report.decks > 0) Text(pluralStringResource(R.plurals.import_report_decks, report.decks, report.decks), style = MaterialTheme.typography.bodyMedium)
                if (report.mediaFiles > 0) Text(pluralStringResource(R.plurals.import_report_media, report.mediaFiles, report.mediaFiles), style = MaterialTheme.typography.bodyMedium)
                if (report.withProgress > 0) Text(pluralStringResource(R.plurals.import_report_progress, report.withProgress, report.withProgress), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (report.errorSamples.isNotEmpty() || report.warnings.isNotEmpty()) {
            item {
                TextButton(onClick = { showErrors = !showErrors }) { Text(stringResource(R.string.import_show_details)) }
            }
            if (showErrors) {
                items(report.errorSamples + report.warnings) { line ->
                    Text(line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.fillMaxWidth())
                }
                if (report.errorSamples.isNotEmpty()) item {
                    Text(stringResource(R.string.import_errors_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(top = 8.dp)) {
                OutlinedButton(onClick = onAnother) { Text(stringResource(R.string.import_another)) }
                Button(onClick = onDone) { Text(stringResource(R.string.action_done)) }
            }
        }
    }
}

// ---------------------------------------------------------------- Export

@Composable
private fun ExportTab(
    state: ExportState,
    language: StudyLanguage,
    decks: List<DeckEntity>,
    onDeck: (Long?) -> Unit,
    onFormat: (ExportFormat) -> Unit,
    onSave: () -> Unit,
    onShare: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            item {
                Section(stringResource(R.string.import_export_what)) {
                    val allLabel = stringResource(R.string.import_export_all, "${language.flag} ${language.nativeName}")
                    Dropdown(
                        value = decks.firstOrNull { it.id == state.deckId }?.name ?: allLabel,
                        options = listOf(allLabel) + decks.map { it.name },
                    ) { i -> onDeck(if (i == 0) null else decks[i - 1].id) }
                }
            }
            item {
                Section(stringResource(R.string.import_export_format)) {
                    ExportFormat.entries.forEach { f ->
                        val (title, desc) = when (f) {
                            ExportFormat.CSV -> R.string.import_export_csv to R.string.import_export_csv_desc
                            ExportFormat.TSV -> R.string.import_export_tsv to R.string.import_export_tsv_desc
                            ExportFormat.JSON -> R.string.import_export_json to R.string.import_export_json_desc
                        }
                        val selected = state.format == f
                        OutlinedCard(
                            onClick = { onFormat(f) },
                            border = if (selected) CardDefaults.outlinedCardBorder().copy(brush = androidx.compose.ui.graphics.SolidColor(MaterialTheme.colorScheme.primary), width = 2.dp) else CardDefaults.outlinedCardBorder(),
                        ) {
                            Row(Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(selected = selected, onClick = null)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(stringResource(title), style = MaterialTheme.typography.titleSmall)
                                    Text(stringResource(desc), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                            }
                        }
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FilledTonalButton(onClick = onShare, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_share))
            }
            Button(onClick = onSave, enabled = !state.busy, modifier = Modifier.weight(1f)) {
                if (state.busy) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp, color = MaterialTheme.colorScheme.onPrimary)
                else Icon(Icons.Default.Save, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.import_save_file))
            }
        }
    }
}
