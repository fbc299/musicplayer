package com.fbc299.musicplayer.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 在线搜索输入防抖（毫秒） */
private const val ONLINE_SEARCH_DEBOUNCE_MS = 350L

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

    /** P4 在线音源设置（开关 + 服务器地址） */
    val onlineSettings: StateFlow<OnlineSettings> = settingsRepository.onlineSettings
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            OnlineSettings(
                enabled = SettingsRepository.DEFAULT_ONLINE_ENABLED,
                serverUrl = SettingsRepository.DEFAULT_ONLINE_SERVER
            )
        )

    private val _onlineResults = MutableStateFlow<List<OnlineSong>>(emptyList())
    val onlineResults: StateFlow<List<OnlineSong>> = _onlineResults

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

    init {
        // 设置变化（含首次读取）→ 重新扫描
        viewModelScope.launch {
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
            _onlineLoading.value = false
            _onlineError.value = false
            return
        }
        onlineJob = viewModelScope.launch {
            _onlineLoading.value = true
            _onlineError.value = false
            _onlineResults.value = emptyList()
            delay(ONLINE_SEARCH_DEBOUNCE_MS)
            val result = runCatching {
                OnlineMusicSource.search(settings.serverUrl, query)
            }
            // runCatching 会吞掉取消异常：这里重新抛出，避免旧请求覆盖新状态
            ensureActive()
            _onlineResults.value = result.getOrDefault(emptyList())
            _onlineError.value = result.isFailure
            _onlineLoading.value = false
        }
    }

    /** 切换在线搜索开关：写入后立即按最新设置重搜/清空 */
    fun setOnlineEnabled(enabled: Boolean) {
        viewModelScope.launch {
            settingsRepository.setOnlineEnabled(enabled)
            searchOnline(lastQuery, settingsRepository.onlineSettings.first())
        }
    }

    /** 保存服务器地址：不主动重搜（逐字输入时避免反复发请求），下次搜索词变化时生效 */
    fun setServerUrl(url: String) {
        viewModelScope.launch { settingsRepository.setServerUrl(url) }
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
    }
}
