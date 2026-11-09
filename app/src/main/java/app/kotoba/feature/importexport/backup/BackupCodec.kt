package app.kotoba.feature.importexport.backup

import android.content.ContentValues
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.util.Base64
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put

/**
 * Schema-agnostic table dump/restore. Every user table is serialized row by row, so new tables and
 * columns are picked up automatically; restore inserts only columns that exist in the current schema.
 */
object BackupCodec {
    const val APP = "kotoba"
    const val FILES_TOKEN = "{FILES}"
    private const val B64 = "\$b64"
    private val internalTables = setOf("android_metadata", "room_master_table", "sqlite_sequence")

    fun userTables(db: SupportSQLiteDatabase): List<String> =
        db.query("SELECT name FROM sqlite_master WHERE type = 'table'").use { c ->
            buildList { while (c.moveToNext()) add(c.getString(0)) }
        }.filter { it !in internalTables && !it.startsWith("sqlite_") }

    /**
     * @param filesDir absolute app files path; occurrences in text values are replaced with [FILES_TOKEN]
     * so paths survive a restore on another device.
     */
    fun dump(
        db: SupportSQLiteDatabase,
        createdAt: Long,
        exclude: Set<String> = emptySet(),
        filesDir: String? = null,
        meta: Map<String, Boolean> = emptyMap(),
    ): JsonObject = buildJsonObject {
        put("app", APP)
        put("schemaVersion", db.version)
        put("createdAt", createdAt)
        meta.forEach { (k, v) -> put(k, v) }
        put("tables", buildJsonObject {
            userTables(db).filter { it !in exclude }.forEach { table ->
                put(table, db.query("SELECT * FROM `$table`").use { c -> rows(c, filesDir) })
            }
        })
    }

    private fun rows(c: Cursor, filesDir: String?): JsonArray = buildJsonArray {
        val names = c.columnNames
        while (c.moveToNext()) {
            add(buildJsonObject {
                names.forEachIndexed { i, col ->
                    put(col, when (c.getType(i)) {
                        Cursor.FIELD_TYPE_NULL -> JsonNull
                        Cursor.FIELD_TYPE_INTEGER -> JsonPrimitive(c.getLong(i))
                        Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i).let { d -> if (d.isFinite()) JsonPrimitive(d) else JsonNull }
                        Cursor.FIELD_TYPE_BLOB -> buildJsonObject { put(B64, Base64.encodeToString(c.getBlob(i), Base64.NO_WRAP)) }
                        else -> JsonPrimitive(c.getString(i).let { s -> if (filesDir != null) s.replace(filesDir, FILES_TOKEN) else s })
                    })
                }
            })
        }
    }

    data class Header(val schemaVersion: Int, val createdAt: Long, val counts: Map<String, Int>)

    fun header(root: JsonObject): Header {
        require((root["app"] as? JsonPrimitive)?.contentOrNull == APP) { "Not a Kotoba backup" }
        val tables = root["tables"]?.jsonObject ?: JsonObject(emptyMap())
        return Header(
            schemaVersion = (root["schemaVersion"] as? JsonPrimitive)?.intOrNull ?: 0,
            createdAt = (root["createdAt"] as? JsonPrimitive)?.longOrNull ?: 0,
            counts = tables.mapValues { (it.value as? JsonArray)?.size ?: 0 },
        )
    }

    /**
     * Replaces the contents of every table present in [root]. Must run inside a transaction;
     * foreign keys are deferred so table order does not matter.
     */
    fun restore(db: SupportSQLiteDatabase, root: JsonObject, filesDir: String? = null) {
        val h = header(root)
        require(h.schemaVersion <= db.version) { "Backup was made by a newer app version" }
        db.execSQL("PRAGMA defer_foreign_keys = ON")
        val existing = userTables(db).toSet()
        val tables = root["tables"]?.jsonObject ?: return
        for ((table, value) in tables) {
            if (table !in existing) continue
            val columns = db.query("PRAGMA table_info(`$table`)").use { c ->
                val idx = c.getColumnIndex("name")
                buildSet { while (c.moveToNext()) add(c.getString(idx)) }
            }
            db.execSQL("DELETE FROM `$table`")
            (value as? JsonArray)?.forEach { row ->
                val cv = ContentValues()
                (row as? JsonObject)?.forEach { (col, v) -> if (col in columns) putValue(cv, col, v, filesDir) }
                if (cv.size() > 0) db.insert(table, SQLiteDatabase.CONFLICT_REPLACE, cv)
            }
        }
    }

    private fun putValue(cv: ContentValues, col: String, v: kotlinx.serialization.json.JsonElement, filesDir: String?) {
        when (v) {
            JsonNull -> cv.putNull(col)
            is JsonObject -> (v[B64] as? JsonPrimitive)?.contentOrNull?.let { cv.put(col, Base64.decode(it, Base64.NO_WRAP)) } ?: cv.putNull(col)
            is JsonArray -> cv.put(col, v.toString())
            is JsonPrimitive -> when {
                v.isString -> cv.put(col, v.content.let { s -> if (filesDir != null) s.replace(FILES_TOKEN, filesDir) else s })
                v.booleanOrNull != null -> cv.put(col, if (v.booleanOrNull == true) 1L else 0L)
                else -> v.content.toLongOrNull()?.let { cv.put(col, it) }
                    ?: v.content.toDoubleOrNull()?.let { cv.put(col, it) }
                    ?: cv.putNull(col)
            }
        }
    }
}
