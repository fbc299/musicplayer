package com.fbc299.musicplayer.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import java.io.File

/**
 * P15-1 在线播放加 ExoPlayer 媒体缓存（512MB 文件缓存）：
 * SimpleCache(LeastRecentlyUsedCacheEvictor) + CacheDataSource 包 DefaultDataSource，
 * 在线流与本地 content:// 都走缓存，重复播放/切歌命中秒开、省流量。
 *
 * Media3 1.4.1 缓存类是 @UnstableApi；ExoPlayer 接缓存走
 * setMediaSourceFactory(ProgressiveMediaSource.Factory(cacheDataSourceFactory))。
 * 用类级 @UnstableApi 标记（1.4.1 的 UnstableApi 非 Kotlin opt-in marker，不能用 @OptIn）。
 */
@UnstableApi
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var mediaCache: Cache? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // P15-1 在线缓存：SimpleCache（512MB LRU）+ CacheDataSource 包 DefaultDataSource
        val cacheDir = File(filesDir, "exo_cache").apply { mkdirs() }
        mediaCache = SimpleCache(cacheDir, LeastRecentlyUsedCacheEvictor(512L * 1024 * 1024))
        val cacheDataSourceFactory = CacheDataSource.Factory()
            .setCache(mediaCache!!)
            .setUpstreamDataSourceFactory(DefaultDataSource.Factory(this))

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setMediaSourceFactory(ProgressiveMediaSource.Factory(cacheDataSourceFactory))
            .build()

        mediaSession = MediaSession.Builder(this, player).build()
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? =
        mediaSession

    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        mediaSession?.run {
            mediaCache?.release()
            player.release()
            release()
        }
        mediaSession = null
        mediaCache = null
        super.onDestroy()
    }
}
