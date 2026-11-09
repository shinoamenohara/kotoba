package app.kotoba.feature.ai

import app.kotoba.core.ai.AiPrompts
import app.kotoba.core.lang.Token
import app.kotoba.core.model.Category
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.StudyLanguage
import kotlinx.serialization.Serializable

enum class FrequencyFilter { ANY, COMMON, RARE }

data class GeneratorOptions(
    val count: Int = 30,
    /** Empty = any level. */
    val level: String = "",
    val frequency: FrequencyFilter = FrequencyFilter.ANY,
    val excludeKnown: Boolean = true,
    val excludeDuplicates: Boolean = true,
    /** Empty = all parts of speech. */
    val partsOfSpeech: Set<PartOfSpeech> = emptySet(),
    val topic: String = "",
    val generateExamples: Boolean = true,
    val deckName: String = "",
)

/** A word found in the text, with how often it occurs and the first sentence it appears in. */
data class Candidate(val term: String, val count: Int, val sentence: String)

object CandidateExtractor {
    /** Hard cap on how much text is segmented locally (very long books are sampled from the start). */
    const val MAX_TEXT_CHARS = 300_000

    private val enStop = """
        a an the and or but if then else of to in on at by for with from as into onto about over under than so such
        is am are was were be been being do does did done doing have has had having will would shall should can could may might must
        i me my mine we us our ours you your yours he him his she her hers it its they them their theirs this that these those
        who whom whose which what when where why how all any both each few more most other some no nor not only own same too very
        just also there here up down out off again further once s t don didn doesn isn wasn aren weren won wouldn couldn shouldn
        let lets get got go goes went say said one two yes oh ok okay mr mrs ms im ive id youre theyre thats dont cant
    """.trimIndent().split(Regex("\\s+")).toHashSet()

    private val jaStop = setOf(
        "です", "ます", "でした", "ました", "ません", "する", "した", "して", "します", "いる", "いた", "いて", "ある", "あった", "ない", "なかった",
        "こと", "もの", "ところ", "これ", "それ", "あれ", "どれ", "この", "その", "あの", "どの", "ここ", "そこ", "あそこ", "そう", "よう",
        "から", "まで", "より", "など", "けど", "だけ", "ので", "のに", "でも", "って", "という", "なる", "なった", "れる", "られる", "せる",
        "だ", "だった", "では", "には", "とは", "ね", "よ", "な", "か", "わ", "ぞ", "さ",
    )

    private val zhStop = setOf(
        "的", "了", "是", "在", "不", "我", "你", "他", "她", "它", "们", "我们", "你们", "他们", "这", "那", "这个", "那个", "和", "也", "就", "都",
        "有", "说", "吗", "呢", "吧", "啊", "呀", "着", "过", "得", "地", "很", "还", "又", "一", "个", "把", "被", "给", "让", "从", "到",
        "对", "要", "会", "能", "没", "没有", "什么", "怎么", "因为", "所以", "但是", "如果", "可以", "自己", "这样", "那样", "一个",
    )

    /** Same normalisation as [app.kotoba.core.lang.TextSegmenter.wordFrequencies]. */
    fun normalize(word: String, lang: StudyLanguage): String =
        if (lang.usesSpaces) word.lowercase(lang.locale) else word

    fun isCandidate(word: String, lang: StudyLanguage): Boolean {
        val w = word.trim().trim('\'', '’', '-', '_')
        if (w.isEmpty() || w.none { it.isLetter() }) return false
        if (w.any { it.isDigit() }) return false
        if (w.any { !it.isLetter() && it != '\'' && it != '’' && it != '-' && it != 'ー' && it != '々' }) return false
        return when (lang) {
            StudyLanguage.JA -> {
                if (w in jaStop) return false
                val allKana = w.all { isKana(it) }
                // Single kana and short pure-hiragana chunks are almost always particles/auxiliaries.
                !(allKana && w.length == 1) && !(w.all { it in '\u3040'..'\u309F' } && w.length <= 2)
            }
            StudyLanguage.ZH -> w !in zhStop
            StudyLanguage.EN -> w.length >= 2 && w.lowercase() !in enStop
            else -> if (lang.usesSpaces) w.length >= 3 else w.length >= 1
        }
    }

    private fun isKana(c: Char) = c in '\u3040'..'\u30FF' || c == 'ー'

    /**
     * Local candidate extraction: counts words, drops noise and known words, orders by the frequency
     * filter and attaches the first sentence each word occurs in.
     */
    fun extract(
        tokens: List<Token>,
        lang: StudyLanguage,
        known: Set<String>,
        excludeKnown: Boolean,
        frequency: FrequencyFilter,
        limit: Int,
        sentenceAt: (Int) -> String,
    ): List<Candidate> {
        val counts = LinkedHashMap<String, Int>()
        val firstOffset = HashMap<String, Int>()
        for (t in tokens) {
            if (!t.isWord || !t.text.any(Char::isLetter)) continue
            val key = normalize(t.text, lang)
            if (!isCandidate(key, lang)) continue
            if (excludeKnown && key.lowercase() in known) continue
            counts[key] = (counts[key] ?: 0) + 1
            firstOffset.putIfAbsent(key, t.start)
        }
        val ordered = when (frequency) {
            FrequencyFilter.COMMON -> counts.entries.sortedByDescending { it.value }
            // Words seen once are often names/typos — prefer 2+ occurrences when the text is long enough.
            FrequencyFilter.RARE -> counts.entries.sortedWith(compareBy({ if (it.value == 1) 1 else 0 }, { it.value }))
            // Repeated words first (capped so very frequent ones don't dominate), then first-appearance order.
            FrequencyFilter.ANY -> counts.entries.sortedByDescending { it.value.coerceAtMost(3) }
        }
        return ordered.take(limit).map { e -> Candidate(e.key, e.value, sentenceAt(firstOffset.getValue(e.key)).take(300)) }
    }

    /** Finds the sentence containing [offset] among pre-computed sentence ranges (sorted). */
    fun sentenceFor(text: String, ranges: List<IntRange>, offset: Int): String {
        var lo = 0
        var hi = ranges.lastIndex
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            val r = ranges[mid]
            when {
                offset < r.first -> hi = mid - 1
                offset > r.last -> lo = mid + 1
                else -> return text.substring(r.first, (r.last + 1).coerceAtMost(text.length)).trim()
            }
        }
        return ""
    }
}

@Serializable
data class GeneratedWordDto(
    val term: String = "",
    val reading: String = "",
    val ipa: String = "",
    val translation: String = "",
    val partOfSpeech: String = "",
    val level: String = "",
    val category: String = "",
    val example: String = "",
    val exampleTranslation: String = "",
    /** 1 = easy … 3 = hard (models sometimes answer "easy"/"hard", see [Difficulty.parse]). */
    val difficulty: String = "",
)

@Serializable
data class GeneratedWordsDto(val words: List<GeneratedWordDto> = emptyList())

enum class Difficulty {
    EASY, MEDIUM, HARD;

    companion object {
        fun parse(raw: String): Difficulty = when (raw.trim().lowercase()) {
            "1", "easy", "low", "beginner" -> EASY
            "3", "4", "5", "hard", "high", "advanced", "difficult" -> HARD
            else -> MEDIUM
        }

        fun fromCount(count: Int, maxCount: Int): Difficulty = when {
            maxCount <= 1 -> MEDIUM
            count >= maxCount * 0.5 -> EASY
            count <= 1 -> HARD
            else -> MEDIUM
        }
    }
}

/** An editable row on the review screen. */
data class GeneratedWord(
    val id: Int,
    val term: String,
    val reading: String = "",
    val ipa: String = "",
    val translation: String = "",
    val partOfSpeech: String = "",
    val level: String = "",
    val category: Category = Category.OTHER,
    val example: String = "",
    val exampleTranslation: String = "",
    /** Sentence from the source text, saved as the card's context. */
    val sourceSentence: String = "",
    val difficulty: Difficulty = Difficulty.MEDIUM,
    val selected: Boolean = false,
)

object GeneratorPrompts {
    const val CHUNK_SIZE = 60
    const val EXCERPT_CHARS = 1500

    fun chunks(candidates: List<Candidate>, size: Int = CHUNK_SIZE): List<List<Candidate>> = candidates.chunked(size.coerceAtLeast(1))

    fun prompt(
        lang: StudyLanguage,
        native: NativeLanguage,
        options: GeneratorOptions,
        candidates: List<Candidate>,
        excerpt: String,
        wanted: Int,
    ): String = buildString {
        appendLine("You help a learner of ${lang.name} build a vocabulary deck from a text they are reading.")
        appendLine(AiPrompts.languageGuide(lang))
        if (excerpt.isNotBlank()) appendLine("Text excerpt (for context):\n\"\"\"\n${excerpt.take(EXCERPT_CHARS)}\n\"\"\"")
        appendLine("Candidate words with the sentence where they occur (term | count | sentence):")
        candidates.forEach { c -> appendLine("- ${c.term} | ${c.count} | ${c.sentence.replace('\n', ' ').take(200)}") }
        appendLine()
        append("Pick up to $wanted items that are most likely unknown and worth learning")
        if (options.level.isNotBlank()) append(" for a learner around level ${options.level}")
        appendLine(". Skip proper names, typos and trivial function words. You may merge inflected forms and include set expressions from the excerpt.")
        if (options.partsOfSpeech.isNotEmpty()) appendLine("Only these parts of speech: ${options.partsOfSpeech.joinToString { it.key }}.")
        if (options.topic.isNotBlank()) appendLine("Prefer words related to the topic: ${options.topic}.")
        when (options.frequency) {
            FrequencyFilter.COMMON -> appendLine("Prefer common, high-frequency vocabulary.")
            FrequencyFilter.RARE -> appendLine("Prefer rarer, more advanced vocabulary.")
            FrequencyFilter.ANY -> Unit
        }
        appendLine("Translate into ${AiPrompts.nativeName(native)}.")
        if (options.generateExamples) {
            appendLine("For `example` reuse the sentence from the text when it is clear and short; otherwise write a natural one. Add `exampleTranslation`.")
        } else {
            appendLine("Leave `example` and `exampleTranslation` empty.")
        }
        appendLine(
            "Return JSON: {\"words\":[{\"term\" (dictionary form),\"reading\",\"ipa\",\"translation\",\"partOfSpeech\" " +
                "(${PartOfSpeech.entries.joinToString("|") { it.key }}),\"level\",\"category\" " +
                "(${Category.entries.joinToString("|") { it.key }}),\"example\",\"exampleTranslation\",\"difficulty\" (1 easy, 2 medium, 3 hard)}]}",
        )
    }

    /** Converts model rows to review rows, attaching source sentences and dropping duplicates/known words. */
    fun toWords(
        dtos: List<GeneratedWordDto>,
        candidates: List<Candidate>,
        lang: StudyLanguage,
        known: Set<String>,
        excludeKnown: Boolean,
        partsOfSpeech: Set<PartOfSpeech>,
        startId: Int = 0,
    ): List<GeneratedWord> {
        val sentenceByTerm = candidates.associate { CandidateExtractor.normalize(it.term, lang) to it.sentence }
        val seen = HashSet<String>()
        var id = startId
        return dtos.mapNotNull { d ->
            val term = d.term.trim()
            val key = term.lowercase()
            if (term.isEmpty() || d.translation.isBlank() || !seen.add(key)) return@mapNotNull null
            if (excludeKnown && key in known) return@mapNotNull null
            val pos = PartOfSpeech.fromKey(d.partOfSpeech)
            if (partsOfSpeech.isNotEmpty() && d.partOfSpeech.isNotBlank() && pos !in partsOfSpeech) return@mapNotNull null
            val source = sentenceByTerm[CandidateExtractor.normalize(term, lang)]
                ?: candidates.firstOrNull { it.sentence.contains(term, ignoreCase = true) }?.sentence
                ?: d.example.takeIf { it.isNotBlank() && excerptContains(candidates, it) }
                ?: ""
            GeneratedWord(
                id = id++,
                term = term,
                reading = d.reading.trim(),
                ipa = d.ipa.trim().trim('/'),
                translation = d.translation.trim(),
                partOfSpeech = if (d.partOfSpeech.isBlank()) "" else pos.key,
                level = d.level.trim(),
                category = Category.fromKey(d.category.trim().lowercase()),
                example = d.example.trim(),
                exampleTranslation = d.exampleTranslation.trim(),
                sourceSentence = source,
                difficulty = Difficulty.parse(d.difficulty),
            )
        }
    }

    private fun excerptContains(candidates: List<Candidate>, sentence: String) = candidates.any { it.sentence == sentence.trim() }

    /** Offline fallback rows: on-device translation only, difficulty guessed from frequency. */
    fun offlineWords(candidates: List<Candidate>, translations: Map<String, String>, generateExamples: Boolean): List<GeneratedWord> {
        val maxCount = candidates.maxOfOrNull { it.count } ?: 1
        return candidates.mapIndexed { i, c ->
            GeneratedWord(
                id = i,
                term = c.term,
                translation = translations[c.term].orEmpty(),
                example = if (generateExamples) c.sentence else "",
                sourceSentence = c.sentence,
                difficulty = Difficulty.fromCount(c.count, maxCount),
            )
        }
    }

    /** Short excerpt for the model: the sentences the candidates came from, in text order. */
    fun excerpt(candidates: List<Candidate>, maxChars: Int = EXCERPT_CHARS): String {
        val sb = StringBuilder()
        for (s in candidates.map { it.sentence }.distinct()) {
            if (sb.length + s.length + 1 > maxChars) break
            sb.append(s).append(' ')
        }
        return sb.toString().trim()
    }
}
