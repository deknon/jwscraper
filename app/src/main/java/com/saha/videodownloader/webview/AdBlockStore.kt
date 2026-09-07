package com.saha.videodownloader.webview

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.saha.videodownloader.R
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Ad-block on/off switch plus the parsed [AdBlockFilter].
 *
 * Same shape as [com.saha.videodownloader.download.DownloadSettingsStore]:
 * an object with a double-checked-locked [init] and StateFlows for Compose.
 * Read from WebView callback threads, so both flows must stay thread-safe.
 */
object AdBlockStore {

    const val DEFAULT_ENABLED = true

    @Volatile
    private var prefs: SharedPreferences? = null

    private val _enabled = MutableStateFlow(DEFAULT_ENABLED)
    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()

    private val _filter = MutableStateFlow(AdBlockFilter.EMPTY)
    val filter: StateFlow<AdBlockFilter> = _filter.asStateFlow()

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            val appContext = context.applicationContext
            prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            _enabled.value = prefs!!.getBoolean(KEY_ENABLED, DEFAULT_ENABLED)
            _filter.value = loadBundledFilter(appContext)
        }
    }

    fun isEnabled(context: Context): Boolean {
        init(context)
        return _enabled.value
    }

    fun setEnabled(context: Context, value: Boolean) {
        init(context)
        prefs?.edit { putBoolean(KEY_ENABLED, value) }
        _enabled.value = value
    }

    /**
     * Hot path — called for every WebView request. Reads two volatile
     * StateFlow values and returns early when the toggle is off.
     */
    fun blocks(url: String): Boolean =
        _enabled.value && _filter.value.blocks(url)

    private fun loadBundledFilter(context: Context): AdBlockFilter =
        try {
            context.resources.openRawResource(R.raw.adblock_hosts).use { stream ->
                stream.bufferedReader().useLines { AdBlockFilter.parse(it) }
            }
        } catch (_: Throwable) {
            AdBlockFilter.EMPTY
        }

    private const val PREFS = "ad_block_settings"
    private const val KEY_ENABLED = "enabled"
}
