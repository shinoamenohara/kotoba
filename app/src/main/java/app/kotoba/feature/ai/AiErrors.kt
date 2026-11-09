package app.kotoba.feature.ai

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import app.kotoba.R
import app.kotoba.core.ai.AiErrorKind
import app.kotoba.core.ai.AiException

/** An error in a shape that survives persistence (chat error bubbles store it as text). */
data class AiFailure(val kind: AiErrorKind?, val detail: String = "") {
    /** Serialized form kept in [app.kotoba.core.db.ChatMessageEntity.content] for error rows. */
    fun encode(): String = "${kind?.name ?: "UNKNOWN"}\n$detail"

    companion object {
        fun of(t: Throwable, apiKey: String? = null): AiFailure = when (t) {
            is AiException -> AiFailure(t.kind, redactSecrets(t.message.orEmpty(), apiKey).take(300))
            else -> AiFailure(null, redactSecrets(t.message.orEmpty(), apiKey).take(300))
        }

        fun decode(raw: String): AiFailure {
            val name = raw.substringBefore('\n')
            val kind = AiErrorKind.entries.firstOrNull { it.name == name }
            return AiFailure(kind, raw.substringAfter('\n', ""))
        }
    }
}

private val keyLike = Regex("""\b(sk|pk|gsk|AIza|sk-ant|sk-or)[-_A-Za-z0-9]{12,}""")

/** Provider error texts sometimes echo the key back; never let it reach the UI or the DB. */
fun redactSecrets(text: String, apiKey: String? = null): String {
    var out = text
    if (!apiKey.isNullOrBlank() && apiKey.length >= 6) out = out.replace(apiKey, maskKey(apiKey))
    return out.replace(keyLike) { maskKey(it.value) }
}

fun maskKey(key: String): String = if (key.length <= 8) "••••" else key.take(3) + "…" + key.takeLast(4)

@StringRes
fun aiErrorTitleRes(kind: AiErrorKind?): Int = when (kind) {
    AiErrorKind.NOT_CONFIGURED -> R.string.ai_err_not_configured
    AiErrorKind.INVALID_KEY -> R.string.ai_err_invalid_key
    AiErrorKind.RATE_LIMITED -> R.string.ai_err_rate_limited
    AiErrorKind.QUOTA_EXCEEDED -> R.string.ai_err_quota
    AiErrorKind.LOCAL_LIMIT -> R.string.ai_err_local_limit
    AiErrorKind.NETWORK -> R.string.ai_err_network
    AiErrorKind.SERVER -> R.string.ai_err_server
    AiErrorKind.BAD_REQUEST -> R.string.ai_err_bad_request
    AiErrorKind.BAD_RESPONSE -> R.string.ai_err_bad_response
    null -> R.string.ai_err_unknown
}

@Composable
fun aiErrorTitle(failure: AiFailure): String = stringResource(aiErrorTitleRes(failure.kind))

/** Whether the provider's own message adds information beyond our localized title. */
val AiFailure.showDetail: Boolean
    get() = detail.isNotBlank() && kind != AiErrorKind.NOT_CONFIGURED && kind != AiErrorKind.NETWORK
