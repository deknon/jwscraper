package com.saha.videodownloader.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * User preferences for download behaviour (concurrency, etc.).
 */
object DownloadSettingsStore {

    const val MIN_CONCURRENT = 1
    const val MAX_CONCURRENT = 5
    const val DEFAULT_CONCURRENT = 2

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _maxConcurrent = MutableStateFlow(DEFAULT_CONCURRENT)
    val maxConcurrent: StateFlow<Int> = _maxConcurrent.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _maxConcurrent.value = prefs!!
                .getInt(KEY_MAX_CONCURRENT, DEFAULT_CONCURRENT)
                .coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)
        }
    }

    fun getMaxConcurrent(context: Context): Int {
        init(context)
        return _maxConcurrent.value
    }

    fun setMaxConcurrent(context: Context, value: Int) {
        init(context)
        val clamped = value.coerceIn(MIN_CONCURRENT, MAX_CONCURRENT)
        prefs?.edit { putInt(KEY_MAX_CONCURRENT, clamped) }
        _maxConcurrent.value = clamped
        Media3DownloadUtil.applyMaxParallelDownloads(context, clamped)
        MuxJobQueue.pump(context.applicationContext)
    }

    private const val PREFS = "download_settings"
    private const val KEY_MAX_CONCURRENT = "max_concurrent"
}
