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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Search
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
import com.fbc299.musicplayer.data.Song

/** 歌曲 Tab：顶部搜索栏 + 歌曲列表（支持模糊搜索） */
@Composable
fun SongsTab(
    songs: List<Song>,
    query: String,
    onQueryChange: (String) -> Unit,
    currentMediaId: String?,
    onSongClick: (Int) -> Unit
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
            filtered.isEmpty() && query.isNotBlank() -> NoMatchResult()
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
            val isCurrent = song.id.toString() == currentMediaId
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onSongClick(index) }
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
        item { Spacer(Modifier.height(8.dp)) }
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
        AsyncImage(
            model = song.artworkUri,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize(),
            error = androidx.compose.ui.res.painterResource(
                android.R.drawable.ic_media_play
            )
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

@Composable
private fun NoMatchResult() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Icon(
            Icons.Default.Search,
            contentDescription = null,
            modifier = Modifier.size(56.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f)
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.no_match),
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}
