package com.saha.videodownloader

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.saha.videodownloader.ui.MainScreen
import com.saha.videodownloader.ui.theme.SahaVideoDownloaderTheme
import com.saha.videodownloader.viewmodel.VideoDownloaderViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: VideoDownloaderViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SahaVideoDownloaderTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    MainScreen(viewModel = viewModel)
                }
            }
        }
    }
}
