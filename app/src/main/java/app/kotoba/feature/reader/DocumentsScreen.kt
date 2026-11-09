package app.kotoba.feature.reader

import android.text.format.DateUtils
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Notes
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.UploadFile
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.kotoba.R
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.EmptyState
import app.kotoba.core.ui.components.KotobaTopBar
import app.kotoba.core.ui.components.rememberHaptics
import coil.compose.AsyncImage
import kotlinx.coroutines.launch
import java.io.File

private val bookMimeTypes = arrayOf(
    "application/epub+zip", "application/pdf", "text/plain", "text/html", "text/markdown", "text/x-markdown",
    "application/x-fictionbook+xml", "application/x-fictionbook", "application/zip",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream",
)
private val mangaArchiveMimeTypes = arrayOf(
    "application/zip", "application/x-cbz", "application/vnd.comicbook+zip", "application/pdf", "application/octet-stream",
)
private val notesMimeTypes = arrayOf(
    "text/plain", "text/markdown", "text/x-markdown", "text/html", "application/pdf",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "application/octet-stream",
)

@Composable
fun DocumentsScreen(
    onBack: () -> Unit,
    onOpen: (DocumentEntity) -> Unit,
    onGenerateDeck: (Long) -> Unit,
    vm: DocumentsViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val haptics = rememberHaptics()
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val pager = rememberPagerState(initialPage = vm.initialTab.ordinal) { DocumentsTab.entries.size }
    val tab = DocumentsTab.entries[pager.currentPage]
    val progress by vm.progress.collectAsStateWithLifecycle()
    var showAdd by remember { mutableStateOf(false) }
    var pasteKind by remember { mutableStateOf<DocumentKind?>(null) }

    val pickBook = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importUri(it) } }
    val pickNotes = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importUri(it, kindHint = DocumentKind.NOTEBOOKLM) } }
    val pickMangaArchive = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { vm.importUri(it, kindHint = DocumentKind.MANGA) } }
    val pickImages = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> vm.importImages(uris) }

    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            when (e) {
                is DocumentsEvent.Imported -> {
                    haptics.success()
                    val target = when (e.doc.kind) {
                        DocumentKind.MANGA -> DocumentsTab.MANGA
                        DocumentKind.NOTE, DocumentKind.NOTEBOOKLM -> DocumentsTab.NOTES
                        else -> DocumentsTab.BOOKS
                    }
                    launch { pager.animateScrollToPage(target.ordinal) }
                    val r = snackbar.showSnackbar(
                        context.getString(R.string.reader_imported, e.doc.title),
                        actionLabel = context.getString(R.string.reader_open),
                        withDismissAction = true,
                    )
                    if (r == SnackbarResult.ActionPerformed) onOpen(e.doc)
                }
                is DocumentsEvent.Failed -> {
                    haptics.error()
                    snackbar.showSnackbar(context.getString(R.string.reader_import_failed, e.message ?: ""), withDismissAction = true)
                }
            }
        }
    }

    Scaffold(
        topBar = { KotobaTopBar(stringResource(R.string.reader_documents_title), onBack) },
        snackbarHost = { SnackbarHost(snackbar) },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { showAdd = true },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text(stringResource(R.string.reader_add)) },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            PrimaryTabRow(selectedTabIndex = pager.currentPage) {
                DocumentsTab.entries.forEach { t ->
                    Tab(
                        selected = t == tab,
                        onClick = { scope.launch { pager.animateScrollToPage(t.ordinal) } },
                        text = { Text(stringResource(t.label())) },
                    )
                }
            }
            AnimatedVisibility(progress != null) {
                val p = progress
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val pct = p?.fraction?.let { " ${(it * 100).toInt()}%" }.orEmpty()
                        Text(stringResource(R.string.reader_importing) + pct, style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                        TextButton(onClick = vm::cancelImport) { Text(stringResource(R.string.action_cancel)) }
                    }
                    if (p?.fraction != null) LinearProgressIndicator(progress = { p.fraction }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(Modifier.fillMaxWidth())
                }
            }
            HorizontalPager(pager, Modifier.fillMaxSize()) { page ->
                val t = DocumentsTab.entries[page]
                val docs by vm.lists.getValue(t).collectAsStateWithLifecycle()
                DocumentList(
                    tab = t,
                    docs = docs,
                    onOpen = onOpen,
                    onGenerateDeck = onGenerateDeck,
                    onDelete = vm::delete,
                    onLanguage = vm::setLanguage,
                    onRename = vm::rename,
                    onAdd = { showAdd = true },
                )
            }
        }
    }

    if (showAdd) {
        ModalBottomSheet(onDismissRequest = { showAdd = false }, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
            Column(Modifier.padding(bottom = 24.dp)) {
                Text(
                    stringResource(R.string.reader_add_to, stringResource(tab.label())),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
                )
                fun pick(action: () -> Unit) { showAdd = false; action() }
                when (tab) {
                    DocumentsTab.BOOKS -> {
                        AddOption(Icons.Filled.UploadFile, R.string.reader_import_file, R.string.reader_import_file_desc) { pick { pickBook.launch(bookMimeTypes) } }
                        AddOption(Icons.Filled.ContentPaste, R.string.reader_paste_text, R.string.reader_paste_article_desc) { pick { pasteKind = DocumentKind.ARTICLE } }
                    }
                    DocumentsTab.MANGA -> {
                        AddOption(Icons.Filled.Collections, R.string.manga_import_images, R.string.manga_import_images_desc) { pick { pickImages.launch(arrayOf("image/*")) } }
                        AddOption(Icons.Filled.FolderZip, R.string.manga_import_archive, R.string.manga_import_archive_desc) { pick { pickMangaArchive.launch(mangaArchiveMimeTypes) } }
                    }
                    DocumentsTab.NOTES -> {
                        AddOption(Icons.Filled.AutoAwesome, R.string.reader_import_notebooklm, R.string.reader_import_notebooklm_desc) { pick { pickNotes.launch(notesMimeTypes) } }
                        AddOption(Icons.Filled.ContentPaste, R.string.reader_paste_notebooklm, R.string.reader_paste_notebooklm_desc) { pick { pasteKind = DocumentKind.NOTEBOOKLM } }
                        AddOption(Icons.AutoMirrored.Filled.Notes, R.string.reader_paste_text, R.string.reader_paste_note_desc) { pick { pasteKind = DocumentKind.NOTE } }
                    }
                }
            }
        }
    }

    pasteKind?.let { kind ->
        PasteTextDialog(
            initialKind = kind,
            onDismiss = { pasteKind = null },
            onConfirm = { title, text, k -> pasteKind = null; vm.importText(title, text, k) },
        )
    }
}

private fun DocumentsTab.label(): Int = when (this) {
    DocumentsTab.BOOKS -> R.string.reader_tab_books
    DocumentsTab.MANGA -> R.string.reader_tab_manga
    DocumentsTab.NOTES -> R.string.reader_tab_notes
}

@Composable
private fun AddOption(icon: ImageVector, title: Int, desc: Int, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(stringResource(title)) },
        supportingContent = { Text(stringResource(desc)) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        colors = ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        modifier = Modifier.clickable(onClick = onClick),
    )
}

@Composable
private fun DocumentList(
    tab: DocumentsTab,
    docs: List<DocumentEntity>?,
    onOpen: (DocumentEntity) -> Unit,
    onGenerateDeck: (Long) -> Unit,
    onDelete: (DocumentEntity) -> Unit,
    onLanguage: (DocumentEntity, StudyLanguage) -> Unit,
    onRename: (DocumentEntity, String) -> Unit,
    onAdd: () -> Unit,
) {
    if (docs == null) return
    if (docs.isEmpty()) {
        val (emoji, title, msg) = when (tab) {
            DocumentsTab.BOOKS -> Triple("📚", R.string.reader_empty_books_title, R.string.reader_empty_books)
            DocumentsTab.MANGA -> Triple("🎌", R.string.manga_empty_title, R.string.manga_empty)
            DocumentsTab.NOTES -> Triple("📝", R.string.reader_empty_notes_title, R.string.reader_empty_notes)
        }
        EmptyState(emoji, stringResource(title), stringResource(msg), Modifier.fillMaxSize().padding(24.dp)) {
            TextButton(onClick = onAdd) { Text(stringResource(R.string.reader_add)) }
        }
        return
    }
    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(docs, key = { it.id }) { doc ->
            DocumentRow(doc, onOpen, onGenerateDeck, onDelete, onLanguage, onRename, Modifier.animateItem())
        }
    }
}

@Composable
private fun DocumentRow(
    doc: DocumentEntity,
    onOpen: (DocumentEntity) -> Unit,
    onGenerateDeck: (Long) -> Unit,
    onDelete: (DocumentEntity) -> Unit,
    onLanguage: (DocumentEntity, StudyLanguage) -> Unit,
    onRename: (DocumentEntity, String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }
    var pickLanguage by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    val lang = StudyLanguage.fromCode(doc.language)
    Card(
        onClick = { onOpen(doc) },
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(width = 56.dp, height = 78.dp).clip(RoundedCornerShape(8.dp))
                    .background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                if (doc.coverPath.isNotEmpty()) {
                    AsyncImage(model = File(doc.coverPath), contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                } else {
                    Text(kindEmoji(doc.kind), style = MaterialTheme.typography.headlineSmall)
                }
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(doc.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (doc.author.isNotBlank()) Text(doc.author, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val meta = buildList {
                    add(lang.flag)
                    add(if (doc.kind == DocumentKind.MANGA) stringResource(R.string.manga_pages, doc.length) else doc.format.name)
                    add("${(doc.progress * 100).toInt()}%")
                }.joinToString("  ·  ")
                Text(meta, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                LinearProgressIndicator(
                    progress = { doc.progress },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                    drawStopIndicator = {},
                )
                Text(
                    doc.lastOpenedAt?.let { stringResource(R.string.reader_last_opened, DateUtils.getRelativeTimeSpanString(it).toString()) }
                        ?: stringResource(R.string.reader_not_opened),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, stringResource(R.string.reader_more)) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_generate_deck)) },
                        leadingIcon = { Icon(Icons.Filled.AutoAwesome, null) },
                        onClick = { menu = false; onGenerateDeck(doc.id) },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_language)) },
                        leadingIcon = { Icon(Icons.Filled.Language, null) },
                        onClick = { menu = false; pickLanguage = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.reader_rename)) },
                        leadingIcon = { Icon(Icons.Filled.Edit, null) },
                        onClick = { menu = false; renaming = true },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.action_delete)) },
                        leadingIcon = { Icon(Icons.Filled.Delete, null) },
                        onClick = { menu = false; confirmDelete = true },
                    )
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(R.string.reader_delete_title)) },
            text = { Text(stringResource(R.string.reader_delete_message, doc.title)) },
            confirmButton = { TextButton(onClick = { confirmDelete = false; onDelete(doc) }) { Text(stringResource(R.string.action_delete)) } },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    if (pickLanguage) {
        LanguagePickerDialog(lang, onDismiss = { pickLanguage = false }) { onLanguage(doc, it); pickLanguage = false }
    }
    if (renaming) {
        var title by remember { mutableStateOf(doc.title) }
        AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text(stringResource(R.string.reader_rename)) },
            text = { OutlinedTextField(title, { title = it }, singleLine = true, modifier = Modifier.fillMaxWidth()) },
            confirmButton = { TextButton(onClick = { renaming = false; onRename(doc, title) }) { Text(stringResource(R.string.action_save)) } },
            dismissButton = { TextButton(onClick = { renaming = false }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
fun LanguagePickerDialog(current: StudyLanguage, onDismiss: () -> Unit, onPick: (StudyLanguage) -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_language)) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                StudyLanguage.entries.forEach { l ->
                    ListItem(
                        headlineContent = { Text("${l.flag}  ${l.nativeName}") },
                        trailingContent = { if (l == current) Text("✓", color = MaterialTheme.colorScheme.primary) },
                        colors = ListItemDefaults.colors(containerColor = androidx.compose.ui.graphics.Color.Transparent),
                        modifier = Modifier.clickable { onPick(l) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_close)) } },
    )
}

private fun kindEmoji(kind: DocumentKind) = when (kind) {
    DocumentKind.BOOK -> "📖"
    DocumentKind.ARTICLE -> "📰"
    DocumentKind.MANGA -> "🎌"
    DocumentKind.NOTEBOOKLM -> "✨"
    DocumentKind.NOTE -> "📝"
}

@Composable
private fun PasteTextDialog(initialKind: DocumentKind, onDismiss: () -> Unit, onConfirm: (String, String, DocumentKind) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var text by rememberSaveable { mutableStateOf("") }
    var kind by rememberSaveable { mutableStateOf(initialKind) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.reader_paste_text)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        DocumentKind.ARTICLE to R.string.reader_kind_article,
                        DocumentKind.NOTE to R.string.reader_kind_note,
                        DocumentKind.NOTEBOOKLM to R.string.reader_kind_notebooklm,
                    ).forEach { (k, label) ->
                        FilterChip(selected = kind == k, onClick = { kind = k }, label = { Text(stringResource(label)) })
                    }
                }
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.reader_title_optional)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                OutlinedTextField(
                    text, { text = it },
                    label = { Text(stringResource(R.string.reader_text)) },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 160.dp, max = 320.dp),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(title, text, kind) }, enabled = text.isNotBlank()) { Text(stringResource(R.string.action_import)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
    )
}
