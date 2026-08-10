package com.saha.videodownloader

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
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
        handleIncomingIntent(intent, showFeedback = true)
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
                        handleIncomingIntent(incoming, showFeedback = true)
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
        handleIncomingIntent(intent, showFeedback = true)
    }

    private fun handleIncomingIntent(intent: Intent?, showFeedback: Boolean) {
        if (intent == null) return
        val isShareOrView = intent.action == Intent.ACTION_SEND ||
            intent.action == Intent.ACTION_SEND_MULTIPLE ||
            intent.action == Intent.ACTION_VIEW
        if (!isShareOrView) return

        val url = extractUrl(intent)
        if (url != null) {
            viewModel.requestNavigate(url)
            if (showFeedback) {
                Toast.makeText(this, R.string.share_received_toast, Toast.LENGTH_SHORT).show()
            }
        } else if (showFeedback &&
            (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE)
        ) {
            Toast.makeText(this, R.string.share_invalid_toast, Toast.LENGTH_SHORT).show()
        }
    }

    private fun extractUrl(intent: Intent): String? {
        when (intent.action) {
            Intent.ACTION_VIEW -> {
                intent.dataString?.let { if (looksLikeUrl(it)) return normalizeSharedUrl(it) }
            }
            Intent.ACTION_SEND, Intent.ACTION_SEND_MULTIPLE -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?.let { extractFirstUrl(it) }
                    ?.let { return it }

                intent.getStringExtra(Intent.EXTRA_SUBJECT)
                    ?.let { extractFirstUrl(it) }
                    ?.let { return it }

                intent.dataString
                    ?.let { extractFirstUrl(it) }
                    ?.let { return it }

                intent.clipData?.let { clip ->
                    for (i in 0 until clip.itemCount) {
                        val item = clip.getItemAt(i)
                        item.text?.toString()?.let { extractFirstUrl(it) }?.let { return it }
                        item.uri?.toString()?.let { extractFirstUrl(it) }?.let { return it }
                        item.htmlText?.let { extractFirstUrl(it) }?.let { return it }
                    }
                }

                val streams = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM)
                }
                streams?.firstOrNull()
                    ?.toString()
                    ?.let { extractFirstUrl(it) }
                    ?.let { return it }
            }
        }
        return null
    }

    private fun extractFirstUrl(text: String): String? {
        val httpMatch = Regex("""https?://[^\s<>"']+""").find(text)?.value
        val raw = httpMatch
            ?: Regex("""(?i)\b(?:www\.)?[\w.-]+\.[a-z]{2,}(?:/[^\s<>"']*)?""").find(text)?.value
            ?: return null
        return normalizeSharedUrl(raw.trim().trimEnd('.', ',', ';', ')', ']', '"', '\'', '>', '”', '’'))
    }

    private fun normalizeSharedUrl(value: String): String {
        val trimmed = value.trim()
        if (trimmed.startsWith("http://") || trimmed.startsWith("https://")) return trimmed
        return "https://$trimmed"
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
