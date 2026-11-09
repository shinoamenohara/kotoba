package app.kotoba.feature.importexport.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.room.withTransaction
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.KotobaDatabase
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.inject.Inject
import javax.inject.Singleton

data class BackupSummary(val createdAt: Long, val cards: Int, val decks: Int, val documents: Int, val hasDocuments: Boolean)

/** Zip backups: `backup.json` (all tables + schema version) and optionally the `files/documents` and `files/media` trees. */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KotobaDatabase,
    private val time: TimeProvider,
    private val json: Json,
) {
    private val filesDir get() = context.filesDir
    private val docTables = setOf("documents", "bookmarks")

    fun defaultFileName(): String = "kotoba-backup-${stamp()}.zip"

    private fun stamp() = SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(Date(time.now()))

    suspend fun backupTo(uri: Uri, includeDocuments: Boolean, includeMedia: Boolean) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write backup")
        out.use { write(it, includeDocuments, includeMedia) }
    }

    fun write(out: OutputStream, includeDocuments: Boolean, includeMedia: Boolean) {
        val sdb = db.openHelper.readableDatabase
        val root = BackupCodec.dump(
            sdb, time.now(),
            exclude = if (includeDocuments) emptySet() else docTables,
            filesDir = filesDir.absolutePath,
            meta = mapOf("includesDocuments" to includeDocuments, "includesMedia" to includeMedia),
        )
        ZipOutputStream(out.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry(JSON_ENTRY))
            zip.write(root.toString().toByteArray())
            zip.closeEntry()
            if (includeDocuments) addDir(zip, File(filesDir, "documents"), "files/documents/")
            if (includeMedia) addDir(zip, File(filesDir, "media"), "files/media/")
        }
    }

    private fun addDir(zip: ZipOutputStream, dir: File, prefix: String) {
        if (!dir.exists()) return
        dir.walkTopDown().filter { it.isFile }.forEach { f ->
            zip.putNextEntry(ZipEntry(prefix + f.relativeTo(dir).invariantSeparatorsPath))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
    }

    suspend fun summary(uri: Uri): BackupSummary = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
        input.use { readJson(it) }.let { root ->
            val h = BackupCodec.header(root)
            BackupSummary(
                createdAt = h.createdAt, cards = h.counts["cards"] ?: 0, decks = h.counts["decks"] ?: 0,
                documents = h.counts["documents"] ?: 0, hasDocuments = "documents" in h.counts,
            )
        }
    }

    private fun readJson(input: InputStream): JsonObject {
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.name == JSON_ENTRY) return json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
            }
        }
        throw IOException("backup.json not found")
    }

    /** Replaces local data with the backup in one transaction, then swaps in the backed-up files. */
    suspend fun restore(uri: Uri) = withContext(Dispatchers.IO) {
        val input = context.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open backup")
        input.use { restore(it) }
    }

    suspend fun restore(input: InputStream) {
        val staging = File(context.cacheDir, "restore-${System.currentTimeMillis()}")
        try {
            var root: JsonObject? = null
            val stagingRoot = staging.canonicalPath + File.separator
            ZipInputStream(input.buffered()).use { zip ->
                while (true) {
                    val e = zip.nextEntry ?: break
                    when {
                        e.isDirectory -> Unit
                        e.name == JSON_ENTRY -> root = json.parseToJsonElement(zip.readBytes().decodeToString()).jsonObject
                        e.name.startsWith("files/") -> {
                            val out = File(staging, e.name)
                            if (!out.canonicalPath.startsWith(stagingRoot)) continue
                            out.parentFile?.mkdirs()
                            out.outputStream().use { zip.copyTo(it) }
                        }
                    }
                }
            }
            val data = root ?: throw IOException("backup.json not found")
            BackupCodec.header(data)
            db.withTransaction { BackupCodec.restore(db.openHelper.writableDatabase, data, filesDir.absolutePath) }
            val includesDocs = (data["includesDocuments"] as? JsonPrimitive)?.booleanOrNull == true
            val stagedDocs = File(staging, "files/documents")
            if (includesDocs) {
                File(filesDir, "documents").deleteRecursively()
                if (stagedDocs.exists()) stagedDocs.copyRecursively(File(filesDir, "documents"), overwrite = true)
            }
            val stagedMedia = File(staging, "files/media")
            if (stagedMedia.exists()) stagedMedia.copyRecursively(File(filesDir, "media"), overwrite = true)
        } finally {
            staging.deleteRecursively()
        }
    }

    // --- Folder (SAF tree) backups: a cloud-backed folder doubles as cross-device sync. ---

    private data class Child(val uri: Uri, val name: String)

    private fun children(tree: Uri): List<Child> {
        val treeId = DocumentsContract.getTreeDocumentId(tree)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, treeId)
        val projection = arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME)
        return context.contentResolver.query(childrenUri, projection, null, null, null)?.use { c ->
            buildList {
                while (c.moveToNext()) add(Child(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)), c.getString(1).orEmpty()))
            }
        }.orEmpty()
    }

    private fun createIn(tree: Uri, name: String): Uri {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        return DocumentsContract.createDocument(context.contentResolver, parent, "application/zip", name)
            ?: throw IOException("Cannot create $name")
    }

    fun folderName(tree: Uri): String = runCatching {
        val doc = DocumentsContract.buildDocumentUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: tree.lastPathSegment.orEmpty().substringAfterLast(':')

    /** Writes `kotoba-backup-latest.zip` plus a dated copy, keeping the newest [KEEP_DATED] dated copies. */
    suspend fun backupToFolder(tree: Uri) = withContext(Dispatchers.IO) {
        val temp = File(context.cacheDir, "auto-backup.zip")
        try {
            temp.outputStream().use { write(it, includeDocuments = false, includeMedia = true) }
            val existing = children(tree)
            val latest = existing.firstOrNull { it.name == LATEST } ?.uri ?: createIn(tree, LATEST)
            copyInto(temp, latest)
            copyInto(temp, createIn(tree, "$PREFIX${stamp()}.zip"))
            children(tree).filter { it.name.startsWith(PREFIX) && it.name != LATEST && it.name.getOrNull(PREFIX.length)?.isDigit() == true }
                .sortedByDescending { it.name }
                .drop(KEEP_DATED)
                .forEach { runCatching { DocumentsContract.deleteDocument(context.contentResolver, it.uri) } }
        } finally {
            temp.delete()
        }
    }

    private fun copyInto(file: File, target: Uri) {
        val out = context.contentResolver.openOutputStream(target, "wt") ?: throw IOException("Cannot write $target")
        out.use { o -> file.inputStream().use { it.copyTo(o) } }
    }

    /** Most recent backup in the folder (the "latest" file, else the newest dated copy). */
    suspend fun latestInFolder(tree: Uri): Uri? = withContext(Dispatchers.IO) {
        val list = children(tree)
        list.firstOrNull { it.name == LATEST }?.uri
            ?: list.filter { it.name.startsWith(PREFIX) && it.name.endsWith(".zip") }.maxByOrNull { it.name }?.uri
    }

    companion object {
        const val JSON_ENTRY = "backup.json"
        const val PREFIX = "kotoba-backup-"
        const val LATEST = "kotoba-backup-latest.zip"
        const val KEEP_DATED = 5
    }
}
