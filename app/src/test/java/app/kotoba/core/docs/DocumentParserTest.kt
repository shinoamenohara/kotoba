package app.kotoba.core.docs

import app.kotoba.core.model.DocumentFormat
import com.google.common.truth.Truth.assertThat
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class DocumentParserTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun zip(name: String, entries: Map<String, ByteArray>): File {
        val f = tmp.newFile(name)
        ZipOutputStream(f.outputStream()).use { z ->
            entries.forEach { (path, bytes) -> z.putNextEntry(ZipEntry(path)); z.write(bytes); z.closeEntry() }
        }
        return f
    }

    private fun file(name: String, bytes: ByteArray) = tmp.newFile(name).apply { writeBytes(bytes) }

    @Test fun `format detection by extension and mime`() {
        assertThat(DocumentParser.formatOf("Book.EPUB")).isEqualTo(DocumentFormat.EPUB)
        assertThat(DocumentParser.formatOf("war.fb2.zip")).isEqualTo(DocumentFormat.FB2)
        assertThat(DocumentParser.formatOf("vol1.cbz")).isEqualTo(DocumentFormat.CBZ)
        assertThat(DocumentParser.formatOf("notes.md")).isEqualTo(DocumentFormat.MARKDOWN)
        assertThat(DocumentParser.formatOf("x", "application/pdf")).isEqualTo(DocumentFormat.PDF)
        assertThat(DocumentParser.formatOf("page", "image/png")).isEqualTo(DocumentFormat.IMAGES)
        assertThat(DocumentParser.formatOf("archive.rar")).isNull()
    }

    @Test fun `paragraph offsets round trip`() {
        val text = DocumentParser.joinParagraphs(listOf("  First  one ", "", "Second\n line", "Third"))
        assertThat(text).isEqualTo("First one\n\nSecond\nline\n\nThird")
        val ps = DocumentParser.paragraphs(text)
        assertThat(ps.map { it.text }).containsExactly("First one", "Second\nline", "Third").inOrder()
        ps.forEach { assertThat(text.substring(it.start, it.end)).isEqualTo(it.text) }
    }

    @Test fun `reflow joins wrapped lines, de-hyphenates and keeps CJK tight`() {
        assertThat(DocumentParser.reflow(listOf("The quick", "brown fox"))).isEqualTo("The quick brown fox")
        assertThat(DocumentParser.reflow(listOf("extra-", "ordinary"))).isEqualTo("extraordinary")
        assertThat(DocumentParser.reflow(listOf("吾輩は猫で", "ある。"))).isEqualTo("吾輩は猫である。")
    }

    @Test fun `txt with title, author and hard-wrapped paragraphs`() {
        val txt = "Alice in Wonderland\nby Lewis Carroll\n\nAlice was beginning to get very tired\nof sitting by her sister.\n\nSo she was considering\nin her own mind."
        val doc = DocumentParser.parse(file("alice.txt", txt.toByteArray()), DocumentFormat.TXT, "alice.txt")
        assertThat(doc.title).isEqualTo("Alice in Wonderland")
        assertThat(doc.author).isEqualTo("Lewis Carroll")
        assertThat(DocumentParser.paragraphs(doc.text).map { it.text }).containsExactly(
            "Alice was beginning to get very tired of sitting by her sister.",
            "So she was considering in her own mind.",
        ).inOrder()
    }

    @Test fun `txt in windows-1251 with indented paragraphs`() {
        val txt = "    Мой дядя самых честных правил,\nкогда не в шутку занемог.\n    Он уважать себя заставил\nи лучше выдумать не мог."
        val doc = DocumentParser.parse(file("onegin.txt", txt.toByteArray(CharsetDetector.WINDOWS_1251)), DocumentFormat.TXT, "Онегин.txt")
        assertThat(doc.title).isEqualTo("Онегин")
        assertThat(DocumentParser.paragraphs(doc.text).map { it.text }).containsExactly(
            "Мой дядя самых честных правил, когда не в шутку занемог.",
            "Он уважать себя заставил и лучше выдумать не мог.",
        ).inOrder()
    }

    @Test fun `html drops ruby readings and scripts`() {
        val html = """<html><head><title>猫</title><script>var x = 1;</script></head>
            <body><h1>第一章</h1><p><ruby>吾輩<rp>(</rp><rt>わがはい</rt><rp>)</rp></ruby>は猫である。</p>
            <p>名前は<b>まだ</b>無い。</p></body></html>"""
        val doc = DocumentParser.parse(file("neko.html", html.toByteArray()), DocumentFormat.HTML)
        assertThat(doc.title).isEqualTo("猫")
        assertThat(DocumentParser.paragraphs(doc.text).map { it.text }).containsExactly("第一章", "吾輩は猫である。", "名前はまだ無い。").inOrder()
    }

    @Test fun `markdown notes become clean paragraphs`() {
        val md = "# Study guide\n\n## Key **terms**\n\n- *Ephemeral*: lasting a [short](http://x) time\n- Ubiquitous\n\nPlain text\ncontinues here.\n\n```\ncode line\n```\n"
        val doc = DocumentParser.parse(file("guide.md", md.toByteArray()), DocumentFormat.MARKDOWN)
        assertThat(doc.title).isEqualTo("Study guide")
        assertThat(DocumentParser.paragraphs(doc.text).map { it.text }).containsExactly(
            "Key terms", "• Ephemeral: lasting a short time", "• Ubiquitous", "Plain text continues here.", "code line",
        ).inOrder()
    }

    @Test fun `epub follows spine order and reads metadata and cover`() {
        val container = """<?xml version="1.0"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
            <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>"""
        val opf = """<?xml version="1.0"?><package xmlns="http://www.idpf.org/2007/opf" version="3.0">
            <metadata xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>The Test Book</dc:title><dc:creator>Jane Doe</dc:creator></metadata>
            <manifest>
              <item id="c2" href="text/ch%202.xhtml" media-type="application/xhtml+xml"/>
              <item id="c1" href="text/ch1.xhtml" media-type="application/xhtml+xml"/>
              <item id="img" href="images/cover.png" media-type="image/png" properties="cover-image"/>
            </manifest>
            <spine><itemref idref="c1"/><itemref idref="c2"/></spine></package>"""
        val ch1 = "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h2>Chapter 1</h2><p>It was a dark night.</p></body></html>"
        val ch2 = "<html><body><div><p>The end.</p></div></body></html>"
        val f = zip(
            "book.epub",
            mapOf(
                "mimetype" to "application/epub+zip".toByteArray(),
                "META-INF/container.xml" to container.toByteArray(),
                "OEBPS/content.opf" to opf.toByteArray(),
                "OEBPS/text/ch1.xhtml" to ch1.toByteArray(),
                "OEBPS/text/ch 2.xhtml" to ch2.toByteArray(),
                "OEBPS/images/cover.png" to byteArrayOf(1, 2, 3),
            ),
        )
        val doc = DocumentParser.parse(f, DocumentFormat.EPUB)
        assertThat(doc.title).isEqualTo("The Test Book")
        assertThat(doc.author).isEqualTo("Jane Doe")
        assertThat(DocumentParser.paragraphs(doc.text).map { it.text }).containsExactly("Chapter 1", "It was a dark night.", "The end.").inOrder()
        assertThat(doc.cover).isEqualTo(byteArrayOf(1, 2, 3))
        assertThat(doc.coverExtension).isEqualTo("png")
    }

    @Test fun `docx paragraphs, tabs and core metadata`() {
        val document = """<?xml version="1.0" encoding="UTF-8"?>
            <w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body>
            <w:p><w:r><w:t>Hello</w:t></w:r><w:r><w:t xml:space="preserve"> world</w:t></w:r></w:p>
            <w:p><w:r><w:t>你好</w:t><w:tab/><w:t>世界</w:t></w:r></w:p>
            <w:p/></w:body></w:document>"""
        val core = """<cp:coreProperties xmlns:cp="x" xmlns:dc="http://purl.org/dc/elements/1.1/"><dc:title>Greetings</dc:title><dc:creator>Li</dc:creator></cp:coreProperties>"""
        val f = zip("g.docx", mapOf("word/document.xml" to document.toByteArray(), "docProps/core.xml" to core.toByteArray()))
        val doc = DocumentParser.parse(f, DocumentFormat.DOCX)
        assertThat(doc.title).isEqualTo("Greetings")
        assertThat(doc.author).isEqualTo("Li")
        assertThat(doc.text).isEqualTo("Hello world\n\n你好 世界")
    }

    @Test(expected = DocumentParseException::class)
    fun `empty document is rejected`() {
        DocumentParser.parse(file("empty.txt", ByteArray(0)), DocumentFormat.TXT)
    }

    @Test fun `natural page order`() {
        val sorted = listOf("p10.jpg", "p2.jpg", "p1.jpg").sortedWith(NaturalOrder)
        assertThat(sorted).containsExactly("p1.jpg", "p2.jpg", "p10.jpg").inOrder()
    }
}
