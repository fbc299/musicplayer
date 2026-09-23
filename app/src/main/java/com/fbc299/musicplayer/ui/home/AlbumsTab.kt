package com.fbc299.musicplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.AlbumInfo
import com.fbc299.musicplayer.playback.rememberPlayerState
import com.fbc299.musicplayer.ui.CoverImage
import com.fbc299.musicplayer.ui.LocalScrapedCovers

/**
 * 专辑 Tab（P8b-1 沉浸式，分层自底向上）：
 * 1) 顶部 ~280dp 封面 hero（当前播放封面 → 首个专辑封面；取不到回退深色渐变 + 音符水印）
 * 2) 黑 0.35 遮罩（白字可读）
 * 3) 顶部下压渐变衔接标题栏 + 底部渐变衔接列表
 * 4) 网格专辑卡：封面突出，专辑名白 / 歌曲数白 55
 */
@Composable
fun AlbumsTab(
    albums: List<AlbumInfo>,
    onAlbumClick: (AlbumInfo) -> Unit
) {
    if (albums.isEmpty()) {
        EmptyState(
            icon = {
                Icon(
                    Icons.Default.Album,
                    contentDescription = null,
                    modifier = Modifier.size(72.dp),
                    tint = Color.White.copy(alpha = 0.35f)
                )
            },
            text = stringResource(R.string.albums_empty)
        )
        return
    }

    // hero 取图：优先当前播放封面，其次首个专辑封面
    val playerState = rememberPlayerState()
    val playing = playerState?.currentMediaItem
    val heroLocal: Any? = playing?.mediaMetadata?.artworkUri
        ?: albums.first().artworkUri
    val heroKey: String? = playing?.mediaId
        ?: albums.first().songs.firstOrNull()?.id?.toString()

    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 116.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // hero 需跨满整行（网格默认单项只占一格）
        item(key = "hero", span = { GridItemSpan(maxLineSpan) }) {
            AlbumsHero(localModel = heroLocal, coverKey = heroKey)
        }
        items(albums, key = { it.albumId }) { album ->
            AlbumCell(album = album, onClick = { onAlbumClick(album) })
        }
    }
}

/**
 * 顶部封面 hero（同播放页海报层做法）：深色渐变 + 音符水印兜底 → 封面 Crop 铺满 →
 * 黑遮罩 → 顶/底双向下压渐变（上接标题栏、下接网格列表）
 */
@Composable
private fun AlbumsHero(localModel: Any?, coverKey: String?) {
    val scrapedUrl = coverKey?.let { LocalScrapedCovers.current[it] }
    var stage by remember(localModel, coverKey) { mutableIntStateOf(0) }
    val model: Any? = when {
        stage == 0 && localModel != null -> localModel
        stage <= 1 && scrapedUrl != null -> scrapedUrl
        else -> null
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
    ) {
        // 回退底层：深色渐变 + 音符水印（同播放页）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF1C1C1F),
                            Color(0xFF0F0F10)
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
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
                onError = { if (stage < 2) stage++ }
            )
        }
        // 黑遮罩（保证白字/列表可读）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.35f))
        )
        // 顶部下压渐变：衔接标题栏
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .height(120.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
        // 底部渐变：hero → 网格列表底色
        Box(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .height(140.dp)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF0F0F10)
                        )
                    )
                )
        )
    }
}

@Composable
private fun AlbumCell(album: AlbumInfo, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .clip(RoundedCornerShape(12.dp))
                .background(Color(0xFF1C1C1F)),
            contentAlignment = Alignment.Center
        ) {
            CoverImage(
                localModel = album.artworkUri,
                coverKey = album.songs.firstOrNull()?.id?.toString(),
                modifier = Modifier.fillMaxSize()
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )
        Text(
            text = stringResource(R.string.album_song_count, album.songCount),
            style = MaterialTheme.typography.labelSmall,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

/** 专辑详情头部：大封面 + 专辑名 + 艺术家 + 播放全部（白字体系，深色基底明暗主题都可读） */
@Composable
fun DetailHeader(
    artworkUri: android.net.Uri,
    /** P5 刮削映射键（该专辑/艺术家下首歌的 songId）；无则只走本地+占位 */
    coverKey: String? = null,
    title: String,
    subtitle: String,
    onPlayAll: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        CoverImage(
            localModel = artworkUri,
            coverKey = coverKey,
            modifier = Modifier
                .size(160.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF1C1C1F))
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            color = Color.White
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color = Color.White.copy(alpha = 0.75f)
        )
        Spacer(Modifier.height(12.dp))
        Row(
            modifier = Modifier
                .clip(RoundedCornerShape(24.dp))
                .background(MaterialTheme.colorScheme.primary)
                .clickable(onClick = onPlayAll)
                .padding(horizontal = 24.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Default.PlayArrow,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onPrimary
            )
            Spacer(Modifier.width(6.dp))
            Text(
                text = stringResource(R.string.play_all),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimary
            )
        }
    }
}
