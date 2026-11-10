package com.sonicareset.app.tracker

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists a list of [BrushHead] records in SharedPreferences, serialized as a
 * JSON array string. Uses only org.json (bundled with Android) — no Room, no
 * external serialization libraries.
 */
class TrackerRepository(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** All tracked heads, sorted by install time, newest first. */
    fun list(): List<BrushHead> {
        return readAll().sortedByDescending { it.installedAtEpochMillis }
    }

    fun add(head: BrushHead) {
        val current = readAll().toMutableList()
        // Replace any existing record with the same id to keep ids unique.
        current.removeAll { it.id == head.id }
        current.add(head)
        writeAll(current)
    }

    /** Update the record whose id matches [head]. No-op if absent. */
    fun update(head: BrushHead) {
        val current = readAll().toMutableList()
        val index = current.indexOfFirst { it.id == head.id }
        if (index >= 0) {
            current[index] = head
            writeAll(current)
        }
    }

    fun remove(id: String) {
        val current = readAll().toMutableList()
        if (current.removeAll { it.id == id }) {
            writeAll(current)
        }
    }

    // --- internals -------------------------------------------------------

    private fun readAll(): List<BrushHead> {
        val raw = prefs.getString(KEY_ITEMS, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = ArrayList<BrushHead>(array.length())
            for (i in 0 until array.length()) {
                val obj = array.optJSONObject(i) ?: continue
                fromJson(obj)?.let { result.add(it) }
            }
            result
        } catch (_: Exception) {
            // Never throw on malformed JSON.
            emptyList()
        }
    }

    private fun writeAll(heads: List<BrushHead>) {
        val array = JSONArray()
        for (head in heads) {
            array.put(toJson(head))
        }
        prefs.edit().putString(KEY_ITEMS, array.toString()).apply()
    }

    private fun toJson(head: BrushHead): JSONObject {
        return JSONObject().apply {
            put("id", head.id)
            put("name", head.name)
            put("installedAtEpochMillis", head.installedAtEpochMillis)
            put("targetDays", head.targetDays)
            put("notes", head.notes)
        }
    }

    /** Deserialize one record, tolerating missing fields. Null if unusable. */
    private fun fromJson(obj: JSONObject): BrushHead? {
        val id = obj.optString("id", "")
        if (id.isEmpty()) return null
        return BrushHead(
            id = id,
            name = obj.optString("name", ""),
            installedAtEpochMillis = obj.optLong("installedAtEpochMillis", 0L),
            targetDays = obj.optInt("targetDays", 90),
            notes = obj.optString("notes", "")
        )
    }

    private companion object {
        const val PREFS_NAME = "brush_heads"
        const val KEY_ITEMS = "items"
    }
}
