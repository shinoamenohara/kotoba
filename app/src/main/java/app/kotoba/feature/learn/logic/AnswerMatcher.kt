package app.kotoba.feature.learn.logic

import app.kotoba.core.model.StudyLanguage
import java.text.Normalizer

enum class MatchResult { EXACT, CLOSE, WRONG }

/**
 * Tolerant comparison for typed answers: ignores case, whitespace, punctuation and
 * diacritics; accepts the kana reading for Japanese and pinyin (with or without tones)
 * for Chinese.
 */
object AnswerMatcher {
    private val combiningMarks = Regex("\\p{Mn}+")
    // Kana (han)dakuten are meaningful: が ≠ か, ぱ ≠ は.
    private val latinMarks = Regex("[\\p{Mn}&&[^\\u3099\\u309A]]+")
    private val nonLetterOrDigit = Regex("[^\\p{L}\\p{N}]+")
    private val toneDigits = Regex("[1-5]")

    /** Case/width/diacritic/punctuation-insensitive form. Kept for CJK characters. */
    fun normalize(text: String, stripDiacritics: Boolean = true): String {
        // NFKC folds full-width letters (ＡＢＣ, ！) to their ASCII forms first.
        var s = Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase()
        if (stripDiacritics) {
            s = Normalizer.normalize(s, Normalizer.Form.NFD).replace(latinMarks, "")
            s = Normalizer.normalize(s, Normalizer.Form.NFC)
        }
        return s.replace(nonLetterOrDigit, "")
    }

    /** "nǐ hǎo", "ni3 hao3", "Ni Hao", "nü / nv / nu:" → "nihao", "nu". */
    fun normalizePinyin(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(combiningMarks, "")
        return decomposed
            .replace("u:", "u")
            .replace('v', 'u')
            .replace(toneDigits, "")
            .replace(nonLetterOrDigit, "")
    }

    fun katakanaToHiragana(text: String): String =
        buildString(text.length) { text.forEach { c -> append(if (c in '\u30A1'..'\u30F6') c - 0x60 else c) } }

    /** Alternatives inside one field: "dog, hound; pup" or "犬 / いぬ". */
    fun variants(field: String): List<String> =
        field.split(',', ';', '/', '、', '，', '；', '|')
            .map { it.trim().replace(Regex("\\s*\\(.*?\\)\\s*"), " ").trim() }
            .filter { it.isNotEmpty() }
            .ifEmpty { listOf(field.trim()) }

    /**
     * Checks [input] against [expected] (the card term) and, for languages with readings,
     * the [reading] line.
     */
    fun match(input: String, expected: String, reading: String, lang: StudyLanguage): MatchResult {
        if (input.isBlank()) return MatchResult.WRONG
        val candidates = buildList {
            add(expected)
            addAll(variants(expected))
            if (lang == StudyLanguage.EN) variants(expected).forEach { v ->
                // Accept "go" for "to go" and vice versa.
                if (v.startsWith("to ", ignoreCase = true)) add(v.substring(3)) else add("to $v")
            }
        }.distinct()
        val typed = normalize(input)
        if (candidates.any { normalize(it) == typed }) return MatchResult.EXACT

        if (reading.isNotBlank()) {
            when (lang) {
                StudyLanguage.JA -> {
                    val kana = normalize(katakanaToHiragana(input), stripDiacritics = false)
                    if (variants(reading).any { normalize(katakanaToHiragana(it), stripDiacritics = false) == kana }) return MatchResult.EXACT
                }
                StudyLanguage.ZH -> {
                    val py = normalizePinyin(input)
                    if (py.isNotEmpty() && variants(reading).any { normalizePinyin(it) == py }) return MatchResult.EXACT
                }
                else -> if (variants(reading).any { normalize(it) == typed }) return MatchResult.EXACT
            }
        }

        // One typo is "almost" for longer words in alphabetic scripts.
        val closeEnough = candidates.any { c ->
            val n = normalize(c)
            n.length >= 5 && !n.any { isCjk(it) } && levenshtein(n, typed) <= 1
        }
        return if (closeEnough) MatchResult.CLOSE else MatchResult.WRONG
    }

    /** Matches against any variant of a translation field (used for "type the meaning"). */
    fun matchMeaning(input: String, translation: String): MatchResult {
        val typed = normalize(input)
        if (typed.isEmpty()) return MatchResult.WRONG
        val vs = variants(translation).map { normalize(it) } + normalize(translation)
        if (vs.any { it == typed }) return MatchResult.EXACT
        return if (vs.any { it.length >= 5 && levenshtein(it, typed) <= 1 }) MatchResult.CLOSE else MatchResult.WRONG
    }

    fun isCjk(c: Char): Boolean {
        val script = Character.UnicodeScript.of(c.code)
        return script == Character.UnicodeScript.HAN || script == Character.UnicodeScript.HIRAGANA ||
            script == Character.UnicodeScript.KATAKANA || script == Character.UnicodeScript.HANGUL
    }

    fun levenshtein(a: String, b: String): Int {
        if (a == b) return 0
        if (a.isEmpty()) return b.length
        if (b.isEmpty()) return a.length
        var prev = IntArray(b.length + 1) { it }
        var cur = IntArray(b.length + 1)
        for (i in 1..a.length) {
            cur[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(cur[j - 1] + 1, prev[j] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return prev[b.length]
    }
}
