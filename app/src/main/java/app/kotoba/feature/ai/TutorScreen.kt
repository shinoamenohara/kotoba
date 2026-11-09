package app.kotoba.feature.ai

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Quiz
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDrawerState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.ai.AiErrorKind
import app.kotoba.core.db.ChatMessageEntity
import app.kotoba.core.db.ConversationEntity
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.LocalUserSettings
import kotlinx.coroutines.launch
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorScreen(onOpenSettings: () -> Unit, vm: TutorViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val wordPicks by vm.wordPicks.collectAsStateWithLifecycle()
    val quiz by vm.quiz.collectAsStateWithLifecycle()
    val drawer = rememberDrawerState(androidx.compose.material3.DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val context = LocalContext.current
    val haptics = rememberHaptics()
    var pendingAction by remember { mutableStateOf<QuickAction?>(null) }
    var renaming by remember { mutableStateOf<ConversationEntity?>(null) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is TutorEvent.WordsAdded -> {
                    haptics.success()
                    snackbar.showSnackbar(context.getString(R.string.ai_words_added, e.added, e.duplicates))
                }
                TutorEvent.StatsSharingNeeded -> {
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.ai_stats_needed),
                        actionLabel = context.getString(R.string.ai_stats_enable),
                    )
                    if (r == SnackbarResult.ActionPerformed) vm.enableStatsSharing()
                }
            }
        }
    }

    val langName = state.language.displayName()
    val runAction: (QuickAction, String) -> Unit = { action, input ->
        val text = context.getString(action.template, input, langName)
        if (action == QuickAction.DIALOGUE) vm.startDialogue(input, text) else vm.send(text)
    }
    val onAction: (QuickAction) -> Unit = { action ->
        haptics.tick()
        if (action.needsStats && !vm.requireStats()) Unit
        else if (action.input == ActionInput.NONE) runAction(action, "")
        else pendingAction = action
    }

    ModalNavigationDrawer(
        drawerState = drawer,
        gesturesEnabled = drawer.isOpen,
        drawerContent = {
            ConversationDrawer(
                conversations = state.conversations,
                currentId = state.current?.id,
                onNew = { vm.newConversation(); scope.launch { drawer.close() } },
                onOpen = { vm.open(it.id); scope.launch { drawer.close() } },
                onRename = { renaming = it },
                onDelete = { vm.delete(it) },
            )
        },
    ) {
        Scaffold(
            topBar = {
                KotobaTopBar(
                    title = state.current?.title ?: stringResource(R.string.ai_tutor_title),
                    actions = {
                        IconButton(onClick = { scope.launch { drawer.open() } }) {
                            Icon(Icons.Filled.History, stringResource(R.string.ai_conversations))
                        }
                        if (state.configured) {
                            IconButton(onClick = { vm.newConversation() }) { Icon(Icons.Filled.Add, stringResource(R.string.ai_new_chat)) }
                            IconButton(onClick = { haptics.tick(); vm.startQuiz() }) { Icon(Icons.Filled.Quiz, stringResource(R.string.ai_exercises)) }
                        }
                        IconButton(onClick = onOpenSettings) { Icon(Icons.Filled.Tune, stringResource(R.string.ai_settings_title)) }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
        ) { padding ->
            if (!state.configured) {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    EmptyState(
                        emoji = "🤖",
                        title = stringResource(R.string.ai_not_configured_title),
                        message = stringResource(R.string.ai_not_configured_message),
                        action = { Button(onClick = onOpenSettings) { Text(stringResource(R.string.ai_open_settings)) } },
                    )
                }
            } else {
                Column(Modifier.fillMaxSize().padding(padding).imePadding()) {
                    ChatList(
                        messages = state.messages,
                        sending = state.sending,
                        language = state.language,
                        onAction = onAction,
                        onRetry = vm::retry,
                        onAddWords = vm::pickWords,
                        onOpenSettings = onOpenSettings,
                        modifier = Modifier.weight(1f),
                    )
                    if (state.messages.isNotEmpty()) QuickActionRow(onAction)
                    ChatInput(enabled = !state.sending, onSend = { haptics.tick(); vm.send(it) })
                }
            }
        }
    }

    pendingAction?.let { action ->
        if (action.input == ActionInput.SCENARIO) {
            ScenarioDialog(state.language, onDismiss = { pendingAction = null }, onPick = { pendingAction = null; runAction(action, it) })
        } else {
            ActionInputDialog(action, onDismiss = { pendingAction = null }, onConfirm = { pendingAction = null; runAction(action, it) })
        }
    }
    renaming?.let { c ->
        RenameDialog(c.title, onDismiss = { renaming = null }, onConfirm = { vm.rename(c, it); renaming = null })
    }
    wordPicks?.let { picks ->
        AddWordsSheet(
            picks = picks,
            onToggle = vm::toggleWord,
            onAll = vm::setAllWords,
            onAdd = vm::addPickedWords,
            onDismiss = vm::dismissWords,
        )
    }
    quiz?.let { q ->
        QuizSheet(
            state = q,
            onAnswer = vm::answerQuiz,
            onNext = vm::nextQuestion,
            onRestart = vm::startQuiz,
            onClose = vm::closeQuiz,
            onOpenSettings = { vm.closeQuiz(); onOpenSettings() },
        )
    }
}

internal fun StudyLanguage.displayName(): String =
    locale.getDisplayLanguage(Locale.getDefault()).replaceFirstChar { it.titlecase(Locale.getDefault()) }

@Composable
private fun ChatList(
    messages: List<ChatMessageEntity>,
    sending: Boolean,
    language: StudyLanguage,
    onAction: (QuickAction) -> Unit,
    onRetry: (ChatMessageEntity) -> Unit,
    onAddWords: (String) -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val total = messages.size + if (sending) 1 else 0
    LaunchedEffect(total) { if (total > 0) listState.animateScrollToItem(total - 1) }

    if (messages.isEmpty() && !sending) {
        Welcome(language, onAction, modifier)
        return
    }
    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        state = listState,
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        items(messages, key = { it.id }) { m ->
            when {
                m.isError -> ErrorBubble(AiFailure.decode(m.content), onRetry = { onRetry(m) }, onOpenSettings = onOpenSettings)
                m.role == TutorViewModel.ROLE_USER -> UserBubble(m.content)
                else -> AssistantBubble(m.content, onAddWords = { onAddWords(m.content) })
            }
        }
        if (sending) item(key = "typing") { TypingIndicator() }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Welcome(language: StudyLanguage, onAction: (QuickAction) -> Unit, modifier: Modifier = Modifier) {
    LazyColumn(modifier.fillMaxWidth(), contentPadding = PaddingValues(20.dp)) {
        item {
            Text("${language.flag} 🤖", style = MaterialTheme.typography.displaySmall)
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.ai_welcome_title), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.ai_welcome_message, language.displayName()),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                QuickAction.entries.forEach { a ->
                    AssistChip(onClick = { onAction(a) }, label = { Text("${a.emoji} ${stringResource(a.label)}") })
                }
            }
        }
    }
}

@Composable
private fun QuickActionRow(onAction: (QuickAction) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(QuickAction.entries) { a ->
            AssistChip(onClick = { onAction(a) }, label = { Text("${a.emoji} ${stringResource(a.label)}", maxLines = 1) })
        }
    }
}

@Composable
private fun ChatInput(enabled: Boolean, onSend: (String) -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            modifier = Modifier.weight(1f),
            placeholder = { Text(stringResource(R.string.ai_input_hint)) },
            maxLines = 5,
            shape = RoundedCornerShape(24.dp),
        )
        Spacer(Modifier.width(8.dp))
        FilledIconButton(
            onClick = { onSend(text); text = "" },
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.size(52.dp),
        ) { Icon(Icons.AutoMirrored.Filled.Send, stringResource(R.string.ai_send)) }
    }
}

@Composable
private fun UserBubble(text: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            shape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            SelectionContainer {
                Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@Composable
private fun AssistantBubble(text: String, onAddWords: () -> Unit) {
    val clipboard = LocalClipboardManager.current
    val hasWords = remember(text) { WordListParser.parse(text).isNotEmpty() }
    Column(Modifier.fillMaxWidth()) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
            modifier = Modifier.widthIn(max = 360.dp),
        ) {
            SelectionContainer {
                MarkdownText(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { clipboard.setText(AnnotatedString(text)) }) {
                Icon(Icons.Filled.ContentCopy, stringResource(R.string.ai_copy), Modifier.size(18.dp))
            }
            if (hasWords) {
                TextButton(onClick = onAddWords) {
                    Icon(Icons.Filled.LibraryAdd, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.ai_add_words))
                }
            }
        }
    }
}

@Composable
private fun ErrorBubble(failure: AiFailure, onRetry: () -> Unit, onOpenSettings: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp),
        modifier = Modifier.widthIn(max = 360.dp),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text("⚠️ " + aiErrorTitle(failure), color = MaterialTheme.colorScheme.onErrorContainer, fontWeight = FontWeight.SemiBold)
            if (failure.showDetail) {
                Text(
                    failure.detail,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f),
                    maxLines = 4,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Row {
                TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
                if (failure.kind in setOf(AiErrorKind.NOT_CONFIGURED, AiErrorKind.INVALID_KEY, AiErrorKind.LOCAL_LIMIT, AiErrorKind.BAD_REQUEST)) {
                    TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.ai_settings_title)) }
                }
            }
        }
    }
}

@Composable
private fun TypingIndicator() {
    val animate = LocalUserSettings.current.animations
    Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(20.dp, 20.dp, 20.dp, 4.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            val transition = rememberInfiniteTransition(label = "typing")
            repeat(3) { i ->
                val alpha = if (animate) {
                    transition.animateFloat(
                        initialValue = 0.25f,
                        targetValue = 1f,
                        animationSpec = infiniteRepeatable(tween(500, delayMillis = i * 150), RepeatMode.Reverse),
                        label = "dot$i",
                    ).value
                } else 0.6f
                Box(Modifier.size(8.dp).alpha(alpha).background(MaterialTheme.colorScheme.onSurfaceVariant, CircleShape))
            }
        }
    }
}

@Composable
private fun ConversationDrawer(
    conversations: List<ConversationEntity>,
    currentId: Long?,
    onNew: () -> Unit,
    onOpen: (ConversationEntity) -> Unit,
    onRename: (ConversationEntity) -> Unit,
    onDelete: (ConversationEntity) -> Unit,
) {
    var confirmDelete by remember { mutableStateOf<ConversationEntity?>(null) }
    ModalDrawerSheet {
        Text(
            stringResource(R.string.ai_conversations),
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(start = 24.dp, top = 24.dp, bottom = 12.dp),
        )
        NavigationDrawerItem(
            label = { Text(stringResource(R.string.ai_new_chat)) },
            icon = { Icon(Icons.Filled.Add, null) },
            selected = currentId == null,
            onClick = onNew,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        if (conversations.isEmpty()) {
            Text(
                stringResource(R.string.ai_no_conversations),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(24.dp),
            )
        }
        LazyColumn {
            items(conversations, key = { it.id }) { c ->
                var menu by remember { mutableStateOf(false) }
                NavigationDrawerItem(
                    label = { Text(c.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    selected = c.id == currentId,
                    onClick = { onOpen(c) },
                    badge = {
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, null) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                DropdownMenuItem(text = { Text(stringResource(R.string.ai_rename)) }, onClick = { menu = false; onRename(c) })
                                DropdownMenuItem(text = { Text(stringResource(R.string.action_delete)) }, onClick = { menu = false; confirmDelete = c })
                            }
                        }
                    },
                    modifier = Modifier.padding(horizontal = 12.dp),
                )
            }
        }
    }
    confirmDelete?.let { c ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.ai_delete_conversation)) },
            text = { Text(c.title) },
            confirmButton = { TextButton(onClick = { onDelete(c); confirmDelete = null }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.ai_rename)) },
        text = { OutlinedTextField(text, { text = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { TextButton(onClick = { onConfirm(text) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_save)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@Composable
private fun ActionInputDialog(action: QuickAction, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    val hint = stringResource(
        when (action.input) {
            ActionInput.WORD -> R.string.ai_input_word
            ActionInput.WORDS -> R.string.ai_input_words
            ActionInput.TOPIC -> if (action == QuickAction.GRAMMAR) R.string.ai_input_grammar else R.string.ai_input_topic
            ActionInput.EXPRESSION -> R.string.ai_input_expression
            else -> R.string.ai_input_hint
        },
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${action.emoji} ${stringResource(action.label)}") },
        text = {
            OutlinedTextField(text, { text = it }, label = { Text(hint) }, singleLine = true, modifier = Modifier.fillMaxWidth())
        },
        confirmButton = { TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.ai_ask)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScenarioDialog(language: StudyLanguage, onDismiss: () -> Unit, onPick: (String) -> Unit) {
    var custom by remember { mutableStateOf("") }
    val scenarios = (DialogueScenarios.common + DialogueScenarios.extra(language)).map { stringResource(it) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("🎭 ${stringResource(R.string.ai_qa_dialogue)}") },
        text = {
            Column {
                Text(stringResource(R.string.ai_scenario_pick), style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scenarios.forEach { s -> AssistChip(onClick = { onPick(s) }, label = { Text(s) }) }
                }
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    custom, { custom = it },
                    label = { Text(stringResource(R.string.ai_scenario_custom)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onPick(custom.trim()) }, enabled = custom.isNotBlank()) { Text(stringResource(R.string.action_start)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddWordsSheet(
    picks: List<WordPick>,
    onToggle: (Int) -> Unit,
    onAll: (Boolean) -> Unit,
    onAdd: () -> Unit,
    onDismiss: () -> Unit,
) {
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.ai_add_words_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                val allSelected = picks.all { it.selected }
                TextButton(onClick = { onAll(!allSelected) }) {
                    Text(stringResource(if (allSelected) R.string.ai_select_none else R.string.ai_select_all))
                }
            }
            Text(
                stringResource(R.string.ai_add_words_deck, TutorViewModel.TUTOR_DECK),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.weight(1f, fill = false)) {
                items(picks.size) { i ->
                    val p = picks[i]
                    Row(
                        Modifier.fillMaxWidth().clickable { onToggle(i) }.padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.Checkbox(checked = p.selected, onCheckedChange = { onToggle(i) })
                        Column(Modifier.weight(1f)) {
                            val reading = listOf(p.word.reading, p.word.ipa).filter { it.isNotBlank() }.joinToString(" · ")
                            Text(p.word.term + if (reading.isNotEmpty()) "  ($reading)" else "", fontWeight = FontWeight.SemiBold)
                            Text(p.word.translation, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            val count = picks.count { it.selected }
            Button(onClick = onAdd, enabled = count > 0, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.ai_add_n_words, count))
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun QuizSheet(
    state: QuizState,
    onAnswer: (String) -> Unit,
    onNext: () -> Unit,
    onRestart: () -> Unit,
    onClose: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    val sheetState = androidx.compose.material3.rememberModalBottomSheetState(skipPartiallyExpanded = true)
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onClose, sheetState = sheetState) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            Text("🧩 " + stringResource(R.string.ai_exercises), style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))
            when (state) {
                QuizState.Loading -> Column(Modifier.fillMaxWidth().padding(vertical = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.CircularProgressIndicator()
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.ai_quiz_loading), color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                is QuizState.Error -> Column {
                    Text("⚠️ " + aiErrorTitle(state.failure), fontWeight = FontWeight.SemiBold)
                    if (state.failure.showDetail) Text(state.failure.detail, style = MaterialTheme.typography.bodySmall)
                    Row {
                        TextButton(onClick = onRestart) { Text(stringResource(R.string.action_retry)) }
                        if (state.failure.kind == AiErrorKind.NOT_CONFIGURED || state.failure.kind == AiErrorKind.INVALID_KEY) {
                            TextButton(onClick = onOpenSettings) { Text(stringResource(R.string.ai_settings_title)) }
                        }
                    }
                }
                is QuizState.Running -> if (state.finished) QuizResult(state, onRestart, onClose) else QuizQuestionView(state, onAnswer, onNext)
            }
        }
    }
}

@Composable
private fun QuizQuestionView(state: QuizState.Running, onAnswer: (String) -> Unit, onNext: () -> Unit) {
    val q = state.current
    val haptics = rememberHaptics()
    androidx.compose.material3.LinearProgressIndicator(
        progress = { (state.index + 1f) / state.questions.size },
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(8.dp))
    Text(
        stringResource(R.string.ai_quiz_progress, state.index + 1, state.questions.size),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(12.dp))
    MarkdownText(q.prompt)
    Spacer(Modifier.height(16.dp))
    val answered = state.response != null
    val wasCorrect = answered && q.isCorrect(state.response!!)
    LaunchedEffect(state.index, answered) { if (answered) { if (wasCorrect) haptics.success() else haptics.error() } }
    when (q.kind) {
        QuizKind.MULTIPLE_CHOICE -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            q.options.forEach { option ->
                val isAnswer = option == q.answer
                val isPicked = option == state.response
                val container = when {
                    !answered -> MaterialTheme.colorScheme.surfaceContainer
                    isAnswer -> MaterialTheme.colorScheme.primaryContainer
                    isPicked -> MaterialTheme.colorScheme.errorContainer
                    else -> MaterialTheme.colorScheme.surfaceContainer
                }
                Surface(
                    color = container,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().clickable(enabled = !answered) { onAnswer(option) },
                ) {
                    Text(option, Modifier.padding(14.dp), style = MaterialTheme.typography.bodyLarge)
                }
            }
        }
        QuizKind.FILL_BLANK -> {
            var text by remember(state.index) { mutableStateOf("") }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(text, { text = it }, enabled = !answered, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                Button(onClick = { onAnswer(text) }, enabled = !answered && text.isNotBlank()) { Text(stringResource(R.string.ai_quiz_check)) }
            }
        }
    }
    AnimatedVisibility(answered, enter = fadeIn(), exit = fadeOut()) {
        Column(Modifier.padding(top = 16.dp)) {
            Text(
                if (wasCorrect) "✅ " + stringResource(R.string.ai_quiz_correct) else "❌ " + stringResource(R.string.ai_quiz_wrong, q.answer),
                fontWeight = FontWeight.SemiBold,
                color = if (wasCorrect) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
            if (q.explanation.isNotBlank()) Text(q.explanation, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(12.dp))
            Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_continue)) }
        }
    }
}

@Composable
private fun QuizResult(state: QuizState.Running, onRestart: () -> Unit, onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        val ratio = state.correct.toFloat() / state.questions.size
        Text(if (ratio >= 0.8f) "🎉" else if (ratio >= 0.5f) "👍" else "💪", style = MaterialTheme.typography.displayMedium)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.ai_quiz_score, state.correct, state.questions.size),
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.SemiBold,
        )
        if (state.linked.isNotEmpty()) {
            Text(
                stringResource(R.string.ai_quiz_linked, state.linked.size),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
        }
        Spacer(Modifier.height(20.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            TextButton(onClick = onClose) { Text(stringResource(R.string.action_close)) }
            Button(onClick = onRestart) { Text(stringResource(R.string.ai_quiz_again)) }
        }
    }
}
