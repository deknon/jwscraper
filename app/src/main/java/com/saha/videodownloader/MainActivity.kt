package com.saha.videodownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.core.util.Consumer
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
        handleIncomingIntent(intent)
        enableEdgeToEdge()
        setContent {
            SahaVideoDownloaderTheme {
                var showDownloads by rememberSaveable { mutableStateOf(false) }

                BackHandler(enabled = showDownloads) {
                    showDownloads = false
                }

                val latestShowDownloadsReset = rememberUpdatedState {
                    showDownloads = false
                }
                val intentListener = remember {
                    Consumer<Intent> { incoming ->
                        handleIncomingIntent(incoming)
                        latestShowDownloadsReset.value.invoke()
                    }
                }
                DisposableEffect(Unit) {
                    addOnNewIntentListener(intentListener)
                    onDispose { removeOnNewIntentListener(intentListener) }
                }

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

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        val url = extractUrl(intent) ?: return
        viewModel.requestNavigate(url)
    }

    private fun extractUrl(intent: Intent?): String? {
        if (intent == null) return null
        return when (intent.action) {
            Intent.ACTION_VIEW -> intent.dataString?.takeIf { looksLikeUrl(it) }
            Intent.ACTION_SEND -> {
                val text = intent.getStringExtra(Intent.EXTRA_TEXT).orEmpty()
                extractFirstUrl(text)
            }
            else -> null
        }
    }

    private fun extractFirstUrl(text: String): String? {
        val match = Regex("""https?://\S+""").find(text)?.value
            ?: Regex("""(?i)\b[\w.-]+\.[a-z]{2,}(/\S*)?""").find(text)?.value
        return match?.trim()?.trimEnd('.', ',', ')', ']', '"', '\'')
    }

    private fun looksLikeUrl(value: String): Boolean =
        value.startsWith("http://") || value.startsWith("https://")

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
