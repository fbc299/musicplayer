package com.fbc299.musicplayer.playback

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.MediaCache
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null
    private var mediaCache: Cache? = null

    override fun onCreate() {
        super.onCreate()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        // P15-1 在线缓存：MediaCache 512MB，CacheDataSource 包 DefaultDataSource；
        // 在线流与本地 content:// 都走缓存，重复播放/切歌命中秒开、省流量
        mediaCache = MediaCache.Builder(this)
            .setMaxSizeBytes(512L * 1024 * 1024)
            .build()
        val upstream = DefaultDataSource.Factory(this, DefaultHttpDataSource.Factory())
        val dataSourceFactory = CacheDataSource.Factory()
            .setCache(mediaCache!!)
            .setUpstreamFactory(upstream)

        val player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setHandleAudioBecomingNoisy(true)
            .setDataSourceFactory(dataSourceFactory)
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
            player.release()
            release()
        }
        mediaSession = null
        mediaCache?.release()
        mediaCache = null
        super.onDestroy()
    }
}
