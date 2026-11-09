package app.kotoba.feature.learn

import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.learn.logic.AnswerMatcher
import app.kotoba.feature.learn.logic.MatchResult
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class KanaMatchingTest {
    private val ja = StudyLanguage.JA

    @Test fun `dakuten and handakuten are not stripped`() {
        assertThat(AnswerMatcher.normalize("が")).isNotEqualTo(AnswerMatcher.normalize("か"))
        assertThat(AnswerMatcher.normalize("ぱ")).isNotEqualTo(AnswerMatcher.normalize("は"))
        assertThat(AnswerMatcher.normalize("ガ")).isEqualTo("ガ")
    }

    @Test fun `japanese kana term does not accept the unvoiced kana`() {
        assertThat(AnswerMatcher.match("かい", "がい", "", ja)).isEqualTo(MatchResult.WRONG)
        assertThat(AnswerMatcher.match("がい", "がい", "", ja)).isEqualTo(MatchResult.EXACT)
    }

    @Test fun `latin diacritics are still ignored`() {
        assertThat(AnswerMatcher.normalize("Café")).isEqualTo("cafe")
        assertThat(AnswerMatcher.match("nǚ", "nü", "", StudyLanguage.ZH)).isEqualTo(MatchResult.EXACT)
    }
}
