package app.kotoba.core.docs

import app.kotoba.core.model.DocumentFormat
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element
import org.jsoup.nodes.Node
import org.jsoup.nodes.TextNode
import org.jsoup.parser.Parser
import org.jsoup.select.NodeTraversor
import org.jsoup.select.NodeVisitor
import java.io.File
import java.net.URLDecoder
import java.util.zip.ZipFile

/** HTML → paragraphs. Ruby annotations (<rt>/<rp>) are dropped so Japanese text stays clean. */
object HtmlText {
    private val blockTags = setOf(
        "p", "div", "h1", "h2", "h3", "h4", "h5", "h6", "li", "blockquote", "pre", "section", "article",
        "header", "footer", "aside", "tr", "dd", "dt", "figcaption", "caption", "table", "ul", "ol", "dl",
        "hr", "body", "main", "figure", "title",
    )

    fun parseDocument(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val html = decodeHtml(bytes)
        val doc = Jsoup.parse(html)
        val title = doc.title().trim().ifEmpty {
            doc.selectFirst("h1")?.text()?.trim().orEmpty()
        }.ifEmpty { fallbackTitle }
        val author = doc.selectFirst("meta[name=author]")?.attr("content")?.trim().orEmpty()
        val root = doc.selectFirst("article") ?: doc.selectFirst("main") ?: doc.body()
        return ParsedDocument(title, author, DocumentParser.joinParagraphs(paragraphs(root)), DocumentFormat.HTML)
    }

    /** Honors a declared `<meta charset>` before falling back to [CharsetDetector]. */
    private fun decodeHtml(bytes: ByteArray): String {
        val head = String(bytes, 0, minOf(bytes.size, 2048), Charsets.ISO_8859_1)
        val declared = Regex("charset=[\"']?([A-Za-z0-9_\\-]+)", RegexOption.IGNORE_CASE).find(head)?.groupValues?.get(1)
        val charset = declared?.let { runCatching { charset(it) }.getOrNull() }
        return if (charset != null && !(charset == Charsets.UTF_8 && !CharsetDetector.isValidUtf8(bytes))) String(bytes, charset)
        else CharsetDetector.decode(bytes)
    }

    fun paragraphs(root: Element): List<String> {
        val clone = root.clone()
        clone.select("script, style, rt, rp, noscript, nav, svg, head, template").remove()
        val out = ArrayList<String>()
        val sb = StringBuilder()
        fun flush() {
            val s = sb.toString().trim()
            if (s.isNotEmpty()) out += s
            sb.setLength(0)
        }
        NodeTraversor.traverse(object : NodeVisitor {
            override fun head(node: Node, depth: Int) {
                when {
                    node is TextNode -> sb.append(cleanText(node.wholeText))
                    node is Element && node.normalName() == "br" -> sb.append('\n')
                    node is Element && node.normalName() == "pre" -> { flush(); sb.append(node.wholeText()); flush() }
                    node is Element && node.normalName() in blockTags -> flush()
                    node is Element && node.normalName() in setOf("td", "th") && sb.isNotEmpty() -> sb.append(" · ")
                }
            }

            override fun tail(node: Node, depth: Int) {
                if (node is Element && node.normalName() in blockTags) flush()
            }
        }, clone)
        flush()
        return out
    }

    /** Collapses source whitespace; newlines between CJK characters vanish instead of becoming spaces. */
    private fun cleanText(s: String): String {
        if (s.isEmpty()) return s
        val sb = StringBuilder(s.length)
        var pendingSpace = false
        for (c in s) {
            if (c == ' ' || c == '\n' || c == '\t' || c == '\r' || c == '\u00A0') { pendingSpace = true; continue }
            if (pendingSpace) {
                val prev = sb.lastOrNull()
                if (!(prev != null && DocumentParser.isCjk(prev) && DocumentParser.isCjk(c))) sb.append(' ')
                pendingSpace = false
            }
            sb.append(c)
        }
        if (pendingSpace) sb.append(' ')
        return sb.toString()
    }
}

/** Reads entries of a ZIP container (EPUB/DOCX/CBZ) by path. */
internal class ZipContainer(file: File) : AutoCloseable {
    private val zip = ZipFile(file)
    private val entries = zip.entries().asSequence().associateBy { it.name }
    private val lowerNames = entries.keys.associateBy { it.lowercase() }

    val names: Set<String> get() = entries.keys

    fun bytes(path: String): ByteArray? {
        val entry = entries[path] ?: lowerNames[path.lowercase()]?.let { entries[it] } ?: return null
        return zip.getInputStream(entry).use { it.readBytes() }
    }

    fun text(path: String): String? = bytes(path)?.let { CharsetDetector.decode(it) }

    override fun close() = zip.close()

    companion object {
        /** Resolves [href] (URL-encoded, may contain ../) against the directory of [base]. */
        fun resolve(base: String, href: String): String {
            val clean = runCatching { URLDecoder.decode(href.substringBefore('#').replace("+", "%2B"), "UTF-8") }
                .getOrDefault(href.substringBefore('#'))
            if (clean.startsWith("/")) return clean.trimStart('/')
            val parts = base.substringBeforeLast('/', "").split('/').filter { it.isNotEmpty() }.toMutableList()
            for (seg in clean.split('/')) when (seg) {
                "", "." -> Unit
                ".." -> if (parts.isNotEmpty()) parts.removeAt(parts.lastIndex)
                else -> parts += seg
            }
            return parts.joinToString("/")
        }
    }
}

internal fun Document.firstByLocalName(name: String): Element? =
    allElements.firstOrNull { it.tagName().substringAfter(':').equals(name, ignoreCase = true) }

internal fun Element.childrenByLocalName(name: String): List<Element> =
    getAllElements().filter { it.tagName().substringAfter(':').equals(name, ignoreCase = true) }

/** EPUB 2/3: container.xml → OPF → spine order → XHTML. */
internal object EpubParser {
    fun parse(file: File, fallbackTitle: String): ParsedDocument = ZipContainer(file).use { zip ->
        val container = zip.text("META-INF/container.xml") ?: throw DocumentParseException("Not an EPUB: container.xml missing")
        val opfPath = Jsoup.parse(container, "", Parser.xmlParser()).firstByLocalName("rootfile")?.attr("full-path")
            ?.takeIf { it.isNotBlank() }
            ?: zip.names.firstOrNull { it.endsWith(".opf", ignoreCase = true) }
            ?: throw DocumentParseException("EPUB package (OPF) not found")
        val opf = Jsoup.parse(zip.text(opfPath) ?: throw DocumentParseException("Missing $opfPath"), "", Parser.xmlParser())

        val title = opf.firstByLocalName("title")?.text()?.trim().orEmpty().ifEmpty { fallbackTitle }
        val author = opf.childrenByLocalName("creator").joinToString(", ") { it.text().trim() }

        data class Item(val id: String, val href: String, val mediaType: String, val properties: String)
        val manifest = opf.childrenByLocalName("item").map {
            Item(it.attr("id"), ZipContainer.resolve(opfPath, it.attr("href")), it.attr("media-type"), it.attr("properties"))
        }
        val byId = manifest.associateBy { it.id }
        val spine = opf.childrenByLocalName("itemref")
            .filter { !it.attr("linear").equals("no", ignoreCase = true) }
            .mapNotNull { byId[it.attr("idref")] }
            .ifEmpty { manifest.filter { it.mediaType.contains("html") } }

        val paragraphs = ArrayList<String>()
        for (item in spine) {
            if (!item.mediaType.contains("html") && !item.href.endsWith("html", true) && !item.href.endsWith(".htm", true)) continue
            val html = zip.text(item.href) ?: continue
            val doc = Jsoup.parse(html)
            paragraphs += HtmlText.paragraphs(doc.body())
        }

        val coverItem = manifest.firstOrNull { "cover-image" in it.properties.split(' ') }
            ?: opf.childrenByLocalName("meta").firstOrNull { it.attr("name") == "cover" }?.attr("content")?.let { byId[it] }
            ?: manifest.firstOrNull { it.mediaType.startsWith("image/") && it.id.contains("cover", ignoreCase = true) }
        val cover = coverItem?.takeIf { it.mediaType.startsWith("image/") }?.let { zip.bytes(it.href) }

        ParsedDocument(
            title = title,
            author = author,
            text = DocumentParser.joinParagraphs(paragraphs),
            format = DocumentFormat.EPUB,
            cover = cover,
            coverExtension = coverItem?.href?.substringAfterLast('.', "jpg")?.lowercase() ?: "jpg",
        )
    }
}

/** DOCX: paragraphs of word/document.xml, metadata from docProps/core.xml. */
internal object DocxParser {
    fun parse(file: File, fallbackTitle: String): ParsedDocument = ZipContainer(file).use { zip ->
        val xml = zip.text("word/document.xml") ?: throw DocumentParseException("Not a DOCX: word/document.xml missing")
        val doc = Jsoup.parse(xml, "", Parser.xmlParser())
        val paragraphs = doc.getElementsByTag("w:p")
            .filter { p -> p.parents().none { it.tagName() == "w:p" } }
            .map { p -> StringBuilder().also { collect(p, it, isRoot = true) }.toString() }
        val core = zip.text("docProps/core.xml")?.let { Jsoup.parse(it, "", Parser.xmlParser()) }
        ParsedDocument(
            title = core?.firstByLocalName("title")?.text()?.trim().orEmpty().ifEmpty { fallbackTitle },
            author = core?.firstByLocalName("creator")?.text()?.trim().orEmpty(),
            text = DocumentParser.joinParagraphs(paragraphs),
            format = DocumentFormat.DOCX,
        )
    }

    /** Text of a paragraph; nested paragraphs (text boxes) become line breaks. */
    private fun collect(e: Element, sb: StringBuilder, isRoot: Boolean) {
        if (!isRoot && e.tagName() == "w:p") sb.append('\n')
        for (child in e.children()) when (child.tagName()) {
            "w:t" -> sb.append(child.wholeText())
            "w:tab" -> sb.append(' ')
            "w:br", "w:cr" -> sb.append('\n')
            "w:delText", "w:instrText" -> Unit
            else -> collect(child, sb, isRoot = false)
        }
    }
}
