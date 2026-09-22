package com.fbc299.musicplayer.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

// 应用级设置存储（DataStore preferences）
private val Context.settingsDataStore by preferencesDataStore(name = "app_settings")

/**
 * 扫描设置：时长过滤（开关 + 阈值秒）+ 文件大小过滤（开关 + 阈值 KB）
 */
data class ScanSettings(
    val filterEnabled: Boolean,
    val minDurationSec: Int,
    val sizeFilterEnabled: Boolean,
    val minSizeKb: Int
)

/**
 * P4 在线音源设置：在线搜索开关 + 聚合服务地址。
 * 开关关闭或地址为空时，应用内不发起任何在线请求。
 */
data class OnlineSettings(
    val enabled: Boolean,
    val serverUrl: String
) {
    /** 是否真正可用（开关开且地址非空） */
    val active: Boolean
        get() = enabled && serverUrl.isNotBlank()
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val FILTER_ENABLED = booleanPreferencesKey("filter_enabled")
        val MIN_DURATION_SEC = intPreferencesKey("min_duration_sec")
        val SIZE_FILTER_ENABLED = booleanPreferencesKey("size_filter_enabled")
        val MIN_SIZE_KB = intPreferencesKey("min_size_kb")
        val ONLINE_ENABLED = booleanPreferencesKey("online_enabled")
        val ONLINE_SERVER = stringPreferencesKey("online_server")
    }

    val settings: Flow<ScanSettings> = context.settingsDataStore.data
        .map { prefs ->
            ScanSettings(
                filterEnabled = prefs[Keys.FILTER_ENABLED] ?: DEFAULT_FILTER_ENABLED,
                minDurationSec = prefs[Keys.MIN_DURATION_SEC] ?: DEFAULT_MIN_DURATION_SEC,
                sizeFilterEnabled = prefs[Keys.SIZE_FILTER_ENABLED] ?: DEFAULT_SIZE_FILTER_ENABLED,
                minSizeKb = prefs[Keys.MIN_SIZE_KB] ?: DEFAULT_MIN_SIZE_KB
            )
        }
        .distinctUntilChanged()

    /** 在线音源设置（独立于扫描设置，修改不会触发媒体库重扫） */
    val onlineSettings: Flow<OnlineSettings> = context.settingsDataStore.data
        .map { prefs ->
            OnlineSettings(
                enabled = prefs[Keys.ONLINE_ENABLED] ?: DEFAULT_ONLINE_ENABLED,
                serverUrl = prefs[Keys.ONLINE_SERVER] ?: DEFAULT_ONLINE_SERVER
            )
        }
        .distinctUntilChanged()

    suspend fun setFilterEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.FILTER_ENABLED] = enabled }
    }

    /** P4：启用/关闭在线搜索 */
    suspend fun setOnlineEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.ONLINE_ENABLED] = enabled }
    }

    /** P4：保存聚合服务地址（允许清空 —— 清空即停用在线请求） */
    suspend fun setServerUrl(url: String) {
        context.settingsDataStore.edit { it[Keys.ONLINE_SERVER] = url.trim() }
    }

    suspend fun setMinDurationSec(sec: Int) {
        context.settingsDataStore.edit { it[Keys.MIN_DURATION_SEC] = sec.coerceIn(1, 60) }
    }

    suspend fun setSizeFilterEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.SIZE_FILTER_ENABLED] = enabled }
    }

    /** @param kb 最短文件大小（KB），范围 0–10 MB */
    suspend fun setMinSizeKb(kb: Int) {
        context.settingsDataStore.edit {
            it[Keys.MIN_SIZE_KB] = kb.coerceIn(0, MAX_SIZE_KB)
        }
    }

    companion object {
        const val DEFAULT_FILTER_ENABLED = true
        const val DEFAULT_MIN_DURATION_SEC = 3
        /** 文件大小过滤默认关闭 */
        const val DEFAULT_SIZE_FILTER_ENABLED = false
        /** 默认阈值 500 KB */
        const val DEFAULT_MIN_SIZE_KB = 500
        /** 阈值上限 10 MB（10240 KB） */
        const val MAX_SIZE_KB = 10 * 1024
        /** 在线搜索默认开启 */
        const val DEFAULT_ONLINE_ENABLED = true
        /** 默认聚合服务地址（与 OnlineMusicSource.DEFAULT_SERVER 同源） */
        const val DEFAULT_ONLINE_SERVER = OnlineMusicSource.DEFAULT_SERVER
    }
}
