package app.kotoba.feature.importexport

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import app.kotoba.core.FileKinds
import app.kotoba.core.data.Achievement
import app.kotoba.core.data.GamificationRepository
import app.kotoba.core.data.SeedRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DeckEntity
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.importexport.anki.AnkiCollectionReader
import app.kotoba.feature.importexport.anki.AnkiPackageReader
import app.kotoba.feature.importexport.anki.AnkiProgressConverter
import app.kotoba.feature.importexport.parse.DelimitedParser
import app.kotoba.feature.importexport.parse.DictionaryLineParser
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ImportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KotobaDatabase,
    private val gamification: GamificationRepository,
    private val time: TimeProvider,
    private val json: Json,
) {
    fun displayName(uri: Uri): String = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: uri.lastPathSegment?.substringAfterLast('/') ?: "import"

    suspend fun parse(uri: Uri, name: String = displayName(uri)): ParsedImport = withContext(Dispatchers.IO) {
        val ext = FileKinds.extension(name)
        val resolver = context.contentResolver
        when (ext) {
            "apkg", "colpkg" -> {
                val work = File(context.cacheDir, "import/${System.currentTimeMillis()}")
                val input = resolver.openInputStream(uri) ?: throw IOException("Cannot open $name")
                val pkg = input.use { AnkiPackageReader.unpack(it, work) }
                val col = AnkiCollectionReader.read(pkg.collectionFile)
                fromAnki(name, col, pkg.media, pkg.warnings + col.warnings, work)
            }
            else -> {
                val bytes = resolver.openInputStream(uri)?.use { it.readBytes() } ?: throw IOException("Cannot open $name")
                parseText(name, bytes.decodeToString(), ext)
            }
        }
    }

    fun parseText(name: String, text: String, ext: String = FileKinds.extension(name)): ParsedImport {
        val trimmed = text.trimStart('\uFEFF', ' ', '\n', '\r', '\t')
        if (ext == "json" || trimmed.startsWith("{") || trimmed.startsWith("[")) {
            runCatching { parseJson(name, trimmed) }.getOrNull()?.let { return it }
        }
        if (ext != "csv" && ext != "tsv" && DictionaryLineParser.looksLikeDictionary(text)) {
            val pairs = DictionaryLineParser.parse(text)
            val records = pairs.map { RawRecord(0, listOf(it.first, it.second)) }
            return table(ImportKind.DICTIONARY, name, listOf("Term", "Translation"), records)
        }
        val t = DelimitedParser.parse(text, delimiter = if (ext == "tsv") '\t' else null)
        if (t.headers.size < 2) {
            // One column per line: still try dictionary separators.
            val pairs = DictionaryLineParser.parse(text)
            if (pairs.isNotEmpty()) return table(ImportKind.DICTIONARY, name, listOf("Term", "Translation"), pairs.map { RawRecord(0, listOf(it.first, it.second)) })
        }
        return table(ImportKind.CSV, name, t.headers, t.rows.map { RawRecord(0, it) })
    }

    private fun parseJson(name: String, text: String): ParsedImport? {
        val root = json.parseToJsonElement(text)
        if (root is JsonObject && (root["format"] as? JsonPrimitive)?.contentOrNull == KotobaExport.FORMAT) {
            val export = json.decodeFromJsonElement(KotobaExport.serializer(), root)
            return ParsedImport(
                kind = ImportKind.KOTOBA, fileName = name, groups = emptyList(), records = emptyList(),
                sourceDecks = export.decks.map { it.name }, kotoba = export,
                suggestedLanguage = (export.language ?: export.cards.firstOrNull()?.language)?.let { StudyLanguage.fromCode(it) },
            )
        }
        val array = when (root) {
            is JsonArray -> root
            is JsonObject -> root.values.firstOrNull { it is JsonArray && it.isNotEmpty() && it.first() is JsonObject } as? JsonArray
            else -> null
        } ?: return null
        val objects = array.filterIsInstance<JsonObject>()
        if (objects.isEmpty()) return null
        val keys = LinkedHashSet<String>().apply { objects.forEach { addAll(it.keys) } }.toList()
        val records = objects.map { o -> RawRecord(0, keys.map { k -> stringify(o[k]) }) }
        return table(ImportKind.JSON, name, keys, records)
    }

    private fun stringify(e: JsonElement?): String = when (e) {
        null, JsonNull -> ""
        is JsonPrimitive -> e.contentOrNull.orEmpty()
        is JsonArray -> e.joinToString(", ") { stringify(it) }
        is JsonObject -> e.toString()
    }

    private fun table(kind: ImportKind, name: String, headers: List<String>, records: List<RawRecord>): ParsedImport {
        val defaults = SmartMapping.defaults(headers, null)
        val termIdx = defaults.indexOf(FieldTarget.TERM)
        val lang = if (termIdx >= 0) SmartMapping.detectLanguage(records.take(50).map { it.fields.getOrElse(termIdx) { "" } }) else null
        return ParsedImport(
            kind = kind, fileName = name,
            groups = listOf(FieldGroup(0, name, headers, records.size, records.take(3).map { it.fields })),
            records = records, suggestedLanguage = lang,
        )
    }

    internal fun fromAnki(
        name: String,
        col: app.kotoba.feature.importexport.anki.AnkiCollection,
        media: Map<String, File>,
        warnings: List<String>,
        work: File?,
    ): ParsedImport {
        val byType = col.notes.groupBy { it.noteTypeId }
        val groups = byType.map { (mid, notes) ->
            val nt = col.noteTypes.getValue(mid)
            FieldGroup(mid, nt.name, nt.fields, notes.size, notes.take(3).map { n -> n.fields })
        }.sortedByDescending { it.count }
        val records = col.notes.map { n ->
            RawRecord(n.noteTypeId, n.fields, n.tags, n.deckId?.let { col.decks[it]?.name }?.let(::deckTitle), n.progress)
        }
        val termSamples = groups.flatMap { g ->
            val idx = SmartMapping.defaults(g.fields, null).indexOf(FieldTarget.TERM)
            g.samples.map { s -> app.kotoba.feature.importexport.anki.AnkiFields.plainText(s.getOrElse(idx) { "" }) }
        }
        return ParsedImport(
            kind = ImportKind.ANKI, fileName = name, groups = groups, records = records,
            sourceDecks = records.mapNotNull { it.deckName }.distinct(), media = media,
            collectionCreatedSec = col.createdSec, warnings = warnings, workDir = work,
            suggestedLanguage = SmartMapping.detectLanguage(termSamples),
        )
    }

    private fun deckTitle(ankiName: String) = ankiName.split("::").joinToString(" › ") { it.trim() }

    /** Writes the parsed import into the database. */
    suspend fun commit(parsed: ParsedImport, options: ImportOptions): ImportReport = withContext(Dispatchers.IO) {
        try {
            val report = if (parsed.kind == ImportKind.KOTOBA) commitKotoba(parsed, options) else commitRecords(parsed, options)
            if (report.added > 0) gamification.unlock(Achievement.FIRST_IMPORT)
            report
        } finally {
            parsed.workDir?.deleteRecursively()
        }
    }

    fun discard(parsed: ParsedImport) { parsed.workDir?.deleteRecursively() }

    private suspend fun deckResolver(options: ImportOptions, source: String, fallbackName: String): suspend (String?) -> Long? {
        val lang = options.language.code
        val cache = HashMap<String, Long>()
        val deckDao = db.deckDao()
        var created = 0
        suspend fun ensure(name: String): Long = cache.getOrPut(name) {
            deckDao.findByName(lang, name)?.id ?: deckDao.insert(
                DeckEntity(name = name, language = lang, source = source, color = SeedRepository.DECK_COLORS[(created++) % SeedRepository.DECK_COLORS.size]),
            )
        }
        return when (options.deckMode) {
            DeckMode.EXISTING -> { _ -> options.existingDeckId }
            DeckMode.NEW -> { _ -> ensure(options.newDeckName.ifBlank { fallbackName }) }
            DeckMode.SOURCE_DECKS -> { deck -> ensure(deck ?: fallbackName) }
        }
    }

    private suspend fun commitRecords(parsed: ParsedImport, options: ImportOptions): ImportReport {
        val now = time.now()
        val isAnki = parsed.kind == ImportKind.ANKI
        val source = if (isAnki) CardSource.ANKI else CardSource.IMPORT
        val fallbackDeck = parsed.fileName.substringBeforeLast('.')
        val mediaDir = File(context.filesDir, "media/import-$now")
        val mediaUris = HashMap<String, String?>()
        fun media(name: String?): String {
            if (name.isNullOrBlank()) return ""
            return mediaUris.getOrPut(name) {
                val src = parsed.media[name] ?: return@getOrPut null
                val safe = name.replace(Regex("[^\\p{L}\\p{N}._-]"), "_").take(120)
                val dst = File(mediaDir, safe)
                runCatching { mediaDir.mkdirs(); src.copyTo(dst, overwrite = true); Uri.fromFile(dst).toString() }.getOrNull()
            }.orEmpty()
        }

        var errors = 0
        val errorSamples = mutableListOf<String>()
        data class Pending(val card: CardEntity, val deck: String?)
        val pending = ArrayList<Pending>(parsed.records.size)
        var withProgress = 0
        parsed.records.forEachIndexed { index, r ->
            val targets = options.mapping[r.groupId] ?: SmartMapping.defaults(parsed.groups.first { it.id == r.groupId }.fields, options.language)
            val draft = RecordMapper.toDraft(r, targets, html = isAnki)
            if (draft == null) {
                errors++
                if (errorSamples.size < 10) errorSamples += "#${index + 1}: ${r.fields.joinToString(" | ").take(80)}"
                return@forEachIndexed
            }
            var card = CardEntity(
                language = options.language.code, term = draft.term, translation = draft.translation, reading = draft.reading,
                ipa = draft.ipa, example = draft.example, exampleTranslation = draft.exampleTranslation, notes = draft.notes,
                partOfSpeech = draft.partOfSpeech, tags = draft.tags.joinToString(", "),
                audioUri = media(draft.audioName), imageUri = media(draft.imageName),
                source = source, sourceTitle = r.deckName ?: fallbackDeck, createdAt = now, updatedAt = now, due = now,
            )
            if (options.importProgress && r.progress != null) {
                AnkiProgressConverter.convert(r.progress, now, parsed.collectionCreatedSec)?.let { p ->
                    withProgress++
                    card = card.copy(
                        state = p.state, mastery = p.mastery, stability = p.stability, difficulty = p.difficulty,
                        due = p.due, lastReview = p.lastReview, reps = p.reps, lapses = p.lapses, suspended = p.suspended,
                        firstStudiedAt = p.lastReview, masteredAt = if (p.mastery == app.kotoba.core.model.Mastery.MASTERED) p.lastReview else null,
                    )
                }
            }
            pending += Pending(card, r.deckName)
        }

        var added = 0
        var duplicates = 0
        val decksBefore = db.deckDao().all().size
        db.withTransaction {
            val resolve = deckResolver(options, source, fallbackDeck)
            val cards = db.cardDao()
            val contexts = db.contextDao()
            val seen = cards.allTermsLower(options.language.code).toHashSet()
            pending.forEach { (c, deck) ->
                val key = c.term.trim().lowercase()
                if (options.skipDuplicates && key in seen) { duplicates++; return@forEach }
                seen += key
                val id = cards.insert(c.copy(deckId = resolve(deck)))
                if (c.example.isNotBlank()) {
                    contexts.insert(ContextEntity(cardId = id, sentence = c.example, translation = c.exampleTranslation, source = ContextSource.IMPORT, sourceTitle = c.sourceTitle))
                }
                added++
            }
        }
        val usedMedia = mediaUris.values.count { !it.isNullOrEmpty() }
        return ImportReport(
            added = added, duplicates = duplicates, errors = errors, errorSamples = errorSamples, mediaFiles = usedMedia,
            decks = db.deckDao().all().size - decksBefore, withProgress = if (added > 0) withProgress else 0,
            warnings = parsed.warnings,
        )
    }

    private suspend fun commitKotoba(parsed: ParsedImport, options: ImportOptions): ImportReport {
        val export = parsed.kotoba ?: return ImportReport(0, 0, 0)
        val now = time.now()
        var added = 0
        var duplicates = 0
        var errors = 0
        val decksBefore = db.deckDao().all().size
        db.withTransaction {
            val resolve = deckResolver(options, CardSource.IMPORT, parsed.fileName.substringBeforeLast('.'))
            val cards = db.cardDao()
            val contexts = db.contextDao()
            val seen = cards.allTermsLower(options.language.code).toHashSet()
            export.cards.forEach { k ->
                val key = k.term.trim().lowercase()
                if (key.isEmpty()) { errors++; return@forEach }
                if (options.skipDuplicates && key in seen) { duplicates++; return@forEach }
                seen += key
                val entity = KotobaMapper.toEntity(k, resolve(k.deck), options.language.code, now, options.importProgress)
                val id = cards.insert(entity)
                contexts.insertAll(k.contexts.map { KotobaMapper.context(it, id) })
                added++
            }
        }
        return ImportReport(added, duplicates, errors, decks = db.deckDao().all().size - decksBefore)
    }
}
