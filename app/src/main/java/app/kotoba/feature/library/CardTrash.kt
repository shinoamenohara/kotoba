package app.kotoba.feature.library

import androidx.room.withTransaction
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.db.ReviewLogEntity
import javax.inject.Inject
import javax.inject.Singleton

/** Everything removed with a card (contexts and logs cascade), kept so "Undo" restores it exactly. */
data class DeletedCards(val cards: List<CardEntity>, val contexts: List<ContextEntity>, val logs: List<ReviewLogEntity>)

@Singleton
class CardTrash @Inject constructor(private val db: KotobaDatabase) {
    suspend fun delete(ids: Collection<Long>): DeletedCards = db.withTransaction {
        val list = ids.toList()
        val cards = db.cardDao().getMany(list)
        val contexts = list.flatMap { db.contextDao().forCard(it) }
        val logs = list.flatMap { db.reviewLogDao().recentForCard(it, Int.MAX_VALUE) }
        db.cardDao().deleteByIds(list)
        DeletedCards(cards, contexts, logs)
    }

    suspend fun restore(d: DeletedCards) = db.withTransaction {
        val m = db.maintenanceDao()
        // Cards first: contexts/logs reference them. Deck may have been deleted meanwhile.
        val deckIds = db.deckDao().all().map { it.id }.toSet()
        m.insertCards(d.cards.map { if (it.deckId != null && it.deckId !in deckIds) it.copy(deckId = null) else it })
        m.insertContexts(d.contexts)
        m.insertLogs(d.logs)
    }

    suspend fun moveToDeck(ids: Collection<Long>, deckId: Long?) {
        val now = System.currentTimeMillis()
        db.cardDao().updateAll(db.cardDao().getMany(ids.toList()).map { it.copy(deckId = deckId, updatedAt = now) })
    }
}
