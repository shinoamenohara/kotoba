package app.kotoba.feature.ai

import app.kotoba.core.ai.validateBaseUrl

/** UI-side check for custom endpoints; the rule itself lives in [validateBaseUrl] so settings and requests agree. */
object BaseUrlPolicy {
    /** Blank means "use the provider default". */
    fun isAllowed(url: String): Boolean = url.isBlank() || runCatching { validateBaseUrl(url) }.isSuccess
}
