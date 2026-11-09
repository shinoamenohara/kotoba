package app.kotoba.core.ai

import app.kotoba.core.runCatchingCancellable
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertThrows
import org.junit.Test

class BaseUrlTest {
    @Test fun httpsIsAcceptedAndTrailingSlashTrimmed() {
        assertThat(validateBaseUrl(" https://api.openai.com/v1/ ")).isEqualTo("https://api.openai.com/v1")
    }

    @Test fun cleartextAllowedOnlyForLocalHosts() {
        assertThat(validateBaseUrl("http://10.0.2.2:11434/v1")).isEqualTo("http://10.0.2.2:11434/v1")
        assertThat(validateBaseUrl("http://localhost:1234/v1")).isEqualTo("http://localhost:1234/v1")
        assertThat(validateBaseUrl("http://192.168.1.5:8080")).isEqualTo("http://192.168.1.5:8080")
        assertThat(validateBaseUrl("http://my-box.local/v1")).isEqualTo("http://my-box.local/v1")
    }

    @Test fun cleartextToPublicHostIsRejected() {
        val e = assertThrows(AiException::class.java) { validateBaseUrl("http://api.example.com/v1") }
        assertThat(e.kind).isEqualTo(AiErrorKind.BAD_REQUEST)
        assertThrows(AiException::class.java) { validateBaseUrl("http://172.32.0.1/v1") }
    }

    @Test fun garbageIsRejected() {
        assertThrows(AiException::class.java) { validateBaseUrl("not a url") }
        assertThrows(AiException::class.java) { validateBaseUrl("ftp://example.com") }
    }

    @Test fun runCatchingCancellableRethrowsCancellation() {
        assertThrows(CancellationException::class.java) { runCatchingCancellable { throw CancellationException("x") } }
        assertThat(runCatchingCancellable { error("boom") }.isFailure).isTrue()
        assertThat(runCatchingCancellable { 1 }.getOrNull()).isEqualTo(1)
    }
}
