package com.saha.videodownloader.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.saha.videodownloader.download.OfflineDownloadRepository
import com.saha.videodownloader.model.LibraryDownload
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn

class DownloadsViewModel(application: Application) : AndroidViewModel(application) {

    private val repository = OfflineDownloadRepository(application)

    val downloads: StateFlow<List<LibraryDownload>> =
        repository.observeLibrary()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _playingId = MutableStateFlow<String?>(null)
    val playingId: StateFlow<String?> = _playingId.asStateFlow()

    fun repository(): OfflineDownloadRepository = repository

    fun play(id: String) {
        _playingId.value = id
    }

    fun stopPlayback() {
        _playingId.value = null
    }

    fun remove(item: LibraryDownload) {
        when (item.kind) {
            LibraryDownload.Kind.MEDIA3_CACHE -> repository.removeMedia3Download(item.id)
            LibraryDownload.Kind.FFMPEG_MP4 -> repository.removeFfmpegEntry(item.id)
        }
        if (_playingId.value == item.id) {
            _playingId.value = null
        }
    }

    fun cancelFfmpeg(item: LibraryDownload) {
        repository.cancelFfmpegJob(item.id)
    }
}
