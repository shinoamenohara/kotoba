package app.kotoba.feature.ai

/** A vocabulary item suggested by the tutor (`term (reading) — translation`). */
data class ParsedWord(
    val term: String,
    val translation: String,
    val reading: String = "",
    val ipa: String = "",
)

/**
 * Extracts `term — translation` pairs from a tutor reply. Accepts bullets/numbering, markdown emphasis,
 * em/en dashes, a spaced hyphen, and an optional `(reading)`, `[reading]` or `/ipa/` after the term.
 */
object WordListParser {
    private val bullet = Regex("""^\s*(?:[-*•·+]|\d{1,3}[.)])\s+""")
    // A plain hyphen only counts when spaced, so "well-known" stays intact.
    private val separator = Regex("""\s*[—–]\s*|\s+-\s+|\s+=\s+""")
    private val trailingReading = Regex("""^(.*?)\s*[(\[（【]\s*([^)\]）】]+?)\s*[)\]）】]\s*$""")
    private val trailingIpa = Regex("""^(.*?)\s*/([^/]+)/\s*$""")

    fun parse(text: String): List<ParsedWord> {
        val seen = HashSet<String>()
        val out = ArrayList<ParsedWord>()
        var inCode = false
        for (raw in text.lineSequence()) {
            if (raw.trimStart().startsWith("```")) { inCode = !inCode; continue }
            if (inCode) continue
            val w = parseLine(raw) ?: continue
            if (seen.add(w.term.lowercase())) out += w
        }
        return out
    }

    fun parseLine(raw: String): ParsedWord? {
        var line = raw.trim()
        if (line.isEmpty() || line.startsWith("#") || line.startsWith(">") || line.startsWith("|")) return null
        line = line.replace(bullet, "")
        line = stripEmphasis(line)
        val match = separator.find(line) ?: return null
        var left = line.substring(0, match.range.first).trim()
        var right = line.substring(match.range.last + 1).trim()
        if (left.isEmpty() || right.isEmpty()) return null

        var reading = ""
        var ipa = ""
        trailingIpa.find(left)?.let { m ->
            left = m.groupValues[1].trim(); ipa = m.groupValues[2].trim()
        }
        trailingReading.find(left)?.let { m ->
            val inner = m.groupValues[2].trim()
            left = m.groupValues[1].trim()
            if (inner.startsWith("/") && inner.endsWith("/") && inner.length > 2) ipa = inner.trim('/') else reading = inner
        }
        left = left.trim(' ', '"', '«', '»', '「', '」', ':', '*', '`')
        right = right.trim().trimEnd('.', ';', ',').trim(' ', '"', '«', '»')

        if (!looksLikeTerm(left) || right.isBlank() || right.length > 160) return null
        return ParsedWord(term = left, translation = right, reading = reading, ipa = ipa)
    }

    private fun stripEmphasis(s: String): String =
        s.replace("**", "").replace("__", "").replace("`", "")
            .replace(Regex("""(?<!\w)[*_](\S[^*_]*?)[*_](?!\w)"""), "$1")

    private fun looksLikeTerm(term: String): Boolean {
        if (term.isEmpty() || term.length > 60) return false
        if (term.endsWith(":") || term.endsWith("?")) return false
        if (term.none { it.isLetter() }) return false
        val words = term.split(Regex("\\s+")).size
        return words <= 6
    }
}
