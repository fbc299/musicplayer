package com.fbc299.musicplayer.data

import android.net.Uri

/** 按 MediaStore ALBUM_ID 聚合出的专辑 */
data class AlbumInfo(
    val albumId: Long,
    val title: String,
    val artist: String,
    val songCount: Int,
    val artworkUri: Uri,
    val songs: List<Song>
)

/** 按 MediaStore ARTIST 聚合出的艺术家 */
data class ArtistInfo(
    val name: String,
    val songCount: Int,
    val albumCount: Int,
    val artworkUri: Uri,
    val songs: List<Song>
)

/** 由歌曲列表派生专辑 / 艺术家聚合（标题按不区分大小写排序） */
fun List<Song>.toAlbums(): List<AlbumInfo> =
    groupBy { it.albumId }
        .map { (albumId, songs) ->
            AlbumInfo(
                albumId = albumId,
                title = songs.first().album,
                artist = songs.first().artist,
                songCount = songs.size,
                artworkUri = songs.first().artworkUri,
                songs = songs.sortedBy { it.title.lowercase() }
            )
        }
        .sortedBy { it.title.lowercase() }

fun List<Song>.toArtists(): List<ArtistInfo> =
    groupBy { it.artist }
        .map { (name, songs) ->
            ArtistInfo(
                name = name,
                songCount = songs.size,
                albumCount = songs.map { it.albumId }.distinct().size,
                artworkUri = songs.first().artworkUri,
                songs = songs.sortedBy { it.title.lowercase() }
            )
        }
        .sortedBy { it.name.lowercase() }
