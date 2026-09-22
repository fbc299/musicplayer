package com.fbc299.musicplayer.playback

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.setValue
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import kotlinx.coroutines.delay

@Stable
class PlayerState(val controller: MediaController) {

    var isPlaying by mutableStateOf(controller.isPlaying)
    var currentMediaItem by mutableStateOf(controller.currentMediaItem)
    var positionMs by mutableStateOf(controller.currentPosition.coerceAtLeast(0L))
    var durationMs by mutableStateOf(controller.duration.coerceAtLeast(0L))
    var shuffleEnabled by mutableStateOf(controller.shuffleModeEnabled)

    /** 播放队列（完整 MediaItem 列表） */
    var mediaItems by mutableStateOf<List<MediaItem>>(controller.mediaItems)

    /** 当前曲目在队列中的下标 */
    var currentIndex by mutableStateOf(controller.currentMediaItemIndex)

    /** 循环模式（Player.REPEAT_MODE_*） */
    var repeatMode by mutableStateOf(controller.repeatMode)

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(value: Boolean) {
            isPlaying = value
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            currentMediaItem = mediaItem
            refresh()
        }

        override fun onEvents(player: Player, events: Player.Events) {
            refresh()
        }
    }

    init {
        controller.addListener(listener)
        refresh()
    }

    private fun refresh() {
        durationMs = controller.duration.coerceAtLeast(0L)
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        shuffleEnabled = controller.shuffleModeEnabled
        mediaItems = controller.mediaItems
        currentIndex = controller.currentMediaItemIndex
        repeatMode = controller.repeatMode
    }

    fun tick() {
        positionMs = controller.currentPosition.coerceAtLeast(0L)
        if (durationMs <= 0L) durationMs = controller.duration.coerceAtLeast(0L)
    }

    fun release() {
        controller.removeListener(listener)
    }
}

@Composable
fun rememberPlayerState(): PlayerState? {
    val state by produceState(initialValue = PlayerConnection.controller?.let { PlayerState(it) }) {
        while (value == null) {
            delay(100)
            PlayerConnection.controller?.let { value = PlayerState(it) }
        }
    }
    DisposableEffect(state) {
        onDispose { state?.release() }
    }
    return state
}

@Composable
fun StartPositionTicker(state: PlayerState?) {
    LaunchedEffect(state) {
        while (state != null) {
            state.tick()
            delay(500)
        }
    }
}
