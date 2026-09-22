package com.fbc299.musicplayer.data

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class LibraryViewModel(app: Application) : AndroidViewModel(app) {

    private val settingsRepository = SettingsRepository(app)

    /** 当前扫描设置（DataStore 持久化），首次 emit 即触发加载 */
    val scanSettings: StateFlow<ScanSettings> = settingsRepository.settings
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            ScanSettings(
                filterEnabled = SettingsRepository.DEFAULT_FILTER_ENABLED,
                minDurationSec = SettingsRepository.DEFAULT_MIN_DURATION_SEC
            )
        )

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

    private suspend fun load(settings: ScanSettings) {
        _loading.value = true
        val minDurationMs =
            if (settings.filterEnabled) settings.minDurationSec * 1000L else 0L
        val list = SongRepository.loadSongs(getApplication(), minDurationMs)
        _songs.value = list
        _albums.value = list.toAlbums()
        _artists.value = list.toArtists()
        _loading.value = false
        _loaded.value = true
    }
}
