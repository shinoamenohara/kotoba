package app.kotoba.core.lang

import app.kotoba.core.db.CardEntity
import app.kotoba.core.runCatchingCancellable
import app.kotoba.core.db.KotobaDatabase
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

data class Definition(val partOfSpeech: String, val text: String, val example: String = "")

data class LookupResult(
    val term: String,
    val language: StudyLanguage,
    val translation: String = "",
    val sentenceTranslation: String = "",
    val reading: String = "",
    val ipa: String = "",
    val partOfSpeech: String = "",
    val definitions: List<Definition> = emptyList(),
    val synonyms: List<String> = emptyList(),
    val antonyms: List<String> = emptyList(),
    val audioUrl: String = "",
    /** Already in the user's library. */
    val existingCard: CardEntity? = null,
    val error: String? = null,
)

/**
 * Reader / manga lookup: library card → on-device translation (ML Kit) → readings (ICU) →
 * free English dictionary (IPA, definitions). Every source is optional; offline still works.
 */
@Singleton
class DictionaryService @Inject constructor(
    private val db: KotobaDatabase,
    private val translator: TranslatorService,
    private val romanizer: Romanizer,
    private val client: OkHttpClient,
    private val json: Json,
) {
    suspend fun lookup(term: String, lang: StudyLanguage, native: NativeLanguage, sentence: String? = null): LookupResult = coroutineScope {
        val clean = term.trim().trim { !it.isLetterOrDigit() && !Character.isSurrogate(it) && it != '\'' && it != '-' }
        val existing = db.cardDao().findByTerm(lang.code, clean)
        val translation = async { translator.translate(clean, lang.code, native.code) }
        val sentenceTr = async { sentence?.takeIf { it.isNotBlank() && it != clean }?.let { translator.translate(it, lang.code, native.code).getOrNull() } }
        val dict = async { if (lang == StudyLanguage.EN) englishDictionary(clean) else null }
        val tr = translation.await()
        val d = dict.await()
        LookupResult(
            term = clean,
            language = lang,
            translation = existing?.translation?.takeIf { it.isNotBlank() } ?: tr.getOrNull().orEmpty(),
            sentenceTranslation = sentenceTr.await().orEmpty(),
            reading = existing?.reading?.takeIf { it.isNotBlank() } ?: romanizer.romanize(clean, lang).orEmpty(),
            ipa = existing?.ipa?.takeIf { it.isNotBlank() } ?: d?.ipa.orEmpty(),
            partOfSpeech = existing?.partOfSpeech?.takeIf { it.isNotBlank() } ?: d?.definitions?.firstOrNull()?.partOfSpeech.orEmpty(),
            definitions = d?.definitions.orEmpty(),
            synonyms = d?.synonyms.orEmpty(),
            antonyms = d?.antonyms.orEmpty(),
            audioUrl = d?.audio.orEmpty(),
            existingCard = existing,
            error = if (tr.isFailure && existing == null && d == null) tr.exceptionOrNull()?.message else null,
        )
    }

    private data class EnglishEntry(val ipa: String, val audio: String, val definitions: List<Definition>, val synonyms: List<String>, val antonyms: List<String>)

    private suspend fun englishDictionary(word: String): EnglishEntry? = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val url = "https://api.dictionaryapi.dev/api/v2/entries/en/".toHttpUrl().newBuilder().addPathSegment(word.lowercase()).build()
            client.newCall(Request.Builder().url(url).get().build()).execute().use { r ->
                if (!r.isSuccessful) return@runCatchingCancellable null
                val entries = json.parseToJsonElement(r.body?.string().orEmpty()).jsonArray
                val first = entries.firstOrNull()?.jsonObject ?: return@runCatchingCancellable null
                val phonetics = first["phonetics"]?.jsonArray.orEmpty().map { it.jsonObject }
                val ipa = first["phonetic"]?.jsonPrimitive?.contentOrNull
                    ?: phonetics.firstNotNullOfOrNull { it["text"]?.jsonPrimitive?.contentOrNull }.orEmpty()
                val audio = phonetics.firstNotNullOfOrNull { p -> p["audio"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() } }.orEmpty()
                val defs = mutableListOf<Definition>()
                val syn = linkedSetOf<String>()
                val ant = linkedSetOf<String>()
                entries.forEach { e ->
                    e.jsonObject["meanings"]?.jsonArray.orEmpty().forEach { m ->
                        val mo = m.jsonObject
                        val pos = mo["partOfSpeech"]?.jsonPrimitive?.contentOrNull.orEmpty()
                        mo["synonyms"]?.jsonArray.orEmpty().forEach { s -> syn += s.jsonPrimitive.content }
                        mo["antonyms"]?.jsonArray.orEmpty().forEach { s -> ant += s.jsonPrimitive.content }
                        mo["definitions"]?.jsonArray.orEmpty().take(3).forEach { d ->
                            val dobj = d.jsonObject
                            defs += Definition(
                                pos,
                                dobj["definition"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                                dobj["example"]?.jsonPrimitive?.contentOrNull.orEmpty(),
                            )
                        }
                    }
                }
                EnglishEntry(ipa, audio, defs.take(6), syn.take(8), ant.take(8))
            }
        }.getOrNull()
    }
}
