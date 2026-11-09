package app.kotoba.feature.ai

import app.kotoba.core.ai.AiErrorKind
import app.kotoba.core.ai.extractJson
import app.kotoba.core.model.Category
import app.kotoba.core.model.PartOfSpeech
import app.kotoba.core.model.StudyLanguage
import com.google.common.truth.Truth.assertThat
import kotlinx.serialization.json.Json
import org.junit.Test

class AiJsonParsingTest {
    // Same configuration as AppModule.json().
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        explicitNulls = false
        encodeDefaults = true
    }

    private fun words(raw: String) = json.decodeFromString(GeneratedWordsDto.serializer(), extractJson(raw))

    @Test fun `parses fenced json with prose around it`() {
        val raw = """
            Sure! Here is your list:
            ```json
            {"words":[{"term":"食べる","reading":"たべる","translation":"есть","partOfSpeech":"verb","level":"N5",
              "category":"food","example":"ご飯を食べる。","exampleTranslation":"Есть рис.","difficulty":1}]}
            ```
            Let me know if you need more.
        """.trimIndent()
        val dto = words(raw)
        assertThat(dto.words).hasSize(1)
        val w = dto.words.single()
        assertThat(w.term).isEqualTo("食べる")
        assertThat(w.reading).isEqualTo("たべる")
        assertThat(w.difficulty).isEqualTo("1")
    }

    @Test fun `tolerates nulls, missing fields, unknown keys and word difficulty`() {
        val raw = """{"words":[{"term":"lighthouse","translation":"маяк","ipa":null,"difficulty":"hard","confidence":0.9},
            {"term":"","translation":"x"}]}"""
        val dto = words(raw)
        assertThat(dto.words[0].ipa).isEmpty()
        assertThat(Difficulty.parse(dto.words[0].difficulty)).isEqualTo(Difficulty.HARD)
    }

    @Test fun `toWords drops blanks, duplicates, known words and filtered parts of speech`() {
        val dto = words(
            """{"words":[
              {"term":"Lighthouse","translation":"маяк","partOfSpeech":"noun","category":"Travel","ipa":"/ˈlaɪthaʊs/"},
              {"term":"lighthouse","translation":"маяк","partOfSpeech":"noun"},
              {"term":"storm","translation":"","partOfSpeech":"noun"},
              {"term":"keeper","translation":"смотритель","partOfSpeech":"noun"},
              {"term":"stand","translation":"стоять","partOfSpeech":"verb"},
              {"term":"alone","translation":"одиноко","partOfSpeech":"adverb"}
            ]}""",
        )
        val candidates = listOf(Candidate("lighthouse", 2, "The lighthouse stood alone."))
        val out = GeneratorPrompts.toWords(
            dto.words, candidates, StudyLanguage.EN, known = setOf("keeper"), excludeKnown = true,
            partsOfSpeech = setOf(PartOfSpeech.NOUN, PartOfSpeech.ADVERB),
        )
        assertThat(out.map { it.term }).containsExactly("Lighthouse", "alone").inOrder()
        assertThat(out[0].sourceSentence).isEqualTo("The lighthouse stood alone.")
        assertThat(out[0].category).isEqualTo(Category.TRAVEL)
        assertThat(out[0].ipa).isEqualTo("ˈlaɪthaʊs")
        assertThat(out[1].sourceSentence).isEqualTo("The lighthouse stood alone.")
        assertThat(out.map { it.id }).containsExactly(0, 1).inOrder()
    }

    @Test fun `quiz output is sanitized`() {
        val raw = """
            ```
            {"questions":[
              {"type":"multiple_choice","prompt":"What does 图书馆 mean?","options":["библиотека","школа","больница"],"answer":"библиотека","term":"图书馆"},
              {"type":"multiple_choice","prompt":"Pick","options":["a","b"],"answer":"c"},
              {"type":"fill_blank","prompt":"我在___看书。(библиотека)","answer":"图书馆","accepted":["圖書館"],"explanation":"Место."},
              {"type":"multiple_choice","prompt":"","options":["x"],"answer":"x"},
              {"type":"multiple_choice","prompt":"Lonely","options":[],"answer":"only"}
            ]}
            ```
        """.trimIndent()
        val qs = QuizBuilder.sanitize(json.decodeFromString(QuizDto.serializer(), extractJson(raw)))
        assertThat(qs).hasSize(3)
        assertThat(qs[0].kind).isEqualTo(QuizKind.MULTIPLE_CHOICE)
        assertThat(qs[0].isCorrect("библиотека")).isTrue()
        // The answer is appended to options when the model forgot it.
        assertThat(qs[1].options).contains("c")
        assertThat(qs[2].kind).isEqualTo(QuizKind.FILL_BLANK)
        assertThat(qs[2].isCorrect(" 圖書館 ")).isTrue()
        assertThat(qs[2].isCorrect("学校")).isFalse()
    }

    @Test fun `fill blank answers ignore case and punctuation`() {
        val q = QuizQuestion(QuizKind.FILL_BLANK, "p", emptyList(), "Went", emptyList(), "", "go")
        assertThat(q.isCorrect("went.")).isTrue()
        assertThat(q.isCorrect("")).isFalse()
    }

    @Test fun `failures survive persistence and never leak keys`() {
        val f = AiFailure(AiErrorKind.INVALID_KEY, "bad key")
        assertThat(AiFailure.decode(f.encode())).isEqualTo(f)
        assertThat(AiFailure.decode("garbage").kind).isNull()
        val redacted = redactSecrets("Incorrect API key provided: sk-proj-abcdefghijklmnop1234", null)
        assertThat(redacted).doesNotContain("abcdefghijklmnop")
        assertThat(redactSecrets("token mysecretkey123 failed", "mysecretkey123")).doesNotContain("mysecretkey123")
    }

    @Test fun `markdown parser recognises blocks and inline styles`() {
        val blocks = MiniMarkdown.blocks("# Title\n\nSome **bold** text\n- item one\n1. first\n```\ncode\n```")
        assertThat(blocks[0]).isEqualTo(MdBlock.Heading(1, "Title"))
        assertThat(blocks[1]).isInstanceOf(MdBlock.Paragraph::class.java)
        assertThat(blocks[2]).isEqualTo(MdBlock.Bullet("•", "item one", 0))
        assertThat(blocks[3]).isEqualTo(MdBlock.Bullet("1.", "first", 0))
        assertThat(blocks[4]).isEqualTo(MdBlock.Code("code"))
        val spans = MiniMarkdown.spans("a **b** *c* `d` snake_case_word")
        assertThat(spans.first { it.text == "b" }.bold).isTrue()
        assertThat(spans.first { it.text == "c" }.italic).isTrue()
        assertThat(spans.first { it.text == "d" }.code).isTrue()
        assertThat(spans.joinToString("") { it.text }).endsWith("snake_case_word")
    }
}
