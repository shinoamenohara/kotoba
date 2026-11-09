package app.kotoba.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.DocumentFormat
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState

@Entity(
    tableName = "decks",
    indices = [Index("language")],
)
data class DeckEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String = "",
    val language: String,
    /** [app.kotoba.core.model.Category.key] or empty for mixed decks. */
    val category: String = "",
    val level: String = "",
    val isBuiltin: Boolean = false,
    /** ARGB colour used for the deck chip. */
    val color: Long = 0xFF6750A4,
    /** e.g. "anki", "ai", "book:12" — where the deck came from. */
    val source: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * A word/expression card. One row per lexical item; card templates (term→translation,
 * translation→term, context, audio) are chosen at review time, so SRS state lives here.
 */
@Entity(
    tableName = "cards",
    foreignKeys = [
        ForeignKey(
            entity = DeckEntity::class,
            parentColumns = ["id"],
            childColumns = ["deckId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("deckId"), Index("language"), Index("due"), Index("mastery"),
        Index("category"), Index(value = ["language", "term"]),
    ],
)
data class CardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val deckId: Long? = null,
    val language: String,
    val term: String,
    /** Kana for Japanese, pinyin for Chinese, romanization for Korean. */
    val reading: String = "",
    val ipa: String = "",
    val translation: String,
    val example: String = "",
    val exampleTranslation: String = "",
    val partOfSpeech: String = "",
    val synonyms: String = "",
    val antonyms: String = "",
    /** Word forms, e.g. "go, went, gone" or conjugations. */
    val forms: String = "",
    /** Value of the language's [app.kotoba.core.model.LevelScale]. */
    val level: String = "",
    val category: String = "",
    /** Comma-separated tags. */
    val tags: String = "",
    val imageUri: String = "",
    val audioUri: String = "",
    /** Mnemonic/association text shown with the image. */
    val association: String = "",
    val source: String = "manual",
    /** Title of the book/manga/deck the word came from. */
    val sourceTitle: String = "",
    val notes: String = "",
    val favorite: Boolean = false,
    val suspended: Boolean = false,

    // --- SRS (FSRS) ---
    val state: SrsState = SrsState.NEW,
    val mastery: Mastery = Mastery.NEW,
    val stability: Double = 0.0,
    val difficulty: Double = 0.0,
    /** Epoch millis when the card is next due. New cards use createdAt. */
    val due: Long = System.currentTimeMillis(),
    val lastReview: Long? = null,
    /** Index into learning steps while in LEARNING/RELEARNING. */
    val step: Int = 0,
    val reps: Int = 0,
    val lapses: Int = 0,
    val correctCount: Int = 0,
    val wrongCount: Int = 0,
    /** Exponential moving average of response time. */
    val avgResponseMs: Long = 0,
    /** Weak Words Radar score; ≥ [app.kotoba.core.srs.WeakWordsRadar.WEAK_THRESHOLD] = weak. */
    val weakScore: Double = 0.0,
    /** Set when a card leaves NEW for the first time (counts "learned today"). */
    val firstStudiedAt: Long? = null,
    /** Set when a card first reaches MASTERED. */
    val masteredAt: Long? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)

@Entity(
    tableName = "review_logs",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId"), Index("timestamp")],
)
data class ReviewLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val timestamp: Long,
    /** FSRS grade 1..4 */
    val rating: Int,
    val correct: Boolean,
    val exercise: ExerciseType,
    val responseMs: Long,
    val stateBefore: SrsState,
    val masteryBefore: Mastery,
    val masteryAfter: Mastery,
    val stabilityAfter: Double,
    val scheduledDays: Double,
)

/** Example sentences from books, manga, built-in library, AI, etc. */
@Entity(
    tableName = "contexts",
    foreignKeys = [
        ForeignKey(entity = CardEntity::class, parentColumns = ["id"], childColumns = ["cardId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("cardId")],
)
data class ContextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val cardId: Long,
    val sentence: String,
    val translation: String = "",
    val source: ContextSource,
    val sourceTitle: String = "",
    /** Document id for BOOK/MANGA sources. */
    val documentId: Long? = null,
    /** Image crop for manga speech bubbles. */
    val imageUri: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

/** Imported material: books, articles, NotebookLM notes, manga volumes. */
@Entity(tableName = "documents", indices = [Index("kind")])
data class DocumentEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val author: String = "",
    val kind: DocumentKind,
    val format: DocumentFormat,
    val language: String,
    /** Private app-storage path of the copied original. */
    val filePath: String,
    /** Extracted plain text cache (for text kinds) — path in app storage. */
    val textPath: String = "",
    val coverPath: String = "",
    val sizeBytes: Long = 0,
    /** Character count for text, page count for manga. */
    val length: Int = 0,
    /** Char offset (text) or page index (manga). */
    val position: Int = 0,
    val progress: Float = 0f,
    val addedAt: Long = System.currentTimeMillis(),
    val lastOpenedAt: Long? = null,
)

@Entity(
    tableName = "bookmarks",
    foreignKeys = [
        ForeignKey(entity = DocumentEntity::class, parentColumns = ["id"], childColumns = ["documentId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("documentId")],
)
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val documentId: Long,
    val position: Int,
    val quote: String = "",
    val note: String = "",
    /** true = user highlight, false = plain bookmark. */
    val isHighlight: Boolean = false,
    val length: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)

/** One row per calendar day (epoch day in local time zone). */
@Entity(tableName = "daily_activity")
data class DailyActivityEntity(
    @PrimaryKey val epochDay: Long,
    val newWords: Int = 0,
    val reviews: Int = 0,
    val correct: Int = 0,
    val wrong: Int = 0,
    val xp: Int = 0,
    val studyMs: Long = 0,
    val forgotten: Int = 0,
    val recovered: Int = 0,
    val goalReached: Boolean = false,
    val rewardClaimed: Boolean = false,
    val bestCombo: Int = 0,
)

@Entity(tableName = "achievements")
data class AchievementEntity(
    @PrimaryKey val key: String,
    val unlockedAt: Long,
)

/** Pairs of cards the learner mixes up (wrong option picked in quizzes). */
@Entity(tableName = "confusions", primaryKeys = ["cardId", "otherCardId"])
data class ConfusionEntity(
    val cardId: Long,
    val otherCardId: Long,
    val count: Int,
    val lastAt: Long,
)

@Entity(tableName = "chat_messages", indices = [Index("conversationId")])
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: Long,
    /** "user" | "assistant" | "system" */
    val role: String,
    val content: String,
    @ColumnInfo(defaultValue = "0") val isError: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "conversations")
data class ConversationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    /** "chat" | "dialogue" | "explain" | … — tutor mode. */
    val mode: String = "chat",
    val language: String,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
)
