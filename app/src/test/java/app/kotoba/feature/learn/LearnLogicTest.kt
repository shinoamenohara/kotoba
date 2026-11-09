package app.kotoba.feature.learn

import app.kotoba.core.data.QueueMode
import app.kotoba.core.model.ExerciseType
import app.kotoba.core.model.Mastery
import app.kotoba.core.model.Rating
import app.kotoba.core.model.StudyLanguage
import app.kotoba.core.navigation.SessionMode
import app.kotoba.feature.learn.logic.AnswerMatcher
import app.kotoba.feature.learn.logic.CardTraits
import app.kotoba.feature.learn.logic.Cloze
import app.kotoba.feature.learn.logic.ExercisePlanner
import app.kotoba.feature.learn.logic.MatchResult
import app.kotoba.feature.learn.logic.RatingPolicy
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class AnswerMatcherTest {
    private fun en(input: String, expected: String) = AnswerMatcher.match(input, expected, "", StudyLanguage.EN)

    @Test fun `ignores case whitespace and punctuation`() {
        assertThat(en("  Hello!! ", "hello")).isEqualTo(MatchResult.EXACT)
        assertThat(en("ice-cream", "ice cream")).isEqualTo(MatchResult.EXACT)
    }

    @Test fun `ignores diacritics and full width`() {
        assertThat(AnswerMatcher.match("cafe", "café", "", StudyLanguage.FR)).isEqualTo(MatchResult.EXACT)
        assertThat(en("ＡＢＣ", "abc")).isEqualTo(MatchResult.EXACT)
    }

    @Test fun `accepts variants and infinitive marker`() {
        assertThat(en("hound", "dog, hound")).isEqualTo(MatchResult.EXACT)
        assertThat(en("go", "to go")).isEqualTo(MatchResult.EXACT)
        assertThat(en("to run", "run")).isEqualTo(MatchResult.EXACT)
    }

    @Test fun `one typo in a long word is close, wrong word is wrong`() {
        assertThat(en("beautifull", "beautiful")).isEqualTo(MatchResult.CLOSE)
        assertThat(en("dog", "cat")).isEqualTo(MatchResult.WRONG)
        assertThat(en("", "cat")).isEqualTo(MatchResult.WRONG)
    }

    @Test fun `japanese accepts kanji, hiragana and katakana reading`() {
        val ja = StudyLanguage.JA
        assertThat(AnswerMatcher.match("食べる", "食べる", "たべる", ja)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("たべる", "食べる", "たべる", ja)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("タベル", "食べる", "たべる", ja)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("のむ", "食べる", "たべる", ja)).isEqualTo(MatchResult.WRONG)
    }

    @Test fun `chinese accepts pinyin with tone marks, tone numbers or no tones`() {
        val zh = StudyLanguage.ZH
        assertThat(AnswerMatcher.match("你好", "你好", "nǐ hǎo", zh)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("nǐ hǎo", "你好", "nǐ hǎo", zh)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("ni3 hao3", "你好", "nǐ hǎo", zh)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("NI HAO", "你好", "nǐ hǎo", zh)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("nihao", "你好", "nǐ hǎo", zh)).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.match("xie xie", "你好", "nǐ hǎo", zh)).isEqualTo(MatchResult.WRONG)
    }

    @Test fun `pinyin normalisation handles u umlaut spellings`() {
        assertThat(AnswerMatcher.normalizePinyin("nǚ")).isEqualTo("nu")
        assertThat(AnswerMatcher.normalizePinyin("nv3")).isEqualTo("nu")
        assertThat(AnswerMatcher.normalizePinyin("nu:3")).isEqualTo("nu")
        assertThat(AnswerMatcher.normalizePinyin("Zhōng wén")).isEqualTo("zhongwen")
    }

    @Test fun `cjk is never close by edit distance`() {
        assertThat(AnswerMatcher.match("学生们们", "学生们好", "", StudyLanguage.ZH)).isEqualTo(MatchResult.WRONG)
    }

    @Test fun `meaning matching accepts any listed translation`() {
        assertThat(AnswerMatcher.matchMeaning("Собака", "собака, пёс")).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.matchMeaning("пёс", "собака, пёс")).isEqualTo(MatchResult.EXACT)
        assertThat(AnswerMatcher.matchMeaning("кошка", "собака, пёс")).isEqualTo(MatchResult.WRONG)
    }
}

class RatingPolicyTest {
    @Test fun `wrong is again`() {
        assertThat(RatingPolicy.rate(correct = false, responseMs = 1_000)).isEqualTo(Rating.AGAIN)
    }

    @Test fun `slow correct is hard`() {
        assertThat(RatingPolicy.rate(correct = true, responseMs = 12_000)).isEqualTo(Rating.HARD)
    }

    @Test fun `close or hinted correct is hard`() {
        assertThat(RatingPolicy.rate(correct = true, responseMs = 2_000, close = true)).isEqualTo(Rating.HARD)
        assertThat(RatingPolicy.rate(correct = true, responseMs = 2_000, usedHint = true)).isEqualTo(Rating.HARD)
    }

    @Test fun `normal correct is good`() {
        assertThat(RatingPolicy.rate(correct = true, responseMs = 4_000)).isEqualTo(Rating.GOOD)
        assertThat(RatingPolicy.rate(correct = true, responseMs = 9_000, typed = true, answerLength = 5)).isEqualTo(Rating.GOOD)
    }

    @Test fun `very fast typed correct is easy, but not for choices`() {
        assertThat(RatingPolicy.rate(correct = true, responseMs = 2_000, typed = true, answerLength = 5)).isEqualTo(Rating.EASY)
        assertThat(RatingPolicy.rate(correct = true, responseMs = 1_000, typed = false)).isEqualTo(Rating.GOOD)
    }

    @Test fun `fast threshold scales with answer length`() {
        assertThat(RatingPolicy.fastTypedThreshold(10)).isGreaterThan(RatingPolicy.fastTypedThreshold(2))
    }
}

class ExercisePlannerTest {
    private fun traits(
        mastery: Mastery = Mastery.LEARNING,
        sentence: Boolean = true,
        cloze: Boolean = true,
        tokens: Int = 6,
        length: Int = 6,
        options: Boolean = true,
    ) = CardTraits(mastery, sentence, cloze, tokens, length, options)

    @Test fun `session modes map to queue modes`() {
        assertThat(ExercisePlanner.queueModeFor(SessionMode.DAILY)).isEqualTo(QueueMode.DAILY)
        assertThat(ExercisePlanner.queueModeFor(SessionMode.NEW)).isEqualTo(QueueMode.NEW_ONLY)
        assertThat(ExercisePlanner.queueModeFor(SessionMode.REVIEW)).isEqualTo(QueueMode.REVIEW_ONLY)
        assertThat(ExercisePlanner.queueModeFor(SessionMode.WEAK)).isEqualTo(QueueMode.WEAK_ONLY)
        assertThat(ExercisePlanner.queueModeFor(SessionMode.QUICK)).isEqualTo(QueueMode.QUICK)
        assertThat(ExercisePlanner.queueModeFor(SessionMode.EXERCISE)).isEqualTo(QueueMode.PRACTICE)
        assertThat(ExercisePlanner.queueModeFor(SessionMode.MIXED)).isEqualTo(QueueMode.PRACTICE)
    }

    @Test fun `practice modes fall back to all cards when nothing studied`() {
        assertThat(ExercisePlanner.fallbackQueueMode(SessionMode.EXERCISE)).isEqualTo(QueueMode.ALL)
        assertThat(ExercisePlanner.fallbackQueueMode(SessionMode.MIXED)).isEqualTo(QueueMode.ALL)
        assertThat(ExercisePlanner.fallbackQueueMode(SessionMode.QUICK)).isEqualTo(QueueMode.ALL)
        assertThat(ExercisePlanner.fallbackQueueMode(SessionMode.DAILY)).isNull()
    }

    @Test fun `fixed modes pick their exercise`() {
        assertThat(ExercisePlanner.exerciseFor(SessionMode.QUICK, null, traits(), 0)).isEqualTo(ExerciseType.QUICK_REVIEW)
        assertThat(ExercisePlanner.exerciseFor(SessionMode.NEW, null, traits(), 3)).isEqualTo(ExerciseType.FLASHCARD_TERM_TO_TRANSLATION)
        assertThat(ExercisePlanner.exerciseFor(SessionMode.EXERCISE, ExerciseType.LISTENING, traits(), 0)).isEqualTo(ExerciseType.LISTENING)
    }

    @Test fun `mixed rotates by mastery`() {
        assertThat(ExercisePlanner.forMixed(traits(Mastery.NEW), 5)).isEqualTo(ExerciseType.FLASHCARD_TERM_TO_TRANSLATION)
        val learning = (0..10).map { ExercisePlanner.forMixed(traits(Mastery.LEARNING), it) }.toSet()
        assertThat(learning).containsAtLeast(ExerciseType.MULTIPLE_CHOICE, ExerciseType.LISTENING)
        assertThat(learning).containsNoneOf(ExerciseType.TYPING, ExerciseType.TRANSLATION)
        val familiar = (0..10).map { ExercisePlanner.forMixed(traits(Mastery.FAMILIAR), it) }.toSet()
        assertThat(familiar).containsAtLeast(ExerciseType.TYPING, ExerciseType.FILL_BLANK, ExerciseType.SENTENCE_BUILDER)
    }

    @Test fun `unavailable exercises degrade gracefully`() {
        val noSentence = traits(sentence = false, cloze = false, tokens = 0)
        assertThat(ExercisePlanner.resolve(ExerciseType.FILL_BLANK, noSentence)).isEqualTo(ExerciseType.MULTIPLE_CHOICE)
        assertThat(ExercisePlanner.resolve(ExerciseType.SENTENCE_BUILDER, noSentence)).isEqualTo(ExerciseType.WORD_SCRAMBLE)
        assertThat(ExercisePlanner.resolve(ExerciseType.TRANSLATION, noSentence)).isEqualTo(ExerciseType.TYPING)
        assertThat(ExercisePlanner.resolve(ExerciseType.MULTIPLE_CHOICE, traits(options = false))).isEqualTo(ExerciseType.TYPING)
        assertThat(ExercisePlanner.resolve(ExerciseType.FLASHCARD_TERM_TO_CONTEXT, traits(cloze = false, options = false)))
            .isEqualTo(ExerciseType.FLASHCARD_TERM_TO_TRANSLATION)
    }

    @Test fun `mixed never picks an unavailable exercise`() {
        val bare = traits(Mastery.MASTERED, sentence = false, cloze = false, tokens = 0, options = false)
        (0..12).forEach { r ->
            val t = ExercisePlanner.forMixed(bare, r)
            assertThat(ExercisePlanner.isAvailable(t, bare)).isTrue()
        }
    }
}

class ClozeTest {
    @Test fun `finds inflected english word`() {
        val s = "I decided to go home."
        val r = Cloze.find(s, "decide", "", StudyLanguage.EN)!!
        assertThat(s.substring(r.first, r.last + 1)).isEqualTo("decided")
        assertThat(Cloze.blank(s, r)).isEqualTo("I ${Cloze.BLANK} to go home.")
    }

    @Test fun `uses listed forms`() {
        val s = "She went to school."
        val r = Cloze.find(s, "go", "go, went, gone", StudyLanguage.EN)!!
        assertThat(s.substring(r.first, r.last + 1)).isEqualTo("went")
    }

    @Test fun `finds japanese and chinese words without spaces`() {
        val ja = "私は毎日日本語を勉強します"
        val r = Cloze.find(ja, "勉強する", "勉強", StudyLanguage.JA)!!
        assertThat(ja.substring(r.first, r.last + 1)).isEqualTo("勉強")

        val zh = "我喜欢学习中文"
        val z = Cloze.find(zh, "学习", "", StudyLanguage.ZH)!!
        assertThat(Cloze.blank(zh, z)).isEqualTo("我喜欢${Cloze.BLANK}中文")
    }

    @Test fun `returns null when the word is absent`() {
        assertThat(Cloze.find("The cat sleeps.", "dog", "", StudyLanguage.EN)).isNull()
        assertThat(Cloze.find("", "dog", "", StudyLanguage.EN)).isNull()
    }
}
