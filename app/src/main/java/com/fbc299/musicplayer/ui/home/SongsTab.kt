package com.fbc299.musicplayer.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.OnlineSong
import com.fbc299.musicplayer.data.Song
import com.fbc299.musicplayer.data.favoriteKey
import com.fbc299.musicplayer.ui.CoverImage

/**
 * 歌曲 Tab：顶部搜索栏 + 列表。
 * - 搜索词为空：本地全量列表（P12 起若已有收藏，其上方多一段「收藏」）；
 * - 搜索词非空：分段显示 —— 第一段「本地」匹配结果，第二段「在线」聚合搜索结果
 *   （在线段仅在 设置→在线音源 开启且服务器地址非空 时出现），
 *   第三段「收藏」P12 新增（收藏越早越靠前，段头带「播放全部」）。
 *
 * P12 收藏：在线行/收藏行尾部爱心 toggle，收藏行点击走与在线行**同一个** onOnlineClick
 *（含 P9 试听换源的点播流程，由 HomeScreen 传入，不在此处复制解析逻辑）。
 */
@Composable
fun SongsTab(
    songs: List<Song>,
    query: String,
    onQueryChange: (String) -> Unit,
    currentMediaId: String?,
    onSongClick: (Int) -> Unit,
    onlineActive: Boolean,
    onlineResults: List<OnlineSong>,
    onlineLoading: Boolean,
    onlineError: Boolean,
    resolvingOnlineId: String?,
    onOnlineClick: (OnlineSong) -> Unit,
    favorites: List<OnlineSong>,
    favoriteKeys: Set<String>,
    onToggleFavorite: (OnlineSong) -> Unit,
    onPlayFavorites: () -> Unit
) {
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter { song ->
            song.title.contains(query, true) ||
                song.artist.contains(query, true) ||
                song.album.contains(query, true)
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 沉浸锚点（同播放页无图回退）：深色渐变 + 音符水印，压在标题/搜索条与列表区背后
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

        Column(modifier = Modifier.fillMaxSize()) {
            SearchField(query = query, onQueryChange = onQueryChange)
            when {
                query.isNotBlank() -> SearchContent(
                    local = filtered,
                    onlineActive = onlineActive,
                    onlineResults = onlineResults,
                    onlineLoading = onlineLoading,
                    onlineError = onlineError,
                    currentMediaId = currentMediaId,
                    resolvingOnlineId = resolvingOnlineId,
                    favorites = favorites,
                    favoriteKeys = favoriteKeys,
                    onLocalSongClick = { song ->
                        // 索引换回全量列表，保证播放的正是被点击的那首（过滤视图下标≠全量下标）
                        val fullIndex = songs.indexOfFirst { it.id == song.id }
                        if (fullIndex >= 0) onSongClick(fullIndex)
                    },
                    onOnlineClick = onOnlineClick,
                    onToggleFavorite = onToggleFavorite,
                    onPlayFavorites = onPlayFavorites
                )

                // P12：无收藏时与原行为完全一致（空库空态 / 本地全量列表）
                favorites.isEmpty() -> if (filtered.isEmpty()) EmptyLibrary()
                else SongList(
                    songs = filtered,
                    currentMediaId = currentMediaId,
                    onSongClick = onSongClick
                )

                // P12：搜索词为空但已有收藏 —— 「收藏」分段在上，本地全量列表在下
                else -> SongListWithFavorites(
                    favorites = favorites,
                    local = filtered,
                    currentMediaId = currentMediaId,
                    resolvingOnlineId = resolvingOnlineId,
                    onOnlineClick = onOnlineClick,
                    onToggleFavorite = onToggleFavorite,
                    onPlayFavorites = onPlayFavorites,
                    onLocalClick = onSongClick
                )
            }
        }

        // 底部 scrim：贴 mini player/底栏上方，透明 → 黑 0.85 压底
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

@Composable
private fun SearchField(
    query: String,
    onQueryChange: (String) -> Unit
) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        placeholder = {
            Text(
                text = stringResource(R.string.search_hint),
                style = MaterialTheme.typography.bodyMedium
            )
        },
        leadingIcon = {
            Icon(Icons.Default.Search, contentDescription = null)
        },
        trailingIcon = {
            if (query.isNotEmpty()) {
                IconButton(onClick = { onQueryChange("") }) {
                    Icon(
                        Icons.Default.Close,
                        contentDescription = stringResource(R.string.clear_search)
                    )
                }
            }
        },
        singleLine = true,
        shape = RoundedCornerShape(24.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = Color.White,
            unfocusedTextColor = Color.White,
            cursorColor = MaterialTheme.colorScheme.primary,
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = Color.White.copy(alpha = 0.25f),
            focusedContainerColor = Color.Black.copy(alpha = 0.35f),
            unfocusedContainerColor = Color.Black.copy(alpha = 0.25f),
            focusedPlaceholderColor = Color.White.copy(alpha = 0.55f),
            unfocusedPlaceholderColor = Color.White.copy(alpha = 0.55f),
            focusedLeadingIconColor = Color.White.copy(alpha = 0.75f),
            unfocusedLeadingIconColor = Color.White.copy(alpha = 0.55f),
            focusedTrailingIconColor = Color.White.copy(alpha = 0.75f),
            unfocusedTrailingIconColor = Color.White.copy(alpha = 0.75f)
        )
    )
}

// ————————————————————— 搜索结果分段（本地 / 在线 / 收藏） —————————————————————

@Composable
private fun SearchContent(
    local: List<Song>,
    onlineActive: Boolean,
    onlineResults: List<OnlineSong>,
    onlineLoading: Boolean,
    onlineError: Boolean,
    currentMediaId: String?,
    resolvingOnlineId: String?,
    favorites: List<OnlineSong>,
    favoriteKeys: Set<String>,
    onLocalSongClick: (Song) -> Unit,
    onOnlineClick: (OnlineSong) -> Unit,
    onToggleFavorite: (OnlineSong) -> Unit,
    onPlayFavorites: () -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        // —— 第一段：本地 ——
        item(key = "h_local") {
            SectionHeader(title = stringResource(R.string.section_local), count = local.size)
        }
        if (local.isEmpty()) {
            item(key = "local_empty") {
                MessageText(text = stringResource(R.string.no_match))
            }
        } else {
            items(local, key = { "local_${it.id}" }) { song ->
                SongRow(
                    song = song,
                    isCurrent = song.id.toString() == currentMediaId,
                    onClick = { onLocalSongClick(song) }
                )
            }
        }

        // —— 第二段：在线（开关关闭或地址为空时整段不出现） ——
        if (onlineActive) {
            item(key = "h_online") {
                SectionHeader(
                    title = stringResource(R.string.section_online),
                    count = onlineResults.size.takeIf { !onlineLoading && !onlineError && it > 0 }
                )
            }
            when {
                onlineLoading -> item(key = "online_loading") {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            text = stringResource(R.string.online_loading),
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }

                onlineError -> item(key = "online_error") {
                    MessageText(text = stringResource(R.string.online_search_failed))
                }

                onlineResults.isEmpty() -> item(key = "online_empty") {
                    MessageText(text = stringResource(R.string.online_no_results))
                }

                else -> items(onlineResults, key = { it.mediaId }) { song ->
                    OnlineSongRow(
                        song = song,
                        isCurrent = song.mediaId == currentMediaId,
                        resolving = song.mediaId == resolvingOnlineId,
                        onClick = { onOnlineClick(song) },
                        favorite = favoriteKeys.contains(
                            favoriteKey(song.source, song.sourceId)
                        ),
                        onToggleFavorite = { onToggleFavorite(song) }
                    )
                }
            }
        }

        // —— 第三段：收藏（P12；复用在线行与同一条 onOnlineClick 点播流程）——
        item(key = "h_fav") {
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
                    favorite = true,
                    onToggleFavorite = { onToggleFavorite(song) }
                )
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

/**
 * P12 搜索词为空时的默认视图：「收藏」分段（含段头播放全部）在上，本地全量列表在下。
 * 收藏行点击同样走 onOnlineClick —— 与搜索结果里的在线行是同一个点播流程。
 */
@Composable
private fun SongListWithFavorites(
    favorites: List<OnlineSong>,
    local: List<Song>,
    currentMediaId: String?,
    resolvingOnlineId: String?,
    onOnlineClick: (OnlineSong) -> Unit,
    onToggleFavorite: (OnlineSong) -> Unit,
    onPlayFavorites: () -> Unit,
    onLocalClick: (Int) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item(key = "h_fav") {
            SectionHeader(
                title = stringResource(R.string.favorites),
                count = favorites.size,
                onPlayAll = onPlayFavorites
            )
        }
        items(favorites, key = { favoriteKey(it.source, it.sourceId) }) { song ->
            OnlineSongRow(
                song = song,
                isCurrent = song.mediaId == currentMediaId,
                resolving = song.mediaId == resolvingOnlineId,
                onClick = { onOnlineClick(song) },
                favorite = true,
                onToggleFavorite = { onToggleFavorite(song) }
            )
        }
        // 收藏段之上加了分段头，本地段也补一个头，避免两段视觉黏连
        item(key = "h_local") {
            SectionHeader(title = stringResource(R.string.section_local), count = local.size)
        }
        items(local.withIndex().toList(), key = { it.value.id }) { (index, song) ->
            SongRow(
                song = song,
                isCurrent = song.id.toString() == currentMediaId,
                onClick = { onLocalClick(index) }
            )
        }
        item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

/** 分段小标题：「本地 12」「在线 20」；P12 收藏段在尾部追加「播放全部」按钮 */
@Composable
private fun SectionHeader(title: String, count: Int?, onPlayAll: (() -> Unit)? = null) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary
        )
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(
                text = "$count",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        if (onPlayAll != null) {
            Spacer(Modifier.weight(1f))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onPlayAll)
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(2.dp))
                Text(
                    text = stringResource(R.string.play_all),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

/** P12 收藏分段空态：音符水印 + 「还没有收藏」提示 */
@Composable
private fun FavoritesEmpty() {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = Color.White.copy(alpha = 0.10f)
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.favorites_empty),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

@Composable
private fun MessageText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = Color.White.copy(alpha = 0.55f),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** 在线搜索结果行：封面（缺图回退云朵）+ 歌名/歌手/专辑 + 来源标；解析直链时显示进度。
 *  P12 尾部追加收藏爱心：图标自身的 clickable 先消费点按，不会冒泡触发行的点播。 */
@Composable
private fun OnlineSongRow(
    song: OnlineSong,
    isCurrent: Boolean,
    resolving: Boolean,
    onClick: () -> Unit,
    favorite: Boolean = false,
    onToggleFavorite: (() -> Unit)? = null
) {
    val sourceLabel = when (song.source) {
        "migu" -> stringResource(R.string.source_migu)
        "netease" -> stringResource(R.string.source_netease)
        "kuwo" -> stringResource(R.string.source_kuwo)
        "qq" -> stringResource(R.string.source_qq)
        else -> song.source
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !resolving, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 封面：URL 为空/加载失败时露出底层云朵图标
        Box(
            modifier = Modifier
                .size(48.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Color(0xFF1C1C1F)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = Color.White.copy(alpha = 0.35f)
            )
            if (song.artworkUrl != null) {
                AsyncImage(
                    model = song.artworkUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent)
                    MaterialTheme.colorScheme.primary
                else
                    Color.White,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
            )
            val subline = listOf(song.artist, song.album)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            Text(
                text = subline,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.width(8.dp))
        if (resolving) {
            CircularProgressIndicator(
                modifier = Modifier.size(18.dp),
                strokeWidth = 2.dp
            )
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = Color.White.copy(alpha = 0.75f)
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
        }
        // P12 收藏爱心（来源标之后）：未藏 FavoriteBorder 白55，已藏 Favorite primary 实心
        if (onToggleFavorite != null) {
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    imageVector = if (favorite) Icons.Filled.Favorite
                    else Icons.Outlined.FavoriteBorder,
                    contentDescription = stringResource(
                        if (favorite) R.string.favorite_remove else R.string.favorite_add
                    ),
                    modifier = Modifier.size(22.dp),
                    tint = if (favorite)
                        MaterialTheme.colorScheme.primary
                    else
                        Color.White.copy(alpha = 0.55f)
                )
            }
        }
    }
}

// ————————————————————— 通用列表（歌曲 Tab 与专辑/艺术家详情页共用） —————————————————————

/** 通用歌曲列表（歌曲 Tab 与专辑/艺术家详情页共用） */
@Composable
fun SongList(
    songs: List<Song>,
    currentMediaId: String?,
    onSongClick: (Int) -> Unit,
    header: (@Composable () -> Unit)? = null
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        if (header != null) {
            item(key = "header") { header() }
        }
        items(songs.withIndex().toList(), key = { it.value.id }) { (index, song) ->
            SongRow(
                song = song,
                isCurrent = song.id.toString() == currentMediaId,
                onClick = { onSongClick(index) }
            )
        }
        item { Spacer(Modifier.height(8.dp)) }
    }
}

/** 本地歌曲行：封面 + 标题/艺术家 + 时长 */
@Composable
private fun SongRow(
    song: Song,
    isCurrent: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SongCover(song = song, size = 48.dp)
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = if (isCurrent)
                    MaterialTheme.colorScheme.primary
                else
                    Color.White,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = Color.White.copy(alpha = 0.55f)
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = song.formattedDuration,
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}

@Composable
fun SongCover(song: Song, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF1C1C1F)),
        contentAlignment = Alignment.Center
    ) {
        CoverImage(
            localModel = song.artworkUri,
            coverKey = song.id.toString(),
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun EmptyLibrary() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            modifier = Modifier.size(72.dp),
            tint = Color.White.copy(alpha = 0.55f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_library),
            style = MaterialTheme.typography.titleLarge,
            color = Color.White
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = Color.White.copy(alpha = 0.55f)
        )
    }
}
