package app.kotoba.feature.importexport

import android.content.Context
import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import app.kotoba.core.db.AchievementEntity
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.db.ReviewLogEntity
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.DocumentFormat
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import app.kotoba.feature.importexport.backup.BackupCodec
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BackupCodecTest {
    private lateinit var db: KotobaDatabase
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KotobaDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    @Test fun roundTripReplacesAllTables() = runBlocking {
        val files = "/data/user/0/app.kotoba/files"
        val deckId = db.deckDao().insert(DeckEntity(name = "N5", language = "ja"))
        val card = CardEntity(
            deckId = deckId, language = "ja", term = "猫", reading = "ねこ", translation = "cat", stability = 12.5,
            difficulty = 4.25, state = SrsState.REVIEW, mastery = Mastery.FAMILIAR, lastReview = 123L, imageUri = "file://$files/media/x/cat.png",
        )
        val cardId = db.cardDao().insert(card)
        db.contextDao().insert(ContextEntity(cardId = cardId, sentence = "猫がいる。", source = ContextSource.BOOK))
        db.reviewLogDao().insert(
            ReviewLogEntity(
                cardId = cardId, timestamp = 1, rating = 3, correct = true, exercise = ExerciseType.TYPING, responseMs = 900,
                stateBefore = SrsState.NEW, masteryBefore = Mastery.NEW, masteryAfter = Mastery.LEARNING, stabilityAfter = 2.0, scheduledDays = 1.5,
            ),
        )
        db.achievementDao().insert(AchievementEntity("first_word", 42))
        db.documentDao().insert(DocumentEntity(title = "Book", kind = DocumentKind.BOOK, format = DocumentFormat.EPUB, language = "ja", filePath = "$files/documents/1/book.epub"))

        val dump = BackupCodec.dump(db.openHelper.readableDatabase, createdAt = 99, exclude = setOf("documents", "bookmarks"), filesDir = files)
        val text = dump.toString()
        assertThat(text).contains(BackupCodec.FILES_TOKEN)
        assertThat(dump["tables"]!!.jsonObject.keys).doesNotContain("documents")
        val header = BackupCodec.header(dump)
        assertThat(header.counts["cards"]).isEqualTo(1)

        // Mutate local state, then restore.
        db.cardDao().insert(CardEntity(language = "en", term = "extra", translation = "x"))
        db.deckDao().insert(DeckEntity(name = "Other", language = "en"))
        val parsed = Json.parseToJsonElement(text).jsonObject
        val restoredFiles = "/data/user/0/app.kotoba.other/files"
        db.withTransaction { BackupCodec.restore(db.openHelper.writableDatabase, parsed, restoredFiles) }

        val cards = db.cardDao().all()
        assertThat(cards).hasSize(1)
        val restored = cards.single()
        assertThat(restored.copy(imageUri = "")).isEqualTo(card.copy(id = cardId, imageUri = ""))
        assertThat(restored.imageUri).isEqualTo("file://$restoredFiles/media/x/cat.png")
        assertThat(db.deckDao().all().map { it.name }).containsExactly("N5")
        assertThat(db.contextDao().forCard(cardId).single().sentence).isEqualTo("猫がいる。")
        assertThat(db.reviewLogDao().all().single().scheduledDays).isEqualTo(1.5)
        assertThat(db.achievementDao().all().single().unlockedAt).isEqualTo(42)
        // Documents were not part of the backup, so local ones are kept.
        assertThat(db.documentDao().all()).hasSize(1)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsForeignJson() {
        BackupCodec.header(Json.parseToJsonElement("""{"app":"other","tables":{}}""").jsonObject)
    }
}
