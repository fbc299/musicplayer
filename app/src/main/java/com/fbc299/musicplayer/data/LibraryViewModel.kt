package com.fbc299.musicplayer.data

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 在线搜索输入防抖（毫秒） */
private const val ONLINE_SEARCH_DEBOUNCE_MS = 350L

/** P5 封面刮削：每新增 N 首映射落盘一次（整轮结束时再兜底存一次） */
private const val COVER_SAVE_EVERY = 10

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

    // ——————————————————— P5 封面刮削 ———————————————————

    /**
     * 刮削结果映射（songId → 封面 URL）：启动时从 DataStore 持久缓存加载进内存，
     * 后台刮削逐步追加；4 处封面 UI 经 LocalScrapedCovers 统一读取。
     */
    private val _coverMap = MutableStateFlow<Map<String, String>>(emptyMap())
    val coverMap: StateFlow<Map<String, String>> = _coverMap

    /** 串行刮削任务（并发 1）；重扫 / 开关变化时先取消旧任务 */
    private var scrapeJob: Job? = null

    init {
        viewModelScope.launch {
            // P5：先把持久化刮削缓存加载进内存（命中即零网络），再启动扫描
            _coverMap.value = runCatching { settingsRepository.loadCoverCache() }
                .getOrDefault(emptyMap())
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
     * - 缓存命中（_coverMap 已有）→ 跳过网络；
     * - 每首成功立即写入 StateFlow，满 COVER_SAVE_EVERY 首落盘一次；
     * - 逐首重读设置：服务器地址热更新，刮削/在线开关关闭立即停；
     * - 尊重取消（重扫时旧任务 cancel），完成或被取消都兜底落盘；
     * - 任何异常静默结束 —— 刮削是尽力而为，绝不影响本地播放。
     */
    private suspend fun runCoverScrape(songs: List<Song>) {
        var sinceSave = 0
        var anyNew = false
        try {
            for (song in songs) {
                // 纯缓存命中的快路径没有挂起点，手动检查取消
                currentCoroutineContext().ensureActive()
                val key = song.id.toString()
                if (_coverMap.value.containsKey(key)) continue
                val online = onlineSettings.value
                if (!online.scraperActive || online.serverUrl.isBlank()) break
                val url = CoverScraper.scrape(online.serverUrl, song.title, song.artist)
                if (url != null) {
                    _coverMap.value = _coverMap.value + (key to url)
                    anyNew = true
                    if (++sinceSave >= COVER_SAVE_EVERY) {
                        sinceSave = 0
                        settingsRepository.saveCoverCache(_coverMap.value)
                    }
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w("LibraryViewModel", "cover scrape stopped: ${e.message}")
        } finally {
            if (anyNew) {
                // 完成或被取消都把已刮到的映射兜底落盘
                withContext(NonCancellable) {
                    runCatching { settingsRepository.saveCoverCache(_coverMap.value) }
                }
            }
        }
    }
}
