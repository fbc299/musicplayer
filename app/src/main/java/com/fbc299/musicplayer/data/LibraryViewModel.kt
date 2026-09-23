package com.fbc299.musicplayer.data

import android.app.Application
import android.util.Log
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fbc299.musicplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 在线搜索输入防抖（毫秒） */
private const val ONLINE_SEARCH_DEBOUNCE_MS = 350L

/** P5 封面刮削：每新增 N 首映射落盘一次（整轮结束时再兜底存一次） */
private const val COVER_SAVE_EVERY = 10

/**
 * P9 封面刮削进度（供设置页展示）：
 * running>0 = 正在刮削；done = 成功数（含缓存命中）；failed = 失败数（含负缓存）；
 * total = 本轮曲目总数（0 = 尚未启动，UI 据此隐藏状态行）。
 */
data class ScrapeStatus(
    val running: Int = 0,
    val done: Int = 0,
    val failed: Int = 0,
    val total: Int = 0
)

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepository = SettingsRepository(app)

    /** 当前扫描设置（DataStore 持久化），首次 emit 即触发加载 */
    val scanSettings: StateFlow<ScanSettings> = settingsRepository.settings
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ScanSettings(
                filterEnabled = SettingsRepository.DEFAULT_FILTER_ENABLED,
                minDurationSec = SettingsRepository.DEFAULT_MIN_DURATION_SEC,
                sizeFilterEnabled = SettingsRepository.DEFAULT_SIZE_FILTER_ENABLED,
                minSizeKb = SettingsRepository.DEFAULT_MIN_SIZE_KB
            )
        )

    /** P4 在线音源设置（开关 + 服务器地址），P5 追加封面刮削开关 */
    val onlineSettings: StateFlow<OnlineSettings> = settingsRepository.onlineSettings
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OnlineSettings(
                enabled = SettingsRepository.DEFAULT_ONLINE_ENABLED,
                serverUrl = SettingsRepository.DEFAULT_ONLINE_SERVER,
                scraperEnabled = SettingsRepository.DEFAULT_SCRAPER_ENABLED
            )
        )

    private val _onlineResults = MutableStateFlow<List<OnlineSong>>(emptyList())
    val onlineResults: StateFlow<List<OnlineSong>> = _onlineResults

    /**
     * P9 试听换源候选池：同一次搜索**去重前**的全量结果（同一首歌的多源条目都在）。
     * onlineResults 已按「歌名+歌手」去重 —— 一首歌只留 SOURCES 中最高优先级的来源，
     * 而换源要找的正是被去重掉的其他源，故额外保留这份池子；展示仍用 onlineResults。
     */
    private val _onlinePool = MutableStateFlow<List<OnlineSong>>(emptyList())
    val onlinePool: StateFlow<List<OnlineSong>> = _onlinePool

    private val _onlineLoading = MutableStateFlow(false)
    val onlineLoading: StateFlow<Boolean> = _onlineLoading

    private val _onlineError = MutableStateFlow(false)
    val onlineError: StateFlow<Boolean> = _onlineError

    private var onlineJob: Job? = null
    private var lastQuery: String = ""

    private val _songs = MutableStateFlow<List<Song>>(emptyList())
    val songs: StateFlow<List<Song>> = _songs

    private val _albums = MutableStateFlow<List<AlbumInfo>>(emptyList())
    val albums: StateFlow<List<AlbumInfo>> = _albums

    private val _artists = MutableStateFlow<List<ArtistInfo>>(emptyList())
    val artists: StateFlow<List<ArtistInfo>> = _artists

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded

    // ——————————————————— P12 收藏 ———————————————————

    private val _favorites = MutableStateFlow<List<OnlineSong>>(emptyList())

    /** P12 收藏列表：启动时从 DataStore 加载，保持收藏顺序（越早收藏越靠前） */
    val favorites: StateFlow<List<OnlineSong>> = _favorites

    /**
     * P12 收藏去重键集合（`source:sourceId`）：由 favorites 派生（stateIn 常驻），
     * UI 判断爱心态时 O(1) contains，不必每次遍历列表。
     */
    val favoriteKeys: StateFlow<Set<String>> = _favorites
        .map { list ->
            list.map { song -> favoriteKey(song.source, song.sourceId) }.toHashSet()
        }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    /**
     * P12 收藏切换：已藏 → 移除、未藏 → 追加（追加到末尾 = 收藏越早越靠前）。
     * StateFlow 先更新（UI 立即变心），随后异步落盘；
     * 落盘读的是 `_favorites.value` 快照，快速连点时后一次写入必然覆盖为最新列表。
     */
    fun toggleFavorite(song: OnlineSong) {
        if (song.source.isEmpty() || song.sourceId.isEmpty()) return
        val key = favoriteKey(song.source, song.sourceId)
        val current = _favorites.value
        val kept = current.filterNot { favoriteKey(it.source, it.sourceId) == key }
        _favorites.value = if (kept.size == current.size) current + song else kept
        viewModelScope.launch {
            runCatching { settingsRepository.saveFavorites(_favorites.value) }
        }
    }

    // ——————————————————— P13 在线歌曲下载 ———————————————————

    /**
     * P13 下载进行中集合（favoriteKey）：点击先入集、结束（无论成败）出集，
     * 同时用于防重复点击与 UI「下载中转圈」态。
     */
    private val _downloading = MutableStateFlow<Set<String>>(emptySet())
    val downloading: StateFlow<Set<String>> = _downloading

    /**
     * P13 下载一首在线歌到公共 Music/格格音乐/：
     * in-flight 防重复 → OnlineMusicSource.resolveUrl 解析直链（onlineSettings 服务器地址）
     * → SongDownloader.save 落盘 → 成功后 load(scanSettings.value) 重扫 MediaStore
     *   刷新本地列表（与刷新按钮同一条入口）→ Toast 结果。
     *
     * 本函数跑在 viewModelScope（Dispatchers.Main）上，网络/文件 IO 由被调函数自行切
     * Dispatchers.IO，故 Toast 可直接弹（Android Toast 不需要 Compose 上下文，
     * getApplication() 即可，文案全走 strings.xml）。
     */
    fun downloadOnline(song: OnlineSong) {
        if (song.source.isEmpty() || song.sourceId.isEmpty()) return
        val key = favoriteKey(song.source, song.sourceId)
        // in-flight 防重复点击（StateFlow 读写都在主线程，无竞态）
        if (_downloading.value.contains(key)) return
        _downloading.value = _downloading.value + key
        viewModelScope.launch {
            try {
                toast(R.string.download_started, song.title)
                val server = onlineSettings.value.serverUrl
                val directUrl = try {
                    OnlineMusicSource.resolveUrl(server, song.source, song.sourceId)?.url
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    null
                }
                if (directUrl.isNullOrBlank()) {
                    toast(R.string.download_resolve_failed)
                    return@launch
                }
                val result = try {
                    SongDownloader.save(getApplication(), directUrl, song.title, song.artist)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
                }
                when (result) {
                    is DownloadResult.Saved -> {
                        // 复用现有刷新入口：重扫 MediaStore，刚下载的歌即出现在本地列表
                        load(scanSettings.value)
                        toast(R.string.download_done)
                    }

                    DownloadResult.AlreadyExists -> toast(R.string.download_exists)

                    is DownloadResult.Failed -> {
                        // 原因仅记日志（含 ≤28 未授权写权限的降级），用户文案统一走 strings
                        Log.w("LibraryViewModel", "download failed: ${result.reason}")
                        toast(R.string.download_failed)
                    }
                }
            } finally {
                _downloading.value = _downloading.value - key
            }
        }
    }

    /** 下载结果 Toast（viewModelScope 主线程，getApplication() 上下文即可） */
    private fun toast(resId: Int, vararg args: Any) {
        val app = getApplication<Application>()
        Toast.makeText(app, app.getString(resId, *args), Toast.LENGTH_SHORT).show()
    }

    // ——————————————————— P5 封面刮削 ———————————————————

    /**
     * 刮削结果映射（songId → 封面 URL）：启动时从 DataStore 持久缓存加载进内存，
     * 后台刮削逐步追加；4 处封面 UI 经 LocalScrapedCovers 统一读取。
     */
    private val _coverMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val coverMap: StateFlow<Map<String, String>> = _coverMap

    /**
     * P9 刮削进度：runCoverScrape 每处理一首更新一次，跑完 running=0。
     * 读取端 MainActivity 会把空串（负缓存）过滤掉，本地封面 UI 不受影响。
     */
    private val _scrapeStatus = MutableStateFlow(ScrapeStatus())
    val scrapeStatus: StateFlow<ScrapeStatus> = _scrapeStatus

    /** 串行刮削任务（并发 1）；重扫 / 开关变化时先取消旧任务 */
    private var scrapeJob: Job? = null

    init {
        viewModelScope.launch {
            // P5：先把持久化刮削缓存加载进内存（命中即零网络），再启动扫描
            _coverMap.value = runCatching { settingsRepository.loadCoverCache() }
                .getOrDefault(emptyMap())
            // P12：加载收藏列表（必须在 settings 收集前 —— 下面的 collect 永不返回）
            _favorites.value = runCatching { settingsRepository.loadFavorites() }
                .getOrDefault(emptyList())
            // 设置变化（含首次读取）→ 重新扫描
            settingsRepository.settings.collect { settings -> load(settings) }
        }
    }

    fun refresh() {
        viewModelScope.launch { load(scanSettings.value) }
    }

    fun setFilterEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setFilterEnabled(enabled) }
    }

    fun setMinDurationSec(sec: Int) {
        viewModelScope.launch { settingsRepository.setMinDurationSec(sec) }
    }

    fun setSizeFilterEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.setSizeFilterEnabled(enabled) }
    }

    fun setMinSizeKb(kb: Int) {
        viewModelScope.launch { settingsRepository.setMinSizeKb(kb) }
    }

    // ——————————————————— P4 在线搜索 ———————————————————

    /**
     * 搜索在线音源（歌曲页搜索词变化时调用）。
     * 开关关闭 / 地址为空 / 词为空：清空结果并保证不发出任何网络请求。
     * 带 350ms 防抖；新请求会取消上一个进行中的请求。
     */
    fun searchOnline(query: String) {
        lastQuery = query
        searchOnline(query, onlineSettings.value)
    }

    private fun searchOnline(query: String, settings: OnlineSettings) {
        onlineJob?.cancel()
        if (query.isBlank() || !settings.active) {
            _onlineResults.value = emptyList()
            _onlinePool.value = emptyList()
            _onlineLoading.value = false
            _onlineError.value = false
            return
        }
        onlineJob = viewModelScope.launch {
            _onlineLoading.value = true
            _onlineError.value = false
            _onlineResults.value = emptyList()
            _onlinePool.value = emptyList()
            delay(ONLINE_SEARCH_DEBOUNCE_MS)
            val result = runCatching {
                OnlineMusicSource.searchRaw(settings.serverUrl, query)
            }
            // runCatching 会吞掉取消异常：这里重新抛出，避免旧请求覆盖新状态
            ensureActive()
            val all = result.getOrDefault(emptyList())
            // 候选池留全量（P9 试听换源用），展示列表按歌名+歌手去重
            _onlinePool.value = all
            _onlineResults.value = OnlineMusicSource.dedupeByTitleArtist(all)
            _onlineError.value = result.isFailure
            _onlineLoading.value = false
        }
    }

    /** 切换在线搜索开关：写入后立即按最新设置重搜/清空；封面刮削跟随总开关启停 */
    fun setOnlineEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOnlineEnabled(enabled)
            val online = settingsRepository.onlineSettings.first()
            searchOnline(lastQuery, online)
            if (online.scraperActive) startCoverScrape(songs.value)
            else scrapeJob?.cancel()
        }
    }

    /** 保存服务器地址：不主动重搜（逐字输入时避免反复发请求），下次搜索词变化时生效 */
    fun setServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
    }

    /** P5：切换「自动刮削封面」开关（依赖在线总开关与同一服务器地址） */
    fun setScraperEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setScraperEnabled(enabled)
            if (enabled) startCoverScrape(songs.value)
            else scrapeJob?.cancel()
        }
    }

    private suspend fun load(settings: ScanSettings) {
        _loading.value = true
        val minDurationMs =
            if (settings.filterEnabled) settings.minDurationSec * 1000L else 0L
        // 关闭大小过滤 → minSizeBytes=0 → SQL 不追加 SIZE>=? 子句
        val minSizeBytes =
            if (settings.sizeFilterEnabled) settings.minSizeKb * 1024L else 0L
        val list = SongRepository.loadSongs(getApplication(), minDurationMs, minSizeBytes)
        _songs.value = list
        _albums.value = list.toAlbums()
        _artists.value = list.toArtists()
        _loading.value = false
        _loaded.value = true
        // P5：歌曲加载完成后后台串行刮削封面（先查缓存；重扫会取消上一轮任务）
        startCoverScrape(list)
    }

    // ——————————————————— P5 封面刮削实现 ———————————————————

    /**
     * 启动一轮封面刮削：先取消上一轮；开关从 DataStore 直读，避免 StateFlow 传播竞态。
     * 开关关 / 地址空 / 歌曲为空：只取消、不开新。
     */
    private suspend fun startCoverScrape(songs: List<Song>) {
        scrapeJob?.cancel()
        val online = settingsRepository.onlineSettings.first()
        if (!online.scraperActive || songs.isEmpty()) return
        scrapeJob = viewModelScope.launch { runCoverScrape(songs) }
    }

    /**
     * 按列表序逐首刮削（循环体内无并发，天然串行 / 并发 1）：
     * - 缓存命中（_coverMap 已有，URL 或空串负缓存）→ 跳过网络，只计数；
     * - 成功写 URL，失败写空串负缓存（containsKey 下轮直接跳过）并记 scrape miss 日志；
     * - 每首更新 scrapeStatus 供设置页展示进度；
     * - 每 COVER_SAVE_EVERY 首落盘一次；逐首重读设置：服务器地址热更新，刮削/在线开关关闭立即停；
     * - 尊重取消（重扫时旧任务 cancel），完成或被取消都兜底落盘；
     * - 任何异常静默结束 —— 刮削是尽力而为，绝不影响本地播放。
     */
    private suspend fun runCoverScrape(songs: List<Song>) {
        var sinceSave = 0
        var anyNew = false
        var done = 0
        var failed = 0
        val total = songs.size
        _scrapeStatus.value = ScrapeStatus(running = 1, total = total)
        try {
            for (song in songs) {
                // 纯缓存命中的快路径没有挂起点，手动检查取消
                currentCoroutineContext().ensureActive()
                val key = song.id.toString()
                val cached = _coverMap.value[key]
                if (cached != null) {
                    // 已试过：URL 计成功、空串负缓存计失败，都不再发网络请求
                    if (cached.isBlank()) failed++ else done++
                    _scrapeStatus.value = ScrapeStatus(1, done, failed, total)
                    continue
                }
                val online = onlineSettings.value
                if (!online.scraperActive || online.serverUrl.isBlank()) break
                val outcome = CoverScraper.scrapeDetailed(
                    online.serverUrl, song.title, song.artist
                )
                val url = outcome.url
                // 负缓存：失败也写入（空串），下一轮 containsKey 直接跳过、不再反复重试
                _coverMap.value = _coverMap.value + (key to (url ?: ""))
                anyNew = true
                if (url != null) {
                    done++
                } else {
                    failed++
                    // 带歌名与原因，便于定位「为什么这首搜不到」
                    Log.d("LibraryViewModel", "scrape miss: ${song.title} - ${outcome.reason}")
                }
                _scrapeStatus.value = ScrapeStatus(1, done, failed, total)
                if (++sinceSave >= COVER_SAVE_EVERY) {
                    sinceSave = 0
                    settingsRepository.saveCoverCache(_coverMap.value)
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LibraryViewModel", "cover scrape stopped: ${e.message}")
        } finally {
            if (anyNew) {
                // 完成或被取消都把已刮到的映射（含负缓存）兜底落盘
                withContext(NonCancellable) {
                    runCatching { settingsRepository.saveCoverCache(_coverMap.value) }
                }
            }
            // 仅当自己仍是当前轮任务才收尾 running（被新任务接管时由新任务接管状态）
            if (scrapeJob === currentCoroutineContext()[Job]) {
                _scrapeStatus.value = _scrapeStatus.value.copy(running = 0)
            }
        }
    }
}
