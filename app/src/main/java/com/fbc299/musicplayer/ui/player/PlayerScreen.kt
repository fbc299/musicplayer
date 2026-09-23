package com.fbc299.musicplayer.ui.player

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
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
import com.fbc299.musicplayer.ui.LocalScrapedCovers
import kotlin.math.abs
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.first

fun formatMs(ms: Long): String {
    val totalSec = ms / 1000
    return "%d:%02d".format(totalSec / 60, totalSec % 60)
}

/**
 * P7 沉浸式播放页（分层，自底向上）：
 * 1) 海报封面 fillMaxSize + Crop 铺满全屏（无封面回退深色渐变 + 音符水印）
 * 2) 全屏半透明黑色遮罩（保证白字可读）
 * 3) 顶部下压渐变（给返回/队列栏）
 * 4) 底部约 45% 高度「透明 → 黑 0.85」渐变 scrim（控件悬浮其上）
 * 5) 内容层：顶栏 / 歌词（海报中层）/ 歌名歌手专辑 + 进度条 + 控制行（底部）
 * 6) 播放队列底部弹层
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onClose: () -> Unit,
    /**
     * P12 收藏爱心：favorite = 当前曲是否已收藏（null = 本地曲/无法判定）；
     * onToggleFavorite = 切换回调（null = 本地曲，不显示爱心）。
     * 两者由组合方（MainActivity）按 mediaId 前缀 on_ 判定后传入。
     */
    favorite: Boolean? = null,
    onToggleFavorite: (() -> Unit)? = null
) {
    val state = rememberPlayerState()
    // P11：播放页是覆盖层（MainActivity 仅在 playerOpen 时组合它），系统返回必须被本页消费，
    // 否则无人处理 → Activity finish = 整个 App 退出。这里改为「收起」：回主界面，播放不中断；
    // 主界面再按返回才是系统默认的退出。
    BackHandler { onClose() }
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
            // 无播放内容空态（保持原状）
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
        } else {
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

            // ── 第 1 层：海报铺满全屏 ──
            PosterBackground(artworkUri = meta.artworkUri, coverKey = mediaId)

            // ── 第 2 层：全屏半透明黑色遮罩（可读性）──
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.35f))
            )

            // ── 第 3 层：顶部下压渐变（返回栏）──
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .fillMaxWidth()
                    .height(140.dp)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Black.copy(alpha = 0.5f),
                                Color.Transparent
                            )
                        )
                    )
            )

            // ── 第 4 层：底部渐变 scrim（控件区）──
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .fillMaxHeight(0.45f)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(
                                Color.Transparent,
                                Color.Black.copy(alpha = 0.85f)
                            )
                        )
                    )
            )

            // ── 第 5 层：内容层（顶栏 + 歌词中层 + 底部控件）──
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .statusBarsPadding()
            ) {
                // 顶栏：收起 + 收藏爱心（P12，仅在线曲显示）+ 播放队列入口
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
                            tint = Color.White
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    // P12 收藏爱心：本地曲 favorite/onToggleFavorite 传 null → 整块不显示
                    if (favorite != null && onToggleFavorite != null) {
                        IconButton(onClick = onToggleFavorite) {
                            Icon(
                                imageVector = if (favorite) Icons.Filled.Favorite
                                else Icons.Outlined.FavoriteBorder,
                                contentDescription = stringResource(
                                    if (favorite) R.string.favorite_remove
                                    else R.string.favorite_add
                                ),
                                tint = if (favorite)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.White.copy(alpha = 0.55f)
                            )
                        }
                    }
                    IconButton(onClick = { showQueue = true }) {
                        Icon(
                            Icons.Default.QueueMusic,
                            contentDescription = stringResource(R.string.queue),
                            tint = Color.White
                        )
                    }
                }

                // 歌词区（海报中层，按进度高亮并滚动居中）
                LyricSection(
                    state = state,
                    lines = lyricLines,
                    loaded = lyricLoaded,
                    mediaId = mediaId,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                )

                // ── 底部控件区（悬浮于底部 scrim 上，白字）──
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // 歌名（白字，大一号）
                    Text(
                        text = meta.title?.toString()
                            ?: stringResource(R.string.unknown_song),
                        style = MaterialTheme.typography.headlineSmall,
                        color = Color.White,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    Spacer(Modifier.height(4.dp))
                    // 歌手
                    Text(
                        text = meta.artist?.toString() ?: "",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color.White.copy(alpha = 0.75f),
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                    // 专辑（有则显示）
                    val album = meta.albumTitle?.toString()
                    if (!album.isNullOrBlank()) {
                        Spacer(Modifier.height(2.dp))
                        Text(
                            text = album,
                            style = MaterialTheme.typography.bodySmall,
                            color = Color.White.copy(alpha = 0.55f),
                            textAlign = TextAlign.Center,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.padding(horizontal = 24.dp)
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    // 进度条 + 时间
                    SeekBar(state = state)

                    Spacer(Modifier.height(4.dp))

                    // 控制按钮行（P11 焕新）：40dp 白9% 圆底开关钮 / 48dp 玻璃圆 上下曲 /
                    // 64dp primary 圆主钮（Crossfade 200ms 切图标）。功能、文案完全不变。
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 随机：40dp 白9% 圆底 + 20dp 图标（未激活 白55 / 激活 primary）
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.09f))
                                .clickable { PlayerConnection.shuffle(!state.shuffleEnabled) },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = stringResource(R.string.shuffle),
                                modifier = Modifier.size(20.dp),
                                tint = if (state.shuffleEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.White.copy(alpha = 0.55f)
                            )
                        }
                        // 上一首：48dp 玻璃圆底（白12%）+ 26dp 纯白图标
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { PlayerConnection.previous() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.SkipPrevious,
                                contentDescription = stringResource(R.string.previous),
                                modifier = Modifier.size(26.dp),
                                tint = Color.White
                            )
                        }
                        // 播放/暂停 主钮：64dp primary 正圆 + 8dp 微投影 + 32dp onPrimary 图标
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .shadow(elevation = 8.dp, shape = CircleShape)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary)
                                .clickable { PlayerConnection.togglePlay() },
                            contentAlignment = Alignment.Center
                        ) {
                            Crossfade(
                                targetState = state.isPlaying,
                                animationSpec = tween(durationMillis = 200),
                                label = "playPause"
                            ) { playing ->
                                Icon(
                                    imageVector = if (playing) Icons.Default.Pause
                                    else Icons.Default.PlayArrow,
                                    contentDescription = if (playing)
                                        stringResource(R.string.pause)
                                    else
                                        stringResource(R.string.play),
                                    modifier = Modifier.size(32.dp),
                                    tint = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                        }
                        // 下一首：同上一首玻璃圆
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.12f))
                                .clickable { PlayerConnection.next() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Default.SkipNext,
                                contentDescription = stringResource(R.string.next),
                                modifier = Modifier.size(26.dp),
                                tint = Color.White
                            )
                        }
                        // 循环模式：关闭 → 列表循环 → 单曲循环（40dp 白9% 圆底，Toast 提示不变）
                        val repeatLabel = when (state.repeatMode) {
                            Player.REPEAT_MODE_ONE -> stringResource(R.string.repeat_one)
                            Player.REPEAT_MODE_ALL -> stringResource(R.string.repeat_list)
                            else -> stringResource(R.string.repeat_off)
                        }
                        val repeatEnabled = state.repeatMode != Player.REPEAT_MODE_OFF
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(Color.White.copy(alpha = 0.09f))
                                .clickable {
                                    val next = PlayerConnection.cycleRepeatMode()
                                    val toast = when (next) {
                                        RepeatMode.ALL -> context.getString(R.string.repeat_list)
                                        RepeatMode.ONE -> context.getString(R.string.repeat_one)
                                        RepeatMode.OFF -> context.getString(R.string.repeat_off)
                                    }
                                    Toast.makeText(context, toast, Toast.LENGTH_SHORT).show()
                                },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = if (state.repeatMode == Player.REPEAT_MODE_ONE)
                                    Icons.Default.RepeatOne
                                else
                                    Icons.Default.Repeat,
                                contentDescription = repeatLabel,
                                modifier = Modifier.size(20.dp),
                                tint = if (repeatEnabled)
                                    MaterialTheme.colorScheme.primary
                                else
                                    Color.White.copy(alpha = 0.55f)
                            )
                        }
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
}

/**
 * P7 海报层：封面 fillMaxSize + Crop 铺满全屏。
 * 封面链路与 CoverImage 三级 fallback 一致：本地 artworkUri → 刮削 URL → 占位；
 * 全部失败 / 无封面时回落到深色渐变 + 放大音符水印（不再绘制满屏占位图标）。
 */
@Composable
private fun PosterBackground(artworkUri: Uri?, coverKey: String) {
    val scrapedUrl = LocalScrapedCovers.current[coverKey]
    var stage by remember(coverKey, artworkUri) { mutableIntStateOf(0) }
    val model: Any? = when {
        stage == 0 && artworkUri != null -> artworkUri
        stage <= 1 && scrapedUrl != null -> scrapedUrl
        else -> null
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // 占位底层：深色渐变 + 音符水印
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
 * 歌词区（海报中层浮块）：无 .lrc 显示「暂无歌词」；有则按进度高亮当前行并滚动到中部。
 * 点击任意歌词行可跳转到对应时间。
 * 海报背景上统一用白色系保证明暗主题下都可读。
 *
 * P9：块头部带手动偏移控件（±1 秒步进 + 重置）。在线歌词时间轴与实际音频版本
 * 常常对不上（VIP 试听片段 30s vs 歌词 4 分钟、不同专辑版本），逐句漂移无法靠
 * 自动对齐解决，手动偏移是这类问题的工业标准解法。偏移是会话级状态，换歌归零。
 */
@Composable
private fun LyricSection(
    state: PlayerState,
    lines: List<LyricLine>,
    loaded: Boolean,
    mediaId: String,
    modifier: Modifier = Modifier
) {
    // 歌词偏移（会话级，换歌归零）：正数=歌词提前显示，负数=延后
    var lyricOffsetMs by remember(mediaId) { mutableStateOf(0L) }

    Column(modifier = modifier) {
        // 头部：− 歌词偏移 N 秒 + / 重置（白55 小字 + primary 图标钮，融入沉浸布局）
        LyricOffsetBar(
            offsetMs = lyricOffsetMs,
            onStep = { delta -> lyricOffsetMs += delta },
            onReset = { lyricOffsetMs = 0L }
        )

        if (lines.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (loaded) stringResource(R.string.no_lyrics) else "",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.6f)
                )
            }
        } else {
            val listState = rememberLazyListState()
            var viewportHeight by remember { mutableIntStateOf(0) }

            // 高亮判定用「进度 + 手动偏移」：偏移为正 → effective 更大 → 歌词行更早点亮（提前）
            val effectiveMs = state.positionMs + lyricOffsetMs

            // 最后一个 timeMs <= effective 的行即当前行
            val currentLineIndex = remember(lines, effectiveMs) {
                var index = -1
                for (i in lines.indices) {
                    if (lines[i].timeMs <= effectiveMs) index = i else break
                }
                index
            }

            LazyColumn(
                state = listState,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .onSizeChanged { viewportHeight = it.height },
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
                            Color.White
                        else
                            Color.White.copy(alpha = 0.6f),
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { PlayerConnection.seekTo(line.timeMs) }
                            .padding(horizontal = 24.dp, vertical = 6.dp)
                    )
                }
            }

            // 当前行滚动到歌词区中部
            // 旧 bug：animateScrollToItem 的 scrollOffset 官方语义是「正数=向前滚=把 item
            // 进一步上移」，曾传入半屏正数，导致正在播放的高亮行被顶出屏幕上沿；
            // 且行高硬编码 34dp，歌词换行时（60-80dp）居中必然偏。
            // 现改为：先粗定位到视口内，再用 layoutInfo 的真实 item 几何计算 delta，
            // animateScrollBy（正数=内容上移）把「当前行中心」精确滚到「视口中心」。
            LaunchedEffect(currentLineIndex, viewportHeight) {
                if (currentLineIndex < 0 || viewportHeight <= 0) return@LaunchedEffect

                // 1) 目标不在视口内 → 先粗定位到顶部（挂起返回后 layoutInfo 已更新）
                if (listState.layoutInfo.visibleItemsInfo.none { it.index == currentLineIndex }) {
                    listState.animateScrollToItem(currentLineIndex)
                }

                // 2) 用真实 item 尺寸把「当前行中心」滚到「视口中心」
                //    scrollBy/animateScrollBy 语义：正数 = 向前 = 内容上移
                val item = listState.layoutInfo.visibleItemsInfo
                    .firstOrNull { it.index == currentLineIndex }
                    ?: return@LaunchedEffect
                val delta = (item.offset + item.size / 2f) - viewportHeight / 2f
                if (abs(delta) > 1f) listState.animateScrollBy(delta)
            }
        }
    }
}

/** 歌词偏移步长：每点一次 ±1000ms（1 秒） */
private const val LYRIC_OFFSET_STEP_MS = 1000L

/**
 * P9 歌词手动偏移控件：`−` 歌词偏移 N 秒 `+` 「重置」。
 * 偏移语义：effective = 进度 + offset，正 offset 使歌词提前显示、负 offset 使歌词延后；
 * 按钮方向与显示值一致（`+` 增大显示值=歌词提前，`−` 减小显示值=歌词延后）。
 */
@Composable
private fun LyricOffsetBar(
    offsetMs: Long,
    onStep: (Long) -> Unit,
    onReset: () -> Unit
) {
    val seconds = offsetMs / LYRIC_OFFSET_STEP_MS
    val shownOffset = when {
        seconds > 0 -> stringResource(R.string.lyric_offset_pos, seconds)
        seconds < 0 -> stringResource(R.string.lyric_offset_neg, -seconds)
        else -> stringResource(R.string.lyric_offset_zero)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        IconButton(
            onClick = { onStep(-LYRIC_OFFSET_STEP_MS) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Remove,
                contentDescription = stringResource(R.string.lyric_offset_later),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = stringResource(R.string.lyric_offset_label, shownOffset),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier.padding(horizontal = 6.dp)
        )
        IconButton(
            onClick = { onStep(LYRIC_OFFSET_STEP_MS) },
            modifier = Modifier.size(32.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Add,
                contentDescription = stringResource(R.string.lyric_offset_earlier),
                modifier = Modifier.size(18.dp),
                tint = MaterialTheme.colorScheme.primary
            )
        }
        Text(
            text = stringResource(R.string.lyric_offset_reset),
            style = MaterialTheme.typography.labelSmall,
            color = Color.White.copy(alpha = 0.55f),
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .clickable(onClick = onReset)
                .padding(horizontal = 8.dp, vertical = 4.dp)
        )
    }
}

/**
 * P11 进度条焕新：已播放段 primary→亮 primary 横向渐变、未播放段白22% 圆角轨道、
 * 17dp（拖动中 20dp）primary 圆拖拽球 + 2dp 白描边。
 * 只改样式（thumb/track 槽位），拖拽状态机 `scrubbing` 与 `PlayerConnection.seekTo` 一行未动。
 * 注：自定义 thumb/track 槽位是 material3 1.3.0 的 @ExperimentalMaterial3Api 重载。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SeekBar(state: PlayerState) {
    val duration = state.durationMs
    var scrubbing by remember { mutableFloatStateOf(-1f) }
    val progress = if (scrubbing >= 0f) scrubbing
    else if (duration > 0) state.positionMs.toFloat() / duration
    else 0f

    // 拖拽球放大动画：scrubbing >= 0 即拖动/点按中。外层固定 20dp 盒子承载，
    // 球体缩放不改变 Slider 的 thumbWidth → 拖动时轨道与滑块零跳动。
    val ballSize by animateDpAsState(
        targetValue = if (scrubbing >= 0f) 20.dp else 17.dp,
        animationSpec = tween(durationMillis = 160),
        label = "seekThumb"
    )
    val primary = MaterialTheme.colorScheme.primary

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
            // 拖拽球：primary 实心 + 2dp 白描边；固定 20dp 外盒（Slider 用它的宽度算 thumbWidth）
            thumb = {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(ballSize)
                            .clip(CircleShape)
                            .background(primary)
                            .border(width = 2.dp, color = Color.White, shape = CircleShape)
                    )
                }
            },
            // 轨道：未播放 白22% 圆角；已播放 primary → 亮 primary（primary 向白插值）渐变
            track = { sliderState ->
                val range = sliderState.valueRange
                val span = range.endInclusive - range.start
                val fraction = if (span > 0f) {
                    ((sliderState.value - range.start) / span).coerceIn(0f, 1f)
                } else 0f
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .background(
                            color = Color.White.copy(alpha = 0.22f),
                            shape = RoundedCornerShape(3.dp)
                        )
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(fraction)
                            .height(6.dp)
                            .background(
                                brush = Brush.horizontalGradient(
                                    colors = listOf(primary, lerp(primary, Color.White, 0.55f))
                                ),
                                shape = RoundedCornerShape(3.dp)
                            )
                    )
                }
            }
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
                color = Color.White.copy(alpha = 0.7f)
            )
            Text(
                text = if (duration > 0) formatMs(duration) else "--:--",
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f)
            )
        }
    }
}
