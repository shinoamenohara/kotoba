package app.kotoba.feature.settings

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.kotoba.core.db.DocumentDao
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.db.KotobaDatabase
import com.google.mlkit.common.model.RemoteModelManager
import com.google.mlkit.nl.translate.TranslateRemoteModel
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

data class StoredDocument(val doc: DocumentEntity, val bytes: Long)

data class StorageState(
    val documentsBytes: Long = 0,
    val mediaBytes: Long = 0,
    val cacheBytes: Long = 0,
    val databaseBytes: Long = 0,
    val models: List<String>? = null,
    val modelsError: Boolean = false,
    val busy: Boolean = false,
) {
    val total: Long get() = documentsBytes + mediaBytes + cacheBytes + databaseBytes
}

@HiltViewModel
class StorageViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val documentDao: DocumentDao,
) : ViewModel() {
    private val _state = MutableStateFlow(StorageState())
    val state: StateFlow<StorageState> = _state.asStateFlow()

    private val documentsRoot get() = File(context.filesDir, "documents")

    val documents: StateFlow<List<StoredDocument>> = documentDao.observeAll()
        .map { list -> withContext(Dispatchers.IO) { list.map { StoredDocument(it, sizeOf(it)) }.sortedByDescending { it.bytes } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    init {
        refresh()
        loadModels()
    }

    fun refresh() = viewModelScope.launch {
        val sizes = withContext(Dispatchers.IO) {
            StorageState(
                documentsBytes = dirSize(documentsRoot),
                mediaBytes = dirSize(File(context.filesDir, "media")),
                cacheBytes = dirSize(context.cacheDir),
                databaseBytes = context.getDatabasePath(KotobaDatabase.NAME).parentFile?.let(::dirSize) ?: 0,
            )
        }
        _state.update { sizes.copy(models = it.models, modelsError = it.modelsError, busy = it.busy) }
    }

    private fun documentDir(doc: DocumentEntity): File? {
        val parent = File(doc.filePath).parentFile ?: return null
        val root = documentsRoot.canonicalPath
        return parent.takeIf { it.canonicalPath.startsWith(root + File.separator) }
    }

    private fun sizeOf(doc: DocumentEntity): Long {
        documentDir(doc)?.let { return dirSize(it) }
        val files = listOf(doc.filePath, doc.textPath, doc.coverPath).filter { it.isNotBlank() }.map(::File)
        return files.sumOf { if (it.exists()) it.length() else 0L }.takeIf { it > 0 } ?: doc.sizeBytes
    }

    fun deleteDocument(doc: DocumentEntity) = viewModelScope.launch {
        withContext(Dispatchers.IO) {
            documentDao.delete(doc)
            val dir = documentDir(doc)
            if (dir != null) dir.deleteRecursively()
            else listOf(doc.filePath, doc.textPath, doc.coverPath).filter { it.isNotBlank() }.forEach { File(it).delete() }
        }
        refresh()
    }

    fun clearCache() = viewModelScope.launch {
        withContext(Dispatchers.IO) { context.cacheDir.listFiles()?.forEach { it.deleteRecursively() } }
        refresh()
    }

    fun loadModels() = viewModelScope.launch {
        runCatching {
            RemoteModelManager.getInstance().getDownloadedModels(TranslateRemoteModel::class.java).await()
                .map { it.language }.sorted()
        }.onSuccess { list -> _state.update { it.copy(models = list, modelsError = false) } }
            .onFailure { _state.update { it.copy(models = emptyList(), modelsError = true) } }
    }

    fun deleteModel(code: String) = viewModelScope.launch {
        _state.update { it.copy(busy = true) }
        runCatching {
            RemoteModelManager.getInstance().deleteDownloadedModel(TranslateRemoteModel.Builder(code).build()).await()
        }
        _state.update { it.copy(busy = false) }
        loadModels()
    }

    companion object {
        fun dirSize(f: File): Long = if (!f.exists()) 0 else f.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
