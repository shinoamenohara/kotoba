package app.kotoba.core.model

import java.util.Locale

/** Proficiency scale used to tag a word's difficulty for a given language. */
enum class LevelScale(val levels: List<String>) {
    CEFR(listOf("A1", "A2", "B1", "B2", "C1", "C2")),
    JLPT(listOf("N5", "N4", "N3", "N2", "N1")),
    HSK(listOf("HSK1", "HSK2", "HSK3", "HSK4", "HSK5", "HSK6")),
    TOPIK(listOf("TOPIK1", "TOPIK2", "TOPIK3", "TOPIK4", "TOPIK5", "TOPIK6")),
}

enum class Script { LATIN, CJK, HANGUL, CYRILLIC, OTHER }

/**
 * Languages the user can study. English, Japanese and Chinese get first-class support
 * (readings, levels, OCR, segmentation); the rest use the generic pipeline.
 */
enum class StudyLanguage(
    val code: String,
    val nativeName: String,
    val flag: String,
    val levelScale: LevelScale,
    val script: Script,
    /** Words are separated by spaces (false → dictionary-based segmentation). */
    val usesSpaces: Boolean,
    /** Cards carry a reading line (kana for Japanese, pinyin for Chinese). */
    val hasReading: Boolean,
    val featured: Boolean = false,
) {
    EN("en", "English", "🇬🇧", LevelScale.CEFR, Script.LATIN, usesSpaces = true, hasReading = false, featured = true),
    JA("ja", "日本語", "🇯🇵", LevelScale.JLPT, Script.CJK, usesSpaces = false, hasReading = true, featured = true),
    ZH("zh", "中文", "🇨🇳", LevelScale.HSK, Script.CJK, usesSpaces = false, hasReading = true, featured = true),
    KO("ko", "한국어", "🇰🇷", LevelScale.TOPIK, Script.HANGUL, usesSpaces = true, hasReading = true),
    ES("es", "Español", "🇪🇸", LevelScale.CEFR, Script.LATIN, usesSpaces = true, hasReading = false),
    FR("fr", "Français", "🇫🇷", LevelScale.CEFR, Script.LATIN, usesSpaces = true, hasReading = false),
    DE("de", "Deutsch", "🇩🇪", LevelScale.CEFR, Script.LATIN, usesSpaces = true, hasReading = false),
    IT("it", "Italiano", "🇮🇹", LevelScale.CEFR, Script.LATIN, usesSpaces = true, hasReading = false),
    PT("pt", "Português", "🇵🇹", LevelScale.CEFR, Script.LATIN, usesSpaces = true, hasReading = false),
    RU("ru", "Русский", "🇷🇺", LevelScale.CEFR, Script.CYRILLIC, usesSpaces = true, hasReading = false),
    ;

    val locale: Locale get() = Locale.forLanguageTag(code)

    /** Name of the reading line shown in editors ("Kana", "Pinyin", …). */
    val readingLabel: String
        get() = when (this) {
            JA -> "かな"
            ZH -> "Pinyin"
            KO -> "Romanization"
            else -> "IPA"
        }

    companion object {
        fun fromCode(code: String?): StudyLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: EN

        val featuredLanguages: List<StudyLanguage> get() = entries.filter { it.featured }
    }
}

/** The learner's own language — translations are produced into it. */
enum class NativeLanguage(val code: String, val nativeName: String) {
    RU("ru", "Русский"),
    EN("en", "English"),
    UK("uk", "Українська"),
    ;

    companion object {
        fun fromCode(code: String?): NativeLanguage =
            entries.firstOrNull { it.code.equals(code, ignoreCase = true) } ?: RU
    }
}
