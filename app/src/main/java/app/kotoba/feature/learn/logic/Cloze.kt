package app.kotoba.feature.learn.logic

import app.kotoba.core.model.StudyLanguage

/** Locates a card's word inside a sentence so it can be hidden or highlighted. */
object Cloze {
    const val BLANK = "＿＿＿"

    fun find(sentence: String, term: String, forms: String, lang: StudyLanguage): IntRange? {
        if (sentence.isBlank() || term.isBlank()) return null
        val candidates = (listOf(term) + AnswerMatcher.variants(term) + AnswerMatcher.variants(forms))
            .map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            .sortedByDescending { it.length }
        for (c in candidates) {
            val i = sentence.indexOf(c, ignoreCase = true)
            if (i >= 0) return wordAround(sentence, i, i + c.length, lang)
        }
        // Inflected forms in spaced languages: "decide" → "decided", "study" → "studies".
        if (lang.usesSpaces) {
            val base = AnswerMatcher.variants(term).first().removePrefix("to ").trim()
            if (base.length >= 4 && !base.contains(' ')) {
                val stem = base.dropLast(1)
                val i = sentence.indexOf(stem, ignoreCase = true)
                if (i >= 0 && (i == 0 || !sentence[i - 1].isLetter())) return wordAround(sentence, i, i + stem.length, lang)
            }
        }
        return null
    }

    /** Extends a match to whole-word boundaries for spaced languages. */
    private fun wordAround(s: String, start: Int, end: Int, lang: StudyLanguage): IntRange {
        if (!lang.usesSpaces) return start until end
        var e = end
        while (e < s.length && s[e].isLetter()) e++
        return start until e
    }

    fun blank(sentence: String, range: IntRange): String =
        sentence.replaceRange(range.first, range.last + 1, BLANK)
}
