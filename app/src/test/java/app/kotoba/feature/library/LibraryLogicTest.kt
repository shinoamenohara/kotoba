package app.kotoba.feature.library

import app.kotoba.core.model.Mastery
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class WordFilterTest {
    @Test fun `parses known filters`() {
        assertThat(WordFilter.parse("all")).isEqualTo(WordFilter.All)
        assertThat(WordFilter.parse("favorites")).isEqualTo(WordFilter.Favorites)
        assertThat(WordFilter.parse("weak")).isEqualTo(WordFilter.Weak)
        assertThat(WordFilter.parse("mastery:FAMILIAR")).isEqualTo(WordFilter.ByMastery(Mastery.FAMILIAR))
        assertThat(WordFilter.parse("mastery:mastered")).isEqualTo(WordFilter.ByMastery(Mastery.MASTERED))
        assertThat(WordFilter.parse("deck:42")).isEqualTo(WordFilter.ByDeck(42))
        assertThat(WordFilter.parse("category:food")).isEqualTo(WordFilter.ByCategory("food"))
    }

    @Test fun `malformed filters fall back to all`() {
        listOf(null, "", "nope", "mastery:", "mastery:GURU", "deck:abc", "deck:-3", "category:").forEach {
            assertThat(WordFilter.parse(it)).isEqualTo(WordFilter.All)
        }
    }

    @Test fun `encode round trips`() {
        listOf(
            WordFilter.All, WordFilter.Favorites, WordFilter.Weak,
            WordFilter.ByMastery(Mastery.NEW), WordFilter.ByDeck(7), WordFilter.ByCategory("travel"),
        ).forEach { assertThat(WordFilter.parse(it.encode())).isEqualTo(it) }
    }
}

class ClozeTest {
    private fun en(sentence: String, term: String, forms: List<String> = emptyList()) =
        Cloze.mask(sentence, term, usesSpaces = true, forms = forms)

    private fun cjk(sentence: String, term: String) = Cloze.mask(sentence, term, usesSpaces = false)

    @Test fun `masks exact english word case-insensitively`() {
        assertThat(en("Apple pie and an apple.", "apple")).isEqualTo("_____ pie and an _____.")
    }

    @Test fun `does not mask inside other words`() {
        assertThat(en("The pineapple is sweet.", "apple")).isEqualTo("The pineapple is sweet.")
    }

    @Test fun `masks regular english inflections`() {
        assertThat(en("She studied all night.", "study")).isEqualTo("She _____ all night.")
        assertThat(en("He stopped the car.", "stop")).isEqualTo("He _____ the car.")
        assertThat(en("We are making dinner.", "make")).isEqualTo("We are _____ dinner.")
        assertThat(en("It goes well.", "go")).isEqualTo("It _____ well.")
    }

    @Test fun `masks irregular forms from card forms field`() {
        assertThat(en("They went home early.", "go", forms = listOf("went", "gone"))).isEqualTo("They _____ home early.")
    }

    @Test fun `masks inflected phrasal verbs as one blank`() {
        assertThat(en("She gives up too easily.", "give up")).isEqualTo("She _____ too easily.")
    }

    @Test fun `masks chinese word without spaces`() {
        assertThat(cjk("我每天学习中文。", "学习")).isEqualTo("我每天＿＿中文。")
    }

    @Test fun `masks japanese dictionary form`() {
        assertThat(cjk("毎朝パンを食べる。", "食べる")).isEqualTo("毎朝パンを＿＿。")
    }

    @Test fun `masks conjugated japanese verb including okurigana`() {
        assertThat(cjk("昨日すしを食べました。", "食べる")).isEqualTo("昨日すしを＿＿。")
    }

    @Test fun `leaves sentence intact when word is absent`() {
        assertThat(cjk("今日は暑い。", "寒い")).isEqualTo("今日は暑い。")
        assertThat(en("Nothing here.", "apple")).isEqualTo("Nothing here.")
    }

    @Test fun `stem fallback handles russian endings`() {
        assertThat(en("Я читаю книгу.", "книга")).isEqualTo("Я читаю _____.")
    }

    @Test fun `mastery progress is weighted`() {
        assertThat(masteryProgress(emptyMap())).isEqualTo(0f)
        assertThat(masteryProgress(mapOf(Mastery.MASTERED to 2))).isEqualTo(1f)
        assertThat(masteryProgress(mapOf(Mastery.NEW to 1, Mastery.MASTERED to 1))).isEqualTo(0.5f)
    }
}
