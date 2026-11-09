package app.kotoba.core.docs

import app.kotoba.core.model.StudyLanguage
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CharsetDetectorTest {
    @Test fun `utf8 with and without bom`() {
        val s = "Привет, 世界!"
        assertThat(CharsetDetector.decode(s.toByteArray())).isEqualTo(s)
        assertThat(CharsetDetector.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + s.toByteArray())).isEqualTo(s)
    }

    @Test fun `utf16 bom`() {
        val s = "日本語のテキスト"
        assertThat(CharsetDetector.decode(byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + s.toByteArray(Charsets.UTF_16LE))).isEqualTo(s)
    }

    @Test fun `windows-1251 russian`() {
        val s = "Однажды в студёную зимнюю пору я из лесу вышел; был сильный мороз."
        assertThat(CharsetDetector.decode(s.toByteArray(CharsetDetector.WINDOWS_1251))).isEqualTo(s)
    }

    @Test fun `shift_jis japanese`() {
        val s = "吾輩は猫である。名前はまだ無い。どこで生れたかとんと見当がつかぬ。"
        assertThat(CharsetDetector.decode(s.toByteArray(CharsetDetector.SHIFT_JIS))).isEqualTo(s)
    }

    @Test fun `gb18030 chinese`() {
        val s = "我们今天学习中文。这是一个很好的机会，大家都很高兴。"
        assertThat(CharsetDetector.decode(s.toByteArray(CharsetDetector.GB18030))).isEqualTo(s)
    }
}

class LanguageDetectorTest {
    @Test fun `scripts map to languages`() {
        assertThat(LanguageDetector.detect("吾輩は猫である。名前はまだ無い。")).isEqualTo(StudyLanguage.JA)
        assertThat(LanguageDetector.detect("我们今天学习中文，大家都很高兴。")).isEqualTo(StudyLanguage.ZH)
        assertThat(LanguageDetector.detect("안녕하세요. 만나서 반갑습니다.")).isEqualTo(StudyLanguage.KO)
        assertThat(LanguageDetector.detect("Мой дядя самых честных правил")).isEqualTo(StudyLanguage.RU)
    }

    @Test fun `kanji-heavy japanese with a little kana is still japanese`() {
        assertThat(LanguageDetector.detect("日本国憲法第九条は戦争の放棄を定める。")).isEqualTo(StudyLanguage.JA)
    }

    @Test fun `latin text uses stop words then the active language`() {
        val en = "It was the best of times, it was the worst of times, it was the age of wisdom and the age of foolishness."
        assertThat(LanguageDetector.detect(en, StudyLanguage.JA)).isEqualTo(StudyLanguage.EN)
        val es = "En un lugar de la Mancha, de cuyo nombre no quiero acordarme, no ha mucho tiempo que vivía un hidalgo de los de lanza."
        assertThat(LanguageDetector.detect(es)).isEqualTo(StudyLanguage.ES)
        assertThat(LanguageDetector.detect("Hallo Welt", StudyLanguage.DE)).isEqualTo(StudyLanguage.DE)
        assertThat(LanguageDetector.detect("Hello", StudyLanguage.ZH)).isEqualTo(StudyLanguage.EN)
    }
}
