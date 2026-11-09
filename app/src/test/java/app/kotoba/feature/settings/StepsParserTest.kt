package app.kotoba.feature.settings

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class StepsParserTest {
    @Test fun parsesCommaAndSpaceSeparatedMinutes() {
        assertThat(parseSteps("1, 10")).containsExactly(1, 10).inOrder()
        assertThat(parseSteps("10 60;1440")).containsExactly(10, 60, 1440).inOrder()
    }

    @Test fun rejectsInvalidInput() {
        assertThat(parseSteps("")).isNull()
        assertThat(parseSteps("1, ten")).isNull()
        assertThat(parseSteps("0")).isNull()
    }
}
