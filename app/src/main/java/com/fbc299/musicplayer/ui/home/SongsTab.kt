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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
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
import com.fbc299.musicplayer.ui.CoverImage

/**
 * 歌曲 Tab：顶部搜索栏 + 列表。
 * - 搜索词为空：本地全量列表；
 * - 搜索词非空：分段显示 —— 第一段「本地」匹配结果，第二段「在线」聚合搜索结果
 *   （在线段仅在 设置→在线音源 开启且服务器地址非空 时出现）。
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
    onOnlineClick: (OnlineSong) -> Unit
) {
    val filtered = remember(songs, query) {
        if (query.isBlank()) songs
        else songs.filter { song ->
            song.title.contains(query, true) ||
                song.artist.contains(query, true) ||
                song.album.contains(query, true)
        }
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
                onLocalSongClick = { song ->
                    // 索引换回全量列表，保证播放的正是被点击的那首（过滤视图下标≠全量下标）
                    val fullIndex = songs.indexOfFirst { it.id == song.id }
                    if (fullIndex >= 0) onSongClick(fullIndex)
                },
                onOnlineClick = onOnlineClick
            )

            filtered.isEmpty() -> EmptyLibrary()
            else -> SongList(
                songs = filtered,
                currentMediaId = currentMediaId,
                onSongClick = onSongClick
            )
        }
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
            focusedBorderColor = MaterialTheme.colorScheme.primary,
            unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
            focusedContainerColor = MaterialTheme.colorScheme.surface,
            unfocusedContainerColor = MaterialTheme.colorScheme.surface
        )
    )
}

// ————————————————————— 搜索结果分段（本地 / 在线） —————————————————————

@Composable
private fun SearchContent(
    local: List<Song>,
    onlineActive: Boolean,
    onlineResults: List<OnlineSong>,
    onlineLoading: Boolean,
    onlineError: Boolean,
    currentMediaId: String?,
    resolvingOnlineId: String?,
    onLocalSongClick: (Song) -> Unit,
    onOnlineClick: (OnlineSong) -> Unit
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
                            color = MaterialTheme.colorScheme.onSurfaceVariant
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
                        onClick = { onOnlineClick(song) }
                    )
                }
            }
        }

        item(key = "bottom_spacer") { Spacer(Modifier.height(8.dp)) }
    }
}

/** 分段小标题：「本地 12」「在线 20」 */
@Composable
private fun SectionHeader(title: String, count: Int?) {
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
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun MessageText(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

/** 在线搜索结果行：封面（缺图回退云朵）+ 歌名/歌手/专辑 + 来源标；解析直链时显示进度 */
@Composable
private fun OnlineSongRow(
    song: OnlineSong,
    isCurrent: Boolean,
    resolving: Boolean,
    onClick: () -> Unit
) {
    val sourceLabel = when (song.source) {
        "netease" -> stringResource(R.string.source_netease)
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
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                Icons.Default.Cloud,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
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
                    MaterialTheme.colorScheme.onBackground,
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
                color = MaterialTheme.colorScheme.onSurfaceVariant
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
                    .background(MaterialTheme.colorScheme.primaryContainer)
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Default.Cloud,
                    contentDescription = null,
                    modifier = Modifier.size(12.dp),
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Spacer(Modifier.width(3.dp))
                Text(
                    text = sourceLabel,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
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
                    MaterialTheme.colorScheme.onBackground,
                fontWeight = if (isCurrent) FontWeight.SemiBold else FontWeight.Normal
            )
            Text(
                text = song.artist,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Spacer(Modifier.width(8.dp))
        Text(
            text = song.formattedDuration,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SongCover(song: Song, size: Dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
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
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.empty_library),
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = stringResource(R.string.empty_hint),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
