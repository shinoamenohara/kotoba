package app.kotoba.feature.ai

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

sealed interface MdBlock {
    data class Heading(val level: Int, val text: String) : MdBlock
    data class Paragraph(val text: String) : MdBlock
    data class Bullet(val marker: String, val text: String, val indent: Int) : MdBlock
    data class Quote(val text: String) : MdBlock
    data class Code(val text: String) : MdBlock
    data object Rule : MdBlock
}

data class MdSpan(val text: String, val bold: Boolean = false, val italic: Boolean = false, val code: Boolean = false)

/** Minimal markdown subset used by chat replies: headings, lists, quotes, fences, **bold**, *italic*, `code`. */
object MiniMarkdown {
    private val heading = Regex("""^(#{1,6})\s+(.*)$""")
    private val unordered = Regex("""^(\s*)[-*+•]\s+(.*)$""")
    private val ordered = Regex("""^(\s*)(\d{1,3}[.)])\s+(.*)$""")

    fun blocks(text: String): List<MdBlock> {
        val out = ArrayList<MdBlock>()
        val para = StringBuilder()
        val code = StringBuilder()
        var inCode = false
        fun flush() {
            if (para.isNotBlank()) out += MdBlock.Paragraph(para.toString().trim())
            para.clear()
        }
        for (line in text.lines()) {
            if (line.trimStart().startsWith("```")) {
                if (inCode) { out += MdBlock.Code(code.toString().trimEnd('\n')); code.clear() } else flush()
                inCode = !inCode
                continue
            }
            if (inCode) { code.append(line).append('\n'); continue }
            val t = line.trim()
            when {
                t.isEmpty() -> flush()
                t == "---" || t == "***" || t == "___" -> { flush(); out += MdBlock.Rule }
                heading.matches(t) -> { flush(); heading.find(t)!!.let { out += MdBlock.Heading(it.groupValues[1].length, it.groupValues[2].trim().trimEnd('#').trim()) } }
                t.startsWith(">") -> { flush(); out += MdBlock.Quote(t.removePrefix(">").trim()) }
                unordered.matches(line) -> { flush(); unordered.find(line)!!.let { out += MdBlock.Bullet("•", it.groupValues[2], it.groupValues[1].length / 2) } }
                ordered.matches(line) -> { flush(); ordered.find(line)!!.let { out += MdBlock.Bullet(it.groupValues[2], it.groupValues[3], it.groupValues[1].length / 2) } }
                else -> { if (para.isNotEmpty()) para.append('\n'); para.append(t) }
            }
        }
        if (inCode && code.isNotEmpty()) out += MdBlock.Code(code.toString().trimEnd('\n'))
        flush()
        return out
    }

    /** Splits inline markup into styled spans; unmatched markers are kept as literal text. */
    fun spans(text: String): List<MdSpan> {
        val out = ArrayList<MdSpan>()
        val buf = StringBuilder()
        var bold = false
        var italic = false
        fun emit() { if (buf.isNotEmpty()) { out += MdSpan(buf.toString(), bold, italic); buf.clear() } }
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                c == '`' -> {
                    val end = text.indexOf('`', i + 1)
                    if (end > i + 1) { emit(); out += MdSpan(text.substring(i + 1, end), code = true); i = end + 1; continue }
                    buf.append(c)
                }
                (c == '*' || c == '_') && text.startsWith("$c$c", i) -> {
                    val marker = "$c$c"
                    if (bold || text.indexOf(marker, i + 2) > 0) { emit(); bold = !bold; i += 2; continue }
                    buf.append(marker); i += 2; continue
                }
                c == '*' || (c == '_' && isWordBoundary(text, i)) -> {
                    val closes = italic || hasClosing(text, i, c)
                    if (closes) { emit(); italic = !italic } else buf.append(c)
                }
                else -> buf.append(c)
            }
            i++
        }
        emit()
        return out
    }

    // `snake_case` words must not turn italic.
    private fun isWordBoundary(text: String, i: Int): Boolean {
        val before = text.getOrNull(i - 1)
        val after = text.getOrNull(i + 1)
        return (before == null || !before.isLetterOrDigit()) || (after == null || !after.isLetterOrDigit())
    }

    private fun hasClosing(text: String, i: Int, c: Char): Boolean {
        val next = text.getOrNull(i + 1) ?: return false
        if (next.isWhitespace()) return false
        val end = text.indexOf(c, i + 1)
        return end > i + 1
    }
}

fun markdownToAnnotated(text: String, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    MiniMarkdown.spans(text).forEach { s ->
        val style = SpanStyle(
            fontWeight = if (s.bold) FontWeight.SemiBold else null,
            fontStyle = if (s.italic) FontStyle.Italic else null,
            fontFamily = if (s.code) FontFamily.Monospace else null,
            background = if (s.code) codeBackground else Color.Unspecified,
        )
        withStyle(style) { append(s.text) }
    }
}

@Composable
fun MarkdownText(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface) {
    val blocks = remember(text) { MiniMarkdown.blocks(text) }
    val codeBg = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f)
    val body = MaterialTheme.typography.bodyLarge.copy(color = color)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        blocks.forEach { b ->
            when (b) {
                is MdBlock.Heading -> Text(
                    markdownToAnnotated(b.text, codeBg),
                    style = (if (b.level <= 2) MaterialTheme.typography.titleMedium else MaterialTheme.typography.titleSmall).copy(color = color),
                    fontWeight = FontWeight.Bold,
                )
                is MdBlock.Paragraph -> Text(markdownToAnnotated(b.text, codeBg), style = body)
                is MdBlock.Bullet -> Row(Modifier.padding(start = (b.indent * 12).dp)) {
                    Text(b.marker, style = body, modifier = Modifier.widthIn(min = 18.dp))
                    Text(markdownToAnnotated(b.text, codeBg), style = body)
                }
                is MdBlock.Quote -> Text(
                    markdownToAnnotated(b.text, codeBg),
                    style = body.copy(fontStyle = FontStyle.Italic, color = color.copy(alpha = 0.8f)),
                    modifier = Modifier.padding(start = 8.dp),
                )
                is MdBlock.Code -> Text(
                    b.text,
                    style = MaterialTheme.typography.bodyMedium.copy(fontFamily = FontFamily.Monospace, color = color),
                    modifier = Modifier.fillMaxWidth()
                        .background(codeBg, RoundedCornerShape(8.dp))
                        .horizontalScroll(rememberScrollState())
                        .padding(8.dp),
                )
                MdBlock.Rule -> HorizontalDivider(color = color.copy(alpha = 0.2f))
            }
        }
    }
}
