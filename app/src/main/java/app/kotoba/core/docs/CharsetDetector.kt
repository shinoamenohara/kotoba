package app.kotoba.core.docs

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/**
 * Guesses the encoding of plain-text files: BOM → strict UTF-8 → byte-pattern heuristics
 * for the legacy encodings most common for our languages (windows-1251, Shift_JIS, GB18030).
 */
object CharsetDetector {
    val WINDOWS_1251: Charset = Charset.forName("windows-1251")
    val SHIFT_JIS: Charset = Charset.forName("Shift_JIS")
    val GB18030: Charset = Charset.forName("GB18030")

    fun decode(bytes: ByteArray): String {
        val (charset, bomLength) = detect(bytes)
        return String(bytes, bomLength, bytes.size - bomLength, charset)
    }

    /** Returns the charset and the number of BOM bytes to skip. */
    fun detect(bytes: ByteArray): Pair<Charset, Int> {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) return Charsets.UTF_8 to 3
        if (bytes.size >= 2 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xFE.toByte()) return Charsets.UTF_16LE to 2
        if (bytes.size >= 2 && bytes[0] == 0xFE.toByte() && bytes[1] == 0xFF.toByte()) return Charsets.UTF_16BE to 2
        utf16WithoutBom(bytes)?.let { return it to 0 }
        if (isValidUtf8(bytes)) return Charsets.UTF_8 to 0
        return guessLegacy(bytes) to 0
    }

    fun isValidUtf8(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, utf8SafeLength(bytes)))
        true
    } catch (_: CharacterCodingException) {
        false
    }

    /** Ignores a multi-byte sequence cut off at the end of the buffer. */
    private fun utf8SafeLength(bytes: ByteArray): Int {
        var i = bytes.size
        var back = 0
        while (i > 0 && back < 3) {
            val b = bytes[i - 1].toInt() and 0xFF
            if (b and 0xC0 != 0x80) {
                val need = when {
                    b and 0x80 == 0 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1
                }
                return if (back + 1 < need) i - 1 else bytes.size
            }
            i--; back++
        }
        return bytes.size
    }

    private fun utf16WithoutBom(bytes: ByteArray): java.nio.charset.Charset? {
        val n = minOf(bytes.size, 4000) / 2 * 2
        if (n < 4) return null
        var evenZero = 0
        var oddZero = 0
        for (i in 0 until n step 2) {
            if (bytes[i].toInt() == 0) evenZero++
            if (bytes[i + 1].toInt() == 0) oddZero++
        }
        val pairs = n / 2
        return when {
            oddZero > pairs * 0.3 && evenZero < pairs * 0.05 -> Charsets.UTF_16LE
            evenZero > pairs * 0.3 && oddZero < pairs * 0.05 -> Charsets.UTF_16BE
            else -> null
        }
    }

    internal fun guessLegacy(bytes: ByteArray): Charset {
        val n = minOf(bytes.size, 64 * 1024)
        var high = 0
        var spaces = 0
        var cyrLower = 0
        var sjisKana = 0
        var gbPairs = 0
        var i = 0
        while (i < n) {
            val b = bytes[i].toInt() and 0xFF
            if (b == 0x20) spaces++
            if (b >= 0x80) {
                high++
                if (b >= 0xE0) cyrLower++
            }
            i++
        }
        if (high == 0) return Charsets.UTF_8
        // Pair scans: Shift_JIS hiragana (0x82 9F–F1) / katakana (0x83 40–96); GB2312 (B0–F7, A1–FE).
        i = 0
        while (i < n - 1) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            when {
                a == 0x82 && b in 0x9F..0xF1 || a == 0x83 && b in 0x40..0x96 -> { sjisKana++; i += 2 }
                a in 0x81..0x9F || a in 0xE0..0xEF -> i += 2
                else -> i++
            }
        }
        i = 0
        while (i < n - 1) {
            val a = bytes[i].toInt() and 0xFF
            val b = bytes[i + 1].toInt() and 0xFF
            if (a in 0xB0..0xF7 && b in 0xA1..0xFE) { gbPairs++; i += 2 } else i++
        }
        val sjisRatio = sjisKana * 2.0 / high
        val gbRatio = gbPairs * 2.0 / high
        val spaceRatio = spaces.toDouble() / n
        return when {
            sjisRatio > 0.15 && decodesCleanly(bytes, n, SHIFT_JIS) -> SHIFT_JIS
            // Russian has spaces between words and mostly lowercase letters in E0–FF.
            spaceRatio > 0.06 && cyrLower > high * 0.55 -> WINDOWS_1251
            gbRatio > 0.6 && decodesCleanly(bytes, n, GB18030) -> GB18030
            decodesCleanly(bytes, n, SHIFT_JIS) && !decodesCleanly(bytes, n, GB18030) -> SHIFT_JIS
            else -> WINDOWS_1251
        }
    }

    private fun decodesCleanly(bytes: ByteArray, length: Int, charset: Charset): Boolean = try {
        // Trim to the last ASCII byte so a cut multi-byte sequence doesn't count as an error.
        var end = length
        while (end > 0 && (bytes[end - 1].toInt() and 0x80) != 0 && length - end < 4) end--
        charset.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes, 0, if (end == 0) length else end))
        true
    } catch (_: CharacterCodingException) {
        false
    }
}
