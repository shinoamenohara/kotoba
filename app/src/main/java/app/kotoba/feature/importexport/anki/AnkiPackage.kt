package app.kotoba.feature.importexport.anki

import com.github.luben.zstd.ZstdInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.util.zip.ZipInputStream

/** An unpacked .apkg/.colpkg: the SQLite collection plus media files keyed by their original name. */
class AnkiPackage(
    val collectionFile: File,
    val media: Map<String, File>,
    val warnings: List<String>,
)

object AnkiPackageReader {
    private val ZSTD_MAGIC = byteArrayOf(0x28, 0xB5.toByte(), 0x2F, 0xFD.toByte())

    fun unpack(input: InputStream, workDir: File): AnkiPackage {
        workDir.mkdirs()
        val root = workDir.canonicalPath + File.separator
        ZipInputStream(input.buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                if (e.isDirectory) continue
                val out = File(workDir, e.name)
                if (!out.canonicalPath.startsWith(root)) continue // zip-slip guard
                out.parentFile?.mkdirs()
                out.outputStream().use { zip.copyTo(it) }
            }
        }
        val warnings = mutableListOf<String>()
        val collection = when {
            File(workDir, "collection.anki21b").exists() -> {
                val out = File(workDir, "collection.db")
                ZstdInputStream(File(workDir, "collection.anki21b").inputStream().buffered()).use { z ->
                    out.outputStream().use { z.copyTo(it) }
                }
                out
            }
            File(workDir, "collection.anki21").exists() -> File(workDir, "collection.anki21")
            File(workDir, "collection.anki2").exists() -> File(workDir, "collection.anki2")
            else -> throw IOException("No Anki collection found in the package")
        }
        val media = runCatching { readMedia(workDir) }.getOrElse {
            warnings += "media: ${it.message}"
            emptyMap()
        }
        return AnkiPackage(collection, media, warnings)
    }

    private fun readMedia(dir: File): Map<String, File> {
        val mediaFile = File(dir, "media")
        if (!mediaFile.exists()) return emptyMap()
        val bytes = mediaFile.readBytes()
        if (bytes.isEmpty()) return emptyMap()
        val trimmed = bytes.dropWhile { it == ' '.code.toByte() || it == '\n'.code.toByte() }
        if (trimmed.firstOrNull() == '{'.code.toByte()) {
            // Legacy: {"0": "file.mp3", ...}
            val obj = Json.parseToJsonElement(bytes.decodeToString()).jsonObject
            return obj.mapNotNull { (zipName, v) ->
                val name = v.jsonPrimitive.contentOrNull ?: return@mapNotNull null
                File(dir, zipName).takeIf { it.exists() && it.canonicalPath.startsWith(dir.canonicalPath + File.separator) }
                    ?.let { name to it }
            }.toMap()
        }
        // Modern: zstd-compressed protobuf MediaEntries; each media file is itself zstd-compressed.
        val raw = if (isZstd(bytes)) ZstdInputStream(ByteArrayInputStream(bytes)).use { it.readBytes() } else bytes
        val result = LinkedHashMap<String, File>()
        parseMediaEntries(raw).forEachIndexed { index, entry ->
            val zipName = (entry.legacyZipName ?: index).toString()
            val packed = File(dir, zipName)
            if (!packed.exists() || entry.name.isBlank()) return@forEachIndexed
            val head = packed.inputStream().use { s -> ByteArray(4).also { s.read(it) } }
            val file = if (isZstd(head)) {
                val out = File(dir, "m_$zipName")
                ZstdInputStream(packed.inputStream().buffered()).use { z -> out.outputStream().use { z.copyTo(it) } }
                out
            } else packed
            result[entry.name] = file
        }
        return result
    }

    private fun isZstd(b: ByteArray) = b.size >= 4 && (0 until 4).all { b[it] == ZSTD_MAGIC[it] }

    data class MediaEntry(val name: String, val size: Long, val legacyZipName: Int?)

    /** message MediaEntries { repeated MediaEntry entries = 1; } MediaEntry { string name = 1; uint32 size = 2; bytes sha1 = 3; optional uint32 legacy_zip_filename = 255; } */
    fun parseMediaEntries(bytes: ByteArray): List<MediaEntry> {
        val out = mutableListOf<MediaEntry>()
        val r = ProtoReader(bytes)
        while (!r.eof()) {
            val (field, wire) = r.tag()
            if (field == 1 && wire == 2) {
                val m = ProtoReader(r.bytes())
                var name = ""; var size = 0L; var legacy: Int? = null
                while (!m.eof()) {
                    val (f, w) = m.tag()
                    when {
                        f == 1 && w == 2 -> name = m.bytes().decodeToString()
                        f == 2 && w == 0 -> size = m.varint()
                        f == 255 && w == 0 -> legacy = m.varint().toInt()
                        else -> m.skip(w)
                    }
                }
                out += MediaEntry(name, size, legacy)
            } else r.skip(wire)
        }
        return out
    }
}

/** Just enough protobuf decoding for Anki's media manifest. */
class ProtoReader(private val buf: ByteArray) {
    private var pos = 0
    fun eof() = pos >= buf.size

    fun varint(): Long {
        var shift = 0
        var result = 0L
        while (true) {
            if (pos >= buf.size) throw IOException("Truncated varint")
            val b = buf[pos++].toInt() and 0xFF
            result = result or ((b and 0x7F).toLong() shl shift)
            if (b and 0x80 == 0) return result
            shift += 7
            if (shift > 63) throw IOException("Malformed varint")
        }
    }

    fun tag(): Pair<Int, Int> { val t = varint(); return (t ushr 3).toInt() to (t and 7).toInt() }

    fun bytes(): ByteArray {
        val len = varint().toInt()
        if (len < 0 || pos + len > buf.size) throw IOException("Truncated field")
        return buf.copyOfRange(pos, pos + len).also { pos += len }
    }

    fun skip(wire: Int) {
        when (wire) {
            0 -> varint()
            1 -> pos += 8
            2 -> bytes()
            5 -> pos += 4
            else -> throw IOException("Unsupported wire type $wire")
        }
    }
}
