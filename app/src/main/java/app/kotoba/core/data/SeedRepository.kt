package app.kotoba.core.data

import android.content.Context
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.Category
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import androidx.room.withTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Format of `assets/seed/<lang>.json`. */
@Serializable
data class SeedFile(val language: String, val decks: List<SeedDeck>)

@Serializable
data class SeedDeck(
    val name: String,
    val nameRu: String = "",
    val category: String = "",
    val level: String = "",
    val description: String = "",
    val cards: List<SeedCard>,
)

@Serializable
data class SeedCard(
    val term: String,
    val reading: String = "",
    val ipa: String = "",
    /** Russian translation. */
    val ru: String,
    /** English translation (used when native language is English; also helps for JA/ZH). */
    val en: String = "",
    val pos: String = "",
    val example: String = "",
    val exampleRu: String = "",
    val exampleEn: String = "",
    val synonyms: String = "",
    val antonyms: String = "",
    val forms: String = "",
    val level: String = "",
)

/** Installs the built-in starter decks shipped in assets. */
@Singleton
class SeedRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KotobaDatabase,
    private val cards: CardRepository,
    private val json: Json,
) {
    fun availableLanguages(): Set<StudyLanguage> =
        context.assets.list("seed").orEmpty()
            .mapNotNull { name -> name.removeSuffix(".json").takeIf { name.endsWith(".json") } }
            .map { StudyLanguage.fromCode(it) }.toSet()

    fun load(lang: StudyLanguage): SeedFile? = runCatching {
        context.assets.open("seed/${lang.code}.json").bufferedReader().use { json.decodeFromString(SeedFile.serializer(), it.readText()) }
    }.getOrNull()

    /** Installs built-in decks for [lang] that aren't installed yet. Returns cards added. */
    suspend fun install(lang: StudyLanguage, native: NativeLanguage): Int = db.withTransaction {
        val seed = load(lang) ?: return@withTransaction 0
        val deckDao = db.deckDao()
        var total = 0
        seed.decks.forEachIndexed { i, d ->
            val name = if (native == NativeLanguage.RU && d.nameRu.isNotBlank()) d.nameRu else d.name
            if (deckDao.findByName(lang.code, name) != null) return@forEachIndexed
            val deckId = deckDao.insert(
                DeckEntity(
                    name = name, description = d.description, language = lang.code,
                    category = Category.fromKey(d.category).key, level = d.level, isBuiltin = true,
                    color = DECK_COLORS[i % DECK_COLORS.size], source = CardSource.BUILTIN,
                ),
            )
            val list = d.cards.map { c ->
                val tr = if (native == NativeLanguage.EN && c.en.isNotBlank()) c.en else c.ru
                val exTr = if (native == NativeLanguage.EN && c.exampleEn.isNotBlank()) c.exampleEn else c.exampleRu
                CardEntity(
                    deckId = deckId, language = lang.code, term = c.term, reading = c.reading, ipa = c.ipa,
                    translation = tr, example = c.example, exampleTranslation = exTr, partOfSpeech = c.pos,
                    synonyms = c.synonyms, antonyms = c.antonyms, forms = c.forms,
                    level = c.level.ifBlank { d.level }, category = Category.fromKey(d.category).key,
                    source = CardSource.BUILTIN, sourceTitle = name,
                )
            }
            total += cards.addCards(list).added
        }
        total
    }

    companion object {
        val DECK_COLORS = listOf(
            0xFF6750A4, 0xFF006A6A, 0xFFB3261E, 0xFF7D5260, 0xFF386A20, 0xFF00639B,
            0xFF8B5000, 0xFF4F378B, 0xFF006E1C, 0xFF984061,
        )
    }
}
