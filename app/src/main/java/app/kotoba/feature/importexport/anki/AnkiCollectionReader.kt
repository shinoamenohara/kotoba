package app.kotoba.feature.importexport.anki

import android.database.sqlite.SQLiteDatabase
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File

data class AnkiNoteType(val id: Long, val name: String, val fields: List<String>)
data class AnkiDeck(val id: Long, val name: String)
data class AnkiNote(
    val id: Long,
    val noteTypeId: Long,
    val fields: List<String>,
    val tags: List<String>,
    val deckId: Long?,
    val progress: AnkiProgress?,
)

data class AnkiCollection(
    /** Collection creation time, seconds. */
    val createdSec: Long,
    val noteTypes: Map<Long, AnkiNoteType>,
    val decks: Map<Long, AnkiDeck>,
    val notes: List<AnkiNote>,
    val warnings: List<String> = emptyList(),
)

/** Reads notes/cards/note types/decks from an Anki SQLite collection (legacy schema 11 and modern schema 18). */
object AnkiCollectionReader {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(dbFile: File): AnkiCollection {
        val db = SQLiteDatabase.openDatabase(
            dbFile.absolutePath, null,
            SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS,
        )
        return db.use { read(it) }
    }

    fun read(db: SQLiteDatabase): AnkiCollection {
        val warnings = mutableListOf<String>()
        var crt = 0L
        var modelsJson = ""
        var decksJson = ""
        db.rawQuery("SELECT crt, models, decks FROM col LIMIT 1", null).use { c ->
            if (c.moveToFirst()) {
                crt = c.getLong(0)
                modelsJson = c.getString(1).orEmpty()
                decksJson = c.getString(2).orEmpty()
            }
        }
        val tables = db.rawQuery("SELECT name FROM sqlite_master WHERE type = 'table'", null).use { c ->
            buildSet { while (c.moveToNext()) add(c.getString(0)) }
        }

        val noteTypes = LinkedHashMap<Long, AnkiNoteType>()
        parseLegacyModels(modelsJson).forEach { noteTypes[it.id] = it }
        if (noteTypes.isEmpty() && "notetypes" in tables) {
            runCatching { readModernNoteTypes(db) }
                .onSuccess { list -> list.forEach { noteTypes[it.id] = it } }
                .onFailure { warnings += "note types: ${it.message}" }
        }

        val decks = LinkedHashMap<Long, AnkiDeck>()
        parseLegacyDecks(decksJson).forEach { decks[it.id] = it }
        if (decks.isEmpty() && "decks" in tables) {
            runCatching {
                db.rawQuery("SELECT id, name FROM decks", null).use { c ->
                    while (c.moveToNext()) {
                        // Schema 18 separates deck levels with \u001f instead of "::".
                        val id = c.getLong(0)
                        decks[id] = AnkiDeck(id, c.getString(1).replace("\u001f", "::"))
                    }
                }
            }.onFailure { warnings += "decks: ${it.message}" }
        }

        data class CardRow(val did: Long, val ord: Int, val p: AnkiProgress)
        val cardsByNote = HashMap<Long, MutableList<CardRow>>()
        db.rawQuery("SELECT nid, did, odid, ord, type, queue, ivl, factor, reps, lapses, due, odue FROM cards", null).use { c ->
            while (c.moveToNext()) {
                val odid = c.getLong(2)
                val did = if (odid != 0L) odid else c.getLong(1)
                val odue = c.getLong(11)
                val p = AnkiProgress(
                    type = c.getInt(4), queue = c.getInt(5), ivlDays = c.getInt(6), factor = c.getInt(7),
                    reps = c.getInt(8), lapses = c.getInt(9), due = if (odid != 0L && odue != 0L) odue else c.getLong(10),
                )
                cardsByNote.getOrPut(c.getLong(0)) { mutableListOf() } += CardRow(did, c.getInt(3), p)
            }
        }

        val notes = ArrayList<AnkiNote>()
        db.rawQuery("SELECT id, mid, tags, flds FROM notes ORDER BY id", null).use { c ->
            while (c.moveToNext()) {
                val id = c.getLong(0)
                val cards = cardsByNote[id].orEmpty()
                val first = cards.minByOrNull { it.ord }
                // The most advanced card represents the word's progress.
                val best = cards.filter { it.p.type == 2 }.maxByOrNull { it.p.ivlDays } ?: first
                notes += AnkiNote(
                    id = id,
                    noteTypeId = c.getLong(1),
                    fields = c.getString(3).orEmpty().split('\u001f'),
                    tags = AnkiFields.tags(c.getString(2).orEmpty()),
                    deckId = first?.did,
                    progress = best?.p,
                )
            }
        }

        // Notes whose note type couldn't be resolved still import with generic field names.
        notes.map { it.noteTypeId }.distinct().filter { it !in noteTypes }.forEach { mid ->
            val width = notes.filter { it.noteTypeId == mid }.maxOf { it.fields.size }
            noteTypes[mid] = AnkiNoteType(mid, "Note type $mid", List(width) { "Field ${it + 1}" })
        }
        return AnkiCollection(crt, noteTypes, decks, notes, warnings)
    }

    private fun readModernNoteTypes(db: SQLiteDatabase): List<AnkiNoteType> {
        val names = LinkedHashMap<Long, String>()
        db.rawQuery("SELECT id, name FROM notetypes", null).use { c -> while (c.moveToNext()) names[c.getLong(0)] = c.getString(1) }
        val fields = HashMap<Long, MutableList<Pair<Int, String>>>()
        db.rawQuery("SELECT ntid, ord, name FROM fields", null).use { c ->
            while (c.moveToNext()) fields.getOrPut(c.getLong(0)) { mutableListOf() } += c.getInt(1) to c.getString(2)
        }
        return names.map { (id, name) -> AnkiNoteType(id, name, fields[id].orEmpty().sortedBy { it.first }.map { it.second }) }
    }

    fun parseLegacyModels(raw: String): List<AnkiNoteType> {
        if (raw.isBlank()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        return obj.mapNotNull { (key, v) ->
            val m = v as? JsonObject ?: return@mapNotNull null
            val id = m["id"]?.jsonPrimitive?.longOrNull ?: key.toLongOrNull() ?: return@mapNotNull null
            val flds = m["flds"]?.jsonArray.orEmpty().mapNotNull { f ->
                val fo = f as? JsonObject ?: return@mapNotNull null
                (fo["ord"]?.jsonPrimitive?.intOrNull ?: 0) to (fo["name"]?.jsonPrimitive?.contentOrNull ?: "")
            }.sortedBy { it.first }.map { it.second }
            AnkiNoteType(id, m["name"]?.jsonPrimitive?.contentOrNull ?: "Note type", flds)
        }
    }

    fun parseLegacyDecks(raw: String): List<AnkiDeck> {
        if (raw.isBlank()) return emptyList()
        val obj = runCatching { json.parseToJsonElement(raw).jsonObject }.getOrNull() ?: return emptyList()
        return obj.mapNotNull { (key, v) ->
            val d = v as? JsonObject ?: return@mapNotNull null
            val id = d["id"]?.jsonPrimitive?.longOrNull ?: key.toLongOrNull() ?: return@mapNotNull null
            AnkiDeck(id, d["name"]?.jsonPrimitive?.contentOrNull ?: "Deck $id")
        }
    }
}
