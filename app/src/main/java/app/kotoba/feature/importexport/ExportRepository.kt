package app.kotoba.feature.importexport

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.StudyLanguage
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

enum class ExportFormat(val extension: String, val mime: String) {
    CSV("csv", "text/csv"),
    TSV("txt", "text/tab-separated-values"),
    JSON("json", "application/json"),
}

@Singleton
class ExportRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: KotobaDatabase,
    private val time: TimeProvider,
    private val json: Json,
) {
    fun suggestedName(lang: StudyLanguage, deckName: String?, format: ExportFormat): String {
        val date = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(time.now()))
        val base = (deckName ?: "kotoba-${lang.code}").replace(Regex("[^\\p{L}\\p{N}_-]+"), "-").trim('-')
        return "$base-$date.${format.extension}"
    }

    /** Renders the deck ([deckId]) or every word of [lang] as text in [format]. Returns content and card count. */
    suspend fun render(lang: StudyLanguage, deckId: Long?, format: ExportFormat): Pair<String, Int> = withContext(Dispatchers.IO) {
        val cards = if (deckId != null) db.cardDao().forDeck(deckId) else db.cardDao().allForLanguage(lang.code)
        val decks = db.deckDao().all().filter { it.language == lang.code }
        val deckNames = decks.associate { it.id to it.name }
        val content = when (format) {
            ExportFormat.CSV -> DelimitedWriter.csv(cards, deckNames)
            ExportFormat.TSV -> DelimitedWriter.ankiTsv(cards, deckNames)
            ExportFormat.JSON -> {
                val contexts = db.contextDao().all().groupBy { it.cardId }
                val usedDecks = cards.mapNotNull { it.deckId }.toSet()
                val export = KotobaExport(
                    exportedAt = time.now(), language = lang.code,
                    decks = decks.filter { it.id in usedDecks }.map(KotobaMapper::deck),
                    cards = cards.map { KotobaMapper.card(it, it.deckId?.let(deckNames::get), contexts[it.id].orEmpty()) },
                )
                json.encodeToString(KotobaExport.serializer(), export)
            }
        }
        content to cards.size
    }

    suspend fun writeTo(uri: Uri, content: String) = withContext(Dispatchers.IO) {
        val out = context.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot write")
        out.use { it.write(content.toByteArray()) }
    }

    /** Writes into cache/exports and returns a share intent through the app's FileProvider. */
    suspend fun shareIntent(fileName: String, content: String, format: ExportFormat): Intent = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val file = File(dir, fileName)
        file.writeText(content)
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        Intent(Intent.ACTION_SEND).apply {
            type = format.mime
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, fileName)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
