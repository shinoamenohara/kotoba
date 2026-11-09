package app.kotoba.core.docs

import app.kotoba.core.model.DocumentFormat
import java.io.File
import java.io.IOException

/**
 * Text extracted from an imported document.
 * @property text plain text, paragraphs separated by one blank line ("\n\n"); a paragraph may
 *   contain single "\n" line breaks (poems, lists).
 * @property cover raw cover image bytes (EPUB/FB2) with [coverExtension].
 */
class ParsedDocument(
    val title: String,
    val author: String,
    val text: String,
    val format: DocumentFormat,
    val cover: ByteArray? = null,
    val coverExtension: String = "jpg",
)

/** A paragraph of a document's plain text and its char offset in the full text. */
data class TextParagraph(val index: Int, val start: Int, val text: String) {
    val end: Int get() = start + text.length
}

class DocumentParseException(message: String, cause: Throwable? = null) : IOException(message, cause)

/** Entry point for all text formats. Parsers are pure JVM except PDF (pdfbox-android, needs init). */
object DocumentParser {
    const val PARAGRAPH_SEPARATOR = "\n\n"

    /** Text formats this parser understands (manga formats are handled by [DocumentRepository]). */
    val textFormats = setOf(
        DocumentFormat.TXT, DocumentFormat.EPUB, DocumentFormat.PDF, DocumentFormat.FB2,
        DocumentFormat.DOCX, DocumentFormat.HTML, DocumentFormat.MARKDOWN,
    )

    fun formatOf(fileName: String?, mimeType: String? = null): DocumentFormat? {
        val name = fileName.orEmpty().lowercase()
        if (name.endsWith(".fb2.zip")) return DocumentFormat.FB2
        return when (name.substringAfterLast('.', "")) {
            "txt", "text", "log", "srt" -> DocumentFormat.TXT
            "epub" -> DocumentFormat.EPUB
            "pdf" -> DocumentFormat.PDF
            "fb2" -> DocumentFormat.FB2
            "docx" -> DocumentFormat.DOCX
            "html", "htm", "xhtml" -> DocumentFormat.HTML
            "md", "markdown" -> DocumentFormat.MARKDOWN
            "cbz", "zip" -> DocumentFormat.CBZ
            "png", "jpg", "jpeg", "webp", "gif", "bmp" -> DocumentFormat.IMAGES
            else -> when (mimeType?.lowercase()) {
                "text/plain" -> DocumentFormat.TXT
                "application/epub+zip" -> DocumentFormat.EPUB
                "application/pdf" -> DocumentFormat.PDF
                "application/x-fictionbook+xml", "application/x-fictionbook" -> DocumentFormat.FB2
                "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> DocumentFormat.DOCX
                "text/html", "application/xhtml+xml" -> DocumentFormat.HTML
                "text/markdown", "text/x-markdown" -> DocumentFormat.MARKDOWN
                "application/vnd.comicbook+zip", "application/x-cbz", "application/zip" -> DocumentFormat.CBZ
                else -> if (mimeType?.startsWith("image/") == true) DocumentFormat.IMAGES else null
            }
        }
    }

    /**
     * Parses [file]. [fileName] (original display name) is used for the fallback title.
     * PDF requires `PDFBoxResourceLoader.init(context)` to have been called.
     */
    fun parse(file: File, format: DocumentFormat, fileName: String = file.name): ParsedDocument {
        val fallbackTitle = titleFromFileName(fileName)
        val parsed = try {
            when (format) {
                DocumentFormat.TXT -> TxtParser.parse(file.readBytes(), fallbackTitle)
                DocumentFormat.EPUB -> EpubParser.parse(file, fallbackTitle)
                DocumentFormat.FB2 -> Fb2Parser.parse(file, fallbackTitle)
                DocumentFormat.DOCX -> DocxParser.parse(file, fallbackTitle)
                DocumentFormat.PDF -> PdfTextParser.parse(file, fallbackTitle)
                DocumentFormat.HTML -> HtmlText.parseDocument(file.readBytes(), fallbackTitle)
                DocumentFormat.MARKDOWN -> MarkdownParser.parse(CharsetDetector.decode(file.readBytes()), fallbackTitle)
                DocumentFormat.IMAGES, DocumentFormat.CBZ -> throw DocumentParseException("Not a text format: $format")
            }
        } catch (e: DocumentParseException) {
            throw e
        } catch (e: Exception) {
            throw DocumentParseException("Cannot read ${format.name} file: ${e.message}", e)
        }
        if (parsed.text.isBlank()) throw DocumentParseException("No text found in the document")
        return parsed
    }

    fun titleFromFileName(name: String): String =
        name.substringAfterLast('/')
            .removeSuffix(".zip").substringBeforeLast('.')
            .replace('_', ' ')
            .trim()
            .ifEmpty { name }

    /** Splits normalized text into paragraphs with offsets (inverse of [joinParagraphs]). */
    fun paragraphs(text: String): List<TextParagraph> {
        val out = ArrayList<TextParagraph>()
        var start = 0
        while (start <= text.length) {
            val sep = text.indexOf(PARAGRAPH_SEPARATOR, start)
            val end = if (sep < 0) text.length else sep
            if (end > start) out += TextParagraph(out.size, start, text.substring(start, end))
            if (sep < 0) break
            start = sep + PARAGRAPH_SEPARATOR.length
        }
        return out
    }

    /** Trims and drops empty paragraphs, collapses runs of spaces, joins with a blank line. */
    fun joinParagraphs(paragraphs: Iterable<String>): String =
        paragraphs.asSequence()
            .map { p ->
                p.replace('\u00A0', ' ')
                    .replace(Regex("[ \\t\\x0B\\f\\r]+"), " ")
                    .replace(Regex(" *\n+ *"), "\n")
                    .trim()
            }
            .filter { it.isNotEmpty() }
            .joinToString(PARAGRAPH_SEPARATOR)

    /** Joins hard-wrapped lines: space between words, nothing between CJK, de-hyphenates. */
    fun reflow(lines: List<String>): String {
        val sb = StringBuilder()
        for (raw in lines) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (sb.isEmpty()) { sb.append(line); continue }
            val prev = sb[sb.length - 1]
            val next = line[0]
            when {
                prev == '-' && sb.length >= 2 && sb[sb.length - 2].isLetter() && next.isLowerCase() -> sb.setLength(sb.length - 1)
                isCjk(prev) || isCjk(next) -> Unit
                else -> sb.append(' ')
            }
            sb.append(line)
        }
        return sb.toString()
    }

    internal fun isCjk(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA || c in '\u3000'..'\u303F' || c in '\uFF00'..'\uFFEF'
    }
}
