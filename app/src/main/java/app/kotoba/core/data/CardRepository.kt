package app.kotoba.core.data

import androidx.room.withTransaction
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.StudyLanguage
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

/** Result of adding cards with duplicate detection. */
data class AddCardsReport(val added: Int, val duplicates: Int, val ids: List<Long>)

@Singleton
class CardRepository @Inject constructor(
    private val db: KotobaDatabase,
    private val time: TimeProvider,
) {
    private val cards = db.cardDao()
    private val decks = db.deckDao()
    private val contexts = db.contextDao()

    fun observeCard(id: Long): Flow<CardEntity?> = cards.observe(id)
    fun observeContexts(cardId: Long): Flow<List<ContextEntity>> = contexts.observeForCard(cardId)
    fun observeDeck(id: Long): Flow<DeckEntity?> = decks.observe(id)
    fun observeDecks(lang: StudyLanguage) = decks.observeWithStats(lang.code, time.now())

    suspend fun get(id: Long): CardEntity? = cards.get(id)
    suspend fun findByTerm(lang: StudyLanguage, term: String): CardEntity? = cards.findByTerm(lang.code, term.trim())

    suspend fun save(card: CardEntity): Long {
        val now = time.now()
        return if (card.id == 0L) cards.insert(card.copy(createdAt = now, updatedAt = now, due = now))
        else { cards.update(card.copy(updatedAt = now)); card.id }
    }

    suspend fun delete(card: CardEntity) = cards.delete(card)
    suspend fun deleteMany(ids: List<Long>) = db.withTransaction { ids.chunked(500).forEach { cards.deleteByIds(it) } }
    suspend fun setFavorite(id: Long, favorite: Boolean) = cards.setFavorite(id, favorite)
    suspend fun setSuspended(id: Long, suspended: Boolean) = cards.setSuspended(id, suspended)

    /**
     * Inserts cards, skipping ones whose term already exists in the language
     * (case-insensitive). Optional context sentences are attached per card index.
     */
    suspend fun addCards(
        newCards: List<CardEntity>,
        contextsByIndex: Map<Int, List<ContextEntity>> = emptyMap(),
        skipDuplicates: Boolean = true,
    ): AddCardsReport = db.withTransaction {
        val now = time.now()
        val existing = newCards.map { it.language }.distinct()
            .associateWith { cards.allTermsLower(it).toHashSet() }
        var dup = 0
        val ids = mutableListOf<Long>()
        newCards.forEachIndexed { index, c ->
            val key = c.term.trim().lowercase()
            val seen = existing.getValue(c.language)
            if (key.isEmpty() || (skipDuplicates && key in seen)) { dup++; return@forEachIndexed }
            seen += key
            val id = cards.insert(c.copy(term = c.term.trim(), createdAt = now, updatedAt = now, due = now))
            ids += id
            contextsByIndex[index]?.let { list -> contexts.insertAll(list.map { it.copy(cardId = id) }) }
            if (c.example.isNotBlank() && contextsByIndex[index].isNullOrEmpty()) {
                contexts.insert(
                    ContextEntity(
                        cardId = id, sentence = c.example, translation = c.exampleTranslation,
                        source = sourceToContext(c.source), sourceTitle = c.sourceTitle,
                    ),
                )
            }
        }
        AddCardsReport(ids.size, dup, ids)
    }

    /** Adds a context sentence unless the exact sentence is already attached. */
    suspend fun addContext(context: ContextEntity): Long? {
        if (contexts.countSame(context.cardId, context.sentence) > 0) return null
        return contexts.insert(context)
    }

    suspend fun deleteContext(context: ContextEntity) = contexts.delete(context)

    /** Creates or reuses a deck by name. */
    suspend fun ensureDeck(lang: StudyLanguage, name: String, category: String = "", source: String = "", description: String = ""): Long =
        decks.findByName(lang.code, name)?.id
            ?: decks.insert(DeckEntity(name = name, language = lang.code, category = category, source = source, description = description))

    suspend fun saveDeck(deck: DeckEntity): Long = if (deck.id == 0L) decks.insert(deck) else { decks.update(deck); deck.id }
    suspend fun deleteDeck(deck: DeckEntity, deleteCards: Boolean) = db.withTransaction {
        if (deleteCards) cards.forDeck(deck.id).map { it.id }.chunked(500).forEach { cards.deleteByIds(it) }
        decks.delete(deck)
    }
    suspend fun getDeck(id: Long) = decks.get(id)
    suspend fun cardsInDeck(id: Long) = cards.forDeck(id)

    /** Deterministic per day so the widget and home screen agree. */
    suspend fun wordOfTheDay(lang: StudyLanguage): CardEntity? {
        val total = cards.countAllNow(lang.code)
        if (total == 0) return null
        val day = time.today()
        val offset = ((day * 2654435761L) % total).toInt().let { if (it < 0) it + total else it }
        return cards.cardAt(lang.code, offset)
    }

    private fun sourceToContext(source: String): ContextSource = when {
        source.startsWith("book") -> ContextSource.BOOK
        source.startsWith("manga") -> ContextSource.MANGA
        source == "ai" -> ContextSource.AI
        source == "builtin" -> ContextSource.BUILTIN
        source == "manual" -> ContextSource.USER
        else -> ContextSource.IMPORT
    }
}
