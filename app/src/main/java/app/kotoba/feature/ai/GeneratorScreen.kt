package app.kotoba.feature.ai

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.model.Category
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.SectionHeader
import app.kotoba.core.ui.components.categoryLabel
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings

@Composable
fun GeneratorScreen(
    onBack: () -> Unit,
    onStudy: (deckId: Long) -> Unit,
    onOpenAiSettings: () -> Unit,
    vm: GeneratorViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val step = state.step
    val inFlow = step is GeneratorStep.Working || step is GeneratorStep.Review
    BackHandler(enabled = inFlow) { vm.backToSetup() }

    Scaffold(
        topBar = {
            KotobaTopBar(
                title = stringResource(R.string.ai_generator_title),
                onBack = { if (inFlow) vm.backToSetup() else onBack() },
                actions = {
                    if (step is GeneratorStep.Review) {
                        val all = step.words.isNotEmpty() && step.words.all { it.selected }
                        TextButton(onClick = { vm.selectAll(!all) }) {
                            Text(stringResource(if (all) R.string.ai_select_none else R.string.ai_select_all))
                        }
                    }
                },
            )
        },
    ) { padding ->
        val animate = LocalUserSettings.current.animations
        AnimatedContent(
            targetState = step,
            contentKey = { it::class },
            transitionSpec = { if (animate) fadeIn() togetherWith fadeOut() else fadeIn(initialAlpha = 1f) togetherWith fadeOut(targetAlpha = 1f) },
            modifier = Modifier.fillMaxSize().padding(padding),
            label = "generator",
        ) { s ->
            when (s) {
                GeneratorStep.Setup -> SetupContent(state, vm, onOpenAiSettings)
                is GeneratorStep.Working -> WorkingContent(s)
                is GeneratorStep.Review -> ReviewContent(state, s, vm, onOpenAiSettings)
                is GeneratorStep.Done -> DoneContent(s, onStudy = { onStudy(s.deckId) }, onMore = vm::backToSetup, onClose = onBack)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SetupContent(state: GeneratorUiState, vm: GeneratorViewModel, onOpenAiSettings: () -> Unit) {
    val o = state.options
    Column(Modifier.fillMaxSize().imePadding()) {
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
            SectionHeader(stringResource(R.string.ai_gen_source))
            val sources = buildList {
                add(SourceKind.DOCUMENT); add(SourceKind.PASTE)
                if (state.shared.isNotBlank()) add(SourceKind.SHARED)
            }
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                sources.forEachIndexed { i, k ->
                    SegmentedButton(
                        selected = state.source == k,
                        onClick = { vm.setSource(k) },
                        shape = SegmentedButtonDefaults.itemShape(i, sources.size),
                    ) {
                        Text(
                            stringResource(
                                when (k) {
                                    SourceKind.DOCUMENT -> R.string.ai_gen_source_document
                                    SourceKind.PASTE -> R.string.ai_gen_source_paste
                                    SourceKind.SHARED -> R.string.ai_gen_source_shared
                                },
                            ),
                            maxLines = 1,
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            when (state.source) {
                SourceKind.DOCUMENT -> DocumentPicker(state.documents, state.document, vm::selectDocument)
                SourceKind.PASTE -> OutlinedTextField(
                    value = state.pasted,
                    onValueChange = vm::setPasted,
                    placeholder = { Text(stringResource(R.string.ai_gen_paste_hint)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp),
                    supportingText = { if (state.pasted.isNotEmpty()) Text(stringResource(R.string.ai_gen_chars, state.pasted.length)) },
                )
                SourceKind.SHARED -> Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                    Text(
                        state.shared.take(600),
                        Modifier.padding(14.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        maxLines = 8,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            state.error?.let { e ->
                Text(
                    stringResource(
                        when (e) {
                            GeneratorError.NO_TEXT -> R.string.ai_gen_err_no_text
                            GeneratorError.NO_WORDS -> R.string.ai_gen_err_no_words
                            GeneratorError.READ_FAILED -> R.string.ai_gen_err_read
                        },
                    ),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            SectionHeader(stringResource(R.string.ai_gen_options))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_gen_count), Modifier.weight(1f))
                Text("${o.count}", fontWeight = FontWeight.SemiBold)
            }
            Slider(
                value = o.count.toFloat(),
                onValueChange = { v -> vm.updateOptions { it.copy(count = (v / 5).toInt() * 5) } },
                valueRange = 10f..100f,
                steps = 17,
            )
            Text(stringResource(R.string.ai_gen_level), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = o.level.isBlank(), onClick = { vm.updateOptions { it.copy(level = "") } }, label = { Text(stringResource(R.string.ai_gen_any)) })
                state.language.levelScale.levels.forEach { lvl ->
                    FilterChip(selected = o.level == lvl, onClick = { vm.updateOptions { it.copy(level = lvl) } }, label = { Text(lvl) })
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ai_gen_frequency), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                FrequencyFilter.entries.forEachIndexed { i, f ->
                    SegmentedButton(
                        selected = o.frequency == f,
                        onClick = { vm.updateOptions { it.copy(frequency = f) } },
                        shape = SegmentedButtonDefaults.itemShape(i, FrequencyFilter.entries.size),
                    ) {
                        Text(
                            stringResource(
                                when (f) {
                                    FrequencyFilter.ANY -> R.string.ai_gen_freq_any
                                    FrequencyFilter.COMMON -> R.string.ai_gen_freq_common
                                    FrequencyFilter.RARE -> R.string.ai_gen_freq_rare
                                },
                            ),
                        )
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ai_gen_pos), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PartOfSpeech.entries.filter { it != PartOfSpeech.OTHER }.forEach { p ->
                    FilterChip(selected = p in o.partsOfSpeech, onClick = { vm.togglePos(p) }, label = { Text(posLabel(p)) })
                }
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = o.topic,
                onValueChange = { t -> vm.updateOptions { it.copy(topic = t) } },
                label = { Text(stringResource(R.string.ai_gen_topic)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            SwitchRow(stringResource(R.string.ai_gen_exclude_known), o.excludeKnown) { v -> vm.updateOptions { it.copy(excludeKnown = v) } }
            SwitchRow(stringResource(R.string.ai_gen_exclude_duplicates), o.excludeDuplicates) { v -> vm.updateOptions { it.copy(excludeDuplicates = v) } }
            SwitchRow(stringResource(R.string.ai_gen_examples), o.generateExamples) { v -> vm.updateOptions { it.copy(generateExamples = v) } }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = o.deckName,
                onValueChange = { t -> vm.updateOptions { it.copy(deckName = t) } },
                label = { Text(stringResource(R.string.ai_gen_deck_name)) },
                placeholder = { Text(GeneratorViewModel.DEFAULT_DECK) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(12.dp))
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(if (state.aiConfigured) "🤖" else "📴", style = MaterialTheme.typography.titleLarge)
                    Spacer(Modifier.width(12.dp))
                    Text(
                        stringResource(if (state.aiConfigured) R.string.ai_gen_mode_ai else R.string.ai_gen_mode_offline),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.weight(1f),
                    )
                    if (!state.aiConfigured) TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.ai_settings_title)) }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        Button(
            onClick = vm::generate,
            enabled = state.hasText,
            modifier = Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding(),
        ) {
            Icon(Icons.Filled.AutoAwesome, null, Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
            Text(stringResource(R.string.ai_gen_generate))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DocumentPicker(documents: List<DocumentEntity>, selected: DocumentEntity?, onSelect: (DocumentEntity) -> Unit) {
    if (documents.isEmpty()) {
        Text(stringResource(R.string.ai_gen_no_documents), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected?.title.orEmpty(),
            onValueChange = {},
            readOnly = true,
            label = { Text(stringResource(R.string.ai_gen_pick_document)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            documents.forEach { d ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(d.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (d.author.isNotBlank()) Text(d.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    },
                    onClick = { expanded = false; onSelect(d) },
                )
            }
        }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
private fun WorkingContent(step: GeneratorStep.Working) {
    Column(Modifier.fillMaxSize().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(20.dp))
        Text(
            stringResource(
                when (step.stage) {
                    GenStage.SEGMENTING -> R.string.ai_gen_stage_segment
                    GenStage.AI -> R.string.ai_gen_stage_ai
                    GenStage.TRANSLATING -> R.string.ai_gen_stage_translate
                },
            ),
            style = MaterialTheme.typography.titleMedium,
        )
        if (step.total > 0) {
            Spacer(Modifier.height(16.dp))
            LinearProgressIndicator(progress = { step.done.toFloat() / step.total }, modifier = Modifier.fillMaxWidth(0.7f))
            Spacer(Modifier.height(6.dp))
            Text("${step.done} / ${step.total}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ReviewContent(state: GeneratorUiState, review: GeneratorStep.Review, vm: GeneratorViewModel, onOpenAiSettings: () -> Unit) {
    val haptics = rememberHaptics()
    val groups = remember(review.words, review.groupBy) {
        when (review.groupBy) {
            GroupBy.TOPIC -> review.words.groupBy { it.category }.toSortedMap(compareBy { it.ordinal }).mapKeys { it.key as Any }
            GroupBy.DIFFICULTY -> review.words.groupBy { it.difficulty }.toSortedMap(compareBy { it.ordinal }).mapKeys { it.key as Any }
        }
    }
    var editing by rememberSaveable { mutableStateOf<Int?>(null) }
    Column(Modifier.fillMaxSize().imePadding()) {
        LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
            item {
                Text(
                    stringResource(R.string.ai_gen_review_hint, review.words.size),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                review.warning?.let { w ->
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = MaterialTheme.shapes.medium, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                        Column(Modifier.padding(12.dp)) {
                            Text("⚠️ " + aiErrorTitle(w), fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
                            Text(
                                stringResource(if (review.usedAi) R.string.ai_gen_partial else R.string.ai_gen_fallback),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                            if (w.kind == app.kotoba.core.ai.AiErrorKind.INVALID_KEY || w.kind == app.kotoba.core.ai.AiErrorKind.NOT_CONFIGURED) {
                                TextButton(onClick = onOpenAiSettings) { Text(stringResource(R.string.ai_settings_title)) }
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    GroupBy.entries.forEachIndexed { i, g ->
                        SegmentedButton(
                            selected = review.groupBy == g,
                            onClick = { vm.setGroupBy(g) },
                            shape = SegmentedButtonDefaults.itemShape(i, GroupBy.entries.size),
                        ) { Text(stringResource(if (g == GroupBy.TOPIC) R.string.ai_gen_group_topic else R.string.ai_gen_group_difficulty)) }
                    }
                }
            }
            groups.forEach { (key, words) ->
                item(key = "h_$key") {
                    val ids = words.map { it.id }.toSet()
                    val allOn = words.all { it.selected }
                    Row(Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(groupTitle(key), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                        Text("${words.count { it.selected }}/${words.size}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Checkbox(checked = allOn, onCheckedChange = { vm.selectGroup(ids, it) })
                    }
                    HorizontalDivider()
                }
                items(words, key = { it.id }) { w ->
                    WordRow(
                        word = w,
                        expanded = editing == w.id,
                        showReading = state.language.hasReading,
                        onToggle = { haptics.tick(); vm.toggle(w.id) },
                        onEditToggle = { editing = if (editing == w.id) null else w.id },
                        onChange = vm::edit,
                    )
                }
            }
        }
        Surface(tonalElevation = 3.dp) {
            Column(Modifier.fillMaxWidth().padding(16.dp).navigationBarsPadding()) {
                OutlinedTextField(
                    value = state.options.deckName,
                    onValueChange = { t -> vm.updateOptions { it.copy(deckName = t) } },
                    label = { Text(stringResource(R.string.ai_gen_deck_name)) },
                    placeholder = { Text(GeneratorViewModel.DEFAULT_DECK) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                val count = review.words.count { it.selected }
                Button(onClick = { haptics.success(); vm.save() }, enabled = count > 0 && !review.saving, modifier = Modifier.fillMaxWidth()) {
                    if (review.saving) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    else Text(stringResource(R.string.ai_add_n_words, count))
                }
            }
        }
    }
}

@Composable
private fun groupTitle(key: Any): String = when (key) {
    is Category -> "${key.emoji} ${categoryLabel(key)}"
    is Difficulty -> stringResource(
        when (key) {
            Difficulty.EASY -> R.string.ai_gen_diff_easy
            Difficulty.MEDIUM -> R.string.ai_gen_diff_medium
            Difficulty.HARD -> R.string.ai_gen_diff_hard
        },
    )
    else -> key.toString()
}

@Composable
private fun WordRow(
    word: GeneratedWord,
    expanded: Boolean,
    showReading: Boolean,
    onToggle: () -> Unit,
    onEditToggle: () -> Unit,
    onChange: (GeneratedWord) -> Unit,
) {
    Column(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = word.selected, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(word.term, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val sub = listOf(word.reading, word.ipa.takeIf { it.isNotBlank() }?.let { "/$it/" }.orEmpty()).filter { it.isNotBlank() }.joinToString(" ")
                    if (sub.isNotBlank()) {
                        Spacer(Modifier.width(8.dp))
                        Text(sub, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Text(
                    word.translation.ifBlank { stringResource(R.string.ai_gen_no_translation) },
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (word.translation.isBlank()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                val meta = listOf(word.partOfSpeech, word.level).filter { it.isNotBlank() }.joinToString(" · ")
                if (meta.isNotBlank()) Text(meta, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                val ex = word.example.ifBlank { word.sourceSentence }
                if (ex.isNotBlank() && !expanded) {
                    Text(ex, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            IconButton(onClick = onEditToggle) {
                Icon(if (expanded) Icons.Filled.ExpandLess else Icons.Filled.Edit, stringResource(R.string.action_edit))
            }
        }
        AnimatedVisibility(expanded) {
            Column(Modifier.padding(start = 48.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                EditField(stringResource(R.string.ai_field_term), word.term) { onChange(word.copy(term = it)) }
                if (showReading) EditField(stringResource(R.string.ai_field_reading), word.reading) { onChange(word.copy(reading = it)) }
                else EditField("IPA", word.ipa) { onChange(word.copy(ipa = it)) }
                EditField(stringResource(R.string.ai_field_translation), word.translation) { onChange(word.copy(translation = it)) }
                EditField(stringResource(R.string.ai_field_example), word.example, singleLine = false) { onChange(word.copy(example = it)) }
                EditField(stringResource(R.string.ai_field_example_translation), word.exampleTranslation, singleLine = false) { onChange(word.copy(exampleTranslation = it)) }
                if (word.sourceSentence.isNotBlank()) {
                    Text(
                        stringResource(R.string.ai_gen_source_sentence, word.sourceSentence),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun EditField(label: String, value: String, singleLine: Boolean = true, onChange: (String) -> Unit) {
    OutlinedTextField(value = value, onValueChange = onChange, label = { Text(label) }, singleLine = singleLine, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun DoneContent(step: GeneratorStep.Done, onStudy: () -> Unit, onMore: () -> Unit, onClose: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (step.added > 0) "🎉" else "🤔", style = MaterialTheme.typography.displayMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                stringResource(R.string.ai_gen_done_title, step.added),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(step.deckName, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (step.duplicates > 0) {
                Text(
                    stringResource(R.string.ai_gen_done_duplicates, step.duplicates),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(24.dp))
            if (step.added > 0) {
                Button(onClick = onStudy, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ai_gen_study)) }
                Spacer(Modifier.height(8.dp))
            }
            OutlinedButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.ai_gen_more)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_done)) }
        }
    }
}

@Composable
internal fun posLabel(p: PartOfSpeech): String = stringResource(
    when (p) {
        PartOfSpeech.NOUN -> R.string.ai_pos_noun
        PartOfSpeech.VERB -> R.string.ai_pos_verb
        PartOfSpeech.ADJECTIVE -> R.string.ai_pos_adjective
        PartOfSpeech.ADVERB -> R.string.ai_pos_adverb
        PartOfSpeech.PRONOUN -> R.string.ai_pos_pronoun
        PartOfSpeech.PREPOSITION -> R.string.ai_pos_preposition
        PartOfSpeech.CONJUNCTION -> R.string.ai_pos_conjunction
        PartOfSpeech.INTERJECTION -> R.string.ai_pos_interjection
        PartOfSpeech.PARTICLE -> R.string.ai_pos_particle
        PartOfSpeech.COUNTER -> R.string.ai_pos_counter
        PartOfSpeech.PHRASE -> R.string.ai_pos_phrase
        PartOfSpeech.IDIOM -> R.string.ai_pos_idiom
        PartOfSpeech.OTHER -> R.string.ai_pos_other
    },
)
