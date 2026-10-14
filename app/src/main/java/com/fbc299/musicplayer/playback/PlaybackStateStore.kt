package com.fbc299.musicplayer.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import org.json.JSONArray
import org.json.JSONObject

/**
 * P15-2 冷启动恢复播放进度：把当前播放队列 + 位置持久化到 SharedPreferences(JSON)。
 *
 * 保存时机：播放中每 15 秒一次（PlayerConnection 的周期协程）；
 * 恢复时机：冷启动 controller 就绪后读一次，有数据则 setMediaItems(位置) 续播。
 *
 * mediaId 原样保留（在线曲 on_ 前缀 / 本地 MediaStore id），
 * 恢复后播放页歌词、收藏、下载识别逻辑（on_ 前缀解析）完全不受影响。
 */
object PlaybackStateStore {

    private const val PREFS_NAME = "playback_state"
    private const val KEY_STATE = "state"

    /** 持久化的单条曲目 */
    data class SavedItem(
        val mediaId: String,
        val uri: String?,
        val title: String,
        val artist: String,
        val album: String,
        val artworkUri: String?
    )

    /** 持久化的完整播放状态 */
    data class SavedState(
        val items: List<SavedItem>,
        val index: Int,
        val positionMs: Long,
        /** 保存时刻（Unix 毫秒），恢复时超过阈值不自动续播（仅提示可选，当前直接恢复） */
        val savedAtMs: Long
    )

    /** 取当前播放器状态并落盘；无 controller / 队列为空则跳过 */
    fun save(context: Context, items: List<MediaItem>, index: Int, positionMs: Long) {
        if (items.isEmpty()) return
        val json = JSONObject()
        val arr = JSONArray()
        for (item in items) {
            val o = JSONObject()
            o.put("mediaId", item.mediaId)
            item.uri?.let { o.put("uri", it.toString()) }
            val meta = item.mediaMetadata
            o.put("title", meta.title?.toString() ?: "")
            o.put("artist", meta.artist?.toString() ?: "")
            o.put("album", meta.albumTitle?.toString() ?: "")
            meta.artworkUri?.let { o.put("artwork", it.toString()) }
            arr.put(o)
        }
        json.put("items", arr)
        json.put("index", index)
        json.put("positionMs", positionMs)
        json.put("savedAtMs", System.currentTimeMillis())
        runCatching {
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit().putString(KEY_STATE, json.toString()).apply()
        }
    }

    /** 读取最近保存的播放状态；没有 / 解析失败返回 null */
    fun load(context: Context): SavedState? = runCatching {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_STATE, null) ?: return null
        val json = JSONObject(raw)
        val arr = json.optJSONArray("items") ?: return null
        val items = ArrayList<SavedItem>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            items.add(
                SavedItem(
                    mediaId = o.optString("mediaId", ""),
                    uri = o.optString("uri", "").takeIf { it.isNotEmpty() },
                    title = o.optString("title", ""),
                    artist = o.optString("artist", ""),
                    album = o.optString("album", ""),
                    artworkUri = o.optString("artwork", "").takeIf { it.isNotEmpty() }
                )
            )
        }
        if (items.isEmpty()) null
        else
            SavedState(
                items = items,
                index = json.optInt("index", 0).coerceIn(0, items.size - 1),
                positionMs = json.optLong("positionMs", 0L).coerceAtLeast(0L),
                savedAtMs = json.optLong("savedAtMs", 0L)
            )
    }.getOrNull()

    /** 把持久化条目还原为 MediaItem（恢复播放队列用） */
    fun toMediaItems(state: SavedState): List<MediaItem> = state.items.map { item ->
        MediaItem.Builder()
            .setMediaId(item.mediaId)
            .setUri(item.uri?.let { Uri.parse(it) } ?: Uri.EMPTY)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(item.title)
                    .setArtist(item.artist)
                    .setAlbumTitle(item.album)
                    .setArtworkUri(item.artworkUri?.let { Uri.parse(it) })
                    .build()
            )
            .build()
    }
}
