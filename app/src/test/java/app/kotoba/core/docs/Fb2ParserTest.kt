package app.kotoba.core.docs

import app.kotoba.core.model.DocumentFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Base64
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** FB2 uses android.util.Xml, hence Robolectric. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class Fb2ParserTest {
    @get:Rule val tmp = TemporaryFolder()

    private val cover = byteArrayOf(9, 8, 7)
    private val fb2 = """<?xml version="1.0" encoding="windows-1251"?>
<FictionBook xmlns="http://www.gribuser.ru/xml/fictionbook/2.0" xmlns:l="http://www.w3.org/1999/xlink">
<description><title-info>
  <author><first-name>Александр</first-name><last-name>Пушкин</last-name></author>
  <book-title>Капитанская дочка</book-title>
  <coverpage><image l:href="#cover.jpg"/></coverpage>
  <lang>ru</lang>
</title-info></description>
<body>
  <title><p>Глава I</p></title>
  <section><p>Отец мой&nbsp;Андрей Петрович <emphasis>Гринёв</emphasis> в молодости своей служил.</p>
  <empty-line/><p>Матушка была ещё мною брюхата.</p></section>
</body>
<body name="notes"><section><p>Примечание</p></section></body>
<binary id="cover.jpg" content-type="image/jpeg">${Base64.getEncoder().encodeToString(cover)}</binary>
</FictionBook>"""

    @Test fun `parses windows-1251 fb2 with metadata, cover and without notes`() {
        val f = tmp.newFile("book.fb2").apply { writeBytes(fb2.toByteArray(CharsetDetector.WINDOWS_1251)) }
        assertDoc(DocumentParser.parse(f, DocumentFormat.FB2))
    }

    @Test fun `parses zipped fb2`() {
        val f = tmp.newFile("book.fb2.zip")
        ZipOutputStream(f.outputStream()).use { z ->
            z.putNextEntry(ZipEntry("book.fb2")); z.write(fb2.toByteArray(CharsetDetector.WINDOWS_1251)); z.closeEntry()
        }
        assertDoc(DocumentParser.parse(f, DocumentFormat.FB2))
    }

    private fun assertDoc(doc: ParsedDocument) {
        assertThat(doc.title).isEqualTo("Капитанская дочка")
        assertThat(doc.author).isEqualTo("Александр Пушкин")
        assertThat(DocumentParser.paragraphs(doc.text).map { it.text }).containsExactly(
            "Глава I",
            "Отец мой Андрей Петрович Гринёв в молодости своей служил.",
            "Матушка была ещё мною брюхата.",
        ).inOrder()
        assertThat(doc.cover).isEqualTo(cover)
    }
}
