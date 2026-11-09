package app.kotoba.feature.ai

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BaseUrlPolicyTest {
    @Test fun httpsAndBlankAreAllowed() {
        assertThat(BaseUrlPolicy.isAllowed("")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("  ")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("https://proxy.example.com/v1")).isTrue()
    }

    @Test fun plainHttpOnlyForLocalHosts() {
        assertThat(BaseUrlPolicy.isAllowed("http://192.168.1.10:11434/v1")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("http://10.0.2.2:11434/v1")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("http://172.20.0.5/v1")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("http://localhost:1234/v1")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("http://mybox.local:1234/v1")).isTrue()
        assertThat(BaseUrlPolicy.isAllowed("http://api.example.com/v1")).isFalse()
        assertThat(BaseUrlPolicy.isAllowed("http://172.32.0.1/v1")).isFalse()
        assertThat(BaseUrlPolicy.isAllowed("http://8.8.8.8/v1")).isFalse()
    }

    @Test fun malformedOrOtherSchemesRejected() {
        assertThat(BaseUrlPolicy.isAllowed("ftp://example.com")).isFalse()
        assertThat(BaseUrlPolicy.isAllowed("https://")).isFalse()
        assertThat(BaseUrlPolicy.isAllowed("not a url")).isFalse()
        assertThat(BaseUrlPolicy.isAllowed("h")).isFalse()
    }
}
