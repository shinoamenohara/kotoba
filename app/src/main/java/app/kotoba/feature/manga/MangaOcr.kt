package app.kotoba.feature.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import app.kotoba.core.model.StudyLanguage
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.Text
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.TextRecognizer
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import com.google.mlkit.vision.text.japanese.JapaneseTextRecognizerOptions
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Serializable
data class OcrBox(val l: Int, val t: Int, val r: Int, val b: Int) {
    val width: Int get() = r - l
    val height: Int get() = b - t
    fun union(o: OcrBox) = OcrBox(minOf(l, o.l), minOf(t, o.t), maxOf(r, o.r), maxOf(b, o.b))
}

@Serializable
data class OcrLine(val text: String, val box: OcrBox)

@Serializable
data class OcrBlock(
    val id: Int,
    val text: String,
    val box: OcrBox,
    val lines: List<OcrLine>,
    val vertical: Boolean,
    /** Text was corrected by the user. */
    val edited: Boolean = false,
)

@Serializable
data class OcrPage(val width: Int, val height: Int, val language: String, val blocks: List<OcrBlock>)

/**
 * Turns raw recognizer lines into reading-order blocks. Manga Japanese is vertical: columns are
 * tall narrow boxes read right-to-left; ML Kit often returns one block per column, so adjacent
 * columns of the same bubble are merged.
 */
object OcrLayout {
    fun isVertical(box: OcrBox, text: String): Boolean = text.length >= 2 && box.height > box.width * 1.5

    fun orderLines(lines: List<OcrLine>, vertical: Boolean): List<OcrLine> =
        if (vertical) lines.sortedWith(compareByDescending<OcrLine> { it.box.r }.thenBy { it.box.t })
        else lines.sortedWith(compareBy<OcrLine> { it.box.t }.thenBy { it.box.l })

    fun join(lines: List<OcrLine>, cjk: Boolean): String =
        lines.joinToString(if (cjk) "" else " ") { it.text.trim() }.trim()

    /** Builds ordered blocks from recognizer blocks (each a list of lines). */
    fun build(rawBlocks: List<List<OcrLine>>, cjk: Boolean): List<OcrBlock> {
        data class Group(var box: OcrBox, val lines: MutableList<OcrLine>, val vertical: Boolean)
        val groups = rawBlocks.filter { it.isNotEmpty() }.map { lines ->
            val box = lines.map { it.box }.reduce(OcrBox::union)
            val vertical = cjk && lines.count { isVertical(it.box, it.text) } * 2 >= lines.size
            Group(box, lines.toMutableList(), vertical)
        }.toMutableList()

        if (cjk) {
            // Merge vertical groups that sit side by side with a small gap (same bubble).
            var merged = true
            while (merged) {
                merged = false
                loop@ for (i in groups.indices) for (j in groups.indices) {
                    if (i == j) continue
                    val a = groups[i]; val b = groups[j]
                    if (!a.vertical || !b.vertical) continue
                    val colW = maxOf(a.lines.maxOf { it.box.width }, b.lines.maxOf { it.box.width })
                    val gap = maxOf(a.box.l, b.box.l) - minOf(a.box.r, b.box.r)
                    val overlapV = minOf(a.box.b, b.box.b) - maxOf(a.box.t, b.box.t)
                    if (gap <= colW * 0.8 && overlapV >= minOf(a.box.height, b.box.height) * 0.5) {
                        a.box = a.box.union(b.box)
                        a.lines += b.lines
                        groups.removeAt(j)
                        merged = true
                        break@loop
                    }
                }
            }
        }

        val ordered = groups.sortedWith { a, b ->
            // Rows of bubbles top→bottom; within a row right→left for vertical CJK, else left→right.
            val sameRow = minOf(a.box.b, b.box.b) - maxOf(a.box.t, b.box.t) > minOf(a.box.height, b.box.height) / 2
            when {
                sameRow && (a.vertical || b.vertical) -> b.box.r.compareTo(a.box.r)
                sameRow -> a.box.l.compareTo(b.box.l)
                else -> a.box.t.compareTo(b.box.t)
            }
        }
        return ordered.mapIndexed { i, g ->
            val lines = orderLines(g.lines, g.vertical)
            OcrBlock(id = i, text = join(lines, cjk), box = g.box, lines = lines, vertical = g.vertical)
        }.filter { it.text.isNotBlank() }
    }
}

/** ML Kit OCR with the recognizer matching the manga's language; results cached as JSON next to the page. */
@Singleton
class MangaOcr @Inject constructor(
    @ApplicationContext private val context: Context,
    private val json: Json,
) {
    private val recognizers = HashMap<String, TextRecognizer>()

    private fun recognizer(lang: StudyLanguage): TextRecognizer = synchronized(recognizers) {
        val key = when (lang) {
            StudyLanguage.JA -> "ja"
            StudyLanguage.ZH -> "zh"
            StudyLanguage.KO -> "ko"
            else -> "latin"
        }
        recognizers.getOrPut(key) {
            TextRecognition.getClient(
                when (key) {
                    "ja" -> JapaneseTextRecognizerOptions.Builder().build()
                    "zh" -> ChineseTextRecognizerOptions.Builder().build()
                    "ko" -> KoreanTextRecognizerOptions.Builder().build()
                    else -> TextRecognizerOptions.DEFAULT_OPTIONS
                },
            )
        }
    }

    fun cacheFile(page: File): File = File(page.parentFile, page.nameWithoutExtension + ".ocr.json")

    suspend fun cached(page: File): OcrPage? = withContext(Dispatchers.IO) {
        val f = cacheFile(page)
        if (!f.isFile) null else runCatching { json.decodeFromString(OcrPage.serializer(), f.readText()) }.getOrNull()
    }

    suspend fun save(page: File, ocr: OcrPage) = withContext(Dispatchers.IO) {
        cacheFile(page).writeText(json.encodeToString(OcrPage.serializer(), ocr))
    }

    suspend fun recognize(page: File, lang: StudyLanguage): OcrPage {
        val bitmap = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(page.absolutePath) }
            ?: error("Cannot decode ${page.name}")
        try {
            val result: Text = recognizer(lang).process(InputImage.fromBitmap(bitmap, 0)).await()
            val cjk = lang == StudyLanguage.JA || lang == StudyLanguage.ZH
            val raw = result.textBlocks.map { block ->
                block.lines.mapNotNull { line ->
                    val r = line.boundingBox ?: return@mapNotNull null
                    OcrLine(line.text, OcrBox(r.left, r.top, r.right, r.bottom))
                }
            }
            val ocr = OcrPage(bitmap.width, bitmap.height, lang.code, OcrLayout.build(raw, cjk))
            save(page, ocr)
            return ocr
        } finally {
            bitmap.recycle()
        }
    }

    /** Saves a padded crop of [box] as PNG (speech-bubble image for cards). */
    suspend fun crop(page: File, box: OcrBox, target: File): File = withContext(Dispatchers.IO) {
        val src = BitmapFactory.decodeFile(page.absolutePath) ?: error("Cannot decode ${page.name}")
        try {
            val pad = (minOf(box.width, box.height) * 0.15f).toInt().coerceIn(6, 40)
            val l = (box.l - pad).coerceAtLeast(0)
            val t = (box.t - pad).coerceAtLeast(0)
            val r = (box.r + pad).coerceAtMost(src.width)
            val b = (box.b + pad).coerceAtMost(src.height)
            val out = Bitmap.createBitmap(src, l, t, (r - l).coerceAtLeast(1), (b - t).coerceAtLeast(1))
            target.parentFile?.mkdirs()
            target.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            if (out !== src) out.recycle()
            target
        } finally {
            src.recycle()
        }
    }
}
