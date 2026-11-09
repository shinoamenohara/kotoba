package app.kotoba.feature.importexport.parse

/** Result of parsing a delimited text table. */
data class DelimitedTable(
    val delimiter: Char,
    val headers: List<String>,
    val rows: List<List<String>>,
    val hasHeader: Boolean,
)

/** CSV/TSV parser: delimiter auto-detection, RFC 4180 quotes, header detection. */
object DelimitedParser {
    private val candidates = charArrayOf('\t', ',', ';', '|')

    private val headerWords = setOf(
        "term", "word", "front", "back", "translation", "meaning", "reading", "pinyin", "kana", "ipa",
        "example", "sentence", "notes", "note", "tags", "tag", "deck", "definition", "english", "expression",
        "hanzi", "kanji", "level", "pos", "part of speech", "audio", "image", "слово", "перевод", "пример",
    )

    fun parse(text: String, delimiter: Char? = null, header: Boolean? = null): DelimitedTable {
        val clean = text.removePrefix("\uFEFF")
        val lines = clean.lineSequence().filter { !it.startsWith("#") }.joinToString("\n")
        val d = delimiter ?: detectDelimiter(lines)
        val all = splitRecords(lines, d).filter { row -> row.any { it.isNotBlank() } }
        if (all.isEmpty()) return DelimitedTable(d, emptyList(), emptyList(), false)
        val width = all.maxOf { it.size }
        val padded = all.map { if (it.size < width) it + List(width - it.size) { "" } else it }
        val hasHeader = header ?: looksLikeHeader(padded)
        val headers = if (hasHeader) padded.first().mapIndexed { i, h -> h.trim().ifBlank { "Column ${i + 1}" } }
        else List(width) { "Column ${it + 1}" }
        return DelimitedTable(d, headers, if (hasHeader) padded.drop(1) else padded, hasHeader)
    }

    /** Picks the delimiter that yields the most consistent column count (> 1) over the first lines. */
    fun detectDelimiter(text: String): Char {
        val sample = text.lineSequence().filter { it.isNotBlank() }.take(20).toList()
        if (sample.isEmpty()) return ','
        var best = ','
        var bestScore = -1.0
        for (c in candidates) {
            val counts = sample.map { countOutsideQuotes(it, c) }
            val nonZero = counts.count { it > 0 }
            if (nonZero == 0) continue
            val mode = counts.groupingBy { it }.eachCount().maxBy { it.value }
            // Consistency first, then prefer tab (less likely to appear inside values).
            val score = mode.value.toDouble() / sample.size * 10 + (if (mode.key > 0) 1 else 0) + if (c == '\t') 0.5 else 0.0
            if (score > bestScore) { bestScore = score; best = c }
        }
        return best
    }

    private fun countOutsideQuotes(line: String, c: Char): Int {
        var inQuotes = false
        var n = 0
        for (ch in line) {
            if (ch == '"') inQuotes = !inQuotes else if (ch == c && !inQuotes) n++
        }
        return n
    }

    /** Splits into records honoring quoted fields that may contain delimiters, quotes ("") and newlines. */
    fun splitRecords(text: String, d: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var inQuotes = false
        var atFieldStart = true
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (inQuotes) {
                if (ch == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else inQuotes = false
                } else field.append(ch)
            } else when {
                ch == '"' && atFieldStart -> { inQuotes = true; atFieldStart = false }
                ch == d -> { row += field.toString(); field.clear(); atFieldStart = true }
                ch == '\r' -> Unit
                ch == '\n' -> { row += field.toString(); field.clear(); rows += row; row = mutableListOf(); atFieldStart = true }
                else -> { field.append(ch); atFieldStart = false }
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row += field.toString(); rows += row }
        return rows.map { r -> r.map { it.trim() } }
    }

    private fun looksLikeHeader(rows: List<List<String>>): Boolean {
        val first = rows.first()
        if (first.any { it.trim().lowercase() in headerWords }) return true
        if (rows.size < 2) return false
        // Header cells are short, non-numeric and unique while data rows vary.
        val distinct = first.map { it.lowercase() }.toSet().size == first.size
        val noDigits = first.none { cell -> cell.any { it.isDigit() } }
        val shortish = first.all { it.length in 1..24 }
        val secondHasDigits = rows[1].any { cell -> cell.any { it.isDigit() } }
        return distinct && noDigits && shortish && secondHasDigits
    }
}

/** Parses simple personal dictionaries: `term<TAB>translation`, `term = translation`, `term - translation`, `term;translation`. */
object DictionaryLineParser {
    private val separators = listOf("\t", " = ", " — ", " – ", " - ", ";", "=", " : ")

    fun parseLine(line: String): Pair<String, String>? {
        val l = line.trim().removePrefix("\uFEFF")
        if (l.isEmpty() || l.startsWith("#") || l.startsWith("//")) return null
        for (sep in separators) {
            val idx = l.indexOf(sep)
            if (idx > 0) {
                val term = l.substring(0, idx).trim()
                val tr = l.substring(idx + sep.length).trim()
                if (term.isNotEmpty() && tr.isNotEmpty()) return term to tr
            }
        }
        return null
    }

    fun parse(text: String): List<Pair<String, String>> = text.lineSequence().mapNotNull { parseLine(it) }.toList()

    /** True if most non-empty lines parse as dictionary entries and the text isn't a regular CSV. */
    fun looksLikeDictionary(text: String): Boolean {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }.take(50).toList()
        if (lines.isEmpty()) return false
        val ok = lines.count { l -> listOf(" = ", " - ", " — ", " – ").any { it in l } }
        return ok >= lines.size * 0.7
    }
}
