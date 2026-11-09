package app.kotoba.feature.importexport

import app.kotoba.feature.importexport.anki.AnkiFields
import app.kotoba.feature.importexport.parse.DelimitedParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class ImportFixesTest {
    @Test fun hashLinesAfterHeaderAreData() {
        val t = DelimitedParser.parse("#separator:tab\n#html:true\n#1 hit\tхит\n#tag\tтег\n", delimiter = '\t', header = false)
        assertThat(t.rows.map { it[0] }).containsExactly("#1 hit", "#tag").inOrder()
    }

    @Test fun multilineClozeIsFlattened() {
        assertThat(AnkiFields.plainText("A {{c1::line one\nline two::hint}} b")).isEqualTo("A line one\nline two b")
    }

    @Test fun furiganaIsDroppedFromRuby() {
        assertThat(AnkiFields.plainText("<ruby>漢字<rp>(</rp><rt>かんじ</rt><rp>)</rp></ruby>")).isEqualTo("漢字")
    }

    @Test fun decodesUtf16AndUtf8Bom() {
        val utf16 = byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + "a\tб".toByteArray(Charsets.UTF_16LE)
        assertThat(ImportRepository.decodeText(utf16)).isEqualTo("a\tб")
        val utf8 = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "猫".toByteArray()
        assertThat(ImportRepository.decodeText(utf8)).isEqualTo("猫")
    }
}
