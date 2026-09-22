package com.fbc299.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val uri: Uri,
    val albumId: Long
) {
    val artworkUri: Uri
        get() = ContentUris.withAppendedId(
            Uri.parse("content://media/external/audio/albumart"),
            albumId
        )

    val formattedDuration: String
        get() {
            val sec = durationMs / 1000
            return "%d:%02d".format(sec / 60, sec % 60)
        }

    val formattedSize: String
        get() = when {
            sizeBytes >= 1_048_576 -> "%.1f MB".format(sizeBytes / 1_048_576.0)
            else -> "%d KB".format(sizeBytes / 1024)
        }
}

object SongRepository {

    /**
     * 扫描本地音乐。
     * @param minDurationMs 最短时长阈值（毫秒），<=0 表示不按时长过滤
     * @param minSizeBytes 最短文件大小阈值（字节），<=0 表示不按大小过滤
     *   注意：SIZE > 0 为永久过滤，0 字节文件任何情况下都不显示
     */
    suspend fun loadSongs(
        context: Context,
        minDurationMs: Long,
        minSizeBytes: Long = 0L
    ): List<Song> =
        withContext(Dispatchers.IO) {
        val songs = mutableListOf<Song>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST,
            MediaStore.Audio.Media.ALBUM,
            MediaStore.Audio.Media.DURATION,
            MediaStore.Audio.Media.SIZE,
            MediaStore.Audio.Media.ALBUM_ID,
            MediaStore.Audio.Media.MIME_TYPE,
            MediaStore.Audio.Media.IS_MUSIC
        )
        val selection = buildString {
            // 0 字节文件永久过滤，与开关无关
            append("${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ")
            append("${MediaStore.Audio.Media.SIZE} > 0")
            if (minDurationMs > 0L) {
                append(" AND ${MediaStore.Audio.Media.DURATION} >= ?")
            }
            if (minSizeBytes > 0L) {
                append(" AND ${MediaStore.Audio.Media.SIZE} >= ?")
            }
        }
        val args = mutableListOf<String>()
        if (minDurationMs > 0L) args += minDurationMs.toString()
        if (minSizeBytes > 0L) args += minSizeBytes.toString()
        val selectionArgs = args.toTypedArray().takeIf { it.isNotEmpty() }
        val sortOrder = "${MediaStore.Audio.Media.TITLE} COLLATE NOCASE ASC"

        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection, selection, selectionArgs, sortOrder
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val durCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val albumIdCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleCol)
                            ?: context.getString(com.fbc299.musicplayer.R.string.unknown_song),
                        artist = cursor.getString(artistCol)
                            ?: context.getString(com.fbc299.musicplayer.R.string.unknown_artist),
                        album = cursor.getString(albumCol)
                            ?: context.getString(com.fbc299.musicplayer.R.string.unknown_album),
                        durationMs = cursor.getLong(durCol),
                        sizeBytes = cursor.getLong(sizeCol),
                        uri = ContentUris.withAppendedId(
                            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id
                        ),
                        albumId = cursor.getLong(albumIdCol)
                    )
                )
            }
        }
        songs
    }
}
