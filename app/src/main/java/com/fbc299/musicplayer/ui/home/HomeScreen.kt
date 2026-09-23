package com.fbc299.musicplayer.ui.home

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.AlbumInfo
import com.fbc299.musicplayer.data.ArtistInfo
import com.fbc299.musicplayer.data.LibraryViewModel
import com.fbc299.musicplayer.data.OnlineMusicSource
import com.fbc299.musicplayer.data.OnlineSong
import com.fbc299.musicplayer.playback.PlayerConnection
import com.fbc299.musicplayer.playback.PlayerState
import com.fbc299.musicplayer.playback.StartPositionTicker
import com.fbc299.musicplayer.playback.rememberPlayerState
import com.fbc299.musicplayer.ui.CoverImage
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/** 底部导航四个 Tab */
enum class HomeTab { SONGS, ALBUMS, ARTISTS, SETTINGS }

/** P12 收藏「播放全部」：直链解析并发度上限（聚合服务单机，压太高易被限流） */
private const val FAVORITES_RESOLVE_CONCURRENCY = 4

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenPlayer: () -> Unit,
    libraryViewModel: LibraryViewModel = viewModel()
) {
    val songs by libraryViewModel.songs.collectAsState()
    val albums by libraryViewModel.albums.collectAsState()
    val artists by libraryViewModel.artists.collectAsState()
    val loading by libraryViewModel.loading.collectAsState()
    val scanSettings by libraryViewModel.scanSettings.collectAsState()
    // P4 在线音源状态
    val onlineSettings by libraryViewModel.onlineSettings.collectAsState()
    val onlineResults by libraryViewModel.onlineResults.collectAsState()
    // P9 试听换源候选池（去重前的全量结果）
    val onlinePool by libraryViewModel.onlinePool.collectAsState()
    val onlineLoading by libraryViewModel.onlineLoading.collectAsState()
    val onlineError by libraryViewModel.onlineError.collectAsState()
    // P9 刮削进度（设置页展示）
    val scrapeStatus by libraryViewModel.scrapeStatus.collectAsState()
    // P12 收藏（列表 + 去重键集合）
    val favorites by libraryViewModel.favorites.collectAsState()
    val favoriteKeys by libraryViewModel.favoriteKeys.collectAsState()
    // P13 下载进行中集合（favoriteKey）：行内转圈 + 防重复点击
    val downloadingKeys by libraryViewModel.downloading.collectAsState()
    val playerState = rememberPlayerState()
    StartPositionTicker(playerState)

    var tab by remember { mutableStateOf(HomeTab.SONGS) }
    var searchQuery by remember { mutableStateOf("") }
    var selectedAlbumId by remember { mutableStateOf<Long?>(null) }
    var selectedArtistName by remember { mutableStateOf<String?>(null) }

    // 搜索词变化 → 触发在线搜索（开关关闭/地址为空时 VM 内部直接清空，不发请求）
    LaunchedEffect(searchQuery) {
        libraryViewModel.searchOnline(searchQuery)
    }

    // 在线项点击 → 取直链 → 插入队列播放
    var resolvingOnlineId by remember { mutableStateOf<String?>(null) }
    val onlineScope = rememberCoroutineScope()
    val appContext = LocalContext.current
    val onlineActive = onlineSettings.active

    val onOnlineClick: (OnlineSong) -> Unit = { song ->
        if (resolvingOnlineId == null && onlineActive) {
            resolvingOnlineId = song.mediaId
            onlineScope.launch {
                // 原条目解析；换源过程任何异常都被吞掉并回落到这里，绝不让点播失败
                var chosen = song
                var resolved = runCatching {
                    OnlineMusicSource.resolveUrl(
                        onlineSettings.serverUrl,
                        song.source,
                        song.sourceId
                    )
                }.getOrNull()
                // 取消时不覆盖状态（runCatching 会吞掉取消异常）
                ensureActive()

                // P9 试听防护：size < 960_000（≈60s@128k）= VIP 试听片段 → 换同歌完整版源
                if (resolved?.isPreview() == true) {
                    runCatching {
                        // 同歌名 + 同规范化歌手、source 不同的条目，按 SOURCES 优先级逐个换源
                        val candidates = onlinePool
                            .filter { candidate ->
                                candidate.source != song.source &&
                                    isSameSongForSwitch(candidate, song)
                            }
                            .sortedBy { candidate -> sourceSortKey(candidate.source) }
                        for (candidate in candidates) {
                            ensureActive()
                            val alt = OnlineMusicSource.resolveUrl(
                                onlineSettings.serverUrl,
                                candidate.source,
                                candidate.sourceId
                            ) ?: continue
                            // 找到完整版（size ≥ 960_000，size 缺失同样按完整版对待）
                            if (!alt.isPreview()) {
                                chosen = candidate
                                resolved = alt
                                Toast.makeText(
                                    appContext,
                                    appContext.getString(
                                        R.string.preview_switched,
                                        sourceLabel(appContext, candidate.source)
                                    ),
                                    Toast.LENGTH_SHORT
                                ).show()
                                break
                            }
                        }
                    }
                    // runCatching 会吞掉取消异常：重新抛出，避免取消后仍去点播
                    ensureActive()
                }

                resolvingOnlineId = null
                val directUrl = resolved?.url
                if (directUrl.isNullOrBlank()) {
                    Toast.makeText(
                        appContext,
                        appContext.getString(R.string.online_url_failed),
                        Toast.LENGTH_SHORT
                    ).show()
                } else {
                    // mediaId / 封面 / 歌词都跟着 chosen（换源后自然是新 source 的条目）
                    PlayerConnection.playOnline(listOf(chosen.toMediaItem(directUrl)), 0)
                    onOpenPlayer()
                }
            }
        }
    }

    // P12 播放全部进行中标记（防连点把收藏列表重复插入队列）
    var favoritesPlaying by remember { mutableStateOf(false) }

    /**
     * P12 收藏「播放全部」：并发解析直链（Semaphore 限并发 ≤4，保持收藏顺序），
     * 成功的条目走现有插入式点播 PlayerConnection.playOnline（插入当前曲目下一位）。
     * 全部失败 → 既有失败文案；否则一条新 Toast 报「成功 N 首 / 失败 M 首」。
     * 注：逐首不走 P9 试听换源（并发下逐首换源请求数会成倍放大），解析失败即计失败。
     */
    val onPlayFavorites: () -> Unit = {
        val targets = favorites
        if (targets.isNotEmpty() && onlineActive && !favoritesPlaying) {
            favoritesPlaying = true
            onlineScope.launch {
                try {
                    val semaphore = Semaphore(FAVORITES_RESOLVE_CONCURRENCY)
                    val resolvedItems = targets.map { song ->
                        async {
                            semaphore.withPermit {
                                val directUrl = runCatching {
                                    OnlineMusicSource.getUrl(
                                        onlineSettings.serverUrl,
                                        song.source,
                                        song.sourceId
                                    )
                                }.getOrNull()
                                if (directUrl.isNullOrBlank()) null
                                else song.toMediaItem(directUrl)
                            }
                        }
                    }.awaitAll().filterNotNull()
                    val failed = targets.size - resolvedItems.size
                    if (resolvedItems.isEmpty()) {
                        // 全部解析失败：沿用既有失败文案
                        Toast.makeText(
                            appContext,
                            appContext.getString(R.string.online_url_failed),
                            Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        PlayerConnection.playOnline(resolvedItems, 0)
                        onOpenPlayer()
                        Toast.makeText(
                            appContext,
                            appContext.getString(
                                R.string.favorites_resolved,
                                resolvedItems.size,
                                failed
                            ),
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                } finally {
                    favoritesPlaying = false
                }
            }
        }
    }

    // 详情对象随数据刷新自动失效（找不到则回到列表）
    val selectedAlbum: AlbumInfo? =
        if (tab == HomeTab.ALBUMS) albums.firstOrNull { it.albumId == selectedAlbumId } else null
    val selectedArtist: ArtistInfo? =
        if (tab == HomeTab.ARTISTS) artists.firstOrNull { it.name == selectedArtistName } else null

    fun switchTab(target: HomeTab) {
        tab = target
        selectedAlbumId = null
        selectedArtistName = null
    }

    val currentMediaId = playerState?.currentMediaItem?.mediaId

    Scaffold(
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
        topBar = {
            when {
                selectedAlbum != null -> DetailBar(
                    title = selectedAlbum.title,
                    onBack = { selectedAlbumId = null }
                )

                selectedArtist != null -> DetailBar(
                    title = selectedArtist.name,
                    onBack = { selectedArtistName = null }
                )

                tab == HomeTab.SONGS -> TitleBar(
                    title = stringResource(R.string.app_name),
                    onRefresh = { libraryViewModel.refresh() }
                )

                tab == HomeTab.ALBUMS -> TitleBar(
                    title = stringResource(R.string.tab_albums),
                    onRefresh = { libraryViewModel.refresh() }
                )

                tab == HomeTab.ARTISTS -> TitleBar(
                    title = stringResource(R.string.tab_artists),
                    onRefresh = { libraryViewModel.refresh() }
                )

                else -> TitleBar(
                    title = stringResource(R.string.tab_settings),
                    onRefresh = null
                )
            }
        },
        containerColor = Color.Transparent,
        bottomBar = {
            Column {
                MiniPlayer(playerState = playerState, onOpenPlayer = onOpenPlayer)
                NavigationBar(containerColor = Color.Black.copy(alpha = 0.85f)) {
                    NavigationBarItem(
                        selected = tab == HomeTab.SONGS,
                        onClick = { switchTab(HomeTab.SONGS) },
                        icon = { Icon(Icons.Default.MusicNote, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_songs)) },
                        colors = navItemColors()
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.ALBUMS,
                        onClick = { switchTab(HomeTab.ALBUMS) },
                        icon = { Icon(Icons.Default.Album, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_albums)) },
                        colors = navItemColors()
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.ARTISTS,
                        onClick = { switchTab(HomeTab.ARTISTS) },
                        icon = { Icon(Icons.Default.Person, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_artists)) },
                        colors = navItemColors()
                    )
                    NavigationBarItem(
                        selected = tab == HomeTab.SETTINGS,
                        onClick = { switchTab(HomeTab.SETTINGS) },
                        icon = { Icon(Icons.Default.Settings, contentDescription = null) },
                        label = { Text(stringResource(R.string.tab_settings)) },
                        colors = navItemColors()
                    )
                }
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when {
                loading && songs.isEmpty() -> {
                    CircularProgressIndicator(Modifier.align(Alignment.Center))
                }

                tab == HomeTab.SONGS -> SongsTab(
                    songs = songs,
                    query = searchQuery,
                    onQueryChange = { searchQuery = it },
                    currentMediaId = currentMediaId,
                    onSongClick = { index ->
                        PlayerConnection.playSongs(songs, index)
                        onOpenPlayer()
                    },
                    onlineActive = onlineActive,
                    onlineResults = onlineResults,
                    onlineLoading = onlineLoading,
                    onlineError = onlineError,
                    resolvingOnlineId = resolvingOnlineId,
                    onOnlineClick = onOnlineClick,
                    favorites = favorites,
                    favoriteKeys = favoriteKeys,
                    onToggleFavorite = { song -> libraryViewModel.toggleFavorite(song) },
                    downloadingKeys = downloadingKeys,
                    onDownload = { song -> libraryViewModel.downloadOnline(song) },
                    onPlayFavorites = onPlayFavorites
                )

                tab == HomeTab.ALBUMS -> {
                    if (selectedAlbum != null) {
                        SongList(
                            songs = selectedAlbum.songs,
                            currentMediaId = currentMediaId,
                            onSongClick = { index ->
                                PlayerConnection.playSongs(selectedAlbum.songs, index)
                                onOpenPlayer()
                            },
                            header = {
                                DetailHeader(
                                    artworkUri = selectedAlbum.artworkUri,
                                    coverKey = selectedAlbum.songs.firstOrNull()?.id?.toString(),
                                    title = selectedAlbum.title,
                                    subtitle = selectedAlbum.artist,
                                    onPlayAll = {
                                        PlayerConnection.playSongs(selectedAlbum.songs, 0)
                                        onOpenPlayer()
                                    }
                                )
                            }
                        )
                    } else {
                        AlbumsTab(albums = albums, onAlbumClick = { album ->
                            selectedAlbumId = album.albumId
                        })
                    }
                }

                tab == HomeTab.ARTISTS -> {
                    if (selectedArtist != null) {
                        SongList(
                            songs = selectedArtist.songs,
                            currentMediaId = currentMediaId,
                            onSongClick = { index ->
                                PlayerConnection.playSongs(selectedArtist.songs, index)
                                onOpenPlayer()
                            },
                            header = {
                                DetailHeader(
                                    artworkUri = selectedArtist.artworkUri,
                                    coverKey = selectedArtist.songs.firstOrNull()?.id?.toString(),
                                    title = selectedArtist.name,
                                    subtitle = stringResource(
                                        R.string.artist_song_count,
                                        selectedArtist.songCount,
                                        selectedArtist.albumCount
                                    ),
                                    onPlayAll = {
                                        PlayerConnection.playSongs(selectedArtist.songs, 0)
                                        onOpenPlayer()
                                    }
                                )
                            }
                        )
                    } else {
                        ArtistsTab(artists = artists, onArtistClick = { artist ->
                            selectedArtistName = artist.name
                        })
                    }
                }

                else -> SettingsTab(
                    settings = scanSettings,
                    onFilterChanged = { libraryViewModel.setFilterEnabled(it) },
                    onThresholdChanged = { libraryViewModel.setMinDurationSec(it) },
                    onSizeFilterChanged = { libraryViewModel.setSizeFilterEnabled(it) },
                    onSizeThresholdChanged = { libraryViewModel.setMinSizeKb(it) },
                    onlineSettings = onlineSettings,
                    onOnlineEnabledChanged = { libraryViewModel.setOnlineEnabled(it) },
                    onServerUrlChanged = { libraryViewModel.setServerUrl(it) },
                    onScraperEnabledChanged = { libraryViewModel.setScraperEnabled(it) },
                    scrapeStatus = scrapeStatus
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TitleBar(title: String, onRefresh: (() -> Unit)?) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // 顶部下压渐变（同播放页顶栏），压在标题区底下
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            },
            actions = {
                if (onRefresh != null) {
                    IconButton(onClick = onRefresh) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = stringResource(R.string.refresh),
                            tint = Color.White.copy(alpha = 0.75f)
                        )
                    }
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = Color.White,
                actionIconContentColor = Color.White.copy(alpha = 0.75f)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DetailBar(title: String, onBack: () -> Unit) {
    Box(modifier = Modifier.fillMaxWidth()) {
        // 顶部下压渐变（同播放页顶栏），压在标题区底下
        Box(
            modifier = Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Black.copy(alpha = 0.5f),
                            Color.Transparent
                        )
                    )
                )
        )
        TopAppBar(
            title = {
                Text(
                    text = title,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            },
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(
                        Icons.Default.ArrowBack,
                        contentDescription = stringResource(R.string.back),
                        tint = Color.White.copy(alpha = 0.75f)
                    )
                }
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color.Transparent,
                titleContentColor = Color.White,
                navigationIconContentColor = Color.White.copy(alpha = 0.75f)
            )
        )
    }
}

@Composable
private fun navItemColors() = NavigationBarItemDefaults.colors(
    selectedIconColor = MaterialTheme.colorScheme.primary,
    selectedTextColor = MaterialTheme.colorScheme.primary,
    unselectedIconColor = Color.White.copy(alpha = 0.55f),
    unselectedTextColor = Color.White.copy(alpha = 0.55f),
    indicatorColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
)

@Composable
private fun MiniPlayer(
    playerState: PlayerState?,
    onOpenPlayer: () -> Unit
) {
    val item = playerState?.currentMediaItem ?: return
    val meta = item.mediaMetadata
    val duration = playerState.durationMs
    val progress = if (duration > 0)
        playerState.positionMs.toFloat() / duration
    else 0f

    // 黑 0.85 玻璃条：顶部渐变进度条 + 白字曲目信息 + primary 播放键
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.85f))
    ) {
        // 顶部进度条（与播放页进度同源：primary → 白 渐变）
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(2.dp)
                .background(Color.White.copy(alpha = 0.15f))
        ) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(progress.coerceIn(0f, 1f))
                    .height(2.dp)
                    .background(
                        Brush.horizontalGradient(
                            colors = listOf(
                                MaterialTheme.colorScheme.primary,
                                Color.White.copy(alpha = 0.9f)
                            )
                        )
                    )
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onOpenPlayer)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                    .background(Color(0xFF1C1C1F)),
                contentAlignment = Alignment.Center
            ) {
                CoverImage(
                    localModel = meta.artworkUri,
                    coverKey = item.mediaId,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = meta.title?.toString()
                        ?: stringResource(R.string.unknown_song),
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White
                )
                Text(
                    text = meta.artist?.toString() ?: "",
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = Color.White.copy(alpha = 0.75f)
                )
            }
            IconButton(onClick = { PlayerConnection.togglePlay() }) {
                Icon(
                    imageVector = if (playerState.isPlaying) Icons.Default.Pause
                    else Icons.Default.PlayArrow,
                    contentDescription = if (playerState.isPlaying)
                        stringResource(R.string.pause)
                    else
                        stringResource(R.string.play),
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }
    }
}

// ————————————————————— P9 短音频试听自动换源 —————————————————————

/**
 * 换源候选匹配：同规范化歌名（必须相等）+ 同规范化歌手
 *（相等或互含；任一侧歌手空缺视为匹配 —— 不同源的歌手字段格式常有出入）。
 */
private fun isSameSongForSwitch(a: OnlineSong, b: OnlineSong): Boolean {
    val titleA = normalizeSwitchKey(a.title)
    val titleB = normalizeSwitchKey(b.title)
    if (titleA.isEmpty() || titleA != titleB) return false
    val artistA = normalizeSwitchKey(a.artist)
    val artistB = normalizeSwitchKey(b.artist)
    return artistA.isEmpty() || artistB.isEmpty() ||
        artistA == artistB ||
        artistA.contains(artistB) || artistB.contains(artistA)
}

/** 规范化口径：小写 + 去空白与常见分隔/括号字符（与刮削打分、搜索去重一致） */
private fun normalizeSwitchKey(text: String): String = text
    .lowercase()
    .replace(Regex("""[\s\-_·・.。，,、:：!！?？()（）\[\]【】'’“”"]+"""), "")

/** 换源候选优先级：SOURCES 顺序（migu → netease → kuwo），未知源排最后 */
private fun sourceSortKey(source: String): Int =
    OnlineMusicSource.SOURCES.indexOf(source)
        .let { index -> if (index >= 0) index else Int.MAX_VALUE }

/** 数据源中文名（Toast 文案用，与在线列表行内来源标同源） */
private fun sourceLabel(context: Context, source: String): String = when (source) {
    "migu" -> context.getString(R.string.source_migu)
    "netease" -> context.getString(R.string.source_netease)
    "kuwo" -> context.getString(R.string.source_kuwo)
    "qq" -> context.getString(R.string.source_qq)
    else -> source
}
