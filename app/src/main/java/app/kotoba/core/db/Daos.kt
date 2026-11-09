package app.kotoba.core.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.Mastery
import kotlinx.coroutines.flow.Flow

data class MasteryCount(val mastery: Mastery, val count: Int)
data class KeyMasteryCount(val key: String, val mastery: Mastery, val count: Int)
data class DeckWithStats(
    val id: Long,
    val name: String,
    val description: String,
    val language: String,
    val category: String,
    val level: String,
    val isBuiltin: Boolean,
    val color: Long,
    val source: String,
    val total: Int,
    val newCount: Int,
    val dueCount: Int,
    val masteredCount: Int,
)
data class DayCount(val epochDay: Long, val count: Int)

@Dao
interface CardDao {
    @Insert suspend fun insert(card: CardEntity): Long
    @Insert suspend fun insertAll(cards: List<CardEntity>): List<Long>
    @Update suspend fun update(card: CardEntity)
    @Update suspend fun updateAll(cards: List<CardEntity>)
    @Delete suspend fun delete(card: CardEntity)
    @Query("DELETE FROM cards WHERE id IN (:ids)") suspend fun deleteByIds(ids: List<Long>)

    @Query("SELECT * FROM cards WHERE id = :id") suspend fun get(id: Long): CardEntity?
    @Query("SELECT * FROM cards WHERE id = :id") fun observe(id: Long): Flow<CardEntity?>
    @Query("SELECT * FROM cards WHERE id IN (:ids)") suspend fun getMany(ids: List<Long>): List<CardEntity>
    @Query("SELECT * FROM cards") suspend fun all(): List<CardEntity>
    @Query("SELECT * FROM cards WHERE language = :lang") suspend fun allForLanguage(lang: String): List<CardEntity>
    @Query("SELECT * FROM cards WHERE deckId = :deckId ORDER BY id") suspend fun forDeck(deckId: Long): List<CardEntity>

    @Query(
        """
        SELECT * FROM cards
        WHERE language = :lang
          AND (:deckId IS NULL OR deckId = :deckId)
          AND (:category IS NULL OR category = :category)
          AND (:mastery IS NULL OR mastery = :mastery)
          AND (:favoritesOnly = 0 OR favorite = 1)
          AND (:weakThreshold IS NULL OR weakScore >= :weakThreshold)
          AND (:query = '' OR term LIKE '%' || :query || '%' OR translation LIKE '%' || :query || '%'
               OR reading LIKE '%' || :query || '%' OR tags LIKE '%' || :query || '%')
        ORDER BY
          CASE WHEN :sort = 'alpha' THEN term END COLLATE NOCASE ASC,
          CASE WHEN :sort = 'due' THEN due END ASC,
          CASE WHEN :sort = 'weak' THEN weakScore END DESC,
          createdAt DESC
        """,
    )
    fun search(
        lang: String,
        query: String = "",
        deckId: Long? = null,
        category: String? = null,
        mastery: Mastery? = null,
        favoritesOnly: Boolean = false,
        weakThreshold: Double? = null,
        sort: String = "recent",
    ): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE language = :lang AND suspended = 0 AND state != 'NEW' AND due <= :now ORDER BY due ASC LIMIT :limit")
    suspend fun dueCards(lang: String, now: Long, limit: Int): List<CardEntity>

    @Query(
        """SELECT * FROM cards WHERE language = :lang AND suspended = 0 AND state = 'NEW'
           AND (:deckId IS NULL OR deckId = :deckId) AND (:category IS NULL OR category = :category)
           ORDER BY deckId ASC, id ASC LIMIT :limit""",
    )
    suspend fun newCards(lang: String, limit: Int, deckId: Long? = null, category: String? = null): List<CardEntity>

    @Query(
        """SELECT * FROM cards WHERE language = :lang AND suspended = 0 AND state != 'NEW'
           AND (:deckId IS NULL OR deckId = :deckId) AND (:category IS NULL OR category = :category)
           ORDER BY due ASC LIMIT :limit""",
    )
    suspend fun studiedCards(lang: String, limit: Int, deckId: Long? = null, category: String? = null): List<CardEntity>

    @Query("SELECT * FROM cards WHERE language = :lang AND suspended = 0 AND weakScore >= :threshold ORDER BY weakScore DESC LIMIT :limit")
    suspend fun weakCards(lang: String, threshold: Double, limit: Int): List<CardEntity>

    @Query("SELECT * FROM cards WHERE language = :lang AND weakScore >= :threshold ORDER BY weakScore DESC")
    fun observeWeak(lang: String, threshold: Double): Flow<List<CardEntity>>

    @Query("SELECT * FROM cards WHERE language = :lang AND id != :excludeId ORDER BY RANDOM() LIMIT :limit")
    suspend fun randomCards(lang: String, excludeId: Long, limit: Int): List<CardEntity>

    @Query("SELECT COUNT(*) FROM cards WHERE language = :lang AND suspended = 0 AND state != 'NEW' AND due <= :now")
    fun countDue(lang: String, now: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE language = :lang AND suspended = 0 AND state = 'NEW'")
    fun countNew(lang: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE language = :lang")
    fun countAll(lang: String): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE language = :lang AND weakScore >= :threshold")
    fun countWeak(lang: String, threshold: Double): Flow<Int>

    @Query("SELECT COUNT(*) FROM cards WHERE language = :lang AND firstStudiedAt >= :since")
    suspend fun countIntroducedSince(lang: String, since: Long): Int

    @Query("SELECT COUNT(*) FROM cards WHERE firstStudiedAt IS NOT NULL")
    suspend fun countStudiedAllLanguages(): Int

    @Query("SELECT COUNT(*) FROM cards WHERE mastery = 'MASTERED'")
    suspend fun countMasteredAllLanguages(): Int

    @Query("SELECT mastery, COUNT(*) AS count FROM cards WHERE language = :lang GROUP BY mastery")
    fun masteryCounts(lang: String): Flow<List<MasteryCount>>

    @Query("SELECT category AS `key`, mastery, COUNT(*) AS count FROM cards WHERE language = :lang GROUP BY category, mastery")
    fun categoryMasteryCounts(lang: String): Flow<List<KeyMasteryCount>>

    @Query("SELECT level AS `key`, mastery, COUNT(*) AS count FROM cards WHERE language = :lang GROUP BY level, mastery")
    fun levelMasteryCounts(lang: String): Flow<List<KeyMasteryCount>>

    @Query("SELECT partOfSpeech AS `key`, mastery, COUNT(*) AS count FROM cards WHERE language = :lang GROUP BY partOfSpeech, mastery")
    fun posMasteryCounts(lang: String): Flow<List<KeyMasteryCount>>

    @Query("SELECT category AS `key`, mastery, COUNT(*) AS count FROM cards GROUP BY category, mastery")
    suspend fun categoryMasteryCountsAll(): List<KeyMasteryCount>

    @Query("SELECT * FROM cards WHERE language = :lang AND lower(term) = lower(:term) LIMIT 1")
    suspend fun findByTerm(lang: String, term: String): CardEntity?

    @Query("SELECT lower(term) FROM cards WHERE language = :lang")
    suspend fun allTermsLower(lang: String): List<String>

    @Query("SELECT * FROM cards WHERE language = :lang ORDER BY id LIMIT 1 OFFSET :offset")
    suspend fun cardAt(lang: String, offset: Int): CardEntity?

    @Query("SELECT COUNT(*) FROM cards WHERE language = :lang")
    suspend fun countAllNow(lang: String): Int

    @Query("SELECT DISTINCT language FROM cards")
    fun observeLanguagesWithCards(): Flow<List<String>>

    /** Daily count of cards first studied — vocabulary growth chart. */
    @Query(
        """SELECT (firstStudiedAt + :offsetMs) / 86400000 AS epochDay, COUNT(*) AS count FROM cards
           WHERE language = :lang AND firstStudiedAt IS NOT NULL GROUP BY epochDay ORDER BY epochDay""",
    )
    fun studiedPerDay(lang: String, offsetMs: Long): Flow<List<DayCount>>

    @Query(
        """SELECT (masteredAt + :offsetMs) / 86400000 AS epochDay, COUNT(*) AS count FROM cards
           WHERE language = :lang AND masteredAt IS NOT NULL GROUP BY epochDay ORDER BY epochDay""",
    )
    fun masteredPerDay(lang: String, offsetMs: Long): Flow<List<DayCount>>

    @Query("UPDATE cards SET favorite = :favorite, updatedAt = :now WHERE id = :id")
    suspend fun setFavorite(id: Long, favorite: Boolean, now: Long = System.currentTimeMillis())

    @Query("UPDATE cards SET suspended = :suspended WHERE id = :id")
    suspend fun setSuspended(id: Long, suspended: Boolean)
}

@Dao
interface DeckDao {
    @Insert suspend fun insert(deck: DeckEntity): Long
    @Update suspend fun update(deck: DeckEntity)
    @Delete suspend fun delete(deck: DeckEntity)
    @Query("SELECT * FROM decks WHERE id = :id") suspend fun get(id: Long): DeckEntity?
    @Query("SELECT * FROM decks WHERE id = :id") fun observe(id: Long): Flow<DeckEntity?>
    @Query("SELECT * FROM decks") suspend fun all(): List<DeckEntity>
    @Query("SELECT * FROM decks WHERE language = :lang ORDER BY name") fun observeForLanguage(lang: String): Flow<List<DeckEntity>>
    @Query("SELECT * FROM decks WHERE language = :lang AND name = :name LIMIT 1") suspend fun findByName(lang: String, name: String): DeckEntity?

    @Query(
        """
        SELECT d.id, d.name, d.description, d.language, d.category, d.level, d.isBuiltin, d.color, d.source,
          COUNT(c.id) AS total,
          SUM(CASE WHEN c.state = 'NEW' THEN 1 ELSE 0 END) AS newCount,
          SUM(CASE WHEN c.state != 'NEW' AND c.due <= :now THEN 1 ELSE 0 END) AS dueCount,
          SUM(CASE WHEN c.mastery = 'MASTERED' THEN 1 ELSE 0 END) AS masteredCount
        FROM decks d LEFT JOIN cards c ON c.deckId = d.id
        WHERE d.language = :lang
        GROUP BY d.id ORDER BY d.isBuiltin ASC, d.createdAt DESC
        """,
    )
    fun observeWithStats(lang: String, now: Long): Flow<List<DeckWithStats>>
}

@Dao
interface ReviewLogDao {
    @Insert suspend fun insert(log: ReviewLogEntity): Long
    @Insert suspend fun insertAll(logs: List<ReviewLogEntity>)
    @Query("SELECT * FROM review_logs") suspend fun all(): List<ReviewLogEntity>
    @Query("SELECT * FROM review_logs WHERE cardId = :cardId ORDER BY timestamp DESC LIMIT :limit")
    suspend fun recentForCard(cardId: Long, limit: Int): List<ReviewLogEntity>
    @Query("SELECT * FROM review_logs WHERE cardId = :cardId ORDER BY timestamp DESC")
    fun observeForCard(cardId: Long): Flow<List<ReviewLogEntity>>
    @Query("SELECT * FROM review_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    fun observeSince(since: Long): Flow<List<ReviewLogEntity>>
    @Query("SELECT * FROM review_logs WHERE timestamp >= :since ORDER BY timestamp ASC")
    suspend fun since(since: Long): List<ReviewLogEntity>
    @Query("SELECT COUNT(*) FROM review_logs") suspend fun countAll(): Int
    @Query("SELECT COUNT(*) FROM review_logs WHERE correct = 1") suspend fun countCorrect(): Int
}

@Dao
interface ContextDao {
    @Insert suspend fun insert(context: ContextEntity): Long
    @Insert suspend fun insertAll(contexts: List<ContextEntity>)
    @Delete suspend fun delete(context: ContextEntity)
    @Query("SELECT * FROM contexts") suspend fun all(): List<ContextEntity>
    @Query("SELECT * FROM contexts WHERE cardId = :cardId ORDER BY createdAt")
    fun observeForCard(cardId: Long): Flow<List<ContextEntity>>
    @Query("SELECT * FROM contexts WHERE cardId = :cardId ORDER BY createdAt")
    suspend fun forCard(cardId: Long): List<ContextEntity>
    @Query("SELECT COUNT(*) FROM contexts WHERE cardId = :cardId AND sentence = :sentence")
    suspend fun countSame(cardId: Long, sentence: String): Int
    @Query("SELECT DISTINCT cardId FROM contexts WHERE documentId = :documentId")
    fun observeCardIdsForDocument(documentId: Long): Flow<List<Long>>
}

@Dao
interface DocumentDao {
    @Insert suspend fun insert(doc: DocumentEntity): Long
    @Update suspend fun update(doc: DocumentEntity)
    @Delete suspend fun delete(doc: DocumentEntity)
    @Query("SELECT * FROM documents") suspend fun all(): List<DocumentEntity>
    @Query("SELECT * FROM documents WHERE id = :id") suspend fun get(id: Long): DocumentEntity?
    @Query("SELECT * FROM documents WHERE id = :id") fun observe(id: Long): Flow<DocumentEntity?>
    @Query("SELECT * FROM documents WHERE kind IN (:kinds) ORDER BY COALESCE(lastOpenedAt, addedAt) DESC")
    fun observeByKinds(kinds: List<DocumentKind>): Flow<List<DocumentEntity>>
    @Query("SELECT * FROM documents ORDER BY sizeBytes DESC") fun observeAll(): Flow<List<DocumentEntity>>
    @Query("UPDATE documents SET position = :position, progress = :progress, lastOpenedAt = :now WHERE id = :id")
    suspend fun updatePosition(id: Long, position: Int, progress: Float, now: Long = System.currentTimeMillis())
}

@Dao
interface BookmarkDao {
    @Insert suspend fun insert(bookmark: BookmarkEntity): Long
    @Update suspend fun update(bookmark: BookmarkEntity)
    @Delete suspend fun delete(bookmark: BookmarkEntity)
    @Query("SELECT * FROM bookmarks") suspend fun all(): List<BookmarkEntity>
    @Query("SELECT * FROM bookmarks WHERE documentId = :documentId ORDER BY position")
    fun observeForDocument(documentId: Long): Flow<List<BookmarkEntity>>
}

@Dao
interface DailyActivityDao {
    @Upsert suspend fun upsert(day: DailyActivityEntity)
    @Query("SELECT * FROM daily_activity WHERE epochDay = :day") suspend fun get(day: Long): DailyActivityEntity?
    @Query("SELECT * FROM daily_activity WHERE epochDay = :day") fun observe(day: Long): Flow<DailyActivityEntity?>
    @Query("SELECT * FROM daily_activity WHERE epochDay >= :from ORDER BY epochDay")
    fun observeFrom(from: Long): Flow<List<DailyActivityEntity>>
    @Query("SELECT * FROM daily_activity ORDER BY epochDay") suspend fun all(): List<DailyActivityEntity>
    @Query("SELECT epochDay FROM daily_activity WHERE reviews > 0 OR newWords > 0 ORDER BY epochDay DESC")
    suspend fun activeDaysDesc(): List<Long>
    @Query("SELECT epochDay FROM daily_activity WHERE reviews > 0 OR newWords > 0 ORDER BY epochDay DESC")
    fun observeActiveDaysDesc(): Flow<List<Long>>
    @Query("SELECT COALESCE(SUM(xp), 0) FROM daily_activity") fun observeTotalXp(): Flow<Int>
    @Query("SELECT COALESCE(SUM(xp), 0) FROM daily_activity") suspend fun totalXp(): Int
}

@Dao
interface AchievementDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(a: AchievementEntity): Long
    @Query("SELECT * FROM achievements") fun observeAll(): Flow<List<AchievementEntity>>
    @Query("SELECT * FROM achievements") suspend fun all(): List<AchievementEntity>
}

@Dao
interface ConfusionDao {
    @Query("SELECT * FROM confusions WHERE cardId = :cardId AND otherCardId = :otherId")
    suspend fun get(cardId: Long, otherId: Long): ConfusionEntity?
    @Upsert suspend fun upsert(c: ConfusionEntity)
    @Query("SELECT * FROM confusions WHERE cardId = :cardId ORDER BY count DESC LIMIT :limit")
    suspend fun topForCard(cardId: Long, limit: Int): List<ConfusionEntity>
    @Query("SELECT * FROM confusions ORDER BY count DESC LIMIT :limit")
    fun observeTop(limit: Int): Flow<List<ConfusionEntity>>
    @Query("SELECT COALESCE(SUM(count), 0) FROM confusions WHERE cardId = :cardId")
    suspend fun totalForCard(cardId: Long): Int
}

@Dao
interface ChatDao {
    @Insert suspend fun insertConversation(c: ConversationEntity): Long
    @Update suspend fun updateConversation(c: ConversationEntity)
    @Delete suspend fun deleteConversation(c: ConversationEntity)
    @Query("SELECT * FROM conversations ORDER BY updatedAt DESC") fun observeConversations(): Flow<List<ConversationEntity>>
    @Query("SELECT * FROM conversations WHERE id = :id") suspend fun getConversation(id: Long): ConversationEntity?
    @Insert suspend fun insertMessage(m: ChatMessageEntity): Long
    @Query("SELECT * FROM chat_messages WHERE conversationId = :id ORDER BY createdAt, id") fun observeMessages(id: Long): Flow<List<ChatMessageEntity>>
    @Query("SELECT * FROM chat_messages WHERE conversationId = :id ORDER BY createdAt, id") suspend fun messages(id: Long): List<ChatMessageEntity>
    @Query("DELETE FROM chat_messages WHERE conversationId = :id") suspend fun clearMessages(id: Long)
}

/** Bulk helpers used by backup/restore. */
@Dao
interface MaintenanceDao {
    @Query("DELETE FROM cards") suspend fun clearCards()
    @Query("DELETE FROM decks") suspend fun clearDecks()
    @Query("DELETE FROM review_logs") suspend fun clearLogs()
    @Query("DELETE FROM contexts") suspend fun clearContexts()
    @Query("DELETE FROM daily_activity") suspend fun clearActivity()
    @Query("DELETE FROM achievements") suspend fun clearAchievements()
    @Query("DELETE FROM confusions") suspend fun clearConfusions()
    @Query("DELETE FROM bookmarks") suspend fun clearBookmarks()
    @Query("DELETE FROM documents") suspend fun clearDocuments()
    @Query("SELECT * FROM confusions") suspend fun allConfusions(): List<ConfusionEntity>
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDecks(d: List<DeckEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertCards(c: List<CardEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertLogs(l: List<ReviewLogEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertContexts(c: List<ContextEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertActivity(a: List<DailyActivityEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertAchievements(a: List<AchievementEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertConfusions(c: List<ConfusionEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertDocuments(d: List<DocumentEntity>)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun insertBookmarks(b: List<BookmarkEntity>)

    @Transaction
    suspend fun clearLearningData() {
        clearLogs(); clearContexts(); clearConfusions(); clearCards(); clearDecks(); clearActivity(); clearAchievements()
    }
}
