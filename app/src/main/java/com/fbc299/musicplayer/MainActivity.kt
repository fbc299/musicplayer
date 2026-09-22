package com.fbc299.musicplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import com.fbc299.musicplayer.playback.PlayerConnection
import com.fbc299.musicplayer.ui.home.HomeScreen
import com.fbc299.musicplayer.ui.player.PlayerScreen
import com.fbc299.musicplayer.ui.theme.MusicTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PlayerConnection.connect(applicationContext)

        setContent {
            MusicTheme {
                var hasPermission by remember {
                    mutableStateOf(checkAudioPermission())
                }
                var playerOpen by remember { mutableStateOf(false) }

                val launcher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted -> hasPermission = granted }

                if (!hasPermission) {
                    PermissionScreen(onGrant = { launcher.launch(audioPermission()) })
                } else {
                    androidx.compose.foundation.layout.Box(
                        modifier = Modifier.fillMaxSize()
                    ) {
                        HomeScreen(onOpenPlayer = { playerOpen = true })
                        if (playerOpen) {
                            PlayerScreen(onClose = { playerOpen = false })
                        }
                    }
                }
            }
        }
    }

    private fun checkAudioPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, audioPermission()) ==
            PackageManager.PERMISSION_GRANTED

    private fun audioPermission(): String =
        if (Build.VERSION.SDK_INT >= 33) Manifest.permission.READ_MEDIA_AUDIO
        else Manifest.permission.READ_EXTERNAL_STORAGE
}
