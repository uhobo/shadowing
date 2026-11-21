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
import com.shadowing.app.domain.model.EnginePhase
import com.shadowing.app.domain.model.ShadowingAction
import com.shadowing.app.ui.ShadowingApp
import com.shadowing.app.ui.theme.ShadowingTheme

@UnstableApi
class MainActivity : ComponentActivity() {

    private var pendingActionAfterPermission: ShadowingAction? = null

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val pending = pendingActionAfterPermission
        pendingActionAfterPermission = null
        if (granted && pending != null) {
            ShadowingAppContainer.engine.dispatch(pending)
        } else if (!granted) {
            ShadowingAppContainer.engine.setStatusMessage("Microphone permission is required to score pronunciation.")
        }
    }

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
                        onExecuteAction = { action -> handleShadowingAction(action) }
                    )
                }
            }
        }
    }

    private fun handleShadowingAction(action: ShadowingAction) {
        val currentPhase = ShadowingAppContainer.engine.uiState.value.phase
        val needsMicToStart = action == ShadowingAction.StartOrResume ||
            (action == ShadowingAction.TogglePause && (currentPhase == EnginePhase.IDLE || currentPhase == EnginePhase.PAUSED))

        if (needsMicToStart && !hasMicrophonePermission()) {
            pendingActionAfterPermission = ShadowingAction.StartOrResume
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }
        ShadowingAppContainer.engine.dispatch(action)
    }

    private fun hasMicrophonePermission(): Boolean =
        ContextCompat.checkSelfPermission(
            this,
            Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED
}
