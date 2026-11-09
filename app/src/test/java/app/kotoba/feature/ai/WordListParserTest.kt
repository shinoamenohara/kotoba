package app.kotoba.feature.ai

import app.kotoba.core.model.StudyLanguage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordListParserTest {
    @Test fun `parses em dash, en dash and spaced hyphen`() {
        val words = WordListParser.parse(
            """
            Here are some words:
            - **serendipity** — счастливая случайность
            - ubiquitous – вездесущий
            * well-known - известный
            """.trimIndent(),
        )
        assertThat(words.map { it.term }).containsExactly("serendipity", "ubiquitous", "well-known").inOrder()
        assertThat(words[0].translation).isEqualTo("счастливая случайность")
        assertThat(words[2].translation).isEqualTo("известный")
    }

    @Test fun `extracts reading in parentheses for japanese and chinese`() {
        val words = WordListParser.parse(
            """
            1. 勉強 (べんきょう) — учёба
            2) 朋友（péngyou）— друг
            """.trimIndent(),
        )
        assertThat(words).hasSize(2)
        assertThat(words[0].term).isEqualTo("勉強")
        assertThat(words[0].reading).isEqualTo("べんきょう")
        assertThat(words[1].term).isEqualTo("朋友")
        assertThat(words[1].reading).isEqualTo("péngyou")
    }

    @Test fun `extracts ipa between slashes`() {
        val w = WordListParser.parseLine("- thorough /ˈθʌrə/ — тщательный")!!
        assertThat(w.term).isEqualTo("thorough")
        assertThat(w.ipa).isEqualTo("ˈθʌrə")
    }

    @Test fun `ignores headings, prose, code blocks and duplicates`() {
        val words = WordListParser.parse(
            """
            ## Food — vocabulary
            This is a long explanatory sentence that should not be treated as a word - even with a dash in it.
            ```
            code — not a word
            ```
            - apple — яблоко
            - Apple — яблоко
            """.trimIndent(),
        )
        assertThat(words.map { it.term }).containsExactly("apple")
    }

    @Test fun `plain hyphenated word without spaces is not a separator`() {
        assertThat(WordListParser.parseLine("mother-in-law")).isNull()
    }

    @Test fun `maps parsed words to cards per language`() {
        val ja = ParsedWord("勉強", "учёба", reading = "べんきょう").toCard(StudyLanguage.JA, 1)
        assertThat(ja.reading).isEqualTo("べんきょう")
        val enIpa = ParsedWord("thorough", "тщательный", reading = "ˈθʌrə").toCard(StudyLanguage.EN, 1)
        assertThat(enIpa.ipa).isEqualTo("ˈθʌrə")
        assertThat(enIpa.reading).isEmpty()
        val enNote = ParsedWord("gonna", "собираться", reading = "informal").toCard(StudyLanguage.EN, 1)
        assertThat(enNote.ipa).isEmpty()
        assertThat(enNote.notes).isEqualTo("informal")
        assertThat(enNote.source).isEqualTo("ai")
    }
}
