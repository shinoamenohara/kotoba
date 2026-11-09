package app.kotoba.feature.library

import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.model.Mastery
import app.kotoba.core.srs.LearningSnapshot

/** Parsed form of [app.kotoba.core.navigation.WordListRoute.filter]. */
sealed interface WordFilter {
    data object All : WordFilter
    data object Favorites : WordFilter
    data object Weak : WordFilter
    data class ByMastery(val mastery: Mastery) : WordFilter
    data class ByDeck(val deckId: Long) : WordFilter
    data class ByCategory(val key: String) : WordFilter

    fun encode(): String = when (this) {
        All -> "all"
        Favorites -> "favorites"
        Weak -> "weak"
        is ByMastery -> "mastery:${mastery.name}"
        is ByDeck -> "deck:$deckId"
        is ByCategory -> "category:$key"
    }

    companion object {
        /** Unknown or malformed filters fall back to [All]. */
        fun parse(raw: String?): WordFilter {
            val s = raw?.trim().orEmpty()
            val kind = s.substringBefore(':').lowercase()
            val arg = s.substringAfter(':', "").trim()
            return when (kind) {
                "favorites", "favourites" -> Favorites
                "weak" -> Weak
                "mastery" -> Mastery.entries.firstOrNull { it.name.equals(arg, ignoreCase = true) }?.let(::ByMastery) ?: All
                "deck" -> arg.toLongOrNull()?.takeIf { it > 0 }?.let(::ByDeck) ?: All
                "category" -> arg.takeIf { it.isNotEmpty() }?.let(::ByCategory) ?: All
                else -> All
            }
        }
    }
}

enum class WordSort(val key: String) { RECENT("recent"), ALPHA("alpha"), DUE("due"), WEAK("weak") }

/**
 * Finds a word inside an example sentence so it can be hidden ("hide the word" cloze).
 * Handles inflected forms for spaced languages (go→goes, stop→stopped, study→studied, phrasal verbs)
 * and okurigana changes for Japanese (食べる→食べました).
 */
object Cloze {
    const val BLANK_SPACED = "_____"
    const val BLANK_CJK = "＿＿"

    fun mask(sentence: String, term: String, usesSpaces: Boolean, forms: List<String> = emptyList()): String {
        val spans = find(sentence, term, usesSpaces, forms)
        if (spans.isEmpty()) return sentence
        val blank = if (usesSpaces) BLANK_SPACED else BLANK_CJK
        val sb = StringBuilder()
        var last = 0
        spans.forEach { r ->
            sb.append(sentence, last, r.first).append(blank)
            last = r.last + 1
        }
        sb.append(sentence, last, sentence.length)
        return sb.toString()
    }

    /** Non-overlapping, sorted ranges of the word's occurrences. */
    fun find(sentence: String, term: String, usesSpaces: Boolean, forms: List<String> = emptyList()): List<IntRange> {
        val t = term.trim()
        if (t.isEmpty() || sentence.isEmpty()) return emptyList()
        val extra = forms.map { it.trim() }.filter { it.isNotEmpty() }
        return if (usesSpaces) findSpaced(sentence, t, extra) else findUnspaced(sentence, t, extra)
    }

    private fun findSpaced(sentence: String, term: String, forms: List<String>): List<IntRange> {
        val words = term.split(Regex("\\s+"))
        val head = words.first().lowercase()
        val tail = words.drop(1).joinToString(" ")
        val candidates = linkedSetOf(term.lowercase())
        forms.forEach { candidates += it.lowercase() }
        inflections(head).forEach { candidates += if (tail.isEmpty()) it else "$it $tail".lowercase() }
        // Longest first so "studied hard" wins over "studied".
        val spans = candidates.sortedByDescending { it.length }.flatMap { c ->
            Regex("(?<![\\p{L}\\p{N}])" + Regex.escape(c) + "(?![\\p{L}\\p{N}])", RegexOption.IGNORE_CASE)
                .findAll(sentence).map { it.range }.toList()
        }
        val merged = merge(spans)
        if (merged.isNotEmpty() || words.size > 1) return merged
        return stemFallback(sentence, head)
    }

    /** For inflected languages without explicit forms (ru, es, de…): token sharing a ≥4-letter stem. */
    private fun stemFallback(sentence: String, word: String): List<IntRange> {
        if (word.length < 4) return emptyList()
        val stem = word.take(maxOf(4, word.length - 2))
        return Regex("[\\p{L}\\p{N}'’-]+").findAll(sentence)
            .filter { m ->
                val tok = m.value.lowercase()
                tok.startsWith(stem) && tok.length <= word.length + 4
            }
            .map { it.range }.toList()
    }

    /** Regular English inflections; harmless extra candidates for other Latin-script languages. */
    internal fun inflections(word: String): Set<String> {
        val w = word.lowercase()
        if (w.isEmpty()) return emptySet()
        val out = linkedSetOf(w, w + "s", w + "es", w + "ed", w + "d", w + "ing", w + "er", w + "est", w + "ly")
        val vowels = "aeiou"
        if (w.endsWith("e") && w.length > 2) {
            val b = w.dropLast(1)
            out += listOf(b + "ing", b + "ed", b + "er", b + "est")
        }
        if (w.endsWith("ie") && w.length > 2) out += w.dropLast(2) + "ying"
        if (w.length > 2 && w.endsWith("y") && w[w.length - 2] !in vowels) {
            val b = w.dropLast(1)
            out += listOf(b + "ies", b + "ied", b + "ier", b + "iest", b + "ily")
        }
        // Consonant doubling: stop → stopped/stopping, big → bigger.
        if (w.length in 2..6) {
            val c = w.last()
            val v = w[w.length - 2]
            val c0 = w.getOrNull(w.length - 3)
            if (c !in vowels && c !in "wxy" && v in vowels && (c0 == null || c0 !in vowels)) {
                out += listOf(w + c + "ed", w + c + "ing", w + c + "er", w + c + "est")
            }
        }
        return out
    }

    private fun findUnspaced(sentence: String, term: String, forms: List<String>): List<IntRange> {
        val candidates = (listOf(term) + forms).distinct().sortedByDescending { it.length }
        val direct = merge(candidates.flatMap { occurrences(sentence, it) })
        if (direct.isNotEmpty()) return direct
        val stem = japaneseStem(term) ?: return emptyList()
        // Hide the stem plus any trailing okurigana so the conjugation is not a giveaway.
        return merge(
            occurrences(sentence, stem).map { r ->
                var end = r.last
                while (end + 1 < sentence.length && isHiragana(sentence[end + 1]) && end - r.last < 4) end++
                r.first..end
            },
        )
    }

    /** 食べる → 食べ, 行く → 行; null when the remainder would be meaningless (pure kana ≤1 char). */
    internal fun japaneseStem(term: String): String? {
        if (term.length < 2 || !isHiragana(term.last())) return null
        val stem = term.dropLast(1)
        val hasKanji = stem.any { !isHiragana(it) && !isKatakana(it) }
        return stem.takeIf { hasKanji || it.length >= 2 }
    }

    private fun occurrences(text: String, needle: String): List<IntRange> {
        if (needle.isEmpty()) return emptyList()
        val out = mutableListOf<IntRange>()
        var i = text.indexOf(needle, ignoreCase = true)
        while (i >= 0) {
            out += i until i + needle.length
            i = text.indexOf(needle, i + needle.length, ignoreCase = true)
        }
        return out
    }

    private fun merge(ranges: List<IntRange>): List<IntRange> {
        val out = mutableListOf<IntRange>()
        ranges.sortedWith(compareBy<IntRange> { it.first }.thenByDescending { it.last }).forEach { r ->
            val prev = out.lastOrNull()
            if (prev != null && r.first <= prev.last) {
                if (r.last > prev.last) out[out.lastIndex] = prev.first..r.last
            } else {
                out += r
            }
        }
        return out
    }

    private fun isHiragana(c: Char) = c in '\u3041'..'\u309F'
    private fun isKatakana(c: Char) = c in '\u30A0'..'\u30FF'
}

/** Splits comma/、/;-separated lists stored in card fields. */
fun splitList(raw: String): List<String> =
    raw.split(',', '、', ';', '，', '；', '\n').map { it.trim() }.filter { it.isNotEmpty() }

/** Weighted progress of a word group: NEW 0, LEARNING ⅓, FAMILIAR ⅔, MASTERED 1. */
fun masteryProgress(counts: Map<Mastery, Int>): Float {
    val total = counts.values.sum()
    if (total == 0) return 0f
    val score = counts.entries.sumOf { (m, c) -> m.ordinal * c }
    return score.toFloat() / (total * Mastery.MASTERED.ordinal)
}

/** Rebuilds the radar input from stored card stats and its review history (most recent first). */
fun snapshotOf(card: CardEntity, history: List<ReviewLogEntity>, retrievability: Double, confusions: Int) = LearningSnapshot(
    state = card.state,
    stability = card.stability,
    reps = card.reps,
    lapses = card.lapses,
    correctCount = card.correctCount,
    wrongCount = card.wrongCount,
    avgResponseMs = card.avgResponseMs,
    retrievability = retrievability,
    recentResults = history.map { it.correct },
    correctExerciseTypes = history.filter { it.correct }.map { it.exercise }.toSet(),
    previous = card.mastery,
    confusionCount = confusions,
)

/** Preset deck colours (ARGB). */
val DeckColors: List<Long> = listOf(
    0xFF6750A4, 0xFF3B82F6, 0xFF10B981, 0xFFF59E0B, 0xFFEF4444, 0xFFEC4899, 0xFF14B8A6, 0xFF8B5CF6, 0xFF64748B,
)
