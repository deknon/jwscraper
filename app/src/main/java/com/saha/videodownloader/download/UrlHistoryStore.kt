package com.saha.videodownloader.download

import android.content.Context
import androidx.core.content.edit
import org.json.JSONArray

/**
 * Persists recently visited page URLs for quick re-open on phone.
 */
class UrlHistoryStore(context: Context) {

    private val prefs =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun getAll(): List<String> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    val value = array.optString(i).trim()
                    if (value.isNotEmpty()) add(value)
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun add(url: String) {
        val normalized = url.trim()
        if (normalized.isEmpty() ||
            normalized == "https://" ||
            normalized == "http://" ||
            normalized == "about:blank"
        ) {
            return
        }
        val next = (listOf(normalized) + getAll().filterNot { it == normalized })
            .take(MAX_ENTRIES)
        val array = JSONArray()
        next.forEach { array.put(it) }
        prefs.edit { putString(KEY, array.toString()) }
    }

    fun clear() {
        prefs.edit { remove(KEY) }
    }

    companion object {
        private const val PREFS = "url_history"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 20
    }
}
