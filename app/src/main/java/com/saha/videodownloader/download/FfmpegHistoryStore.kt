package com.saha.videodownloader.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Persists completed download results (ffmpeg mux / progressive MP4) for the library
 * and duplicate-detection before starting a new download.
 */
class FfmpegHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    data class Entry(
        val id: String,
        val title: String,
        val sourceUrl: String,
        val contentUri: String,
        val pageUrl: String? = null,
        val createdAtMs: Long,
        val completedAtMs: Long = createdAtMs
    )

    fun add(entry: Entry) {
        val current = getAll().toMutableList()
        current.removeAll {
            it.id == entry.id ||
                it.contentUri == entry.contentUri ||
                it.sourceUrl == entry.sourceUrl
        }
        current.add(0, entry)
        save(current.take(MAX_ENTRIES))
        notifyChanged()
    }

    fun remove(id: String) {
        save(getAll().filterNot { it.id == id })
        notifyChanged()
    }

    fun removeByMediaUrl(mediaUrl: String) {
        save(getAll().filterNot { it.sourceUrl == mediaUrl })
        notifyChanged()
    }

    fun clear() {
        save(emptyList())
        notifyChanged()
    }

    fun findByMediaUrl(mediaUrl: String): Entry? =
        getAll().firstOrNull { it.sourceUrl == mediaUrl }

    fun getAll(): List<Entry> {
        val raw = prefs.getString(KEY_ENTRIES, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val obj = array.getJSONObject(i)
                    add(
                        Entry(
                            id = obj.getString("id"),
                            title = obj.getString("title"),
                            sourceUrl = obj.getString("sourceUrl"),
                            contentUri = obj.optString("contentUri", ""),
                            pageUrl = if (obj.isNull("pageUrl")) {
                                null
                            } else {
                                obj.optString("pageUrl").takeIf { it.isNotBlank() }
                            },
                            createdAtMs = obj.getLong("createdAtMs"),
                            completedAtMs = obj.optLong(
                                "completedAtMs",
                                obj.getLong("createdAtMs")
                            )
                        )
                    )
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun save(entries: List<Entry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            array.put(
                JSONObject()
                    .put("id", entry.id)
                    .put("title", entry.title)
                    .put("sourceUrl", entry.sourceUrl)
                    .put("contentUri", entry.contentUri)
                    .put("pageUrl", entry.pageUrl)
                    .put("createdAtMs", entry.createdAtMs)
                    .put("completedAtMs", entry.completedAtMs)
            )
        }
        prefs.edit { putString(KEY_ENTRIES, array.toString()) }
    }

    companion object {
        private const val PREFS_NAME = "ffmpeg_download_history"
        private const val KEY_ENTRIES = "entries"
        private const val MAX_ENTRIES = 50

        private val _changes = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
        val changes: SharedFlow<Unit> = _changes.asSharedFlow()

        fun notifyChanged() {
            _changes.tryEmit(Unit)
        }
    }
}
