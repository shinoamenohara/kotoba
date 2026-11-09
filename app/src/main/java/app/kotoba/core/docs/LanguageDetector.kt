package app.kotoba.core.docs

import app.kotoba.core.model.Script
import app.kotoba.core.model.StudyLanguage

/**
 * Script-ratio language guess for imported text. Kana → Japanese, Han without kana → Chinese,
 * Hangul → Korean, Cyrillic → Russian; Latin text uses stop words, then [fallback] if it is
 * Latin-script, else English.
 */
object LanguageDetector {
    private const val SAMPLE = 20_000

    private val stopWords: Map<StudyLanguage, Set<String>> = mapOf(
        StudyLanguage.EN to setOf("the", "and", "of", "to", "is", "in", "that", "it", "was", "you", "he", "with", "for", "this", "have", "are", "not", "but"),
        StudyLanguage.ES to setOf("el", "la", "que", "de", "y", "los", "las", "en", "un", "una", "por", "con", "no", "es", "se", "del", "para", "pero"),
        StudyLanguage.FR to setOf("le", "la", "les", "et", "de", "des", "un", "une", "est", "que", "qui", "dans", "pas", "ne", "il", "elle", "pour", "avec"),
        StudyLanguage.DE to setOf("der", "die", "das", "und", "ist", "nicht", "ich", "zu", "den", "mit", "sie", "ein", "eine", "es", "auf", "auch", "dem", "sich"),
        StudyLanguage.IT to setOf("il", "lo", "la", "di", "che", "e", "non", "un", "una", "per", "sono", "gli", "con", "del", "della", "ma", "mi", "si"),
        StudyLanguage.PT to setOf("o", "a", "os", "as", "de", "que", "e", "do", "da", "não", "um", "uma", "em", "para", "com", "por", "se", "mas"),
    )

    fun detect(text: String, fallback: StudyLanguage = StudyLanguage.EN): StudyLanguage {
        val sample = if (text.length > SAMPLE) text.substring(0, SAMPLE) else text
        var kana = 0; var han = 0; var hangul = 0; var cyr = 0; var latin = 0
        for (c in sample) {
            when {
                c in '\u3040'..'\u30FF' || c in '\u31F0'..'\u31FF' || c in '\uFF66'..'\uFF9D' -> kana++
                c in '\uAC00'..'\uD7AF' || c in '\u1100'..'\u11FF' || c in '\u3130'..'\u318F' -> hangul++
                Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN -> han++
                c in '\u0400'..'\u04FF' -> cyr++
                c.isLetter() && Character.UnicodeScript.of(c.code) == Character.UnicodeScript.LATIN -> latin++
            }
        }
        val cjk = kana + han
        val max = maxOf(cjk, hangul, cyr, latin)
        if (max == 0) return latinFallback(fallback)
        return when (max) {
            cjk -> if (kana > 0 && kana >= cjk * 0.05) StudyLanguage.JA else StudyLanguage.ZH
            hangul -> StudyLanguage.KO
            cyr -> StudyLanguage.RU
            else -> detectLatin(sample) ?: latinFallback(fallback)
        }
    }

    private fun latinFallback(fallback: StudyLanguage) = if (fallback.script == Script.LATIN) fallback else StudyLanguage.EN

    private fun detectLatin(sample: String): StudyLanguage? {
        val words = sample.lowercase().split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
        if (words.size < 8) return null
        val scores = stopWords.mapValues { (_, set) -> words.count { it in set } }
        val sorted = scores.entries.sortedByDescending { it.value }
        val best = sorted[0]
        val second = sorted[1].value
        // Needs a clear winner; short or mixed text falls back to the user's language.
        return if (best.value >= words.size * 0.08 && best.value > second * 1.3) best.key else null
    }
}
