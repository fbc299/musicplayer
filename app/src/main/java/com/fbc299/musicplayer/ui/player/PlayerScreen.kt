package com.fbc299.musicplayer.ui.player

import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.Player
import coil.compose.AsyncImage
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.LrcParser
import com.fbc299.musicplayer.data.LyricLine
import com.fbc299.musicplayer.data.ONLINE_MEDIA_ID_PREFIX
import com.fbc299.musicplayer.data.OnlineMusicSource
import com.fbc299.musicplayer.data.SettingsRepository
import com.fbc299.musicplayer.playback.PlayerConnection
import com.fbc299.musicplayer.playback.PlayerState
import com.fbc299.musicplayer.playback.RepeatMode
import com.fbc299.musicplayer.playback.StartPositionTicker
import com.fbc299.musicplayer.playback.rememberPlayerState
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(onClose: () -> Unit) {
    val state = rememberPlayerState()
    StartPositionTicker(state)
    val context = LocalContext.current

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    colors = listOf(
                        MaterialTheme.colorScheme.surfaceVariant,
                        MaterialTheme.colorScheme.background
                    )
                )
            )
    ) {
        val item = state?.currentMediaItem
        if (state == null || item == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    text = stringResource(R.string.no_playback),
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(
                onClick = onClose,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .statusBarsPadding()
                    .padding(4.dp)
            ) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = stringResource(R.string.collapse),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            return
        }

        val meta = item.mediaMetadata

        // 歌词：本地曲目读同目录 .lrc；在线曲目（mediaId 前缀 "on_"）拉聚合服务歌词
        val mediaId = item.mediaId
        var lyricLines by remember(mediaId) { mutableStateOf<List<LyricLine>>(emptyList()) }
        var lyricLoaded by remember(mediaId) { mutableStateOf(false) }
        LaunchedEffect(mediaId) {
            lyricLoaded = false
            lyricLines = if (mediaId.startsWith(ONLINE_MEDIA_ID_PREFIX)) {
                val rest = mediaId.removePrefix(ONLINE_MEDIA_ID_PREFIX)
                val source = rest.substringBefore('_', "")
                val id = rest.substringAfter('_', "")
                if (source.isEmpty() || id.isEmpty()) {
                    emptyList()
                } else {
                    val serverResult = runCatching {
                        SettingsRepository(context).onlineSettings.first().serverUrl
                    }
                    ensureActive()
                    val server = serverResult.getOrNull().orEmpty()
                    val lyricResult = if (server.isBlank()) null
                    else runCatching { OnlineMusicSource.lyric(server, source, id) }
                    ensureActive()
                    val lrc = lyricResult?.getOrNull()
                    if (lrc.isNullOrBlank()) emptyList() else LrcParser.parseLrc(lrc)
                }
            } else {
                LrcParser.loadLyrics(context, mediaId.toLongOrNull() ?: -1L)
            }
            lyricLoaded = true
        }

        // 播放队列弹层
        var showQueue by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // 顶栏：收起 + 播放队列入口
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onClose) {
                    Icon(
                        Icons.Default.KeyboardArrowDown,
                        contentDescription = stringResource(R.string.collapse),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { showQueue = true }) {
                    Icon(
                        Icons.Default.QueueMusic,
                        contentDescription = stringResource(R.string.queue),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(Modifier.height(8.dp))

            // 大封面
            AsyncImage(
                model = meta.artworkUri,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(240.dp)
                    .shadow(
                        elevation = 16.dp,
                        shape = RoundedCornerShape(24.dp),
                        ambientColor = MaterialTheme.colorScheme.primary,
                        spotColor = MaterialTheme.colorScheme.primary
                    )
                    .clip(RoundedCornerShape(24.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
            )

            Spacer(Modifier.height(20.dp))

            // 歌名 + 艺术家
            Text(
                text = meta.title?.toString()
                    ?: stringResource(R.string.unknown_song),
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onBackground,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = meta.artist?.toString() ?: "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )

            Spacer(Modifier.height(12.dp))

            // 歌词区域（按进度高亮并滚动居中）
            LyricSection(
                state = state,
                lines = lyricLines,
                loaded = lyricLoaded,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp)
            )

            Spacer(Modifier.height(8.dp))

            // 进度条
            SeekBar(state = state)

            Spacer(Modifier.height(8.dp))

            // 控制按钮行
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    PlayerConnection.shuffle(!state.shuffleEnabled)
                }) {
                    Icon(
                        Icons.Default.Shuffle,
                        contentDescription = stringResource(R.string.shuffle),
                        tint = if (state.shuffleEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                IconButton(onClick = { PlayerConnection.previous() }) {
                    Icon(
                        Icons.Default.SkipPrevious,
                        contentDescription = stringResource(R.string.previous),
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                // 播放/暂停 大圆钮
                Box(
                    modifier = Modifier
                        .size(72.dp)
                        .clip(RoundedCornerShape(36.dp))
                        .background(MaterialTheme.colorScheme.primary)
                        .clickable { PlayerConnection.togglePlay() },
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Default.Pause
                        else Icons.Default.PlayArrow,
                        contentDescription = if (state.isPlaying)
                            stringResource(R.string.pause)
                        else
                            stringResource(R.string.play),
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onPrimary
                    )
                }
                IconButton(onClick = { PlayerConnection.next() }) {
                    Icon(
                        Icons.Default.SkipNext,
                        contentDescription = stringResource(R.string.next),
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.onBackground
                    )
                }
                // 循环模式：关闭 → 列表循环 → 单曲循环
                val repeatLabel = when (state.repeatMode) {
                    Player.REPEAT_MODE_ONE -> stringResource(R.string.repeat_one)
                    Player.REPEAT_MODE_ALL -> stringResource(R.string.repeat_list)
                    else -> stringResource(R.string.repeat_off)
                }
                val repeatEnabled = state.repeatMode != Player.REPEAT_MODE_OFF
                IconButton(onClick = {
                    val next = PlayerConnection.cycleRepeatMode()
                    val toast = when (next) {
                        RepeatMode.ALL -> context.getString(R.string.repeat_list)
                        RepeatMode.ONE -> context.getString(R.string.repeat_one)
                        RepeatMode.OFF -> context.getString(R.string.repeat_off)
                    }
                    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                }) {
                    Icon(
                        imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE)
                            Icons.Default.RepeatOne
                        else
                            Icons.Default.Repeat,
                        contentDescription = repeatLabel,
                        tint = if (repeatEnabled)
                            MaterialTheme.colorScheme.primary
                        else
                            MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Spacer(
                Modifier
                    .navigationBarsPadding()
                    .height(12.dp)
            )
        }

        // 播放队列底部弹层
        if (showQueue) {
            ModalBottomSheet(
                onDismissRequest = { showQueue = false },
                sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
                containerColor = MaterialTheme.colorScheme.surface
            ) {
                QueueSheet(state = state, onDismiss = { showQueue = false })
            }
        }
    }
}

/** 播放队列：列出全部曲目，当前曲目高亮，点击跳转 */
@Composable
private fun QueueSheet(state: PlayerState, onDismiss: () -> Unit) {
    val queue = state.mediaItems

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(bottom = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.queue),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = stringResource(R.string.queue_count, queue.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(4.dp))
            IconButton(onClick = onDismiss) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = stringResource(R.string.close),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)

        if (queue.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(140.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.queue_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 420.dp)
            ) {
                itemsIndexed(queue) { index, media ->
                    val isCurrent = index == state.currentIndex
                    val title = media.mediaMetadata.title?.toString()
                        ?: stringResource(R.string.unknown_song)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                PlayerConnection.jumpTo(index)
                                onDismiss()
                            }
                            .padding(horizontal = 20.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier.size(24.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            if (isCurrent) {
                                Icon(
                                    Icons.Default.PlayArrow,
                                    contentDescription = stringResource(R.string.queue_item_playing),
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.primary
                                )
                            } else {
                                Text(
                                    text = "${index + 1}",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = title,
                                style = MaterialTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = if (isCurrent)
                                    MaterialTheme.colorScheme.primary
                                else
                                    MaterialTheme.colorScheme.onBackground,
                                fontWeight = if (isCurrent) FontWeight.SemiBold
                                else FontWeight.Normal
                            )
                            Text(
                                text = media.mediaMetadata.artist?.toString() ?: "",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 歌词区：无 .lrc 显示「暂无歌词」；有则按进度高亮当前行并滚动到中部。
 * 点击任意歌词行可跳转到对应时间。
 */
@Composable
private fun LyricSection(
    state: PlayerState,
    lines: List<LyricLine>,
    loaded: Boolean,
    modifier: Modifier = Modifier
) {
    if (lines.isEmpty()) {
        Box(modifier = modifier, contentAlignment = Alignment.Center) {
            Text(
                text = if (loaded) stringResource(R.string.no_lyrics) else "",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
        return
    }

    val listState = rememberLazyListState()
    val density = LocalDensity.current
    var viewportHeight by remember { mutableIntStateOf(0) }

    // 最后一个 timeMs <= 当前进度的行即当前行
    val currentLineIndex = remember(lines, state.positionMs) {
        var index = -1
        for (i in lines.indices) {
            if (lines[i].timeMs <= state.positionMs) index = i else break
        }
        index
    }

    LazyColumn(
        state = listState,
        modifier = modifier.onSizeChanged { viewportHeight = it.height },
        horizontalAlignment = Alignment.CenterHorizontally,
        contentPadding = PaddingValues(vertical = 4.dp)
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == currentLineIndex
            Text(
                text = line.text,
                style = MaterialTheme.typography.bodyLarge,
                fontSize = 16.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active)
                    MaterialTheme.colorScheme.primary
                else
                    MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { PlayerConnection.seekTo(line.timeMs) }
                    .padding(horizontal = 24.dp, vertical = 6.dp)
            )
        }
    }

    // 当前行滚动到歌词区中部
    LaunchedEffect(currentLineIndex, viewportHeight) {
        if (currentLineIndex >= 0 &&
            viewportHeight > 0 &&
            currentLineIndex < listState.layoutInfo.totalItemsCount
        ) {
            val linePx = with(density) { 34.dp.toPx() }.toInt()
            val offset = (viewportHeight / 2 - linePx / 2).coerceAtLeast(0)
            listState.animateScrollToItem(currentLineIndex, offset)
        }
    }
}

@Composable
private fun SeekBar(state: PlayerState) {
    val duration = state.durationMs
    var scrubbing by remember { mutableFloatStateOf(-1f) }
    val progress = if (scrubbing >= 0f) scrubbing
    else if (duration > 0) state.positionMs.toFloat() / duration
    else 0f

    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp)) {
        Slider(
            value = progress.coerceIn(0f, 1f),
            onValueChange = { scrubbing = it },
            onValueChangeFinished = {
                if (duration > 0 && scrubbing >= 0f) {
                    PlayerConnection.seekTo((scrubbing * duration).toLong())
                }
                scrubbing = -1f
            },
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
            )
        )
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            val shownPos = if (scrubbing >= 0f && duration > 0)
                (scrubbing * duration).toLong()
            else state.positionMs
            Text(
                text = formatMs(shownPos),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = if (duration > 0) formatMs(duration) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
