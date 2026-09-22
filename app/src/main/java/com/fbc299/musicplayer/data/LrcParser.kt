package com.fbc299.musicplayer.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.charset.Charset

/** 一行歌词：从 timeMs 起生效 */
data class LyricLine(
    val timeMs: Long,
    val text: String
)

/**
 * LRC 歌词解析与加载。
 *
 * - 解析标准 [mm:ss.xx] / [mm:ss.xxx] 时间标签，支持一行多个时间标签；
 * - 支持 [offset:±ms] 整体偏移；忽略 [ti:] [ar:] 等元信息行；
 * - 加载：音频同目录同名 .lrc 文件（先直接读文件，失败再走 MediaStore file uri）。
 */
object LrcParser {

    /** 匹配 [mm:ss] / [mm:ss.xx] / [mm:ss.xxx] 时间标签 */
    private val TIME_TAG = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")

    /** 匹配 [offset:±123] 偏移标签 */
    private val OFFSET_TAG = Regex("""\[offset:\s*(-?\d+)]""", RegexOption.IGNORE_CASE)

    /** 解析 LRC 文本为按时间升序的歌词行 */
    fun parseLrc(content: String): List<LyricLine> {
        var offsetMs = 0L
        val lines = ArrayList<LyricLine>()

        content.lineSequence().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty()) return@forEach

            OFFSET_TAG.find(line)?.let { m ->
                offsetMs = m.groupValues[1].toLongOrNull() ?: 0L
                return@forEach
            }

            val tags = TIME_TAG.findAll(line).toList()
            if (tags.isEmpty()) return@forEach

            // 去掉所有时间标签后剩余部分即歌词文本
            val text = TIME_TAG.replace(line, "").trim()
            if (text.isEmpty()) return@forEach

            for (tag in tags) {
                val min = tag.groupValues[1].toLongOrNull() ?: continue
                val sec = tag.groupValues[2].toLongOrNull() ?: continue
                val frac = tag.groupValues[3]
                val fracMs = when (frac.length) {
                    0 -> 0L
                    1 -> (frac.toLongOrNull() ?: 0L) * 100
                    2 -> (frac.toLongOrNull() ?: 0L) * 10
                    else -> (frac.toLongOrNull() ?: 0L)
                }
                val timeMs = min * 60_000L + sec * 1000L + fracMs + offsetMs
                if (timeMs >= 0L) lines.add(LyricLine(timeMs, text))
            }
        }

        return lines.sortedBy { it.timeMs }
    }

    /** 根据 MediaStore id 找到音频文件，进而加载同目录同名 .lrc；找不到返回空列表 */
    suspend fun loadLyrics(context: Context, mediaId: Long): List<LyricLine> =
        withContext(Dispatchers.IO) {
            if (mediaId <= 0L) return@withContext emptyList()
            val audioPath = queryAudioPath(context, mediaId) ?: return@withContext emptyList()
            val lrcPath = audioPath.substringBeforeLast('.', audioPath) + ".lrc"
            val bytes = readLrcBytes(context, lrcPath) ?: return@withContext emptyList()
            parseLrc(decodeText(bytes))
        }

    private fun queryAudioPath(context: Context, mediaId: Long): String? =
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media.DATA),
                "${MediaStore.Audio.Media._ID} = ?",
                arrayOf(mediaId.toString()),
                null
            )?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        }.getOrNull()

    private fun readLrcBytes(context: Context, absolutePath: String): ByteArray? {
        // 1) 直接读文件
        runCatching {
            val file = File(absolutePath)
            if (file.exists() && file.isFile) file.readBytes() else null
        }.getOrNull()?.let { return it }

        // 2) 回退：通过 MediaStore file uri 读取
        return runCatching {
            val base = MediaStore.Files.getContentUri("external")
            context.contentResolver.query(
                base,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.DATA} = ?",
                arrayOf(absolutePath),
                null
            )?.use { cursor ->
                if (!cursor.moveToFirst()) return@use null
                val fileId = cursor.getLong(0)
                val uri: Uri = ContentUris.withAppendedId(base, fileId)
                context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }
        }.getOrNull()
    }

    /** LRC 常见编码：优先 UTF-8，出现替换字符时回退 GB18030（兼容 GBK） */
    private fun decodeText(bytes: ByteArray): String {
        val utf8 = String(bytes, Charsets.UTF_8)
        if (!utf8.contains('\uFFFD')) return utf8
        return runCatching { String(bytes, Charset.forName("GB18030")) }
            .getOrDefault(utf8)
    }
}
