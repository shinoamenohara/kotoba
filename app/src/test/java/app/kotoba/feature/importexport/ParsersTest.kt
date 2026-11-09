package app.kotoba.feature.importexport

import app.kotoba.core.model.Mastery
import app.kotoba.core.model.SrsState
import app.kotoba.core.model.StudyLanguage
import app.kotoba.feature.importexport.anki.AnkiFields
import app.kotoba.feature.importexport.anki.AnkiPackageReader
import app.kotoba.feature.importexport.anki.AnkiProgress
import app.kotoba.feature.importexport.anki.AnkiProgressConverter
import app.kotoba.feature.importexport.parse.DelimitedParser
import app.kotoba.feature.importexport.parse.DictionaryLineParser
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DelimitedParserTest {
    @Test fun csvWithQuotesAndHeader() {
        val t = DelimitedParser.parse("term,translation,example\napple,яблоко,\"I ate an apple, then \"\"another\"\"\"\nrun,бежать,\"line1\nline2\"\n")
        assertThat(t.delimiter).isEqualTo(',')
        assertThat(t.hasHeader).isTrue()
        assertThat(t.headers).containsExactly("term", "translation", "example").inOrder()
        assertThat(t.rows).hasSize(2)
        assertThat(t.rows[0][2]).isEqualTo("I ate an apple, then \"another\"")
        assertThat(t.rows[1][2]).isEqualTo("line1\nline2")
    }

    @Test fun tsvWithoutHeaderAndCommasInValues() {
        val t = DelimitedParser.parse("猫\tねこ\tcat, kitty\n犬\tいぬ\tdog\n")
        assertThat(t.delimiter).isEqualTo('\t')
        assertThat(t.hasHeader).isFalse()
        assertThat(t.headers).containsExactly("Column 1", "Column 2", "Column 3").inOrder()
        assertThat(t.rows[0]).containsExactly("猫", "ねこ", "cat, kitty").inOrder()
    }

    @Test fun semicolonDelimiterAndAnkiCommentsSkipped() {
        val t = DelimitedParser.parse("#separator:semicolon\nword;meaning\nhello;привет\nworld;мир\n")
        assertThat(t.delimiter).isEqualTo(';')
        assertThat(t.hasHeader).isTrue()
        assertThat(t.rows.map { it[0] }).containsExactly("hello", "world").inOrder()
    }

    @Test fun raggedRowsArePadded() {
        val t = DelimitedParser.parse("a,b,c\nx,y\n", header = false)
        assertThat(t.rows[1]).containsExactly("x", "y", "").inOrder()
    }
}

class DictionaryLineParserTest {
    @Test fun supportedSeparators() {
        assertThat(DictionaryLineParser.parseLine("apple\tяблоко")).isEqualTo("apple" to "яблоко")
        assertThat(DictionaryLineParser.parseLine("食べる = to eat")).isEqualTo("食べる" to "to eat")
        assertThat(DictionaryLineParser.parseLine("well-known - известный")).isEqualTo("well-known" to "известный")
        assertThat(DictionaryLineParser.parseLine("你好;hello")).isEqualTo("你好" to "hello")
        assertThat(DictionaryLineParser.parseLine("run — бежать")).isEqualTo("run" to "бежать")
    }

    @Test fun ignoresCommentsAndBlanks() {
        assertThat(DictionaryLineParser.parseLine("# my words")).isNull()
        assertThat(DictionaryLineParser.parseLine("   ")).isNull()
        assertThat(DictionaryLineParser.parseLine("lonely")).isNull()
    }

    @Test fun detectsDictionaryText() {
        assertThat(DictionaryLineParser.looksLikeDictionary("cat = кот\ndog = собака\n")).isTrue()
        assertThat(DictionaryLineParser.looksLikeDictionary("a,b\nc,d\n")).isFalse()
    }
}

class AnkiFieldsTest {
    @Test fun plainTextKeepsLineBreaksAndDecodesEntities() {
        val html = "<div>to eat&nbsp;<b>(verb)</b></div><div>2nd&amp;3rd<br>line</div>[sound:taberu.mp3]"
        assertThat(AnkiFields.plainText(html)).isEqualTo("to eat (verb)\n2nd&3rd\nline")
    }

    @Test fun extractsSoundsAndImages() {
        val f = "[sound:a b.mp3] text <img src=\"pic.jpg\"> and <img src='x.png'/>[sound:c.ogg]"
        assertThat(AnkiFields.sounds(f)).containsExactly("a b.mp3", "c.ogg").inOrder()
        assertThat(AnkiFields.images(f)).containsExactly("pic.jpg", "x.png").inOrder()
        assertThat(AnkiFields.plainText(f)).isEqualTo("text and")
    }

    @Test fun clozeIsFlattened() {
        assertThat(AnkiFields.plainText("I {{c1::ate::verb}} it")).isEqualTo("I ate it")
    }

    @Test fun tagsSplitOnSpaces() {
        assertThat(AnkiFields.tags(" jlpt::n5  food ")).containsExactly("jlpt::n5", "food").inOrder()
    }

    @Test fun recordMapperUsesMappingAndEmbeddedMedia() {
        val record = RawRecord(1, listOf("<b>食べる</b>", "たべる", "to eat<br>consume", "[sound:taberu.mp3]", "<img src=\"eat.png\">"), listOf("verb"))
        val targets = listOf(FieldTarget.TERM, FieldTarget.READING, FieldTarget.TRANSLATION, FieldTarget.IGNORE, FieldTarget.IGNORE)
        val d = RecordMapper.toDraft(record, targets, html = true)!!
        assertThat(d.term).isEqualTo("食べる")
        assertThat(d.reading).isEqualTo("たべる")
        assertThat(d.translation).isEqualTo("to eat\nconsume")
        assertThat(d.audioName).isEqualTo("taberu.mp3")
        assertThat(d.imageName).isEqualTo("eat.png")
        assertThat(d.tags).containsExactly("verb")
    }

    @Test fun recordWithoutTermIsRejected() {
        assertThat(RecordMapper.toDraft(RawRecord(0, listOf("", "x")), listOf(FieldTarget.TERM, FieldTarget.TRANSLATION), html = false)).isNull()
    }

    @Test fun smartMappingDefaults() {
        assertThat(SmartMapping.defaults(listOf("Front", "Back"), null))
            .containsExactly(FieldTarget.TERM, FieldTarget.TRANSLATION).inOrder()
        assertThat(SmartMapping.defaults(listOf("Expression", "Meaning", "Reading", "Audio"), StudyLanguage.JA))
            .containsExactly(FieldTarget.TERM, FieldTarget.TRANSLATION, FieldTarget.READING, FieldTarget.AUDIO).inOrder()
        assertThat(SmartMapping.defaults(listOf("Hanzi", "Pinyin", "English", "Sentence", "Sentence English"), StudyLanguage.ZH))
            .containsExactly(FieldTarget.TERM, FieldTarget.READING, FieldTarget.TRANSLATION, FieldTarget.EXAMPLE, FieldTarget.EXAMPLE_TRANSLATION).inOrder()
        assertThat(SmartMapping.defaults(listOf("Word", "Translation", "Notes"), null))
            .containsExactly(FieldTarget.TERM, FieldTarget.TRANSLATION, FieldTarget.NOTES).inOrder()
        assertThat(SmartMapping.defaults(listOf("Column 1", "Column 2"), null))
            .containsExactly(FieldTarget.TERM, FieldTarget.TRANSLATION).inOrder()
        // For English learners an "English" column holds the word itself.
        assertThat(SmartMapping.defaults(listOf("English", "Russian"), StudyLanguage.EN))
            .containsExactly(FieldTarget.TERM, FieldTarget.TRANSLATION).inOrder()
    }

    @Test fun detectsLanguageByScript() {
        assertThat(SmartMapping.detectLanguage(listOf("食べる", "ねこ"))).isEqualTo(StudyLanguage.JA)
        assertThat(SmartMapping.detectLanguage(listOf("你好", "谢谢"))).isEqualTo(StudyLanguage.ZH)
        assertThat(SmartMapping.detectLanguage(listOf("apple", "run"))).isEqualTo(StudyLanguage.EN)
    }
}

class AnkiProgressTest {
    private val day = 86_400_000L
    private val now = 1_750_000_000_000L

    @Test fun newAndLearningCardsStartFresh() {
        assertThat(AnkiProgressConverter.convert(AnkiProgress(0, 0, 0, 0, 0, 0, 1), now)).isNull()
        assertThat(AnkiProgressConverter.convert(AnkiProgress(1, 1, 0, 2500, 2, 0, 1), now)).isNull()
    }

    @Test fun reviewCardMapsToFsrsState() {
        val p = AnkiProgressConverter.convert(AnkiProgress(type = 2, queue = 2, ivlDays = 30, factor = 2500, reps = 8, lapses = 1, due = 0), now)!!
        assertThat(p.state).isEqualTo(SrsState.REVIEW)
        assertThat(p.mastery).isEqualTo(Mastery.MASTERED)
        assertThat(p.stability).isEqualTo(30.0)
        assertThat(p.difficulty).isWithin(0.01).of(9.0 - 1200 * 7.0 / 2200)
        assertThat(p.due).isEqualTo(now + 30 * day)
        assertThat(p.reps).isEqualTo(8)
        assertThat(p.lapses).isEqualTo(1)
        assertThat(p.suspended).isFalse()
    }

    @Test fun difficultyAndMasteryBounds() {
        assertThat(AnkiProgressConverter.difficultyFromFactor(1300)).isEqualTo(9.0)
        assertThat(AnkiProgressConverter.difficultyFromFactor(3500)).isEqualTo(2.0)
        assertThat(AnkiProgressConverter.difficultyFromFactor(5000)).isEqualTo(2.0)
        assertThat(AnkiProgressConverter.masteryFor(21)).isEqualTo(Mastery.MASTERED)
        assertThat(AnkiProgressConverter.masteryFor(4)).isEqualTo(Mastery.FAMILIAR)
        assertThat(AnkiProgressConverter.masteryFor(3)).isEqualTo(Mastery.LEARNING)
    }

    @Test fun dueResolvedFromCollectionCreation() {
        val crt = now / 1000 - 100 * 86_400L
        val p = AnkiProgressConverter.convert(AnkiProgress(2, -1, 10, 2500, 3, 0, due = 105), now, crt)!!
        assertThat(p.due).isEqualTo((crt + 105 * 86_400L) * 1000)
        assertThat(p.lastReview).isEqualTo(p.due - 10 * day)
        assertThat(p.suspended).isTrue()
        assertThat(p.mastery).isEqualTo(Mastery.FAMILIAR)
    }
}

class MediaManifestTest {
    @Test fun parsesProtobufMediaEntries() {
        fun varint(v: Int): ByteArray {
            val out = mutableListOf<Byte>(); var x = v
            while (x >= 0x80) { out += ((x and 0x7F) or 0x80).toByte(); x = x ushr 7 }
            out += x.toByte(); return out.toByteArray()
        }
        fun field(n: Int, wire: Int) = varint((n shl 3) or wire)
        fun lenDelimited(n: Int, data: ByteArray) = field(n, 2) + varint(data.size) + data
        val e1 = lenDelimited(1, "a.mp3".toByteArray()) + field(2, 0) + varint(1234) + lenDelimited(3, ByteArray(20))
        val e2 = lenDelimited(1, "b.png".toByteArray()) + field(255, 0) + varint(7)
        val manifest = lenDelimited(1, e1) + lenDelimited(1, e2)
        val entries = AnkiPackageReader.parseMediaEntries(manifest)
        assertThat(entries.map { it.name }).containsExactly("a.mp3", "b.png").inOrder()
        assertThat(entries[0].size).isEqualTo(1234)
        assertThat(entries[1].legacyZipName).isEqualTo(7)
    }
}

class ExportFormatTest {
    @Test fun csvEscapingAndAnkiHeaders() {
        assertThat(DelimitedWriter.escapeCsv("a,b")).isEqualTo("\"a,b\"")
        assertThat(DelimitedWriter.escapeCsv("say \"hi\"")).isEqualTo("\"say \"\"hi\"\"\"")
        assertThat(DelimitedWriter.escapeTsv("a\tb\nc <d>")).isEqualTo("a b<br>c &lt;d&gt;")
        val card = app.kotoba.core.db.CardEntity(language = "en", term = "run", translation = "бежать, идти", tags = "verb, basic")
        val tsv = DelimitedWriter.ankiTsv(listOf(card), emptyMap())
        assertThat(tsv).startsWith("#separator:tab\n#html:true\n#columns:")
        assertThat(tsv.lines()[4]).isEqualTo("run\tбежать, идти\t\t\t\t\t\t\t\t\tverb basic")
        val csv = DelimitedWriter.csv(listOf(card), emptyMap())
        assertThat(DelimitedParser.parse(csv).rows[0][1]).isEqualTo("бежать, идти")
    }
}
