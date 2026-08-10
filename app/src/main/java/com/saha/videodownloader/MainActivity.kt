package com.saha.videodownloader

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.saha.videodownloader.ui.DownloadsScreen
import com.saha.videodownloader.ui.MainScreen
import com.saha.videodownloader.ui.theme.SahaVideoDownloaderTheme
import com.saha.videodownloader.viewmodel.DownloadsViewModel
import com.saha.videodownloader.viewmodel.VideoDownloaderViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VideoDownloaderViewModel by viewModels()
    private val downloadsViewModel: DownloadsViewModel by viewModels()

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestNotificationPermissionIfNeeded()
        enableEdgeToEdge()
        setContent {
            SahaVideoDownloaderTheme {
                var showDownloads by rememberSaveable { mutableStateOf(false) }
                Surface(modifier = Modifier.fillMaxSize()) {
                    if (showDownloads) {
                        DownloadsScreen(
                            viewModel = downloadsViewModel,
                            onBack = { showDownloads = false }
                        )
                    } else {
                        MainScreen(
                            viewModel = viewModel,
                            onOpenDownloads = { showDownloads = true }
                        )
                    }
                }
            }
        }
    }

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
