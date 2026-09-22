package com.fbc299.musicplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// 应用级设置存储（DataStore preferences）
private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/** 扫描设置：是否启用最短时长过滤 + 阈值（秒） */
data class ScanSettings(
    val filterEnabled: Boolean,
    val minDurationSec: Int
)

class SettingsRepository(private val context: Context) {

    private object Keys {
        val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
        val MIN_DURATION_SEC = intPreferencesKey("min_duration_sec")
    }

    val settings: Flow<ScanSettings> = context.settingsDataStore.data
        .map { prefs ->
            ScanSettings(
                filterEnabled = prefs[Keys.FILTER_ENABLED] ?: DEFAULT_FILTER_ENABLED,
                minDurationSec = prefs[Keys.MIN_DURATION_SEC] ?: DEFAULT_MIN_DURATION_SEC
            )
        }
        .distinctUntilChanged()

    suspend fun setFilterEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FILTER_ENABLED] = enabled }
    }

    suspend fun setMinDurationSec(sec: Int) {
        context.settingsDataStore.edit { it[Keys.MIN_DURATION_SEC] = sec.coerceIn(1, 60) }
    }

    companion object {
        const val DEFAULT_FILTER_ENABLED = true
        const val DEFAULT_MIN_DURATION_SEC = 3
    }
}
