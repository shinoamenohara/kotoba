package app.kotoba.feature.ai

import app.kotoba.core.lang.Token
import app.kotoba.core.model.StudyLanguage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CandidateExtractorTest {
    /** Simple whitespace/punctuation tokenizer standing in for ICU in JVM tests. */
    private fun tokens(text: String): List<Token> =
        Regex("""[\p{L}\p{N}'’-]+|[^\s\p{L}\p{N}]""").findAll(text).map { m ->
            Token(m.range.first, m.range.last + 1, m.value, m.value.any { it.isLetterOrDigit() })
        }.toList()

    @Test fun `english filter drops stop words, numbers and punctuation`() {
        assertThat(CandidateExtractor.isCandidate("the", StudyLanguage.EN)).isFalse()
        assertThat(CandidateExtractor.isCandidate("2024", StudyLanguage.EN)).isFalse()
        assertThat(CandidateExtractor.isCandidate("abc123", StudyLanguage.EN)).isFalse()
        assertThat(CandidateExtractor.isCandidate("—", StudyLanguage.EN)).isFalse()
        assertThat(CandidateExtractor.isCandidate("a", StudyLanguage.EN)).isFalse()
        assertThat(CandidateExtractor.isCandidate("lighthouse", StudyLanguage.EN)).isTrue()
        assertThat(CandidateExtractor.isCandidate("well-known", StudyLanguage.EN)).isTrue()
    }

    @Test fun `japanese filter skips single kana and particles but keeps kanji words`() {
        assertThat(CandidateExtractor.isCandidate("は", StudyLanguage.JA)).isFalse()
        assertThat(CandidateExtractor.isCandidate("を", StudyLanguage.JA)).isFalse()
        assertThat(CandidateExtractor.isCandidate("です", StudyLanguage.JA)).isFalse()
        assertThat(CandidateExtractor.isCandidate("から", StudyLanguage.JA)).isFalse()
        assertThat(CandidateExtractor.isCandidate("勉強", StudyLanguage.JA)).isTrue()
        assertThat(CandidateExtractor.isCandidate("食べる", StudyLanguage.JA)).isTrue()
        assertThat(CandidateExtractor.isCandidate("コーヒー", StudyLanguage.JA)).isTrue()
        assertThat(CandidateExtractor.isCandidate("人々", StudyLanguage.JA)).isTrue()
    }

    @Test fun `chinese filter skips function characters`() {
        assertThat(CandidateExtractor.isCandidate("的", StudyLanguage.ZH)).isFalse()
        assertThat(CandidateExtractor.isCandidate("了", StudyLanguage.ZH)).isFalse()
        assertThat(CandidateExtractor.isCandidate("我们", StudyLanguage.ZH)).isFalse()
        assertThat(CandidateExtractor.isCandidate("图书馆", StudyLanguage.ZH)).isTrue()
    }

    @Test fun `extract counts, excludes known words and keeps source sentence`() {
        val text = "The lighthouse stood alone. A storm hit the lighthouse at night. The keeper slept."
        val ranges = listOf(0..27, 28..64, 65..text.lastIndex)
        val out = CandidateExtractor.extract(
            tokens(text), StudyLanguage.EN, known = setOf("keeper"), excludeKnown = true,
            frequency = FrequencyFilter.COMMON, limit = 10,
        ) { CandidateExtractor.sentenceFor(text, ranges, it) }
        val terms = out.map { it.term }
        assertThat(terms.first()).isEqualTo("lighthouse")
        assertThat(out.first().count).isEqualTo(2)
        assertThat(out.first().sentence).isEqualTo("The lighthouse stood alone.")
        assertThat(terms).doesNotContain("keeper")
        assertThat(terms).doesNotContain("the")
        assertThat(out.first { it.term == "storm" }.sentence).isEqualTo("A storm hit the lighthouse at night.")
    }

    @Test fun `known words are kept when exclusion is off`() {
        val text = "keeper keeper"
        val out = CandidateExtractor.extract(tokens(text), StudyLanguage.EN, setOf("keeper"), false, FrequencyFilter.ANY, 5) { "" }
        assertThat(out.map { it.term }).containsExactly("keeper")
    }

    @Test fun `rare filter prefers words that repeat but are least frequent`() {
        val text = "alpha alpha alpha beta beta gamma"
        val out = CandidateExtractor.extract(tokens(text), StudyLanguage.EN, emptySet(), false, FrequencyFilter.RARE, 3) { "" }
        assertThat(out.map { it.term }).containsExactly("beta", "alpha", "gamma").inOrder()
    }

    @Test fun `sentenceFor returns empty when offset is outside ranges`() {
        assertThat(CandidateExtractor.sentenceFor("abc", listOf(0..1), 5)).isEmpty()
    }

    @Test fun `offline words keep sentences and guess difficulty from frequency`() {
        val words = GeneratorPrompts.offlineWords(
            listOf(Candidate("lighthouse", 4, "The lighthouse."), Candidate("storm", 1, "A storm.")),
            mapOf("lighthouse" to "маяк"),
            generateExamples = true,
        )
        assertThat(words[0].translation).isEqualTo("маяк")
        assertThat(words[0].difficulty).isEqualTo(Difficulty.EASY)
        assertThat(words[1].difficulty).isEqualTo(Difficulty.HARD)
        assertThat(words[1].example).isEqualTo("A storm.")
        assertThat(words.none { it.selected }).isTrue()
    }
}
