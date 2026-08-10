package com.saha.videodownloader.download

import com.saha.videodownloader.model.LibraryDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.ConcurrentHashMap

/**
 * In-memory tracker for active ffmpeg mux jobs (progress + cancel).
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
        val updatedAtMs: Long = System.currentTimeMillis()
    )

    private val jobs = ConcurrentHashMap<String, Job>()
    private val _snapshot = MutableStateFlow<List<Job>>(emptyList())
    val snapshot: StateFlow<List<Job>> = _snapshot.asStateFlow()

    fun start(id: String, title: String, sourceUrl: String) {
        jobs[id] = Job(
            id = id,
            title = title,
            sourceUrl = sourceUrl,
            state = LibraryDownload.State.DOWNLOADING,
            progressPercent = 0f,
            message = "เริ่ม mux…"
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

    private fun update(id: String, transform: (Job) -> Job) {
        val current = jobs[id] ?: return
        jobs[id] = transform(current)
        publish()
    }

    private fun publish() {
        _snapshot.update {
            jobs.values.sortedByDescending { job -> job.updatedAtMs }
        }
    }
}
