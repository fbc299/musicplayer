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
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.MediaItem
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.LibraryViewModel
import com.fbc299.musicplayer.data.ONLINE_MEDIA_ID_PREFIX
import com.fbc299.musicplayer.data.OnlineSong
import com.fbc299.musicplayer.data.favoriteKey
import com.fbc299.musicplayer.playback.PlayerConnection
import com.fbc299.musicplayer.playback.rememberPlayerState
import com.fbc299.musicplayer.ui.LocalScrapedCovers
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
                    // P5：刮削封面映射（songId → 封面 URL）在组合根提供，
                    // 列表行 / 专辑格 / 详情头 / 播放页 / 迷你条的封面统一取用
                    val libraryViewModel: LibraryViewModel = viewModel()
                    val coverMap by libraryViewModel.coverMap.collectAsState()
                    // P9：负缓存用空串标记「已试失败」，读取端一律当无值（封面 UI 不受影响）
                    val scrapedCovers = remember(coverMap) {
                        coverMap.filterValues { it.isNotBlank() }
                    }
                    CompositionLocalProvider(LocalScrapedCovers provides scrapedCovers) {
                        androidx.compose.foundation.layout.Box(
                            modifier = Modifier.fillMaxSize()
                        ) {
                            HomeScreen(onOpenPlayer = { playerOpen = true })
                            if (playerOpen) {
                                // P12 播放页顶栏爱心：仅在线曲（mediaId 前缀 on_）显示；
                                // 本地曲 onlineSongOf 返回 null → favorite/onToggleFavorite 传 null 不显示
                                val playerState = rememberPlayerState()
                                val favoriteKeys by libraryViewModel.favoriteKeys.collectAsState()
                                val currentOnline = onlineSongOf(playerState?.currentMediaItem)
                                val favorite: Boolean? = currentOnline?.let { song ->
                                    favoriteKeys.contains(
                                        favoriteKey(song.source, song.sourceId)
                                    )
                                }
                                val onToggleFavorite: (() -> Unit)? = currentOnline?.let { song ->
                                    { libraryViewModel.toggleFavorite(song) }
                                }
                                PlayerScreen(
                                    onClose = { playerOpen = false },
                                    favorite = favorite,
                                    onToggleFavorite = onToggleFavorite
                                )
                            }
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

/**
 * P12 收藏：在线曲（mediaId 前缀 on_）→ OnlineSong（播放页顶栏爱心的收藏对象）。
 * 本地曲 / 解析不出 source 或 id 时返回 null —— 爱心随之不显示（本轮只收藏在线歌）。
 * title/artist/album/封面取自播放元数据（即当初 OnlineSong.toMediaItem 写入的值）；
 * picId 置空：它只用于本地曲封面刮削，收藏曲展示直接用 artworkUrl。
 * mediaId 解析口径与播放页歌词加载处一致（removePrefix("on_") 后 前段=source 后段=id）。
 */
private fun onlineSongOf(item: MediaItem?): OnlineSong? {
    val mediaId = item?.mediaId ?: return null
    if (!mediaId.startsWith(ONLINE_MEDIA_ID_PREFIX)) return null
    val rest = mediaId.removePrefix(ONLINE_MEDIA_ID_PREFIX)
    val source = rest.substringBefore('_', "")
    val id = rest.substringAfter('_', "")
    if (source.isEmpty() || id.isEmpty()) return null
    val meta = item.mediaMetadata
    return OnlineSong(
        source = source,
        sourceId = id,
        title = meta.title?.toString() ?: "",
        artist = meta.artist?.toString() ?: "",
        album = meta.albumTitle?.toString() ?: "",
        artworkUrl = meta.artworkUri?.toString(),
        picId = null
    )
}
