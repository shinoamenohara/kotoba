package app.kotoba.core.ai

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException

enum class AiRole { USER, ASSISTANT }

data class AiMessage(val role: AiRole, val content: String)

data class AiRequest(
    val messages: List<AiMessage>,
    val system: String? = null,
    val model: String,
    val temperature: Double,
    val maxTokens: Int,
    /** Ask the provider for a JSON object response when supported. */
    val json: Boolean = false,
)

data class AiCompletion(val text: String, val inputTokens: Int, val outputTokens: Int) {
    val totalTokens: Int get() = inputTokens + outputTokens
}

enum class AiErrorKind { NOT_CONFIGURED, INVALID_KEY, RATE_LIMITED, QUOTA_EXCEEDED, LOCAL_LIMIT, NETWORK, SERVER, BAD_REQUEST, BAD_RESPONSE }

class AiException(val kind: AiErrorKind, message: String, val httpCode: Int? = null) : Exception(message)

/** Connection details passed to a provider; the key never leaves this object/request headers. */
data class AiEndpoint(val baseUrl: String, val apiKey: String)

/**
 * A pluggable LLM backend. Add a provider by implementing this and registering it in [AiProviders];
 * nothing else in the app needs to change.
 */
interface AiProvider {
    val id: String
    val displayName: String
    val defaultBaseUrl: String
    /** Suggested models; the user may type any model id, and [listModels] can fetch live ones. */
    val suggestedModels: List<String>
    val requiresApiKey: Boolean get() = true
    val keyHelpUrl: String

    suspend fun complete(endpoint: AiEndpoint, request: AiRequest): AiCompletion
    suspend fun listModels(endpoint: AiEndpoint): List<String>
}

internal val JSON_MEDIA = "application/json; charset=utf-8".toMediaType()

internal fun OkHttpClient.execute(request: Request, json: Json): JsonObject {
    val response: Response = try {
        newCall(request).execute()
    } catch (e: IOException) {
        throw AiException(AiErrorKind.NETWORK, e.message ?: "Network error")
    }
    response.use { r ->
        val body = r.body?.string().orEmpty()
        if (!r.isSuccessful) throw httpError(r.code, body, json)
        return try {
            json.parseToJsonElement(body).jsonObject
        } catch (e: Exception) {
            throw AiException(AiErrorKind.BAD_RESPONSE, "Unexpected response: ${body.take(200)}")
        }
    }
}

internal fun httpError(code: Int, body: String, json: Json): AiException {
    val message = runCatching {
        val obj = json.parseToJsonElement(body).jsonObject
        val err = obj["error"]
        when (err) {
            is JsonObject -> err["message"]?.jsonPrimitive?.contentOrNull
            is JsonPrimitive -> err.contentOrNull
            else -> null
        } ?: obj["message"]?.jsonPrimitive?.contentOrNull
    }.getOrNull() ?: body.take(300).ifBlank { "HTTP $code" }
    val kind = when (code) {
        401, 403 -> AiErrorKind.INVALID_KEY
        402 -> AiErrorKind.QUOTA_EXCEEDED
        429 -> if (message.contains("quota", true) || message.contains("billing", true)) AiErrorKind.QUOTA_EXCEEDED else AiErrorKind.RATE_LIMITED
        in 400..499 -> AiErrorKind.BAD_REQUEST
        else -> AiErrorKind.SERVER
    }
    return AiException(kind, message, code)
}

/** OpenAI Chat Completions API — also used by OpenRouter, DeepSeek, Groq, Mistral, Ollama, LM Studio. */
class OpenAiCompatibleProvider(
    override val id: String,
    override val displayName: String,
    override val defaultBaseUrl: String,
    override val suggestedModels: List<String>,
    override val keyHelpUrl: String,
    override val requiresApiKey: Boolean = true,
    private val supportsJsonMode: Boolean = true,
    private val client: OkHttpClient,
    private val json: Json,
) : AiProvider {
    override suspend fun complete(endpoint: AiEndpoint, request: AiRequest): AiCompletion {
        val body = buildJsonObject {
            put("model", request.model)
            put("temperature", request.temperature)
            put("max_tokens", request.maxTokens)
            putJsonArray("messages") {
                request.system?.let { add(buildJsonObject { put("role", "system"); put("content", it) }) }
                request.messages.forEach { m ->
                    add(buildJsonObject { put("role", if (m.role == AiRole.USER) "user" else "assistant"); put("content", m.content) })
                }
            }
            if (request.json && supportsJsonMode) putJsonObject("response_format") { put("type", "json_object") }
        }
        val http = Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + "/chat/completions")
            .apply { if (endpoint.apiKey.isNotBlank()) header("Authorization", "Bearer ${endpoint.apiKey}") }
            .header("HTTP-Referer", "https://github.com/shinoamenohara/kotoba")
            .header("X-Title", "Kotoba")
            .post(body.toString().toRequestBody(JSON_MEDIA))
            .build()
        val obj = client.execute(http, json)
        val text = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
            ?.get("content")?.jsonPrimitive?.contentOrNull
            ?: throw AiException(AiErrorKind.BAD_RESPONSE, "Empty completion")
        val usage = obj["usage"]?.jsonObject
        return AiCompletion(
            text,
            usage?.get("prompt_tokens")?.jsonPrimitive?.intOrNull ?: 0,
            usage?.get("completion_tokens")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    override suspend fun listModels(endpoint: AiEndpoint): List<String> {
        val http = Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + "/models")
            .apply { if (endpoint.apiKey.isNotBlank()) header("Authorization", "Bearer ${endpoint.apiKey}") }
            .get().build()
        val obj = client.execute(http, json)
        return obj["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }?.sorted().orEmpty()
    }
}

/** Anthropic Messages API. */
class AnthropicProvider(private val client: OkHttpClient, private val json: Json) : AiProvider {
    override val id = "anthropic"
    override val displayName = "Anthropic Claude"
    override val defaultBaseUrl = "https://api.anthropic.com/v1"
    override val suggestedModels = listOf("claude-sonnet-4-5", "claude-haiku-4-5", "claude-opus-4-1")
    override val keyHelpUrl = "https://console.anthropic.com/settings/keys"

    private fun Request.Builder.auth(key: String) = header("x-api-key", key).header("anthropic-version", "2023-06-01")

    override suspend fun complete(endpoint: AiEndpoint, request: AiRequest): AiCompletion {
        val system = listOfNotNull(
            request.system,
            if (request.json) "Respond with a single valid JSON object and nothing else." else null,
        ).joinToString("\n\n")
        val body = buildJsonObject {
            put("model", request.model)
            put("max_tokens", request.maxTokens)
            put("temperature", request.temperature.coerceIn(0.0, 1.0))
            if (system.isNotBlank()) put("system", system)
            putJsonArray("messages") {
                request.messages.forEach { m ->
                    add(buildJsonObject { put("role", if (m.role == AiRole.USER) "user" else "assistant"); put("content", m.content) })
                }
            }
        }
        val http = Request.Builder().url(endpoint.baseUrl.trimEnd('/') + "/messages").auth(endpoint.apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA)).build()
        val obj = client.execute(http, json)
        val text = obj["content"]?.jsonArray
            ?.filter { it.jsonObject["type"]?.jsonPrimitive?.contentOrNull == "text" }
            ?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?.takeIf { it.isNotBlank() } ?: throw AiException(AiErrorKind.BAD_RESPONSE, "Empty completion")
        val usage = obj["usage"]?.jsonObject
        return AiCompletion(text, usage?.get("input_tokens")?.jsonPrimitive?.intOrNull ?: 0, usage?.get("output_tokens")?.jsonPrimitive?.intOrNull ?: 0)
    }

    override suspend fun listModels(endpoint: AiEndpoint): List<String> {
        val obj = client.execute(Request.Builder().url(endpoint.baseUrl.trimEnd('/') + "/models").auth(endpoint.apiKey).get().build(), json)
        return obj["data"]?.jsonArray?.mapNotNull { it.jsonObject["id"]?.jsonPrimitive?.contentOrNull }.orEmpty()
    }
}

/** Google Gemini (Generative Language API). */
class GeminiProvider(private val client: OkHttpClient, private val json: Json) : AiProvider {
    override val id = "gemini"
    override val displayName = "Google Gemini"
    override val defaultBaseUrl = "https://generativelanguage.googleapis.com/v1beta"
    override val suggestedModels = listOf("gemini-2.5-flash", "gemini-2.5-pro", "gemini-2.5-flash-lite")
    override val keyHelpUrl = "https://aistudio.google.com/app/apikey"

    override suspend fun complete(endpoint: AiEndpoint, request: AiRequest): AiCompletion {
        val body = buildJsonObject {
            request.system?.let { sys -> putJsonObject("systemInstruction") { putJsonArray("parts") { add(buildJsonObject { put("text", sys) }) } } }
            put(
                "contents",
                buildJsonArray {
                    request.messages.forEach { m ->
                        add(
                            buildJsonObject {
                                put("role", if (m.role == AiRole.USER) "user" else "model")
                                putJsonArray("parts") { add(buildJsonObject { put("text", m.content) }) }
                            },
                        )
                    }
                },
            )
            putJsonObject("generationConfig") {
                put("temperature", request.temperature)
                put("maxOutputTokens", request.maxTokens)
                if (request.json) put("responseMimeType", "application/json")
            }
        }
        val http = Request.Builder()
            .url(endpoint.baseUrl.trimEnd('/') + "/models/${request.model}:generateContent")
            .header("x-goog-api-key", endpoint.apiKey)
            .post(body.toString().toRequestBody(JSON_MEDIA)).build()
        val obj = client.execute(http, json)
        val parts = obj["candidates"]?.jsonArray?.firstOrNull()?.jsonObject?.get("content")?.jsonObject?.get("parts") as? JsonArray
        val text = parts?.joinToString("") { it.jsonObject["text"]?.jsonPrimitive?.contentOrNull.orEmpty() }
            ?.takeIf { it.isNotBlank() } ?: throw AiException(AiErrorKind.BAD_RESPONSE, "Empty completion (possibly blocked by safety filters)")
        val usage = obj["usageMetadata"]?.jsonObject
        return AiCompletion(
            text,
            usage?.get("promptTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
            usage?.get("candidatesTokenCount")?.jsonPrimitive?.intOrNull ?: 0,
        )
    }

    override suspend fun listModels(endpoint: AiEndpoint): List<String> {
        val obj = client.execute(
            Request.Builder().url(endpoint.baseUrl.trimEnd('/') + "/models?pageSize=200").header("x-goog-api-key", endpoint.apiKey).get().build(),
            json,
        )
        return obj["models"]?.jsonArray?.mapNotNull { m ->
            val o = m.jsonObject
            val methods = o["supportedGenerationMethods"]?.jsonArray?.map { it.jsonPrimitive.content }.orEmpty()
            o["name"]?.jsonPrimitive?.contentOrNull?.removePrefix("models/")?.takeIf { "generateContent" in methods }
        }.orEmpty()
    }
}

/** Registry of available providers. */
class AiProviders(client: OkHttpClient, json: Json) {
    val all: List<AiProvider> = listOf(
        OpenAiCompatibleProvider(
            "openai", "OpenAI", "https://api.openai.com/v1", listOf("gpt-4o-mini", "gpt-4.1-mini", "gpt-4.1", "gpt-4o"),
            "https://platform.openai.com/api-keys", client = client, json = json,
        ),
        AnthropicProvider(client, json),
        GeminiProvider(client, json),
        OpenAiCompatibleProvider(
            "openrouter", "OpenRouter", "https://openrouter.ai/api/v1",
            listOf("openai/gpt-4o-mini", "anthropic/claude-sonnet-4.5", "google/gemini-2.5-flash", "deepseek/deepseek-chat"),
            "https://openrouter.ai/keys", client = client, json = json,
        ),
        OpenAiCompatibleProvider(
            "deepseek", "DeepSeek", "https://api.deepseek.com/v1", listOf("deepseek-chat", "deepseek-reasoner"),
            "https://platform.deepseek.com/api_keys", client = client, json = json,
        ),
        OpenAiCompatibleProvider(
            "mistral", "Mistral", "https://api.mistral.ai/v1", listOf("mistral-small-latest", "mistral-large-latest"),
            "https://console.mistral.ai/api-keys", client = client, json = json,
        ),
        OpenAiCompatibleProvider(
            "groq", "Groq", "https://api.groq.com/openai/v1", listOf("llama-3.3-70b-versatile"),
            "https://console.groq.com/keys", supportsJsonMode = true, client = client, json = json,
        ),
        OpenAiCompatibleProvider(
            "custom", "OpenAI-compatible (Ollama, LM Studio…)", "http://10.0.2.2:11434/v1", listOf("llama3.1", "qwen2.5"),
            "https://ollama.com", requiresApiKey = false, supportsJsonMode = false, client = client, json = json,
        ),
    )

    fun byId(id: String): AiProvider = all.firstOrNull { it.id == id } ?: all.first()
}

/** Strips markdown fences and isolates the outermost JSON object/array in a model reply. */
fun extractJson(text: String): String {
    val unfenced = text.replace(Regex("```(?:json)?"), "").trim()
    val objStart = unfenced.indexOf('{')
    val arrStart = unfenced.indexOf('[')
    val start = listOf(objStart, arrStart).filter { it >= 0 }.minOrNull() ?: return unfenced
    val close = if (unfenced[start] == '{') '}' else ']'
    val end = unfenced.lastIndexOf(close)
    return if (end > start) unfenced.substring(start, end + 1) else unfenced
}
