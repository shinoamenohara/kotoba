package app.kotoba.feature.manga

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DocumentScanner
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.rememberHaptics
import app.kotoba.feature.reader.LanguagePickerDialog
import app.kotoba.feature.reader.MangaDirection
import app.kotoba.feature.reader.WordLookupContent
import coil.compose.AsyncImage
import kotlinx.coroutines.flow.distinctUntilChanged

@Composable
fun MangaReaderScreen(
    onBack: () -> Unit,
    onExplainWithAi: (String) -> Unit,
    vm: MangaReaderViewModel = hiltViewModel(),
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val prefs by vm.prefs.collectAsStateWithLifecycle()
    val ocr by vm.ocr.collectAsStateWithLifecycle()
    val recognizing by vm.recognizing.collectAsStateWithLifecycle()
    val selection by vm.selection.collectAsStateWithLifecycle()
    val picks by vm.picks.collectAsStateWithLifecycle()
    val adding by vm.adding.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val snackbar = remember { SnackbarHostState() }
    var menu by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var chromeVisible by remember { mutableStateOf(true) }

    LifecycleResumeEffect(Unit) {
        vm.onResume()
        onPauseOrDispose { vm.onPause() }
    }
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                MangaEvent.AchievementUnlocked -> {
                    haptics.success()
                    snackbar.showSnackbar(context.getString(R.string.reader_achievement, context.getString(R.string.achievement_first_manga_title)))
                }
                is MangaEvent.Message -> snackbar.showSnackbar(
                    if (e.arg != null) context.getString(e.res, e.arg) else context.getString(e.res),
                )
            }
        }
    }

    if (state.loading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    val pagerState = rememberPagerState(initialPage = state.initialPage) { state.pages.size }
    LaunchedEffect(pagerState) {
        snapshotFlow { pagerState.currentPage }.distinctUntilChanged().collect { vm.onPageShown(it) }
    }
    val current = pagerState.currentPage

    Scaffold(
        containerColor = Color.Black,
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                KotobaTopBar(
                    title = state.doc?.title.orEmpty(),
                    onBack = onBack,
                    actions = {
                        if (current in recognizing) {
                            CircularProgressIndicator(Modifier.size(24.dp).padding(2.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(12.dp))
                        } else {
                            IconButton(onClick = { haptics.tick(); vm.recognize(current) }) {
                                Icon(Icons.Filled.DocumentScanner, stringResource(R.string.manga_recognize))
                            }
                        }
                        IconButton(onClick = { vm.setShowBoxes(!prefs.showOcrBoxes) }) {
                            Icon(
                                if (prefs.showOcrBoxes) Icons.Filled.Visibility else Icons.Filled.VisibilityOff,
                                stringResource(R.string.manga_show_boxes),
                            )
                        }
                        Box {
                            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.reader_more)) }
                            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                                Text(
                                    stringResource(R.string.manga_direction),
                                    style = MaterialTheme.typography.labelMedium,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                                )
                                SingleChoiceSegmentedButtonRow(Modifier.padding(horizontal = 12.dp)) {
                                    MangaDirection.entries.forEachIndexed { i, d ->
                                        SegmentedButton(
                                            selected = prefs.mangaDirection == d,
                                            onClick = { vm.setDirection(d) },
                                            shape = SegmentedButtonDefaults.itemShape(i, MangaDirection.entries.size),
                                        ) {
                                            Text(
                                                stringResource(
                                                    when (d) {
                                                        MangaDirection.AUTO -> R.string.reader_theme_auto
                                                        MangaDirection.RTL -> R.string.manga_rtl
                                                        MangaDirection.LTR -> R.string.manga_ltr
                                                    },
                                                ),
                                            )
                                        }
                                    }
                                }
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.manga_ocr_language, state.language.flag + " " + state.language.nativeName)) },
                                    onClick = { menu = false; pickLanguage = true },
                                )
                            }
                        }
                    },
                )
            }
        },
    ) { padding ->
        if (state.pages.isEmpty()) {
            EmptyState("🎌", stringResource(R.string.manga_empty_title), stringResource(R.string.manga_no_pages), Modifier.fillMaxSize().padding(padding).padding(24.dp))
            return@Scaffold
        }
        var zoomed by remember { mutableStateOf(false) }
        Box(Modifier.fillMaxSize().padding(padding)) {
            HorizontalPager(
                state = pagerState,
                reverseLayout = vm.isRtl(prefs),
                userScrollEnabled = !zoomed,
                beyondViewportPageCount = 1,
                key = { it },
                modifier = Modifier.fillMaxSize(),
            ) { index ->
                ZoomablePage(
                    onZoomChanged = { if (index == pagerState.currentPage) zoomed = it },
                    onTap = { chromeVisible = !chromeVisible },
                ) {
                    AsyncImage(
                        model = state.pages[index],
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                    val pageOcr = ocr[index]
                    if (pageOcr != null && prefs.showOcrBoxes) {
                        BlockOverlay(
                            page = pageOcr,
                            selectedId = selection?.takeIf { it.page == index }?.block?.id,
                            pickedIds = picks.filter { it.page == index }.map { it.blockId }.toSet(),
                            onBlock = { b -> haptics.tick(); vm.openBlock(index, b) },
                        )
                    }
                }
            }
            Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                AnimatedVisibility(picks.isNotEmpty(), enter = slideInVertically { it }, exit = slideOutVertically { it }) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                    ) {
                        Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.manga_picked, picks.size) + "\n" + picks.joinToString("、") { it.term },
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                modifier = Modifier.weight(1f),
                            )
                            TextButton(onClick = vm::clearPicks) { Text(stringResource(R.string.action_cancel)) }
                            Button(onClick = { haptics.success(); vm.addPicks() }, enabled = !adding) {
                                Icon(Icons.Filled.LibraryAdd, null)
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(R.string.action_add))
                            }
                        }
                    }
                }
                AnimatedVisibility(chromeVisible, enter = fadeIn(), exit = fadeOut()) {
                    Row(
                        Modifier.fillMaxWidth().background(Color.Black.copy(alpha = 0.55f)).padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text("${current + 1} / ${state.pages.size}", color = Color.White, style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        val blocks = ocr[current]?.blocks?.size
                        Text(
                            when {
                                current in recognizing -> stringResource(R.string.manga_recognizing)
                                blocks == null -> stringResource(R.string.manga_tap_recognize)
                                else -> stringResource(R.string.manga_blocks, blocks)
                            },
                            color = Color.White.copy(alpha = 0.8f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                }
            }
        }
    }

    selection?.let { sel ->
        BlockSheet(vm = vm, sel = sel, picks = picks, language = state.language, onExplainWithAi = onExplainWithAi)
    }
    if (pickLanguage) {
        LanguagePickerDialog(state.language, onDismiss = { pickLanguage = false }) { vm.setLanguage(it); pickLanguage = false }
    }
}

/** Pinch-zoom / pan container; single-finger drags pass through to the pager unless zoomed. */
@Composable
private fun ZoomablePage(
    onZoomChanged: (Boolean) -> Unit,
    onTap: () -> Unit,
    content: @Composable () -> Unit,
) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var size by remember { mutableStateOf(IntSize.Zero) }
    val animatedScale by animateFloatAsState(scale, label = "zoom")

    fun clamp(o: Offset, s: Float): Offset {
        val maxX = size.width * (s - 1) / 2
        val maxY = size.height * (s - 1) / 2
        return Offset(o.x.coerceIn(-maxX, maxX), o.y.coerceIn(-maxY, maxY))
    }

    Box(
        Modifier
            .fillMaxSize()
            .onSizeChanged { size = it }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = { tap ->
                        if (scale > 1f) { scale = 1f; offset = Offset.Zero } else {
                            scale = 2.5f
                            val center = Offset(size.width / 2f, size.height / 2f)
                            offset = clamp((center - tap) * 1.5f, 2.5f)
                        }
                        onZoomChanged(scale > 1f)
                    },
                )
            }
            .pointerInput(Unit) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val multi = event.changes.count { it.pressed } > 1
                        if (multi || scale > 1f) {
                            val newScale = (scale * event.calculateZoom()).coerceIn(1f, 5f)
                            offset = clamp(offset + event.calculatePan(), newScale)
                            if (newScale != scale) { scale = newScale; onZoomChanged(scale > 1.01f) }
                            if (scale <= 1.01f) offset = Offset.Zero
                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                }
            },
    ) {
        Box(
            Modifier.fillMaxSize().graphicsLayer {
                scaleX = if (scale == 1f) animatedScale else scale
                scaleY = if (scale == 1f) animatedScale else scale
                translationX = offset.x
                translationY = offset.y
            },
        ) { content() }
    }
}

/** Translucent tappable boxes over recognized text, mapped from image pixels to the fitted image. */
@Composable
private fun BlockOverlay(page: OcrPage, selectedId: Int?, pickedIds: Set<Int>, onBlock: (OcrBlock) -> Unit) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val w = with(density) { maxWidth.toPx() }
        val h = with(density) { maxHeight.toPx() }
        if (page.width <= 0 || page.height <= 0) return@BoxWithConstraints
        val s = minOf(w / page.width, h / page.height)
        val dx = (w - page.width * s) / 2
        val dy = (h - page.height * s) / 2
        val primary = MaterialTheme.colorScheme.primary
        page.blocks.forEach { b ->
            val selected = b.id == selectedId
            val color = when {
                selected -> primary
                b.id in pickedIds -> Color(0xFF10B981)
                else -> Color(0xFF3B82F6)
            }
            with(density) {
                Box(
                    Modifier
                        .offset((dx + b.box.l * s - 2).toDp(), (dy + b.box.t * s - 2).toDp())
                        .size((b.box.width * s + 4).toDp(), (b.box.height * s + 4).toDp())
                        .background(color.copy(alpha = if (selected) 0.32f else 0.16f), RoundedCornerShape(6.dp))
                        .border(1.5.dp, color.copy(alpha = 0.75f), RoundedCornerShape(6.dp))
                        .clickable { onBlock(b) },
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BlockSheet(
    vm: MangaReaderViewModel,
    sel: BlockSelection,
    picks: List<PickedWord>,
    language: StudyLanguage,
    onExplainWithAi: (String) -> Unit,
) {
    val lookup by vm.lookup.collectAsStateWithLifecycle()
    val haptics = rememberHaptics()
    var editing by remember(sel.block.id, sel.page) { mutableStateOf(false) }
    var draft by remember(sel.block.id, sel.page, sel.block.text) { mutableStateOf(sel.block.text) }
    val tokens = remember(sel.block.text, language) { vm.tokens(sel.block.text) }
    val pickedTerms = picks.filter { it.page == sel.page && it.blockId == sel.block.id }.map { it.term }.toSet()

    ModalBottomSheet(onDismissRequest = vm::closeBlock, sheetState = rememberModalBottomSheetState()) {
        Column(
            Modifier.padding(horizontal = 20.dp).padding(bottom = 28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (editing) {
                OutlinedTextField(
                    draft, { draft = it },
                    label = { Text(stringResource(R.string.manga_edit_text)) },
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { vm.saveBlockText(draft); editing = false }, enabled = draft.isNotBlank()) { Text(stringResource(R.string.action_save)) }
                    TextButton(onClick = { draft = sel.block.text; editing = false }) { Text(stringResource(R.string.action_cancel)) }
                }
            } else {
                Row(verticalAlignment = Alignment.Top) {
                    Text(sel.block.text, style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f).clickable { editing = true })
                    app.kotoba.core.ui.components.SpeakButton(onClick = { vm.speak(sel.block.text) })
                }
                if (sel.block.edited) Text(stringResource(R.string.manga_edited), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = vm::translateBlock, enabled = !sel.translating) {
                    Icon(Icons.Filled.Translate, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.manga_translate_line))
                }
                if (!editing) TextButton(onClick = { editing = true }) { Text(stringResource(R.string.manga_fix_text)) }
            }
            if (sel.translating) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            sel.translation?.let {
                Text(it, style = MaterialTheme.typography.bodyLarge, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.primary)
            }
            Text(stringResource(R.string.manga_words_hint), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                tokens.forEach { t ->
                    val picked = t.text in pickedTerms
                    val active = lookup?.term == t.text
                    val shape = RoundedCornerShape(10.dp)
                    Surface(
                        shape = shape,
                        color = when {
                            picked -> Color(0xFF10B981).copy(alpha = 0.25f)
                            active -> MaterialTheme.colorScheme.primaryContainer
                            else -> MaterialTheme.colorScheme.surfaceContainerHighest
                        },
                        modifier = Modifier.clip(shape).combinedClickable(
                            onClick = { haptics.tick(); vm.lookupWord(t.text) },
                            onLongClick = { haptics.tick(); vm.togglePick(t.text) },
                        ),
                    ) {
                        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (picked) { Icon(Icons.Filled.Check, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                            Text(t.text, style = MaterialTheme.typography.titleMedium)
                        }
                    }
                }
            }
            lookup?.let { l ->
                Surface(color = MaterialTheme.colorScheme.surfaceContainerHigh, shape = RoundedCornerShape(16.dp)) {
                    val prompt = stringResource(R.string.reader_ai_prompt, l.term, l.sentence, language.nativeName)
                    WordLookupContent(
                        state = l,
                        language = language,
                        onSpeak = { vm.speak(l.term) },
                        onAdd = { haptics.success(); vm.addWord() },
                        onAddContext = { haptics.success(); vm.addContext() },
                        onExplain = { vm.closeBlock(); onExplainWithAi(prompt) },
                        modifier = Modifier.padding(16.dp),
                        extraActions = {
                            val picked = l.term in pickedTerms
                            TextButton(onClick = { vm.togglePick(l.term) }) {
                                Text(stringResource(if (picked) R.string.manga_unpick else R.string.manga_pick))
                            }
                        },
                    )
                }
            }
        }
    }
}
