package com.fbc299.musicplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.OnlineSong
import com.fbc299.musicplayer.data.favoriteKey

/**
 * P14 收藏 Tab（底部导航第二项）：整列已收藏的在线歌曲 —— 收藏从歌曲列表的分段
 * 升为独立入口，解决「收藏的音乐找不到位置」。
 *
 * 分层自底向上（同歌曲/艺术家页）：顶部下压渐变 + 音符水印锚点 → 收藏分段头
 * （段头带「播放全部」）→ 收藏行（来源标 / 爱心 / 下载全套）→ 底部 scrim。
 * 空态复用 FavoritesEmpty（音符水印 + 白字）。
 *
 * 组件复用：SectionHeader / FavoritesEmpty / OnlineSongRow 定义在同包 SongsTab.kt，
 * P14 起由 private 放宽为 internal —— 改可见性（3 处删关键字）而不是复制组件。
 * 点播一律走 onOnlineClick：HomeScreen 传入的同一条含 P9 试听换源的主 lambda。
 */
@Composable
fun FavoritesTab(
    favorites: List<OnlineSong>,
    favoriteKeys: Set<String>,
    currentMediaId: String?,
    resolvingOnlineId: String?,
    onOnlineClick: (OnlineSong) -> Unit,
    onToggleFavorite: (OnlineSong) -> Unit,
    downloadingKeys: Set<String>,
    onDownload: (OnlineSong) -> Unit,
    onPlayFavorites: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // 沉浸锚点（同歌曲/艺术家页）：深色渐变 + 音符水印，压在列表背后
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1C1C1F),
                            Color.Transparent
                        )
                    )
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.MusicNote,
                contentDescription = null,
                modifier = Modifier.size(220.dp),
                tint = Color.White.copy(alpha = 0.10f)
            )
        }

        LazyColumn(modifier = Modifier.fillMaxSize()) {
            item(key = "fav_header") {
                SectionHeader(
                    title = stringResource(R.string.favorites),
                    count = favorites.size,
                    onPlayAll = if (favorites.isNotEmpty()) onPlayFavorites else null
                )
            }
            if (favorites.isEmpty()) {
                item(key = "fav_empty") {
                    FavoritesEmpty()
                }
            } else {
                items(favorites, key = { favoriteKey(it.source, it.sourceId) }) { song ->
                    OnlineSongRow(
                        song = song,
                        isCurrent = song.mediaId == currentMediaId,
                        resolving = song.mediaId == resolvingOnlineId,
                        // 与在线段同一入口：HomeScreen 的点播 lambda（含 P9 试听换源）
                        onClick = { onOnlineClick(song) },
                        favorite = favoriteKeys.contains(
                            favoriteKey(song.source, song.sourceId)
                        ),
                        onToggleFavorite = { onToggleFavorite(song) },
                        downloading = downloadingKeys.contains(
                            favoriteKey(song.source, song.sourceId)
                        ),
                        onDownload = { onDownload(song) }
                    )
                }
            }
            item(key = "fav_bottom_spacer") { Spacer(Modifier.height(8.dp)) }
        }

        // 底部 scrim：贴 mini player/底栏上方，透明 → 黑 0.85 压底（同歌曲页）
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(104.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.85f)
                        )
                    )
                )
        )
    }
}
