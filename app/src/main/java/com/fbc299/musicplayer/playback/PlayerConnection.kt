package com.fbc299.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fbc299.musicplayer.data.Song
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors

/** 循环模式三态（对应 Media3 REPEAT_MODE_*） */
enum class RepeatMode { OFF, ALL, ONE }

object PlayerConnection {

    @Volatile
    var controller: MediaController? = null
        private set

    private var future: ListenableFuture<MediaController>? = null

    @Synchronized
    fun connect(context: Context) {
        if (future != null) return
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java)
        )
        val f = MediaController.Builder(context.applicationContext, token).buildAsync()
        future = f
        f.addListener({
            try {
                controller = f.get()
            } catch (_: Exception) {
                future = null
            }
        }, MoreExecutors.directExecutor())
    }

    fun playSongs(songs: List<Song>, startIndex: Int) {
        val c = controller ?: return
        val items = songs.map { it.toMediaItem() }
        c.setMediaItems(items, startIndex, 0)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun shuffle(enabled: Boolean) {
        val c = controller ?: return
        c.shuffleModeEnabled = enabled
    }

    /** 设置循环模式 */
    fun setRepeatMode(mode: RepeatMode) {
        val c = controller ?: return
        c.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /** 按 关闭 → 列表循环 → 单曲循环 → 关闭 的顺序切换，返回切换后的模式 */
    fun cycleRepeatMode(): RepeatMode {
        val c = controller ?: return RepeatMode.OFF
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        return when (next) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
    }

    /** 跳转到播放队列中的指定位置 */
    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index < 0 || index >= c.mediaItemCount) return
        c.seekToMediaItem(index)
        c.play()
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
}
