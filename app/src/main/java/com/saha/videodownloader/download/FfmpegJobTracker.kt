package com.saha.videodownloader.download

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.saha.videodownloader.model.LibraryDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap

/**
 * Tracker for active ffmpeg mux jobs.
 *
 * Persists to SharedPreferences so the downloads list still shows a row after
 * process death (common on HyperOS when FGS / native ffmpeg crashes).
 */
object FfmpegJobTracker {

    data class Job(
        val id: String,
        val title: String,
        val sourceUrl: String,
        val state: LibraryDownload.State,
        val progressPercent: Float,
        val sessionId: Long? = null,
        val message: String? = null,
        val refererUrl: String? = null,
        val userAgent: String? = null,
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _snapshot = MutableStateFlow<List<Job>>(emptyList())
    val snapshot: StateFlow<List<Job>> = _snapshot.asStateFlow()

    @Volatile
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        if (prefs != null) return
        synchronized(this) {
            if (prefs != null) return
            prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            loadFromDisk()
            markInterruptedActiveJobs()
        }
    }

    fun start(
        id: String,
        title: String,
        sourceUrl: String,
        refererUrl: String? = null,
        userAgent: String? = null
    ) {
        jobs[id] = Job(
            id = id,
            title = title,
            sourceUrl = sourceUrl,
            state = LibraryDownload.State.DOWNLOADING,
            progressPercent = 0f,
            message = "เริ่ม mux…",
            refererUrl = refererUrl,
            userAgent = userAgent
        )
        publish()
    }

    fun bindSession(id: String, sessionId: Long) {
        update(id) { it.copy(sessionId = sessionId, updatedAtMs = System.currentTimeMillis()) }
    }

    fun updateProgress(id: String, progressPercent: Float, message: String? = null) {
        update(id) {
            it.copy(
                progressPercent = progressPercent.coerceIn(0f, 0.99f),
                state = LibraryDownload.State.DOWNLOADING,
                message = message ?: it.message,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun complete(id: String) {
        jobs.remove(id)
        publish()
    }

    fun fail(id: String, message: String) {
        update(id) {
            it.copy(
                state = LibraryDownload.State.FAILED,
                message = message,
                updatedAtMs = System.currentTimeMillis()
            )
        }
    }

    fun remove(id: String) {
        jobs.remove(id)
        publish()
    }

    fun get(id: String): Job? = jobs[id]

    private fun markInterruptedActiveJobs() {
        var changed = false
        jobs.forEach { (id, job) ->
            if (job.state == LibraryDownload.State.DOWNLOADING ||
                job.state == LibraryDownload.State.QUEUED
            ) {
                jobs[id] = job.copy(
                    state = LibraryDownload.State.FAILED,
                    message = "งานหยุดกลางคัน (แอปถูกปิด/ระบบหยุด) — กดดาวน์โหลดใหม่",
                    sessionId = null,
                    updatedAtMs = System.currentTimeMillis()
                )
                changed = true
            }
        }
        if (changed) publish()
    }

    private fun update(id: String, transform: (Job) -> Job) {
        val current = jobs[id] ?: return
        jobs[id] = transform(current)
        publish()
    }

    private fun publish() {
        val ordered = jobs.values.sortedByDescending { job -> job.updatedAtMs }
        _snapshot.update { ordered }
        persist(ordered)
    }

    private fun persist(ordered: List<Job>) {
        val p = prefs ?: return
        val array = JSONArray()
        ordered.forEach { job ->
            array.put(
                JSONObject()
                    .put("id", job.id)
                    .put("title", job.title)
                    .put("sourceUrl", job.sourceUrl)
                    .put("state", job.state.name)
                    .put("progressPercent", job.progressPercent.toDouble())
                    .put("message", job.message)
                    .put("refererUrl", job.refererUrl)
                    .put("userAgent", job.userAgent)
                    .put("updatedAtMs", job.updatedAtMs)
            )
        }
        // commit() so a sudden HyperOS kill still leaves the row on disk.
        p.edit(commit = true) { putString(KEY_JOBS, array.toString()) }
    }

    private fun loadFromDisk() {
        val raw = prefs?.getString(KEY_JOBS, null) ?: return
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val id = obj.getString("id")
                val state = runCatching {
                    LibraryDownload.State.valueOf(obj.getString("state"))
                }.getOrDefault(LibraryDownload.State.FAILED)
                jobs[id] = Job(
                    id = id,
                    title = obj.getString("title"),
                    sourceUrl = obj.getString("sourceUrl"),
                    state = state,
                    progressPercent = obj.optDouble("progressPercent", 0.0).toFloat(),
                    message = if (obj.isNull("message")) {
                        null
                    } else {
                        obj.optString("message").takeIf { it.isNotBlank() }
                    },
                    refererUrl = if (obj.isNull("refererUrl")) {
                        null
                    } else {
                        obj.optString("refererUrl").takeIf { it.isNotBlank() }
                    },
                    userAgent = if (obj.isNull("userAgent")) {
                        null
                    } else {
                        obj.optString("userAgent").takeIf { it.isNotBlank() }
                    },
                    updatedAtMs = obj.optLong("updatedAtMs", System.currentTimeMillis())
                )
            }
            _snapshot.value = jobs.values.sortedByDescending { it.updatedAtMs }
        } catch (_: Exception) {
            // Corrupt prefs — start empty.
            jobs.clear()
            _snapshot.value = emptyList()
        }
    }

    private const val PREFS = "ffmpeg_active_jobs"
    private const val KEY_JOBS = "jobs"
}
