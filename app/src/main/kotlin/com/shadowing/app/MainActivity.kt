package com.shadowing.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.media3.common.util.UnstableApi
import com.shadowing.app.car.ShadowingAppContainer
import com.shadowing.app.car.ShadowingMediaLibraryService
import com.shadowing.app.ui.ShadowingApp
import com.shadowing.app.ui.theme.ShadowingTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ShadowingAppContainer.ensureInitialized(this)
        runCatching {
            startService(Intent(this, ShadowingMediaLibraryService::class.java))
        }

        enableEdgeToEdge()
        setContent {
            ShadowingTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ShadowingApp(
                        onRequestMicPermission = { ensureMicrophonePermission() }
                    )
                }
            }
        }
    }

    private fun ensureMicrophonePermission() {
        val granted = ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }
}
