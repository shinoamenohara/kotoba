package app.kotoba.feature.manga

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OcrLayoutTest {
    private fun line(text: String, l: Int, t: Int, r: Int, b: Int) = OcrLine(text, OcrBox(l, t, r, b))

    @Test fun `vertical japanese columns are read right to left and joined without spaces`() {
        // Two columns of one bubble returned as separate blocks, left column first.
        val left = listOf(line("ある。", 100, 50, 130, 200))
        val right = listOf(line("吾輩は猫で", 140, 50, 170, 260))
        val blocks = OcrLayout.build(listOf(left, right), cjk = true)
        assertThat(blocks).hasSize(1)
        assertThat(blocks[0].vertical).isTrue()
        assertThat(blocks[0].text).isEqualTo("吾輩は猫である。")
        assertThat(blocks[0].box).isEqualTo(OcrBox(100, 50, 170, 260))
    }

    @Test fun `separate bubbles in a row are ordered right to left for vertical text`() {
        val a = listOf(line("左です", 10, 10, 40, 150))
        val b = listOf(line("右です", 400, 10, 430, 150))
        val blocks = OcrLayout.build(listOf(a, b), cjk = true)
        assertThat(blocks.map { it.text }).containsExactly("右です", "左です").inOrder()
        assertThat(blocks.map { it.id }).containsExactly(0, 1).inOrder()
    }

    @Test fun `horizontal latin lines keep top to bottom order with spaces`() {
        val block = listOf(line("world!", 10, 40, 80, 60), line("Hello", 10, 10, 70, 30))
        val blocks = OcrLayout.build(listOf(block), cjk = false)
        assertThat(blocks.single().text).isEqualTo("Hello world!")
        assertThat(blocks.single().vertical).isFalse()
    }

    @Test fun `rows of bubbles go top to bottom`() {
        val bottom = listOf(line("Bye", 10, 500, 80, 520))
        val top = listOf(line("Hi", 300, 10, 360, 30))
        assertThat(OcrLayout.build(listOf(bottom, top), cjk = false).map { it.text }).containsExactly("Hi", "Bye").inOrder()
    }

    @Test fun `many overlapping bubbles never break ordering`() {
        // Staggered boxes make a pairwise same-row comparison non-transitive; ordering must still succeed.
        val raw = (0 until 60).map { i -> listOf(line("w$i", (i * 37) % 500, i * 13, (i * 37) % 500 + 40, i * 13 + 60 + (i % 7) * 15)) }
        val blocks = OcrLayout.build(raw, cjk = false)
        assertThat(blocks).hasSize(60)
        assertThat(blocks.map { it.id }).containsExactlyElementsIn(0 until 60).inOrder()
    }

    @Test fun `sample size keeps the longest side within the limit`() {
        assertThat(OcrLayout.sampleSize(1000, 1500, 3000)).isEqualTo(1)
        assertThat(OcrLayout.sampleSize(3000, 3000, 3000)).isEqualTo(1)
        assertThat(OcrLayout.sampleSize(4000, 6000, 3000)).isEqualTo(2)
        assertThat(OcrLayout.sampleSize(8000, 12000, 3000)).isEqualTo(4)
        assertThat(OcrLayout.sampleSize(0, 0, 3000)).isEqualTo(1)
    }
}
