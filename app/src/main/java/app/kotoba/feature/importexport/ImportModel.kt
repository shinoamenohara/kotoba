package app.kotoba.feature.importexport

import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.importexport.anki.AnkiFields
import app.kotoba.feature.importexport.anki.AnkiProgress
import java.io.File

/** Where a source field goes on a Kotoba card. */
enum class FieldTarget {
    TERM, TRANSLATION, READING, IPA, EXAMPLE, EXAMPLE_TRANSLATION, NOTES, PART_OF_SPEECH, TAGS, AUDIO, IMAGE, IGNORE;

    val isMedia: Boolean get() = this == AUDIO || this == IMAGE
}

enum class ImportKind { ANKI, CSV, DICTIONARY, JSON, KOTOBA }

/** A set of records sharing the same field layout (an Anki note type or a table). */
data class FieldGroup(
    val id: Long,
    val name: String,
    val fields: List<String>,
    val count: Int,
    /** First few records' raw values, for preview. */
    val samples: List<List<String>>,
)

data class RawRecord(
    val groupId: Long,
    val fields: List<String>,
    val tags: List<String> = emptyList(),
    val deckName: String? = null,
    val progress: AnkiProgress? = null,
)

data class ParsedImport(
    val kind: ImportKind,
    val fileName: String,
    val groups: List<FieldGroup>,
    val records: List<RawRecord>,
    val sourceDecks: List<String> = emptyList(),
    /** Anki media by original file name. */
    val media: Map<String, File> = emptyMap(),
    val collectionCreatedSec: Long = 0,
    val warnings: List<String> = emptyList(),
    val kotoba: KotobaExport? = null,
    val workDir: File? = null,
    val suggestedLanguage: StudyLanguage? = null,
) {
    val total: Int get() = kotoba?.cards?.size ?: records.size
    val reviewCount: Int get() = records.count { it.progress?.type == 2 } + (kotoba?.cards?.count { it.state != "NEW" } ?: 0)
}

enum class DeckMode { SOURCE_DECKS, NEW, EXISTING }

data class ImportOptions(
    val language: StudyLanguage,
    val deckMode: DeckMode,
    val newDeckName: String = "",
    val existingDeckId: Long? = null,
    val skipDuplicates: Boolean = true,
    val importProgress: Boolean = true,
    val mapping: Map<Long, List<FieldTarget>> = emptyMap(),
)

data class ImportReport(
    val added: Int,
    val duplicates: Int,
    val errors: Int,
    val errorSamples: List<String> = emptyList(),
    val mediaFiles: Int = 0,
    val decks: Int = 0,
    val withProgress: Int = 0,
    val warnings: List<String> = emptyList(),
)

/** Card values extracted from one record with a mapping. */
data class CardDraft(
    val term: String,
    val translation: String,
    val reading: String = "",
    val ipa: String = "",
    val example: String = "",
    val exampleTranslation: String = "",
    val notes: String = "",
    val partOfSpeech: String = "",
    val tags: List<String> = emptyList(),
    val audioName: String? = null,
    val imageName: String? = null,
)

object RecordMapper {
    /** Returns a draft or null when no term could be extracted. */
    fun toDraft(record: RawRecord, targets: List<FieldTarget>, html: Boolean): CardDraft? {
        val values = HashMap<FieldTarget, MutableList<String>>()
        var audio: String? = null
        var image: String? = null
        record.fields.forEachIndexed { i, raw ->
            val target = targets.getOrElse(i) { FieldTarget.IGNORE }
            when (target) {
                FieldTarget.AUDIO -> audio = audio ?: AnkiFields.sounds(raw).firstOrNull() ?: raw.trim().takeIf { !html && it.isNotEmpty() }
                FieldTarget.IMAGE -> image = image ?: AnkiFields.images(raw).firstOrNull() ?: raw.trim().takeIf { !html && it.isNotEmpty() }
                FieldTarget.IGNORE -> Unit
                else -> {
                    val text = if (html) AnkiFields.plainText(raw) else raw.trim()
                    if (text.isNotEmpty()) values.getOrPut(target) { mutableListOf() } += text
                }
            }
        }
        // Media embedded in text fields still counts when no explicit audio/image column is mapped.
        if (html) {
            if (audio == null) audio = record.fields.firstNotNullOfOrNull { AnkiFields.sounds(it).firstOrNull() }
            if (image == null) image = record.fields.firstNotNullOfOrNull { AnkiFields.images(it).firstOrNull() }
        }
        fun v(t: FieldTarget, sep: String = "\n") = values[t].orEmpty().joinToString(sep)
        val term = v(FieldTarget.TERM, " ").trim()
        if (term.isEmpty()) return null
        val extraTags = values[FieldTarget.TAGS].orEmpty().flatMap { it.split(',', ';', ' ') }.map { it.trim() }.filter { it.isNotEmpty() }
        return CardDraft(
            term = term,
            translation = v(FieldTarget.TRANSLATION, "; "),
            reading = v(FieldTarget.READING, " "),
            ipa = v(FieldTarget.IPA, " "),
            example = v(FieldTarget.EXAMPLE),
            exampleTranslation = v(FieldTarget.EXAMPLE_TRANSLATION),
            notes = v(FieldTarget.NOTES),
            partOfSpeech = v(FieldTarget.PART_OF_SPEECH, ", "),
            tags = (record.tags + extraTags).distinct(),
            audioName = audio,
            imageName = image,
        )
    }
}

/** Guesses field roles from names like Front/Back, Expression/Meaning/Reading, Hanzi/Pinyin/English. */
object SmartMapping {
    private fun norm(s: String) = s.lowercase().replace(Regex("[\\s_\\-.:/()]+"), "")

    private val languageWords = mapOf(
        StudyLanguage.EN to listOf("english", "английский"),
        StudyLanguage.JA to listOf("japanese", "日本語", "японский"),
        StudyLanguage.ZH to listOf("chinese", "中文", "китайский", "mandarin"),
        StudyLanguage.KO to listOf("korean", "한국어"),
        StudyLanguage.ES to listOf("spanish", "español"),
        StudyLanguage.FR to listOf("french", "français"),
        StudyLanguage.DE to listOf("german", "deutsch"),
        StudyLanguage.IT to listOf("italian", "italiano"),
        StudyLanguage.PT to listOf("portuguese", "português"),
        StudyLanguage.RU to listOf("russian", "русский"),
    )

    fun guess(name: String, lang: StudyLanguage?): FieldTarget {
        val n = norm(name)
        fun has(vararg k: String) = k.any { n.contains(it) }
        if (lang != null && languageWords[lang].orEmpty().any { n == norm(it) }) return FieldTarget.TERM
        return when {
            has("sentence", "example", "пример") && has("meaning", "translation", "english", "russian", "перевод") -> FieldTarget.EXAMPLE_TRANSLATION
            has("audio", "sound", "аудио", "звук") -> FieldTarget.AUDIO
            has("image", "picture", "pic", "img", "photo", "картинка") -> FieldTarget.IMAGE
            has("reading", "pinyin", "kana", "furigana", "hiragana", "romaji", "zhuyin", "чтение") -> FieldTarget.READING
            has("ipa", "phonetic", "transcription", "транскрипция") -> FieldTarget.IPA
            has("pronunciation") -> if (lang?.hasReading == true) FieldTarget.READING else FieldTarget.IPA
            has("sentence", "example", "context", "пример") -> FieldTarget.EXAMPLE
            n == "pos" || has("partofspeech", "wordtype", "часть") -> FieldTarget.PART_OF_SPEECH
            n == "tags" || n == "tag" || n == "теги" -> FieldTarget.TAGS
            has("note", "extra", "remark", "comment", "mnemonic", "hint", "заметк") -> FieldTarget.NOTES
            // "Vocabulary-English" is a translation, so meaning-like words win over term-like ones.
            has("meaning", "translation", "english", "definition", "gloss", "russian", "перевод", "значение") -> FieldTarget.TRANSLATION
            has("front", "expression", "word", "hanzi", "kanji", "vocab", "term", "simplified", "traditional", "question", "target", "слово", "lemma", "headword") -> FieldTarget.TERM
            has("back", "answer") -> FieldTarget.TRANSLATION
            else -> FieldTarget.IGNORE
        }
    }

    fun defaults(fields: List<String>, lang: StudyLanguage?): List<FieldTarget> {
        val result = fields.map { guess(it, lang) }.toMutableList()
        val unique = setOf(FieldTarget.TERM, FieldTarget.READING, FieldTarget.IPA, FieldTarget.AUDIO, FieldTarget.IMAGE)
        unique.forEach { t ->
            result.indices.filter { result[it] == t }.drop(1).forEach { result[it] = FieldTarget.IGNORE }
        }
        if (FieldTarget.TERM !in result) result.indexOfFirst { it == FieldTarget.IGNORE }.takeIf { it >= 0 }?.let { result[it] = FieldTarget.TERM }
        if (FieldTarget.TRANSLATION !in result) result.indexOfFirst { it == FieldTarget.IGNORE }.takeIf { it >= 0 }?.let { result[it] = FieldTarget.TRANSLATION }
        return result
    }

    /** Detects the study language from term samples by script. */
    fun detectLanguage(samples: List<String>): StudyLanguage? {
        val text = samples.joinToString(" ")
        var kana = 0; var han = 0; var hangul = 0; var cyr = 0; var latin = 0
        text.forEach { c ->
            when (Character.UnicodeBlock.of(c)) {
                Character.UnicodeBlock.HIRAGANA, Character.UnicodeBlock.KATAKANA -> kana++
                Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS -> han++
                Character.UnicodeBlock.HANGUL_SYLLABLES -> hangul++
                Character.UnicodeBlock.CYRILLIC -> cyr++
                else -> if (c in 'a'..'z' || c in 'A'..'Z') latin++
            }
        }
        val total = kana + han + hangul + cyr + latin
        if (total == 0) return null
        return when {
            kana > 0 && kana + han > total / 3 -> StudyLanguage.JA
            han > total / 3 -> StudyLanguage.ZH
            hangul > total / 3 -> StudyLanguage.KO
            cyr > total / 2 -> StudyLanguage.RU
            latin > total / 2 -> StudyLanguage.EN
            else -> null
        }
    }
}
