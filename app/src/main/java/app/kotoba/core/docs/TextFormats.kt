package app.kotoba.core.docs

import app.kotoba.core.model.DocumentFormat
import com.tom_roush.pdfbox.pdmodel.PDDocument
import com.tom_roush.pdfbox.text.PDFTextStripper
import java.io.File

/** Plain text: encoding detection, paragraph reconstruction and title/author from the header lines. */
internal object TxtParser {
    private val authorPrefix = Regex("^(?:by|автор[:：]?|著者[:：]|作者[:：]|著[:：]|作[:：])\\s*(.+)$", RegexOption.IGNORE_CASE)

    fun parse(bytes: ByteArray, fallbackTitle: String): ParsedDocument {
        val lines = CharsetDetector.decode(bytes).replace("\r\n", "\n").replace('\r', '\n').split('\n').toMutableList()
        while (lines.isNotEmpty() && lines.first().isBlank()) lines.removeAt(0)
        var title = fallbackTitle
        var author = ""
        // Header: a short unpunctuated first line (title), optionally "by …" next, then a blank line.
        val first = lines.getOrNull(0)?.trim()
        val second = lines.getOrNull(1)?.trim()
        val authorMatch = second?.takeIf { it.length <= 60 }?.let { authorPrefix.find(it) }
        if (first != null && isHeaderLine(first) && lines.size > 3) {
            when {
                authorMatch != null && lines.getOrNull(2)?.isBlank() != false -> {
                    title = first; author = authorMatch.groupValues[1].trim()
                    repeat(2) { lines.removeAt(0) }
                }
                second.isNullOrBlank() -> { title = first; lines.removeAt(0) }
            }
        }
        return ParsedDocument(title, author, DocumentParser.joinParagraphs(paragraphs(lines.joinToString("\n"))), DocumentFormat.TXT)
    }

    private fun isHeaderLine(s: String): Boolean =
        s.length in 1..80 && '\n' !in s && s.trimEnd().lastOrNull() !in setOf('.', '。', ',', '、', ';', ':', '!', '?', '！', '？')

    /**
     * Blank lines separate paragraphs. Inside a block, lines are hard-wrapped text unless they
     * start with an indent (common in Russian/Japanese TXT books), which marks a new paragraph.
     */
    fun paragraphs(text: String): List<String> {
        val lines = text.replace("\r\n", "\n").replace('\r', '\n').split('\n')
        val blocks = mutableListOf<MutableList<String>>(mutableListOf())
        for (line in lines) {
            if (line.isBlank()) { if (blocks.last().isNotEmpty()) blocks += mutableListOf<String>() } else blocks.last() += line
        }
        if (blocks.last().isEmpty()) blocks.removeAt(blocks.lastIndex)
        val multiLineBlocks = blocks.count { it.size > 1 }
        val out = ArrayList<String>()
        for (block in blocks) {
            if (block.size == 1) { out += block[0].trim(); continue }
            val indented = block.count { isIndented(it) }
            when {
                // Every line indented: one paragraph per line.
                indented >= block.size * 0.9 -> block.mapTo(out) { it.trim() }
                indented > 0 && indented >= block.size * 0.05 -> {
                    var current = mutableListOf<String>()
                    for (line in block) {
                        if (isIndented(line) && current.isNotEmpty()) { out += DocumentParser.reflow(current); current = mutableListOf() }
                        current += line
                    }
                    if (current.isNotEmpty()) out += DocumentParser.reflow(current)
                }
                blocks.size == 1 || multiLineBlocks == 0 -> block.mapTo(out) { it.trim() }
                else -> out += DocumentParser.reflow(block)
            }
        }
        return out
    }

    private fun isIndented(line: String) = line.startsWith("  ") || line.startsWith('\t') || line.startsWith('\u3000')
}

/** Markdown (incl. NotebookLM exports) → plain paragraphs; first H1 becomes the title. */
internal object MarkdownParser {
    private val heading = Regex("^\\s{0,3}#{1,6}\\s+(.*?)\\s*#*\\s*$")
    private val listItem = Regex("^\\s*(?:[-*+]|\\d+[.)])\\s+(?:\\[[ xX]]\\s+)?")
    private val rule = Regex("^\\s*(?:-{3,}|\\*{3,}|_{3,})\\s*$")
    private val tableSeparator = Regex("^\\s*\\|?\\s*:?-{2,}:?\\s*(\\|\\s*:?-{2,}:?\\s*)*\\|?\\s*$")

    fun parse(markdown: String, fallbackTitle: String): ParsedDocument {
        var title: String? = null
        var text = markdown.replace("\r\n", "\n")
        // YAML front matter
        if (text.startsWith("---\n")) {
            val end = text.indexOf("\n---", 4)
            if (end > 0) {
                Regex("(?m)^title:\\s*\"?(.+?)\"?\\s*$").find(text.substring(0, end))?.let { title = it.groupValues[1] }
                text = text.substring(end + 4)
            }
        }
        val out = ArrayList<String>()
        val current = ArrayList<String>()
        fun flush() { if (current.isNotEmpty()) { out += DocumentParser.reflow(current); current.clear() } }
        var inCode = false
        val code = StringBuilder()
        for (line in text.split('\n')) {
            if (line.trimStart().startsWith("```") || line.trimStart().startsWith("~~~")) {
                if (inCode) { out += code.toString().trimEnd(); code.clear() } else flush()
                inCode = !inCode
                continue
            }
            if (inCode) { code.append(line).append('\n'); continue }
            if (line.isBlank() || rule.matches(line) || tableSeparator.matches(line)) { flush(); continue }
            val h = heading.find(line)
            if (h != null) {
                flush()
                val t = inline(h.groupValues[1])
                if (title == null && line.trimStart().startsWith("# ")) title = t
                out += t
                continue
            }
            var l = if (line.trimStart().startsWith(">")) line.trimStart().trimStart('>').trimStart() else line
            if (listItem.containsMatchIn(l)) { flush(); l = "• " + l.replaceFirst(listItem, "") }
            if (l.trim().startsWith("|")) { flush(); out += inline(l.trim().trim('|').split('|').joinToString(" · ") { it.trim() }); continue }
            current += inline(l)
            if (l.startsWith("• ")) flush()
        }
        flush()
        if (inCode && code.isNotEmpty()) out += code.toString().trimEnd()
        // Title equal to the first paragraph is not repeated in the body.
        val body = if (title != null && out.firstOrNull() == title) out.drop(1) else out
        return ParsedDocument(title ?: fallbackTitle, "", DocumentParser.joinParagraphs(body), DocumentFormat.MARKDOWN)
    }

    fun inline(s: String): String = s
        .replace(Regex("!\\[[^\\]]*]\\([^)]*\\)"), "")
        .replace(Regex("\\[([^\\]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[\\^?\\d+]"), "")
        .replace(Regex("<[^>]+>"), "")
        .replace(Regex("(\\*\\*|__|~~|`)"), "")
        .replace(Regex("(?<![\\p{L}\\d])[*_](\\S(?:.*?\\S)?)[*_](?![\\p{L}\\d])"), "$1")
        .replace(Regex("\\\\([\\\\`*_{}\\[\\]()#+\\-.!])"), "$1")
}

/** PDF text layer via pdfbox-android. Scanned PDFs (no text) yield an empty result. */
internal object PdfTextParser {
    fun parse(file: File, fallbackTitle: String): ParsedDocument = PDDocument.load(file).use { doc ->
        val stripper = PDFTextStripper().apply {
            sortByPosition = true
            paragraphStart = ""
            paragraphEnd = "\n\n"
            lineSeparator = "\n"
        }
        val raw = stripper.getText(doc)
        val paragraphs = raw.split(Regex("\n\\s*\n")).map { block ->
            DocumentParser.reflow(block.split('\n'))
        }
        val info = doc.documentInformation
        ParsedDocument(
            title = info?.title?.takeIf { it.isNotBlank() }?.trim() ?: fallbackTitle,
            author = info?.author?.trim().orEmpty(),
            text = DocumentParser.joinParagraphs(paragraphs),
            format = DocumentFormat.PDF,
        )
    }
}
