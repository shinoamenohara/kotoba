package app.kotoba.feature.importexport

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.importexport.anki.AnkiCollectionReader
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class AnkiImportTest {
    private lateinit var context: Context
    private lateinit var db: KotobaDatabase
    private val now = 1_750_000_000_000L
    private val time = object : TimeProvider { override fun now() = now }
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, KotobaDatabase::class.java).allowMainThreadQueries().build()
    }

    @After fun tearDown() = db.close()

    /** Builds a schema-11 (legacy) collection like the ones inside .apkg files. */
    private fun legacyCollection(): File {
        val file = File(context.cacheDir, "collection.anki2").apply { delete() }
        SQLiteDatabase.openOrCreateDatabase(file, null).use { d ->
            d.execSQL("CREATE TABLE col (id integer primary key, crt integer, mod integer, scm integer, ver integer, dty integer, usn integer, ls integer, conf text, models text, decks text, dconf text, tags text)")
            d.execSQL("CREATE TABLE notes (id integer primary key, guid text, mid integer, mod integer, usn integer, tags text, flds text, sfld text, csum integer, flags integer, data text)")
            d.execSQL("CREATE TABLE cards (id integer primary key, nid integer, did integer, ord integer, mod integer, usn integer, type integer, queue integer, due integer, ivl integer, factor integer, reps integer, lapses integer, left integer, odue integer, odid integer, flags integer, data text)")
            val models = """{"1001":{"id":1001,"name":"Japanese (recognition)","flds":[{"name":"Expression","ord":0},{"name":"Meaning","ord":1},{"name":"Reading","ord":2}]},
                |"1002":{"id":1002,"name":"Basic","flds":[{"name":"Front","ord":0},{"name":"Back","ord":1}]}}""".trimMargin()
            val decks = """{"1":{"id":1,"name":"Default"},"20":{"id":20,"name":"Japanese::Core"}}"""
            d.execSQL("INSERT INTO col VALUES (1, ${now / 1000 - 50 * 86400}, 0, 0, 11, 0, 0, 0, '{}', ?, ?, '{}', '{}')", arrayOf(models, decks))
            fun note(id: Long, mid: Long, tags: String, vararg fields: String) =
                d.execSQL("INSERT INTO notes VALUES ($id, 'g$id', $mid, 0, 0, ?, ?, '', 0, 0, '')", arrayOf(tags, fields.joinToString("\u001f")))
            note(1, 1001, " food verb ", "<b>食べる</b>[sound:taberu.mp3]", "to eat", "たべる")
            note(2, 1001, "", "飲む", "to drink<br>to swallow", "のむ")
            note(3, 1002, "basic", "", "empty front")
            note(4, 1002, "", "cat", "кошка")
            fun card(id: Long, nid: Long, did: Long, ord: Int, type: Int, queue: Int, due: Long, ivl: Int, factor: Int) =
                d.execSQL("INSERT INTO cards VALUES ($id, $nid, $did, $ord, 0, 0, $type, $queue, $due, $ivl, $factor, 5, 1, 0, 0, 0, 0, '')")
            card(10, 1, 20, 0, 2, 2, 60, 25, 2500)
            card(11, 1, 20, 1, 2, 2, 55, 3, 2300)
            card(20, 2, 20, 0, 0, 0, 2, 0, 0)
            card(30, 3, 1, 0, 0, 0, 3, 0, 0)
            card(40, 4, 1, 0, 2, 2, 52, 5, 2000)
        }
        return file
    }

    @Test fun readsLegacyCollection() {
        val col = AnkiCollectionReader.read(legacyCollection())
        assertThat(col.noteTypes.keys).containsExactly(1001L, 1002L)
        assertThat(col.noteTypes.getValue(1001).fields).containsExactly("Expression", "Meaning", "Reading").inOrder()
        assertThat(col.decks.getValue(20).name).isEqualTo("Japanese::Core")
        assertThat(col.notes).hasSize(4)
        val first = col.notes.first { it.id == 1L }
        assertThat(first.tags).containsExactly("food", "verb")
        assertThat(first.deckId).isEqualTo(20)
        assertThat(first.progress!!.ivlDays).isEqualTo(25) // most advanced card wins
    }

    @Test fun importsCollectionWithDecksProgressAndDuplicates() = runBlocking {
        val col = AnkiCollectionReader.read(legacyCollection())
        val settings = SettingsRepository(context)
        val repo = ImportRepository(context, db, GamificationRepository(db, settings, time), time, json)
        val audio = File(context.cacheDir, "0").apply { writeText("fake mp3") }
        val parsed = repo.fromAnki("deck.apkg", col, mapOf("taberu.mp3" to audio), emptyList(), null)
        assertThat(parsed.suggestedLanguage).isEqualTo(StudyLanguage.JA)

        val options = ImportOptions(
            language = StudyLanguage.JA, deckMode = DeckMode.SOURCE_DECKS,
            mapping = parsed.groups.associate { it.id to SmartMapping.defaults(it.fields, StudyLanguage.JA) },
        )
        val report = repo.commit(parsed, options)
        assertThat(report.added).isEqualTo(3)
        assertThat(report.errors).isEqualTo(1) // note with an empty front
        assertThat(report.mediaFiles).isEqualTo(1)
        assertThat(report.decks).isEqualTo(2)

        val cards = db.cardDao().allForLanguage("ja").associateBy { it.term }
        val taberu = cards.getValue("食べる")
        assertThat(taberu.translation).isEqualTo("to eat")
        assertThat(taberu.reading).isEqualTo("たべる")
        assertThat(taberu.tags).isEqualTo("food, verb")
        assertThat(taberu.source).isEqualTo(CardSource.ANKI)
        assertThat(taberu.audioUri).startsWith("file:")
        assertThat(taberu.state).isEqualTo(SrsState.REVIEW)
        assertThat(taberu.mastery).isEqualTo(Mastery.MASTERED)
        assertThat(taberu.stability).isEqualTo(25.0)
        assertThat(db.deckDao().get(taberu.deckId!!)!!.name).isEqualTo("Japanese › Core")
        assertThat(cards.getValue("飲む").translation).isEqualTo("to drink\nto swallow")
        assertThat(cards.getValue("飲む").state).isEqualTo(SrsState.NEW)
        assertThat(db.achievementDao().all().map { it.key }).contains("first_import")

        // Second run: everything is a duplicate.
        val again = repo.commit(parsed, options)
        assertThat(again.added).isEqualTo(0)
        assertThat(again.duplicates).isEqualTo(3)
    }

    @Test fun csvAndKotobaJsonRoundTrip() = runBlocking {
        val settings = SettingsRepository(context)
        val repo = ImportRepository(context, db, GamificationRepository(db, settings, time), time, json)
        val csv = repo.parseText("words.csv", "Word,Translation,Example\napple,яблоко,I like apples.\nrun,бежать,\n")
        assertThat(csv.kind).isEqualTo(ImportKind.CSV)
        val r = repo.commit(csv, ImportOptions(StudyLanguage.EN, DeckMode.NEW, newDeckName = "Fruits", mapping = mapOf(0L to SmartMapping.defaults(csv.groups[0].fields, StudyLanguage.EN))))
        assertThat(r.added).isEqualTo(2)
        val apple = db.cardDao().findByTerm("en", "apple")!!
        assertThat(db.contextDao().forCard(apple.id).map { it.sentence }).containsExactly("I like apples.")

        val dict = repo.parseText("list.txt", "cat = кошка\ndog = собака\n")
        assertThat(dict.kind).isEqualTo(ImportKind.DICTIONARY)
        assertThat(dict.records.map { it.fields }).containsExactly(listOf("cat", "кошка"), listOf("dog", "собака")).inOrder()

        val export = KotobaExport(
            language = "zh",
            decks = listOf(KDeck("HSK1", language = "zh")),
            cards = listOf(KCard(language = "zh", deck = "HSK1", term = "你好", reading = "nǐ hǎo", translation = "привет", state = "REVIEW", mastery = "FAMILIAR", stability = 7.0, due = now + 5, contexts = listOf(KContext("你好，世界！", "Привет, мир!")))),
        )
        val parsed = repo.parseText("export.json", json.encodeToString(KotobaExport.serializer(), export))
        assertThat(parsed.kind).isEqualTo(ImportKind.KOTOBA)
        val k = repo.commit(parsed, ImportOptions(StudyLanguage.ZH, DeckMode.SOURCE_DECKS))
        assertThat(k.added).isEqualTo(1)
        val nihao = db.cardDao().findByTerm("zh", "你好")!!
        assertThat(nihao.state).isEqualTo(SrsState.REVIEW)
        assertThat(nihao.stability).isEqualTo(7.0)
        assertThat(db.deckDao().get(nihao.deckId!!)!!.name).isEqualTo("HSK1")
        assertThat(db.contextDao().forCard(nihao.id).single().translation).isEqualTo("Привет, мир!")

        val generic = repo.parseText("g.json", """[{"word":"hola","meaning":"привет"},{"word":"adiós","meaning":"пока","pos":"interj"}]""")
        assertThat(generic.kind).isEqualTo(ImportKind.JSON)
        assertThat(generic.groups[0].fields).containsExactly("word", "meaning", "pos").inOrder()
        assertThat(generic.records[1].fields).containsExactly("adiós", "пока", "interj").inOrder()
    }
}
