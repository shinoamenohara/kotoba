package app.kotoba.core.docs

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.DocumentDao
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.model.DocumentFormat
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.StudyLanguage
import com.tom_roush.pdfbox.android.PDFBoxResourceLoader
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import java.util.zip.ZipFile
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.coroutineContext

/**
 * Imported books, articles, notes and manga. Each document owns `filesDir/documents/<uuid>/`:
 * `original.<ext>` (copied source), `text.txt` (extracted paragraphs), `cover.<ext>`,
 * and for manga `pages/0001.png…` plus per-page OCR caches written by the manga reader.
 */
@Singleton
class DocumentRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val dao: DocumentDao,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
) {
    private val root: File get() = File(context.filesDir, "documents").apply { mkdirs() }
    @Volatile private var pdfBoxReady = false

    fun observe(kinds: List<DocumentKind>): Flow<List<DocumentEntity>> = dao.observeByKinds(kinds)
    fun observe(id: Long): Flow<DocumentEntity?> = dao.observe(id)
    suspend fun get(id: Long): DocumentEntity? = dao.get(id)

    /**
     * Copies [uri] into app storage and imports it. Text formats are parsed (kind = [kindHint] or
     * BOOK/ARTICLE by format); images, CBZ/ZIP — and PDF when [kindHint] is MANGA — become manga.
     * @param onProgress 0..1
     * @return the new document id
     * @throws DocumentParseException for unsupported or empty files
     */
    suspend fun importFromUri(
        uri: Uri,
        displayName: String? = null,
        kindHint: DocumentKind? = null,
        onProgress: (Float) -> Unit = {},
    ): Long = withContext(Dispatchers.IO) {
        val meta = queryMeta(uri)
        val name = displayName?.takeIf { it.isNotBlank() } ?: meta.first ?: uri.lastPathSegment ?: "document"
        val mime = context.contentResolver.getType(uri)
        val format = DocumentParser.formatOf(name, mime) ?: throw DocumentParseException("Unsupported file type: $name")
        val asManga = format == DocumentFormat.IMAGES || format == DocumentFormat.CBZ ||
            (format == DocumentFormat.PDF && kindHint == DocumentKind.MANGA)
        if (asManga) {
            return@withContext if (format == DocumentFormat.IMAGES) importMangaImages(listOf(uri), DocumentParser.titleFromFileName(name), onProgress)
            else importMangaArchive(uri, name, format, onProgress)
        }

        val dir = newDocumentDir()
        try {
            val ext = if (name.lowercase().endsWith(".fb2.zip")) "fb2.zip" else FileKindsExt.of(name, format)
            val original = File(dir, "original.$ext")
            copy(uri, original, meta.second) { onProgress(it * 0.4f) }
            if (format == DocumentFormat.PDF) ensurePdfBox()
            val parsed = DocumentParser.parse(original, format, name)
            onProgress(0.85f)
            val textFile = File(dir, TEXT_FILE).apply { writeText(parsed.text) }
            val cover = parsed.cover?.let { bytes -> File(dir, "cover.${parsed.coverExtension}").apply { writeBytes(bytes) } }
            val kind = kindHint ?: if (format == DocumentFormat.HTML || format == DocumentFormat.MARKDOWN) DocumentKind.ARTICLE else DocumentKind.BOOK
            val id = dao.insert(
                DocumentEntity(
                    title = parsed.title.take(200),
                    author = parsed.author.take(200),
                    kind = kind,
                    format = format,
                    language = detectLanguage(parsed.text).code,
                    filePath = original.absolutePath,
                    textPath = textFile.absolutePath,
                    coverPath = cover?.absolutePath.orEmpty(),
                    sizeBytes = dir.sizeOnDisk(),
                    length = parsed.text.length,
                    addedAt = time.now(),
                ),
            )
            onProgress(1f)
            id
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Pasted text, articles and NotebookLM notes (text is normalized into paragraphs). */
    suspend fun importText(
        title: String,
        text: String,
        kind: DocumentKind = DocumentKind.NOTE,
        language: StudyLanguage? = null,
    ): Long = withContext(Dispatchers.IO) {
        val looksMarkdown = Regex("(?m)^(#{1,6} |[-*] |\\d+\\. |> )").containsMatchIn(text)
        val md = if (looksMarkdown) MarkdownParser.parse(text, "") else null
        val body = (md?.text ?: DocumentParser.joinParagraphs(TxtParser.paragraphs(text)))
            .ifBlank { throw DocumentParseException("Text is empty") }
        val autoTitle = md?.title?.takeIf { it.isNotBlank() } ?: body.lineSequence().first().take(60)
        val dir = newDocumentDir()
        val textFile = File(dir, TEXT_FILE).apply { writeText(body) }
        dao.insert(
            DocumentEntity(
                title = title.trim().ifEmpty { autoTitle }.take(200),
                kind = kind,
                format = if (looksMarkdown) DocumentFormat.MARKDOWN else DocumentFormat.TXT,
                language = (language ?: detectLanguage(body)).code,
                filePath = textFile.absolutePath,
                textPath = textFile.absolutePath,
                sizeBytes = textFile.length(),
                length = body.length,
                addedAt = time.now(),
            ),
        )
    }

    /** Manga from individual images (sorted naturally by file name). */
    suspend fun importMangaImages(uris: List<Uri>, title: String? = null, onProgress: (Float) -> Unit = {}): Long =
        withContext(Dispatchers.IO) {
            if (uris.isEmpty()) throw DocumentParseException("No images selected")
            val named = uris.map { it to (queryMeta(it).first ?: it.lastPathSegment ?: "page") }
                .sortedWith(compareBy(NaturalOrder) { it.second })
            val dir = newDocumentDir()
            try {
                val pages = File(dir, PAGES_DIR).apply { mkdirs() }
                named.forEachIndexed { i, (uri, name) ->
                    coroutineContext.ensureActive()
                    val ext = name.substringAfterLast('.', "jpg").lowercase().takeIf { it in imageExtensions } ?: "jpg"
                    copy(uri, File(pages, pageName(i, ext)), null) {}
                    onProgress((i + 1f) / named.size)
                }
                insertManga(dir, title ?: DocumentParser.titleFromFileName(named.first().second), DocumentFormat.IMAGES, pages)
            } catch (e: Throwable) {
                dir.deleteRecursively()
                throw e
            }
        }

    private suspend fun importMangaArchive(uri: Uri, name: String, format: DocumentFormat, onProgress: (Float) -> Unit): Long {
        val dir = newDocumentDir()
        try {
            val original = File(dir, "original.${if (format == DocumentFormat.PDF) "pdf" else "cbz"}")
            copy(uri, original, queryMeta(uri).second) { onProgress(it * 0.3f) }
            val pages = File(dir, PAGES_DIR).apply { mkdirs() }
            if (format == DocumentFormat.PDF) renderPdfPages(original, pages) { onProgress(0.3f + it * 0.7f) }
            else extractArchivePages(original, pages) { onProgress(0.3f + it * 0.7f) }
            // Pages are what we read; the archive would only double the storage.
            original.delete()
            return insertManga(dir, DocumentParser.titleFromFileName(name), if (format == DocumentFormat.PDF) DocumentFormat.PDF else DocumentFormat.CBZ, pages)
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
    }

    private suspend fun insertManga(dir: File, title: String, format: DocumentFormat, pagesDir: File): Long {
        val pages = pageFiles(pagesDir)
        if (pages.isEmpty()) throw DocumentParseException("No images found")
        return dao.insert(
            DocumentEntity(
                title = title.take(200),
                kind = DocumentKind.MANGA,
                format = format,
                language = settings.current().activeLanguage.code,
                filePath = pagesDir.absolutePath,
                coverPath = pages.first().absolutePath,
                sizeBytes = dir.sizeOnDisk(),
                length = pages.size,
                addedAt = time.now(),
            ),
        )
    }

    private suspend fun extractArchivePages(archive: File, pages: File, onProgress: (Float) -> Unit) {
        ZipFile(archive).use { zip ->
            val entries = zip.entries().asSequence()
                .filter { !it.isDirectory && it.name.substringAfterLast('.').lowercase() in imageExtensions && !it.name.contains("__MACOSX") }
                .sortedWith(compareBy(NaturalOrder) { it.name })
                .toList()
            entries.forEachIndexed { i, e ->
                coroutineContext.ensureActive()
                val ext = e.name.substringAfterLast('.').lowercase()
                zip.getInputStream(e).use { input -> File(pages, pageName(i, ext)).outputStream().use { input.copyTo(it) } }
                onProgress((i + 1f) / entries.size)
            }
        }
    }

    private suspend fun renderPdfPages(pdf: File, pages: File, onProgress: (Float) -> Unit) {
        ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
            PdfRenderer(fd).use { renderer ->
                for (i in 0 until renderer.pageCount) {
                    coroutineContext.ensureActive()
                    renderer.openPage(i).use { page ->
                        val scale = (MANGA_PAGE_WIDTH.toFloat() / page.width).coerceIn(1f, 4f)
                        val bmp = Bitmap.createBitmap((page.width * scale).toInt(), (page.height * scale).toInt(), Bitmap.Config.ARGB_8888)
                        bmp.eraseColor(Color.WHITE)
                        page.render(bmp, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                        File(pages, pageName(i, "png")).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        bmp.recycle()
                    }
                    onProgress((i + 1f) / renderer.pageCount)
                }
            }
        }
    }

    suspend fun delete(doc: DocumentEntity) = withContext(Dispatchers.IO) {
        dao.delete(doc)
        val dir = documentDir(doc)
        // Never delete anything outside our documents root.
        if (dir != null && dir.canonicalPath.startsWith(root.canonicalPath + File.separator)) dir.deleteRecursively()
    }

    /** Extracted plain text (paragraphs separated by blank lines). */
    suspend fun readText(doc: DocumentEntity): String = withContext(Dispatchers.IO) {
        val f = File(doc.textPath.ifEmpty { doc.filePath })
        if (f.isFile) f.readText() else ""
    }

    suspend fun readParagraphs(doc: DocumentEntity): List<TextParagraph> = DocumentParser.paragraphs(readText(doc))

    /** Page images of a manga document in reading order. */
    suspend fun mangaPages(doc: DocumentEntity): List<File> = withContext(Dispatchers.IO) { pageFiles(File(doc.filePath)) }

    /** The document's private folder (`filesDir/documents/<uuid>`). */
    fun documentDir(doc: DocumentEntity): File? {
        val f = File(doc.filePath)
        return f.parentFile?.takeIf { it.parentFile?.canonicalPath == root.canonicalPath }
    }

    suspend fun savePosition(id: Long, position: Int, progress: Float) =
        dao.updatePosition(id, position, progress.coerceIn(0f, 1f), time.now())

    suspend fun update(doc: DocumentEntity) = dao.update(doc)

    suspend fun setLanguage(doc: DocumentEntity, lang: StudyLanguage) = dao.update(doc.copy(language = lang.code))

    private suspend fun detectLanguage(text: String): StudyLanguage =
        LanguageDetector.detect(text, settings.current().activeLanguage)

    private fun newDocumentDir(): File = File(root, UUID.randomUUID().toString()).apply { mkdirs() }

    private fun ensurePdfBox() {
        if (!pdfBoxReady) { PDFBoxResourceLoader.init(context); pdfBoxReady = true }
    }

    private fun queryMeta(uri: Uri): Pair<String?, Long?> = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE), null, null, null)?.use { c ->
            if (!c.moveToFirst()) return@use null to null
            val n = c.getColumnIndex(OpenableColumns.DISPLAY_NAME).takeIf { it >= 0 }?.let { c.getString(it) }
            val s = c.getColumnIndex(OpenableColumns.SIZE).takeIf { it >= 0 && !c.isNull(it) }?.let { c.getLong(it) }
            n to s
        }
    }.getOrNull() ?: (null to null)

    private suspend fun copy(uri: Uri, target: File, size: Long?, onProgress: (Float) -> Unit) {
        val input = context.contentResolver.openInputStream(uri) ?: throw DocumentParseException("Cannot open file")
        input.use { ins ->
            target.outputStream().use { out ->
                val buf = ByteArray(64 * 1024)
                var total = 0L
                while (true) {
                    coroutineContext.ensureActive()
                    val n = ins.read(buf)
                    if (n < 0) break
                    out.write(buf, 0, n)
                    total += n
                    if (size != null && size > 0) onProgress((total.toFloat() / size).coerceAtMost(1f))
                }
            }
        }
    }

    companion object {
        const val TEXT_FILE = "text.txt"
        const val PAGES_DIR = "pages"
        private const val MANGA_PAGE_WIDTH = 1440
        val imageExtensions = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

        fun pageName(index: Int, ext: String) = "%04d.%s".format(index + 1, ext)

        fun pageFiles(dir: File): List<File> =
            dir.listFiles { f -> f.isFile && f.extension.lowercase() in imageExtensions }?.sortedBy { it.name }.orEmpty()

        private fun File.sizeOnDisk(): Long = walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}

private object FileKindsExt {
    fun of(name: String, format: DocumentFormat): String =
        name.substringAfterLast('.', "").lowercase().takeIf { it.isNotEmpty() && it.length <= 8 } ?: format.name.lowercase()
}

/** "page2" < "page10": compares digit runs numerically. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("\\d+|\\D+")
    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(a.lowercase()).map { it.value }.toList()
        val cb = chunk.findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]; val y = cb[i]
            val r = if (x[0].isDigit() && y[0].isDigit()) {
                x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 } ?: x.trimStart('0').compareTo(y.trimStart('0'))
            } else x.compareTo(y)
            if (r != 0) return r
        }
        return ca.size.compareTo(cb.size)
    }
}
