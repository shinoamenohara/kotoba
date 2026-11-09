package app.kotoba.feature.manga

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Rect
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

    /** Power-of-two decode sample size keeping the longest side at or below [maxSide]. */
    fun sampleSize(width: Int, height: Int, maxSide: Int): Int {
        var s = 1
        var longest = maxOf(width, height)
        while (longest > maxSide) { s *= 2; longest /= 2 }
        return s
    }

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

        // Rows of bubbles top→bottom; within a row right→left for vertical CJK, else left→right.
        // Built greedily: a pairwise "same row" comparator is not transitive and makes TimSort throw.
        val ordered = ArrayList<Group>(groups.size)
        val pending = groups.sortedBy { it.box.t }.toMutableList()
        while (pending.isNotEmpty()) {
            val first = pending.removeAt(0)
            val row = mutableListOf(first)
            val iter = pending.iterator()
            while (iter.hasNext()) {
                val g = iter.next()
                val overlap = minOf(first.box.b, g.box.b) - maxOf(first.box.t, g.box.t)
                if (overlap > minOf(first.box.height, g.box.height) / 2) { row += g; iter.remove() }
            }
            ordered += if (row.any { it.vertical }) row.sortedByDescending { it.box.r } else row.sortedBy { it.box.l }
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

    private companion object {
        const val MAX_OCR_SIDE = 3000
    }

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
        // Decoded downsampled to avoid OOM on huge scans; boxes are scaled back to full-size coordinates.
        val (bitmap, fullW, fullH) = withContext(Dispatchers.IO) {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(page.absolutePath, bounds)
            val sample = OcrLayout.sampleSize(bounds.outWidth, bounds.outHeight, MAX_OCR_SIDE)
            val bmp = BitmapFactory.decodeFile(page.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
            Triple(bmp, bounds.outWidth, bounds.outHeight)
        }
        if (bitmap == null) error("Cannot decode ${page.name}")
        try {
            val result: Text = recognizer(lang).process(InputImage.fromBitmap(bitmap, 0)).await()
            val cjk = lang == StudyLanguage.JA || lang == StudyLanguage.ZH
            val sx = fullW.toFloat() / bitmap.width
            val sy = fullH.toFloat() / bitmap.height
            val raw = result.textBlocks.map { block ->
                block.lines.mapNotNull { line ->
                    val r = line.boundingBox ?: return@mapNotNull null
                    OcrLine(
                        line.text,
                        OcrBox((r.left * sx).toInt(), (r.top * sy).toInt(), (r.right * sx).toInt(), (r.bottom * sy).toInt()),
                    )
                }
            }
            val ocr = OcrPage(fullW, fullH, lang.code, OcrLayout.build(raw, cjk))
            save(page, ocr)
            return ocr
        } finally {
            bitmap.recycle()
        }
    }

    /** Saves a padded crop of [box] as PNG (speech-bubble image for cards). */
    suspend fun crop(page: File, box: OcrBox, target: File): File = withContext(Dispatchers.IO) {
        // Region decoding keeps memory proportional to the crop, not the whole page.
        @Suppress("DEPRECATION")
        val decoder = BitmapRegionDecoder.newInstance(page.absolutePath, false) ?: error("Cannot decode ${page.name}")
        try {
            val pad = (minOf(box.width, box.height) * 0.15f).toInt().coerceIn(6, 40)
            val l = (box.l - pad).coerceIn(0, decoder.width - 1)
            val t = (box.t - pad).coerceIn(0, decoder.height - 1)
            val r = (box.r + pad).coerceIn(l + 1, decoder.width)
            val b = (box.b + pad).coerceIn(t + 1, decoder.height)
            val out = decoder.decodeRegion(Rect(l, t, r, b), null) ?: error("Cannot decode ${page.name}")
            try {
                target.parentFile?.mkdirs()
                target.outputStream().use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            } finally {
                out.recycle()
            }
            target
        } finally {
            decoder.recycle()
        }
    }
}
