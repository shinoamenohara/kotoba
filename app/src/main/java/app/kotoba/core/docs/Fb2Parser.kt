package app.kotoba.core.docs

import android.util.Xml
import app.kotoba.core.model.DocumentFormat
import org.xmlpull.v1.XmlPullParser
import java.io.File
import java.io.StringReader
import java.nio.charset.Charset
import java.util.zip.ZipFile

/** FictionBook 2 (also zipped as .fb2.zip): streaming XmlPullParser, cover from <binary>. */
internal object Fb2Parser {
    private val paragraphTags = setOf("p", "v", "subtitle", "text-author")
    private val htmlEntities = mapOf("&nbsp;" to "\u00A0", "&mdash;" to "—", "&ndash;" to "–", "&laquo;" to "«", "&raquo;" to "»", "&hellip;" to "…")

    fun parse(file: File, fallbackTitle: String): ParsedDocument {
        val bytes = if (isZip(file)) {
            ZipFile(file).use { zip ->
                val entry = zip.entries().asSequence().firstOrNull { it.name.endsWith(".fb2", ignoreCase = true) }
                    ?: zip.entries().asSequence().firstOrNull { !it.isDirectory }
                    ?: throw DocumentParseException("Empty FB2 archive")
                zip.getInputStream(entry).use { it.readBytes() }
            }
        } else file.readBytes()
        return parse(bytes, fallbackTitle)
    }

    private fun isZip(file: File): Boolean = file.inputStream().use { s ->
        val h = ByteArray(2); s.read(h) == 2 && h[0] == 'P'.code.toByte() && h[1] == 'K'.code.toByte()
    }

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        var xml = decode(bytes)
        htmlEntities.forEach { (k, v) -> xml = xml.replace(k, v) }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, false)
        parser.setInput(StringReader(xml))

        val path = ArrayList<String>()
        var title = ""
        val firstName = StringBuilder(); val middleName = StringBuilder(); val lastName = StringBuilder()
        var authorDone = false
        var coverId: String? = null
        var cover: ByteArray? = null
        var coverExt = "jpg"
        var skipBody = false
        var binaryId: String? = null
        var binaryType = ""
        val binary = StringBuilder()
        val paragraphs = ArrayList<String>()
        val current = StringBuilder()
        var inParagraph = false

        var event = parser.eventType
        while (event != XmlPullParser.END_DOCUMENT) {
            when (event) {
                XmlPullParser.START_TAG -> {
                    val name = parser.name.substringAfter(':')
                    path += name
                    when {
                        name == "body" -> skipBody = parser.getAttributeValue(null, "name")?.let { it == "notes" || it == "comments" } == true
                        name == "image" && "coverpage" in path -> coverId = hrefOf(parser)?.removePrefix("#")
                        name == "binary" -> {
                            binaryId = parser.getAttributeValue(null, "id")
                            binaryType = parser.getAttributeValue(null, "content-type").orEmpty()
                            binary.setLength(0)
                        }
                        name in paragraphTags && "body" in path && !skipBody -> { inParagraph = true; current.setLength(0) }
                        name == "empty-line" && "body" in path -> paragraphs += ""
                    }
                }
                XmlPullParser.TEXT -> {
                    val t = parser.text
                    when {
                        inParagraph -> current.append(t)
                        binaryId != null && binaryId == coverId -> binary.append(t)
                        path.lastOrNull() == "book-title" && "title-info" in path -> title += t
                        "title-info" in path && "author" in path && !authorDone -> when (path.last()) {
                            "first-name" -> firstName.append(t)
                            "middle-name" -> middleName.append(t)
                            "last-name" -> lastName.append(t)
                        }
                    }
                }
                XmlPullParser.END_TAG -> {
                    val name = parser.name.substringAfter(':')
                    when {
                        name in paragraphTags && inParagraph -> { paragraphs += current.toString(); inParagraph = false }
                        name == "author" && "title-info" in path -> authorDone = authorDone || firstName.isNotBlank() || lastName.isNotBlank()
                        name == "binary" -> {
                            if (binaryId != null && binaryId == coverId && binary.isNotEmpty()) {
                                cover = runCatching { java.util.Base64.getMimeDecoder().decode(binary.toString()) }.getOrNull()
                                coverExt = if (binaryType.contains("png")) "png" else "jpg"
                            }
                            binaryId = null
                        }
                    }
                    if (path.isNotEmpty()) path.removeAt(path.lastIndex)
                }
            }
            event = parser.next()
        }
        val author = listOf(firstName, middleName, lastName).map { it.toString().trim() }.filter { it.isNotEmpty() }.joinToString(" ")
        return ParsedDocument(
            title = title.trim().ifEmpty { fallbackTitle },
            author = author,
            text = DocumentParser.joinParagraphs(paragraphs),
            format = DocumentFormat.FB2,
            cover = cover,
            coverExtension = coverExt,
        )
    }

    private fun hrefOf(parser: XmlPullParser): String? =
        (0 until parser.attributeCount).firstOrNull { parser.getAttributeName(it).endsWith("href") }?.let { parser.getAttributeValue(it) }

    /** Uses the encoding from the XML declaration, else [CharsetDetector]. */
    private fun decode(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 200), Charsets.ISO_8859_1)
        val declared = Regex("encoding=[\"']([A-Za-z0-9_\\-]+)[\"']").find(head)?.groupValues?.get(1)
        val cs = declared?.let { runCatching { Charset.forName(it) }.getOrNull() }
        val text = if (cs != null && !(cs == Charsets.UTF_8 && !CharsetDetector.isValidUtf8(bytes))) {
            String(bytes, cs).removePrefix("\uFEFF")
        } else CharsetDetector.decode(bytes)
        // The declaration's encoding is meaningless for a Reader and some parsers reject it.
        return text.replaceFirst(Regex("^\\s*<\\?xml[^>]*\\?>"), "")
    }
}
