package app.kotoba.feature.settings

import android.text.format.Formatter
import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.GTranslate
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.PermMedia
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import app.kotoba.core.ui.components.KotobaTopBar
import java.util.Locale

@Composable
fun StorageScreen(onBack: () -> Unit, vm: StorageViewModel = hiltViewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val docs by vm.documents.collectAsStateWithLifecycle()
    val context = LocalContext.current
    fun size(b: Long) = Formatter.formatShortFileSize(context, b)
    var confirmDelete by remember { mutableStateOf<DocumentEntity?>(null) }
    var confirmModel by remember { mutableStateOf<String?>(null) }

    Scaffold(topBar = { KotobaTopBar(stringResource(R.string.settings_storage), onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 32.dp)) {
            item {
                Card(
                    Modifier.fillMaxWidth().padding(16.dp),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                ) {
                    Column(Modifier.padding(20.dp)) {
                        Text(stringResource(R.string.settings_storage_used), style = MaterialTheme.typography.labelLarge)
                        Text(size(state.total), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(12.dp))
                        val parts = listOf(
                            state.documentsBytes to MaterialTheme.colorScheme.primary,
                            state.mediaBytes to MaterialTheme.colorScheme.tertiary,
                            state.databaseBytes to MaterialTheme.colorScheme.secondary,
                            state.cacheBytes to MaterialTheme.colorScheme.outline,
                        )
                        UsageBar(parts, state.total)
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_storage_breakdown)) {
                    PrefRow(stringResource(R.string.settings_storage_documents), Icons.AutoMirrored.Filled.MenuBook, size(state.documentsBytes))
                    SettingsDivider()
                    PrefRow(stringResource(R.string.settings_storage_media), Icons.Default.PermMedia, size(state.mediaBytes))
                    SettingsDivider()
                    PrefRow(stringResource(R.string.settings_storage_database), Icons.Default.Storage, size(state.databaseBytes))
                    SettingsDivider()
                    PrefRow(stringResource(R.string.settings_storage_cache), Icons.Default.CleaningServices, size(state.cacheBytes)) {
                        TextButton(onClick = vm::clearCache, enabled = state.cacheBytes > 0) { Text(stringResource(R.string.settings_storage_clear)) }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_storage_documents)) {
                    if (docs.isEmpty()) {
                        Text(
                            stringResource(R.string.settings_storage_no_documents), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                    }
                    Column(Modifier.animateContentSize()) {
                        docs.forEachIndexed { i, d ->
                            if (i > 0) SettingsDivider()
                            PrefRow(
                                d.doc.title, if (d.doc.kind == DocumentKind.MANGA) Icons.Default.Image else Icons.AutoMirrored.Filled.MenuBook,
                                subtitle = "${d.doc.format.name} · ${size(d.bytes)}",
                            ) {
                                IconButton(onClick = { confirmDelete = d.doc }) {
                                    Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                }
                            }
                        }
                    }
                }
            }
            item {
                SettingsGroup(stringResource(R.string.settings_storage_models)) {
                    val models = state.models
                    when {
                        models == null -> Text("…", modifier = Modifier.padding(16.dp))
                        models.isEmpty() -> Text(
                            stringResource(if (state.modelsError) R.string.settings_storage_models_error else R.string.settings_storage_no_models),
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(16.dp),
                        )
                        else -> models.forEachIndexed { i, code ->
                            if (i > 0) SettingsDivider()
                            PrefRow(Locale.forLanguageTag(code).let { it.getDisplayName(it).replaceFirstChar { c -> c.titlecase(it) } }, Icons.Default.GTranslate, code) {
                                // The English model is built in and can't be removed.
                                if (code != "en") {
                                    IconButton(onClick = { confirmModel = code }, enabled = !state.busy) {
                                        Icon(Icons.Default.Delete, stringResource(R.string.action_delete), tint = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                    Text(
                        stringResource(R.string.settings_storage_models_desc), style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    )
                }
            }
        }
    }

    confirmDelete?.let { doc ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text(stringResource(R.string.settings_storage_delete_title)) },
            text = { Text(stringResource(R.string.settings_storage_delete_body, doc.title), maxLines = 6, overflow = TextOverflow.Ellipsis) },
            confirmButton = {
                TextButton(onClick = { vm.deleteDocument(doc); confirmDelete = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
    confirmModel?.let { code ->
        AlertDialog(
            onDismissRequest = { confirmModel = null },
            title = { Text(stringResource(R.string.settings_storage_delete_model, Locale.forLanguageTag(code).displayName)) },
            confirmButton = {
                TextButton(onClick = { vm.deleteModel(code); confirmModel = null }) { Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { confirmModel = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun UsageBar(parts: List<Pair<Long, Color>>, total: Long) {
    Row(Modifier.fillMaxWidth().height(10.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.5f))) {
        if (total > 0) parts.filter { it.first > 0 }.forEach { (bytes, color) ->
            Box(Modifier.fillMaxHeight().weight(bytes.toFloat() / total).background(color))
        }
    }
    Spacer(Modifier.height(8.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        listOf(R.string.settings_storage_documents, R.string.settings_storage_media, R.string.settings_storage_database, R.string.settings_storage_cache)
            .zip(parts).forEach { (label, part) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(8.dp).clip(CircleShape).background(part.second))
                    Spacer(Modifier.width(4.dp))
                    Text(stringResource(label), style = MaterialTheme.typography.labelSmall)
                }
            }
    }
}
