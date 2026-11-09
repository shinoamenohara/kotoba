package app.kotoba.core.lang

import android.icu.text.BreakIterator
import android.icu.text.Transliterator
import android.os.Build
import app.kotoba.core.model.StudyLanguage
import javax.inject.Inject
import javax.inject.Singleton

data class Token(val start: Int, val end: Int, val text: String, val isWord: Boolean)

/**
 * Splits text into tappable words. Uses ICU's dictionary-based word breaker, which segments
 * Japanese and Chinese (no spaces) as well as space-delimited languages.
 */
@Singleton
class TextSegmenter @Inject constructor() {
    fun tokenize(text: String, lang: StudyLanguage): List<Token> {
        if (text.isEmpty()) return emptyList()
        val it = BreakIterator.getWordInstance(lang.locale)
        it.setText(text)
        val out = ArrayList<Token>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            val piece = text.substring(start, end)
            val status = it.ruleStatus
            val isWord = status != BreakIterator.WORD_NONE || piece.any { ch -> Character.isLetterOrDigit(ch) }
            out += Token(start, end, piece, isWord && piece.isNotBlank())
            start = end
            end = it.next()
        }
        return out
    }

    fun sentences(text: String, lang: StudyLanguage): List<IntRange> {
        val it = BreakIterator.getSentenceInstance(lang.locale)
        it.setText(text)
        val out = ArrayList<IntRange>()
        var start = it.first()
        var end = it.next()
        while (end != BreakIterator.DONE) {
            out += start until end
            start = end
            end = it.next()
        }
        return out
    }

    /** The sentence around [offset] — saved as the context of a word picked in the reader. */
    fun sentenceAt(text: String, offset: Int, lang: StudyLanguage): String =
        sentences(text, lang).firstOrNull { offset in it }?.let { text.substring(it.first, it.last + 1).trim() } ?: ""

    /** Unique words of a text with frequencies — for deck generation and book statistics. */
    fun wordFrequencies(text: String, lang: StudyLanguage): Map<String, Int> =
        tokenize(text, lang).asSequence()
            .filter { it.isWord && it.text.any(Char::isLetter) }
            .map { if (lang.usesSpaces) it.text.lowercase(lang.locale) else it.text }
            .groupingBy { it }.eachCount()
}

/** Readings: pinyin for Chinese, rōmaji for kana, romanization for Korean (ICU transliteration). */
@Singleton
class Romanizer @Inject constructor() {
    private val cache = HashMap<String, Transliterator?>()

    private fun transliterator(id: String): Transliterator? {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return null
        return cache.getOrPut(id) { runCatching { Transliterator.getInstance(id) }.getOrNull() }
    }

    /** Pinyin with tone marks (zh), hiragana→rōmaji (ja kana only), RR (ko). Null if unsupported. */
    fun romanize(text: String, lang: StudyLanguage): String? = when (lang) {
        StudyLanguage.ZH -> transliterator("Han-Latin")?.transliterate(text)
        StudyLanguage.JA -> if (text.all { isKana(it) || !it.isLetter() }) transliterator("Any-Latin")?.transliterate(text) else null
        StudyLanguage.KO -> transliterator("Hangul-Latin")?.transliterate(text)
        else -> null
    }

    fun katakanaToHiragana(text: String): String =
        text.map { c -> if (c in '\u30A1'..'\u30F6') (c - 0x60) else c }.joinToString("")

    companion object {
        fun isKana(c: Char): Boolean = c in '\u3040'..'\u30FF' || c == 'ー'
        fun isHan(c: Char): Boolean = Character.UnicodeScript.of(c.code) == Character.UnicodeScript.HAN
        fun containsCjk(text: String): Boolean = text.any { isHan(it) || isKana(it) }
    }
}
