package app.kotoba.feature.reader

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.LibraryAdd
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.kotoba.R
import app.kotoba.core.data.AddCardsReport
import app.kotoba.core.data.CardRepository
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.db.CardEntity
import app.kotoba.core.db.ContextEntity
import app.kotoba.core.db.DocumentEntity
import app.kotoba.core.lang.DictionaryService
import app.kotoba.core.lang.LookupResult
import app.kotoba.core.lang.SpeechService
import app.kotoba.core.lang.TranslatorService
import app.kotoba.core.model.CardSource
import app.kotoba.core.model.Category
import app.kotoba.core.model.ContextSource
import app.kotoba.core.model.DocumentKind
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.ui.components.MasteryBadge
import app.kotoba.core.ui.components.SpeakButton
import app.kotoba.core.ui.theme.LocalUserSettings
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import javax.inject.Inject

/** A word or phrase picked in the reader / manga, plus where it came from. */
data class LookupState(
    val term: String,
    val sentence: String,
    val loading: Boolean = true,
    val result: LookupResult? = null,
    val addedCardId: Long? = null,
    val contextAdded: Boolean = false,
    val busy: Boolean = false,
    /** Global char range in the book (reader) — used for highlights. */
    val start: Int = -1,
    val end: Int = -1,
)

/** Lookup → card pipeline shared by the book reader and the manga reader. */
class WordStudyHelper @Inject constructor(
    private val cards: CardRepository,
    private val dictionary: DictionaryService,
    private val settings: SettingsRepository,
    private val speech: SpeechService,
    private val translator: TranslatorService,
) {
    suspend fun lookup(term: String, lang: StudyLanguage, sentence: String?): LookupResult =
        dictionary.lookup(term, lang, settings.current().nativeLanguage, sentence)

    suspend fun translate(text: String, lang: StudyLanguage): Result<String> =
        translator.translate(text, lang.code, settings.current().nativeLanguage.code)

    suspend fun speak(text: String, lang: StudyLanguage) = speech.speak(text, lang)

    fun deckName(doc: DocumentEntity): String = (if (doc.kind == DocumentKind.MANGA) "🎌 " else "📖 ") + doc.title

    private fun contextSource(doc: DocumentEntity) = if (doc.kind == DocumentKind.MANGA) ContextSource.MANGA else ContextSource.BOOK
    private fun cardSource(doc: DocumentEntity) = if (doc.kind == DocumentKind.MANGA) CardSource.MANGA else CardSource.BOOK

    private suspend fun deckFor(doc: DocumentEntity, lang: StudyLanguage): Long = cards.ensureDeck(
        lang, deckName(doc),
        category = Category.LITERATURE.key,
        source = (if (doc.kind == DocumentKind.MANGA) "manga:" else "book:") + doc.id,
    )

    /** Adds the looked-up word with its sentence as context. Returns the card id, or null if it already existed. */
    suspend fun addWord(
        doc: DocumentEntity,
        lang: StudyLanguage,
        result: LookupResult,
        sentence: String,
        imageUri: String = "",
    ): Long? {
        val card = CardEntity(
            deckId = deckFor(doc, lang),
            language = lang.code,
            term = result.term,
            reading = result.reading,
            ipa = result.ipa,
            translation = result.translation,
            example = sentence,
            exampleTranslation = result.sentenceTranslation,
            partOfSpeech = result.partOfSpeech,
            synonyms = result.synonyms.joinToString(", "),
            antonyms = result.antonyms.joinToString(", "),
            imageUri = imageUri,
            source = cardSource(doc),
            sourceTitle = doc.title,
            notes = result.definitions.take(3).joinToString("\n") { d -> listOf(d.partOfSpeech, d.text).filter { it.isNotBlank() }.joinToString(": ") },
        )
        val ctx = context(doc, sentence, result.sentenceTranslation, imageUri)
        return cards.addCards(listOf(card), mapOf(0 to listOfNotNull(ctx))).ids.firstOrNull()
    }

    /** Attaches [sentence] to an existing card. Returns false if it was already there. */
    suspend fun addContext(cardId: Long, doc: DocumentEntity, sentence: String, translation: String, imageUri: String = ""): Boolean {
        val ctx = context(doc, sentence, translation, imageUri)?.copy(cardId = cardId) ?: return false
        return cards.addContext(ctx) != null
    }

    /** Bulk add: translates each term on-device, skips duplicates. */
    suspend fun addMany(doc: DocumentEntity, lang: StudyLanguage, picks: List<Pick>): AddCardsReport = coroutineScope {
        val deckId = deckFor(doc, lang)
        val limit = Semaphore(4)
        val translated = picks.map { p -> async { limit.withPermit { translate(p.term, lang).getOrDefault("") } } }.awaitAll()
        val newCards = picks.mapIndexed { i, p ->
            CardEntity(
                deckId = deckId, language = lang.code, term = p.term, translation = translated[i],
                example = p.sentence, imageUri = p.imageUri, source = cardSource(doc), sourceTitle = doc.title,
            )
        }
        val contexts = picks.mapIndexedNotNull { i, p -> context(doc, p.sentence, "", p.imageUri)?.let { i to listOf(it) } }.toMap()
        cards.addCards(newCards, contexts)
    }

    data class Pick(val term: String, val sentence: String, val imageUri: String = "")

    private fun context(doc: DocumentEntity, sentence: String, translation: String, imageUri: String): ContextEntity? =
        sentence.takeIf { it.isNotBlank() }?.let {
            ContextEntity(
                cardId = 0, sentence = it.take(500), translation = translation, source = contextSource(doc),
                sourceTitle = doc.title, documentId = doc.id, imageUri = imageUri,
            )
        }
}

/** Body of the word sheet: term, reading, translation, sentence, definitions and actions. */
@Composable
fun WordLookupContent(
    state: LookupState,
    language: StudyLanguage,
    onSpeak: () -> Unit,
    onAdd: () -> Unit,
    onAddContext: () -> Unit,
    onExplain: () -> Unit,
    modifier: Modifier = Modifier,
    extraActions: @Composable () -> Unit = {},
) {
    val r = state.result
    val existing = r?.existingCard
    val showReading = LocalUserSettings.current.showReading
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(r?.term ?: state.term, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                val reading = listOfNotNull(
                    r?.reading?.takeIf { it.isNotBlank() && it != r.term && (showReading || !language.hasReading) },
                    r?.ipa?.takeIf { it.isNotBlank() },
                ).joinToString("  ·  ")
                if (reading.isNotEmpty()) Text(reading, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            SpeakButton(onClick = onSpeak)
        }
        if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        if (existing != null) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MasteryBadge(existing.mastery)
                Text(stringResource(R.string.reader_in_library), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        r?.translation?.takeIf { it.isNotBlank() }?.let {
            Text(it, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
        }
        if (r?.partOfSpeech?.isNotBlank() == true) {
            Text(r.partOfSpeech, style = MaterialTheme.typography.labelMedium, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r?.error != null && r.translation.isBlank()) {
            Text(stringResource(R.string.reader_lookup_offline), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
        }
        if (state.sentence.isNotBlank() && state.sentence != state.term) {
            Column(
                Modifier.fillMaxWidth()
                    .background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(12.dp))
                    .padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row {
                    Icon(Icons.Filled.FormatQuote, null, Modifier.size(18.dp), tint = MaterialTheme.colorScheme.primary)
                    Spacer(Modifier.width(6.dp))
                    Text(state.sentence, style = MaterialTheme.typography.bodyMedium)
                }
                r?.sentenceTranslation?.takeIf { it.isNotBlank() }?.let {
                    Text(it, style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        r?.definitions?.takeIf { it.isNotEmpty() }?.let { defs ->
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                defs.take(4).forEachIndexed { i, d ->
                    Text(
                        "${i + 1}. " + (if (d.partOfSpeech.isNotBlank()) "(${d.partOfSpeech}) " else "") + d.text,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (d.example.isNotBlank()) Text("“${d.example}”", style = MaterialTheme.typography.bodySmall, fontStyle = FontStyle.Italic, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (r != null && r.synonyms.isNotEmpty()) {
            Text(stringResource(R.string.reader_synonyms, r.synonyms.take(6).joinToString(", ")), style = MaterialTheme.typography.bodySmall)
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when {
                state.addedCardId != null -> FilledTonalButton(onClick = {}, enabled = false) {
                    Icon(Icons.Filled.Check, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reader_added))
                }
                existing != null -> FilledTonalButton(onClick = onAddContext, enabled = !state.contextAdded && !state.busy && state.sentence.isNotBlank()) {
                    Icon(if (state.contextAdded) Icons.Filled.Check else Icons.Filled.FormatQuote, null)
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(if (state.contextAdded) R.string.reader_context_added else R.string.reader_add_context))
                }
                else -> Button(onClick = onAdd, enabled = !state.loading && !state.busy) {
                    Icon(Icons.Filled.LibraryAdd, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reader_add_to_dictionary))
                }
            }
            OutlinedButton(onClick = onExplain) {
                Icon(Icons.Filled.AutoAwesome, null); Spacer(Modifier.width(6.dp)); Text(stringResource(R.string.reader_explain_ai))
            }
            extraActions()
        }
        AnimatedVisibility(state.addedCardId != null) {
            Text(stringResource(R.string.reader_added_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
