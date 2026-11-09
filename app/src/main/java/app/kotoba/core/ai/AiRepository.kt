package app.kotoba.core.ai

import app.kotoba.core.runCatchingCancellable
import app.kotoba.core.data.AiSettings
import app.kotoba.core.data.SettingsRepository
import app.kotoba.core.data.TimeProvider
import app.kotoba.core.model.NativeLanguage
import app.kotoba.core.model.StudyLanguage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

data class AiStatus(val configured: Boolean, val providerName: String, val model: String)

/** Single entry point for AI calls: resolves provider/key/model, enforces local limits, tracks usage. */
@Singleton
class AiRepository @Inject constructor(
    private val registry: AiProviders,
    private val keys: SecureKeyStore,
    private val settings: SettingsRepository,
    private val time: TimeProvider,
    private val json: Json,
) {
    val providers: List<AiProvider> get() = registry.all

    fun provider(id: String): AiProvider = registry.byId(id)

    val status: Flow<AiStatus> = settings.settings.map { s ->
        val p = registry.byId(s.ai.providerId)
        AiStatus(isConfigured(s.ai), p.displayName, modelFor(s.ai, p))
    }

    fun isConfigured(ai: AiSettings): Boolean {
        val p = registry.byId(ai.providerId)
        return !p.requiresApiKey || keys.hasKey(p.id)
    }

    fun saveKey(providerId: String, key: String) = keys.put(providerId, key)
    fun removeKey(providerId: String) = keys.remove(providerId)
    fun maskedKey(providerId: String): String? = keys.masked(providerId)
    fun hasKey(providerId: String): Boolean = keys.hasKey(providerId)

    private fun modelFor(ai: AiSettings, p: AiProvider) = ai.model.ifBlank { p.suggestedModels.first() }

    suspend fun complete(
        messages: List<AiMessage>,
        system: String? = null,
        jsonMode: Boolean = false,
        temperature: Double? = null,
        maxTokens: Int? = null,
    ): Result<AiCompletion> = withContext(Dispatchers.IO) {
        runCatchingCancellable {
            val s = settings.current()
            val ai = s.ai
            val p = registry.byId(ai.providerId)
            val key = keys.get(p.id).orEmpty()
            if (p.requiresApiKey && key.isBlank()) throw AiException(AiErrorKind.NOT_CONFIGURED, "No API key for ${p.displayName}")
            val today = time.today()
            val usage = s.aiUsage.takeIf { it.epochDay == today }
            if (usage != null) {
                if (usage.requests >= ai.dailyRequestLimit) throw AiException(AiErrorKind.LOCAL_LIMIT, "Daily request limit reached (${ai.dailyRequestLimit})")
                if (usage.tokens >= ai.dailyTokenLimit) throw AiException(AiErrorKind.LOCAL_LIMIT, "Daily token limit reached (${ai.dailyTokenLimit})")
            }
            val completion = p.complete(
                AiEndpoint(validateBaseUrl(ai.baseUrl.ifBlank { p.defaultBaseUrl }), key),
                AiRequest(
                    messages = messages,
                    system = system,
                    model = modelFor(ai, p),
                    temperature = temperature ?: ai.temperature,
                    maxTokens = maxTokens ?: ai.maxOutputTokens,
                    json = jsonMode,
                ),
            )
            settings.addAiUsage(today, completion.totalTokens)
            completion
        }
    }

    suspend fun ask(prompt: String, system: String? = null, temperature: Double? = null, maxTokens: Int? = null): Result<String> =
        complete(listOf(AiMessage(AiRole.USER, prompt)), system, false, temperature, maxTokens).map { it.text }

    /** Requests JSON and decodes it with [deserializer]; tolerant to markdown fences. */
    suspend fun <T> completeJson(
        prompt: String,
        deserializer: DeserializationStrategy<T>,
        system: String? = null,
        temperature: Double? = 0.3,
        maxTokens: Int? = null,
    ): Result<T> = complete(listOf(AiMessage(AiRole.USER, prompt)), system, jsonMode = true, temperature = temperature, maxTokens = maxTokens)
        .mapCatching { c ->
            try {
                json.decodeFromString(deserializer, extractJson(c.text))
            } catch (e: Exception) {
                throw AiException(AiErrorKind.BAD_RESPONSE, "Could not parse AI response: ${e.message?.take(120)}")
            }
        }

    /** Verifies credentials by listing models and sending a tiny prompt. Doesn't count against limits. */
    suspend fun testConnection(providerId: String, apiKey: String, baseUrl: String, model: String): Result<ConnectionTest> =
        withContext(Dispatchers.IO) {
            runCatchingCancellable {
                val p = registry.byId(providerId)
                val endpoint = AiEndpoint(validateBaseUrl(baseUrl.ifBlank { p.defaultBaseUrl }), apiKey.ifBlank { keys.get(p.id).orEmpty() })
                val models = runCatchingCancellable { p.listModels(endpoint) }.getOrDefault(emptyList())
                val reply = p.complete(
                    endpoint,
                    AiRequest(listOf(AiMessage(AiRole.USER, "Reply with the single word: OK")), null, model.ifBlank { p.suggestedModels.first() }, 0.0, 16),
                )
                ConnectionTest(models, reply.text.trim().take(40))
            }
        }
}

data class ConnectionTest(val models: List<String>, val reply: String)

/** Language-specific instructions shared by every prompt. */
object AiPrompts {
    fun languageGuide(lang: StudyLanguage): String = when (lang) {
        StudyLanguage.JA -> "Target language is Japanese. Write terms in natural Japanese script (kanji+kana). " +
            "Give readings in hiragana in the `reading` field. Use JLPT levels N5–N1. Mention pitch/politeness nuances when useful."
        StudyLanguage.ZH -> "Target language is Mandarin Chinese (simplified characters). Give pinyin with tone marks in the `reading` field. " +
            "Use HSK levels HSK1–HSK6. Mention measure words and tones when useful."
        StudyLanguage.EN -> "Target language is English. Give IPA (General American or British, consistent) in the `ipa` field. Use CEFR levels A1–C2."
        StudyLanguage.KO -> "Target language is Korean. Give Revised Romanization in the `reading` field. Use TOPIK levels TOPIK1–TOPIK6."
        else -> "Target language is ${lang.locale.getDisplayLanguage(java.util.Locale.ENGLISH)}. Give IPA in the `ipa` field. Use CEFR levels A1–C2."
    }

    fun nativeName(native: NativeLanguage): String = java.util.Locale.forLanguageTag(native.code).getDisplayLanguage(java.util.Locale.ENGLISH)

    fun tutorSystem(lang: StudyLanguage, native: NativeLanguage, profile: String?): String = buildString {
        append("You are Kotoba, a friendly, concise language tutor inside a vocabulary app. ")
        append("Explain in ${nativeName(native)} unless the learner writes in the target language or asks otherwise. ")
        append(languageGuide(lang))
        append(" Prefer short paragraphs and bullet lists; include examples with translations. ")
        append("When you suggest words to learn, format each as `term — translation`.")
        if (!profile.isNullOrBlank()) append("\n\nLearner profile (from app statistics):\n").append(profile)
    }
}

/** AI-filled card fields. Every field optional so partial answers still parse. */
@Serializable
data class CardEnrichment(
    val term: String = "",
    val translation: String = "",
    val reading: String = "",
    val ipa: String = "",
    val partOfSpeech: String = "",
    val example: String = "",
    val exampleTranslation: String = "",
    val synonyms: String = "",
    val antonyms: String = "",
    val forms: String = "",
    val level: String = "",
    val category: String = "",
    val association: String = "",
)

/** Fills card fields (translation, reading, examples…) with AI. */
@Singleton
class AiCardEnricher @Inject constructor(private val ai: AiRepository) {
    suspend fun enrich(term: String, lang: StudyLanguage, native: NativeLanguage, context: String? = null): Result<CardEnrichment> {
        val prompt = buildString {
            appendLine("Create a vocabulary flashcard for the ${lang.name} term \"$term\".")
            if (!context.isNullOrBlank()) appendLine("It appeared in this sentence: \"$context\". Use the meaning that fits this context.")
            appendLine(AiPrompts.languageGuide(lang))
            appendLine("Translate into ${AiPrompts.nativeName(native)}.")
            appendLine(
                "Return JSON with keys: term (dictionary form), translation, reading, ipa, partOfSpeech " +
                    "(noun|verb|adjective|adverb|pronoun|preposition|conjunction|interjection|particle|counter|phrase|idiom|other), " +
                    "example (natural sentence in the target language), exampleTranslation, synonyms (comma-separated), " +
                    "antonyms (comma-separated), forms (inflections/conjugations, comma-separated), level, " +
                    "category (one of: everyday,business,technology,science,literature,nature,food,travel,education,entertainment,internet_gaming,other), " +
                    "association (a short memorable mnemonic in ${AiPrompts.nativeName(native)}).",
            )
        }
        return ai.completeJson(prompt, CardEnrichment.serializer())
    }
}
