package app.kotoba.feature.importexport

import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import kotlinx.serialization.Serializable

/** Lossless Kotoba exchange format (cards with SRS state and context sentences). */
@Serializable
data class KotobaExport(
    val format: String = FORMAT,
    val version: Int = 1,
    val exportedAt: Long = 0,
    val language: String? = null,
    val decks: List<KDeck> = emptyList(),
    val cards: List<KCard> = emptyList(),
) {
    companion object { const val FORMAT = "kotoba" }
}

@Serializable
data class KDeck(
    val name: String,
    val description: String = "",
    val language: String,
    val category: String = "",
    val level: String = "",
    val color: Long = 0xFF6750A4,
    val source: String = "",
)

@Serializable
data class KContext(
    val sentence: String,
    val translation: String = "",
    val source: String = ContextSource.IMPORT.name,
    val sourceTitle: String = "",
    val imageUri: String = "",
    val createdAt: Long = 0,
)

@Serializable
data class KCard(
    val language: String,
    val deck: String? = null,
    val term: String,
    val reading: String = "",
    val ipa: String = "",
    val translation: String = "",
    val example: String = "",
    val exampleTranslation: String = "",
    val partOfSpeech: String = "",
    val synonyms: String = "",
    val antonyms: String = "",
    val forms: String = "",
    val level: String = "",
    val category: String = "",
    val tags: String = "",
    val imageUri: String = "",
    val audioUri: String = "",
    val association: String = "",
    val source: String = "",
    val sourceTitle: String = "",
    val notes: String = "",
    val favorite: Boolean = false,
    val suspended: Boolean = false,
    val state: String = SrsState.NEW.name,
    val mastery: String = Mastery.NEW.name,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    val due: Long = 0,
    val lastReview: Long? = null,
    val step: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    val avgResponseMs: Long = 0,
    val weakScore: Double = 0.0,
    val firstStudiedAt: Long? = null,
    val masteredAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val contexts: List<KContext> = emptyList(),
)

object KotobaMapper {
    fun deck(d: DeckEntity) = KDeck(d.name, d.description, d.language, d.category, d.level, d.color, d.source)

    fun card(c: CardEntity, deckName: String?, contexts: List<ContextEntity>) = KCard(
        language = c.language, deck = deckName, term = c.term, reading = c.reading, ipa = c.ipa,
        translation = c.translation, example = c.example, exampleTranslation = c.exampleTranslation,
        partOfSpeech = c.partOfSpeech, synonyms = c.synonyms, antonyms = c.antonyms, forms = c.forms,
        level = c.level, category = c.category, tags = c.tags, imageUri = c.imageUri, audioUri = c.audioUri,
        association = c.association, source = c.source, sourceTitle = c.sourceTitle, notes = c.notes,
        favorite = c.favorite, suspended = c.suspended, state = c.state.name, mastery = c.mastery.name,
        stability = c.stability, difficulty = c.difficulty, due = c.due, lastReview = c.lastReview, step = c.step,
        reps = c.reps, lapses = c.lapses, correctCount = c.correctCount, wrongCount = c.wrongCount,
        avgResponseMs = c.avgResponseMs, weakScore = c.weakScore, firstStudiedAt = c.firstStudiedAt,
        masteredAt = c.masteredAt, createdAt = c.createdAt, updatedAt = c.updatedAt,
        contexts = contexts.map { KContext(it.sentence, it.translation, it.source.name, it.sourceTitle, it.imageUri, it.createdAt) },
    )

    /** @param withProgress false resets the card to NEW. */
    fun toEntity(k: KCard, deckId: Long?, language: String, now: Long, withProgress: Boolean): CardEntity {
        val base = CardEntity(
            deckId = deckId, language = language, term = k.term.trim(), reading = k.reading, ipa = k.ipa,
            translation = k.translation, example = k.example, exampleTranslation = k.exampleTranslation,
            partOfSpeech = k.partOfSpeech, synonyms = k.synonyms, antonyms = k.antonyms, forms = k.forms,
            level = k.level, category = k.category, tags = k.tags, imageUri = k.imageUri, audioUri = k.audioUri,
            association = k.association, source = k.source.ifBlank { app.kotoba.core.model.CardSource.IMPORT },
            sourceTitle = k.sourceTitle, notes = k.notes, favorite = k.favorite, suspended = k.suspended,
            createdAt = if (k.createdAt > 0) k.createdAt else now, updatedAt = now, due = now,
        )
        if (!withProgress) return base
        return base.copy(
            state = runCatching { SrsState.valueOf(k.state) }.getOrDefault(SrsState.NEW),
            mastery = runCatching { Mastery.valueOf(k.mastery) }.getOrDefault(Mastery.NEW),
            stability = k.stability, difficulty = k.difficulty, due = if (k.due > 0) k.due else now,
            lastReview = k.lastReview, step = k.step, reps = k.reps, lapses = k.lapses,
            correctCount = k.correctCount, wrongCount = k.wrongCount, avgResponseMs = k.avgResponseMs,
            weakScore = k.weakScore, firstStudiedAt = k.firstStudiedAt, masteredAt = k.masteredAt,
        )
    }

    fun context(k: KContext, cardId: Long) = ContextEntity(
        cardId = cardId, sentence = k.sentence, translation = k.translation,
        source = runCatching { ContextSource.valueOf(k.source) }.getOrDefault(ContextSource.IMPORT),
        sourceTitle = k.sourceTitle, imageUri = k.imageUri, createdAt = if (k.createdAt > 0) k.createdAt else System.currentTimeMillis(),
    )
}

/** CSV/TSV writers. TSV carries Anki's file headers so it imports into Anki without configuration. */
object DelimitedWriter {
    val columns = listOf("term", "translation", "reading", "ipa", "example", "exampleTranslation", "partOfSpeech", "notes", "level", "deck", "tags")

    private fun row(c: CardEntity, deck: String?): List<String> = listOf(
        c.term, c.translation, c.reading, c.ipa, c.example, c.exampleTranslation, c.partOfSpeech, c.notes, c.level, deck.orEmpty(), c.tags,
    )

    fun csv(cards: List<CardEntity>, deckNames: Map<Long, String>): String = buildString {
        append(columns.joinToString(",")).append("\r\n")
        cards.forEach { c -> append(row(c, c.deckId?.let(deckNames::get)).joinToString(",") { escapeCsv(it) }).append("\r\n") }
    }

    fun ankiTsv(cards: List<CardEntity>, deckNames: Map<Long, String>): String = buildString {
        append("#separator:tab\n#html:true\n")
        append("#columns:").append(columns.joinToString("\t")).append('\n')
        append("#tags column:").append(columns.size).append('\n')
        cards.forEach { c ->
            val tags = c.tags.split(',').map { it.trim().replace(' ', '_') }.filter { it.isNotEmpty() }.joinToString(" ")
            val values = row(c, c.deckId?.let(deckNames::get)).dropLast(1) + tags
            append(values.joinToString("\t") { escapeTsv(it) }).append('\n')
        }
    }

    fun escapeCsv(v: String): String =
        if (v.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + v.replace("\"", "\"\"") + "\"" else v

    /** Anki's html mode: tabs can't appear in a field and newlines become <br>. */
    fun escapeTsv(v: String): String = v.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
        .replace("\t", " ").replace("\r\n", "<br>").replace("\n", "<br>")
}
