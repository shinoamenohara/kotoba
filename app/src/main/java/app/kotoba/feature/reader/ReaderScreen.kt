package app.kotoba.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.BookmarkAdd
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.FormatSize
import androidx.compose.material.icons.filled.Highlight
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.ShortText
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.db.BookmarkEntity
import app.kotoba.core.docs.TextParagraph
import app.kotoba.core.lang.Token
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.core.ui.theme.MasteryColors
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

private val HighlightColor = Color(0xFFFFD54F)

private enum class ReaderSheet { BOOKMARKS, SETTINGS, STATS }

@Composable
fun ReaderScreen(
    onBack: () -> Unit,
    onExplainWithAi: (String) -> Unit,
    onGenerateDeck: (Long) -> Unit,
    vm: ReaderViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val phraseMode by vm.phraseMode.collectAsStateWithLifecycle()
    val anchor by vm.anchor.collectAsStateWithLifecycle()
    val bookmarks by vm.bookmarks.collectAsStateWithLifecycle()
    val known by vm.known.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    var sheet by remember { mutableStateOf<ReaderSheet?>(null) }
    var menu by remember { mutableStateOf(false) }
    var noteFor by remember { mutableStateOf<LookupState?>(null) }
    var editNote by remember { mutableStateOf<BookmarkEntity?>(null) }
    val colors = prefs.theme.colors()

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                ReaderEvent.AchievementUnlocked -> {
                    haptics.success()
                    snackbar.showSnackbar(context.getString(R.string.reader_achievement, context.getString(R.string.achievement_first_book_title)))
                }
                is ReaderEvent.Message -> snackbar.showSnackbar(context.getString(e.res))
            }
        }
    }

    Scaffold(
        containerColor = colors.background,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            KotobaTopBar(
                title = state.doc?.title.orEmpty(),
                onBack = onBack,
                actions = {
                    IconButton(onClick = { sheet = ReaderSheet.BOOKMARKS }) { Icon(Icons.Filled.Bookmarks, stringResource(R.string.reader_bookmarks)) }
                    IconButton(onClick = { sheet = ReaderSheet.SETTINGS }) { Icon(Icons.Filled.FormatSize, stringResource(R.string.reader_settings)) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.reader_more)) }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(
                                text = { Text(stringResource(if (phraseMode) R.string.reader_phrase_mode_off else R.string.reader_phrase_mode)) },
                                leadingIcon = { Icon(Icons.Filled.ShortText, null) },
                                onClick = { menu = false; vm.togglePhraseMode() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_stats)) },
                                leadingIcon = { Icon(Icons.Filled.BarChart, null) },
                                onClick = { menu = false; sheet = ReaderSheet.STATS; vm.computeStats() },
                            )
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.reader_generate_deck)) },
                                leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                                onClick = { menu = false; state.doc?.let { onGenerateDeck(it.id) } },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.loading -> CircularProgressIndicator(Modifier.align(Alignment.Center))
                state.error || state.paragraphs.isEmpty() -> EmptyState("📄", stringResource(R.string.reader_empty_title), stringResource(R.string.reader_empty), Modifier.align(Alignment.Center).padding(24.dp))
                else -> {
                    val listState = rememberLazyListState(initialFirstVisibleItemIndex = state.initialParagraph)
                    LaunchedEffect(listState) {
                        snapshotFlow { listState.firstVisibleItemIndex to !listState.canScrollForward }
                            .distinctUntilChanged()
                            .collect { (first, atEnd) -> vm.onVisible(first, atEnd) }
                    }
                    val highlights = remember(bookmarks) { bookmarks.filter { it.isHighlight }.map { it.position until it.position + it.length } }
                    ReaderContent(
                        vm = vm,
                        listState = listState,
                        paragraphs = state.paragraphs,
                        language = state.language,
                        prefs = prefs,
                        colors = colors,
                        known = known,
                        highlights = highlights,
                        selected = lookup?.let { it.start until it.end },
                        anchor = anchor,
                        onTap = { p, t -> haptics.tick(); vm.onWordTap(p, t) },
                        onLongPress = { p, t -> haptics.tick(); vm.onWordLongPress(p, t) },
                    )
                    val progress by remember { derivedStateOf { if (!listState.canScrollForward) 1f else listState.firstVisibleItemIndex.toFloat() / state.paragraphs.size } }
                    Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                        AnimatedVisibility(phraseMode, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(16.dp), modifier = Modifier.padding(12.dp).fillMaxWidth()) {
                                Row(Modifier.padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        stringResource(if (anchor == null) R.string.reader_phrase_first else R.string.reader_phrase_last),
                                        style = MaterialTheme.typography.bodyMedium,
                                        modifier = Modifier.weight(1f),
                                    )
                                    TextButton(onClick = vm::togglePhraseMode) { Text(stringResource(R.string.action_cancel)) }
                                }
                            }
                        }
                        Row(Modifier.fillMaxWidth().background(colors.background).padding(horizontal = 16.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(progress = { progress }, modifier = Modifier.weight(1f), drawStopIndicator = {})
                            Spacer(Modifier.width(10.dp))
                            Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = colors.secondary)
                            IconButton(onClick = { vm.addBookmark(listState.firstVisibleItemIndex) }) {
                                Icon(Icons.Filled.BookmarkAdd, stringResource(R.string.reader_add_bookmark), tint = colors.secondary)
                            }
                        }
                    }
                    if (sheet == ReaderSheet.BOOKMARKS) {
                        BookmarksSheet(
                            bookmarks = bookmarks,
                            onDismiss = { sheet = null },
                            onJump = { b -> sheet = null; scope.launch { listState.animateScrollToItem(vm.paragraphIndexFor(b.position)) } },
                            onDelete = vm::deleteBookmark,
                            onEditNote = { editNote = it },
                        )
                    }
                }
            }
        }
    }

    lookup?.let { l ->
        ModalBottomSheet(onDismissRequest = vm::dismissLookup, sheetState = rememberModalBottomSheetState()) {
            val prompt = stringResource(R.string.reader_ai_prompt, l.term, l.sentence, state.language.nativeName)
            WordLookupContent(
                state = l,
                language = state.language,
                onSpeak = { vm.speak(l.term) },
                onAdd = { haptics.success(); vm.addWord() },
                onAddContext = { haptics.success(); vm.addContext() },
                onExplain = { vm.dismissLookup(); onExplainWithAi(prompt) },
                modifier = Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp).verticalScroll(rememberScrollState()),
                extraActions = {
                    OutlinedButton(onClick = { vm.addHighlight(l.start, l.end); vm.dismissLookup() }) {
                        Icon(Icons.Filled.Highlight, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reader_highlight))
                    }
                    OutlinedButton(onClick = { noteFor = l; vm.dismissLookup() }) {
                        Icon(Icons.Filled.EditNote, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reader_note))
                    }
                },
            )
        }
    }

    when (sheet) {
        ReaderSheet.SETTINGS -> ReaderSettingsSheet(prefs, state.language, onDismiss = { sheet = null }, onChange = vm::updatePrefs)
        ReaderSheet.STATS -> {
            val stats by vm.stats.collectAsStateWithLifecycle()
            val added by vm.addedFromBook.collectAsStateWithLifecycle()
            BookStatsSheet(stats, added, state.doc?.progress ?: 0f, onDismiss = { sheet = null }, onGenerateDeck = { sheet = null; state.doc?.let { onGenerateDeck(it.id) } })
        }
        else -> Unit
    }

    noteFor?.let { l ->
        NoteDialog(initial = "", quote = l.term, onDismiss = { noteFor = null }, onSave = { note -> noteFor = null; vm.addHighlight(l.start, l.end, note) })
    }
    editNote?.let { b ->
        NoteDialog(initial = b.note, quote = b.quote, onDismiss = { editNote = null }, onSave = { note -> editNote = null; vm.updateNote(b, note) })
    }
}

@Composable
private fun ReaderContent(
    vm: ReaderViewModel,
    listState: LazyListState,
    paragraphs: List<TextParagraph>,
    language: StudyLanguage,
    prefs: ReaderPrefs,
    colors: ReaderColors,
    known: Map<String, KnownWord>,
    highlights: List<IntRange>,
    selected: IntRange?,
    anchor: PhraseAnchor?,
    onTap: (TextParagraph, Token) -> Unit,
    onLongPress: (TextParagraph, Token) -> Unit,
) {
    val cjk = !language.usesSpaces
    val style = TextStyle(
        fontSize = (if (cjk) prefs.fontSize + 1 else prefs.fontSize).sp,
        lineHeight = prefs.lineSpacing.em,
        color = colors.text,
    )
    val ruby = prefs.showReadings && language.hasReading
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 120.dp),
        verticalArrangement = Arrangement.spacedBy((prefs.fontSize * 0.8f).dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        itemsIndexed(paragraphs, key = { _, p -> p.index }) { _, p ->
            val tokens = remember(p, language) { vm.tokens(p) }
            val pHighlights = highlights.filter { it.first < p.end && it.last >= p.start }
            val pSelected = selected?.takeIf { it.first < p.end && it.last >= p.start }
            val pAnchor = anchor?.takeIf { it.start < p.end && it.end > p.start }
            if (ruby) {
                RubyParagraph(p, tokens, vm, known, style, colors, pHighlights, pSelected, pAnchor, onTap, onLongPress)
            } else {
                ParagraphText(p, tokens, vm, known, style, pHighlights, pSelected, pAnchor, onTap, onLongPress)
            }
        }
    }
}

private fun wordBackground(
    globalStart: Int,
    globalEnd: Int,
    knownWord: KnownWord?,
    highlights: List<IntRange>,
    selected: IntRange?,
    anchor: PhraseAnchor?,
): Color? = when {
    selected != null && globalStart <= selected.last && globalEnd > selected.first -> MasteryColors.Familiar.copy(alpha = 0.38f)
    anchor != null && globalStart < anchor.end && globalEnd > anchor.start -> MasteryColors.Familiar.copy(alpha = 0.3f)
    highlights.any { globalStart <= it.last && globalEnd > it.first } -> HighlightColor.copy(alpha = 0.45f)
    knownWord != null -> MasteryColors.of(knownWord.mastery).copy(alpha = 0.2f)
    else -> null
}

@Composable
private fun ParagraphText(
    p: TextParagraph,
    tokens: List<Token>,
    vm: ReaderViewModel,
    known: Map<String, KnownWord>,
    style: TextStyle,
    highlights: List<IntRange>,
    selected: IntRange?,
    anchor: PhraseAnchor?,
    onTap: (TextParagraph, Token) -> Unit,
    onLongPress: (TextParagraph, Token) -> Unit,
) {
    val text: AnnotatedString = remember(p, tokens, known, highlights, selected, anchor) {
        buildAnnotatedString {
            append(p.text)
            for (t in tokens) {
                if (!t.isWord) continue
                val k = known[vm.key(t.text)]
                val bg = wordBackground(p.start + t.start, p.start + t.end, k, highlights, selected, anchor)
                if (bg != null) addStyle(SpanStyle(background = bg), t.start, t.end)
            }
        }
    }
    var layout by remember { mutableStateOf<TextLayoutResult?>(null) }
    val tap by rememberUpdatedState(onTap)
    val longPress by rememberUpdatedState(onLongPress)
    fun tokenAt(pos: androidx.compose.ui.geometry.Offset): Token? {
        val l = layout ?: return null
        val line = l.getLineForVerticalPosition(pos.y)
        if (pos.x < l.getLineLeft(line) - 8 || pos.x > l.getLineRight(line) + 8) return null
        val off = l.getOffsetForPosition(pos)
        // The nearest cursor position can land on the next word's start; prefer the glyph actually under the finger.
        val len = l.layoutInput.text.length
        for (i in intArrayOf(off, off - 1)) {
            if (i in 0 until len && l.getBoundingBox(i).contains(pos)) {
                tokens.firstOrNull { it.isWord && i >= it.start && i < it.end }?.let { return it }
            }
        }
        return tokens.firstOrNull { it.isWord && off >= it.start && off < it.end }
            ?: tokens.firstOrNull { it.isWord && off - 1 >= it.start && off - 1 < it.end }
    }
    Text(
        text = text,
        style = style,
        onTextLayout = { layout = it },
        modifier = Modifier.fillMaxWidth().pointerInput(tokens) {
            detectTapGestures(
                onTap = { pos -> tokenAt(pos)?.let { tap(p, it) } },
                onLongPress = { pos -> tokenAt(pos)?.let { longPress(p, it) } },
            )
        },
    )
}

/** Paragraph with ruby (furigana/pinyin) above each word; tokens are laid out in a FlowRow. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RubyParagraph(
    p: TextParagraph,
    tokens: List<Token>,
    vm: ReaderViewModel,
    known: Map<String, KnownWord>,
    style: TextStyle,
    colors: ReaderColors,
    highlights: List<IntRange>,
    selected: IntRange?,
    anchor: PhraseAnchor?,
    onTap: (TextParagraph, Token) -> Unit,
    onLongPress: (TextParagraph, Token) -> Unit,
) {
    val rubyStyle = style.copy(fontSize = style.fontSize * 0.5f, lineHeight = style.fontSize * 0.6f, color = colors.secondary)
    val baseStyle = style.copy(lineHeight = style.fontSize * 1.25f)
    FlowRow(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (t in tokens) {
            if (t.text.contains('\n')) { Spacer(Modifier.fillMaxWidth()); continue }
            if (!t.isWord) {
                Column { Text(" ", style = rubyStyle); Text(t.text, style = baseStyle) }
                continue
            }
            val k = known[vm.key(t.text)]
            val bg = wordBackground(p.start + t.start, p.start + t.end, k, highlights, selected, anchor)
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .background(bg ?: Color.Transparent, RoundedCornerShape(4.dp))
                    .combinedClickable(onClick = { onTap(p, t) }, onLongClick = { onLongPress(p, t) }),
            ) {
                Text(vm.reading(t) ?: " ", style = rubyStyle, maxLines = 1)
                Text(t.text, style = baseStyle)
            }
        }
    }
}
