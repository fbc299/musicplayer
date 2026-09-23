package com.fbc299.musicplayer.data

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.nio.charset.Charset

/**
 * 在线曲目 mediaId 前缀。
 * 本地 mediaId 为纯 MediaStore 数字 id，加 "on_" 前缀保证永不冲突。
 */
const val ONLINE_MEDIA_ID_PREFIX = "on_"

/** 在线搜索结果（自建 Meting 聚合 API） */
data class OnlineSong(
    /** 数据源：migu / netease / kuwo / qq */
    val source: String,
    /** 源站原始 id（网易为数字，QQ 为 mid 字符串） */
    val sourceId: String,
    val title: String,
    val artist: String,
    val album: String,
    /** 封面 http URL，可为空 */
    val artworkUrl: String?,
    /** Meting pic 端点用的封面 id（netease 搜索结果的 pic_id 字段），可为空 */
    val picId: String? = null
) {
    val mediaId: String
        get() = "$ONLINE_MEDIA_ID_PREFIX${source}_$sourceId"

    /** 用播放直链构造 MediaItem（metadata 带 title/artist/album/封面 URL） */
    fun toMediaItem(directUrl: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(mediaId)
            .setUri(directUrl)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUrl?.let { Uri.parse(it) })
                    .build()
            )
            .build()
}

/**
 * 在线音源网络层：HttpURLConnection + kotlinx.coroutines，无第三方依赖。
 *
 * 对接自建 Meting 聚合服务（标准 metowolf/Meting 接口）：
 *   GET /?type=search&source={migu|netease|kuwo|qq}&name={关键词}&s=0&n=20  → JSON 数组
 *   GET /?type=url&source=...&id=...                              → {url: 直链}
 *   GET /?type=lyric&source=...&id=...                            → LRC 文本
 *   GET /?type=pic&source=...&id={pic_id}&size=500                → {url: 封面图 URL}
 *
 * 所有响应解析集中在本文件（parseSearchList / findUrlIn / findLyricIn），
 * 服务端实际格式如有出入只需改这里：兼容 数组 / {data:[...]} 等外壳、
 * name/song/title 等字段别名，解析失败抛异常由调用方兜底，不崩溃。
 */
object OnlineMusicSource {

    /** 默认聚合服务地址（与 SettingsRepository.DEFAULT_ONLINE_SERVER 同源） */
    const val DEFAULT_SERVER = "http://fbc299.xyz:18090"

    /**
     * 依次尝试的数据源（搜索时全部并发请求后合并去重）。
     * 顺序即优先级：migu（咪咕 VIP 歌给整曲）→ netease → kuwo；
     * 同一首歌只保留列表中最先命中来源的条目。
     */
    val SOURCES = listOf("migu", "netease", "kuwo")

    private const val TIMEOUT_MS = 8_000
    private const val PAGE_SIZE = 20

    /**
     * 聚合搜索：并发请求给定数据源（默认 SOURCES），单源失败静默忽略；
     * 全部数据源失败才抛异常（调用方显示「在线搜索失败」）。
     * 结果先按 SOURCES 优先级稳定排序（migu → netease → kuwo），再按
     * 「歌名+歌手」规范化去重 —— 同一首歌只留优先级最高来源的条目，
     * 不同歌曲各自保留（咪咕整曲优先）。
     * @param sources 仅请求这些数据源（P5 封面刮削只搜 netease 取 pic_id）
     */
    suspend fun search(
        server: String,
        keyword: String,
        sources: List<String> = SOURCES
    ): List<OnlineSong> =
        withContext(Dispatchers.IO) {
            val kw = keyword.trim()
            if (kw.isEmpty() || sources.isEmpty()) return@withContext emptyList()
            val results = coroutineScope {
                sources.map { source ->
                    async { runCatching { searchSource(server, source, kw) } }
                }.awaitAll()
            }
            val lists = results.mapNotNull { it.getOrNull() }
            if (lists.isEmpty()) {
                throw (results.firstOrNull()?.exceptionOrNull()
                    ?: IOException("online search failed"))
            }
            // 稳定排序到 SOURCES 优先级（migu → netease → kuwo），再按歌名+歌手去重取首个
            dedupeByTitleArtist(
                lists.flatten().sortedBy { song -> sourcePriority(song.source) }
            )
        }

    /** 来源优先级：SOURCES 中越靠前越优先（咪咕整曲优先），未知来源排最后 */
    private fun sourcePriority(source: String): Int =
        SOURCES.indexOf(source).let { index -> if (index >= 0) index else SOURCES.size }

    /** 取试听直链；响应异常或找不到 http URL 时返回 null */
    suspend fun getUrl(server: String, source: String, id: String): String? =
        withContext(Dispatchers.IO) {
            val url = normalizeServer(server) +
                "/?type=url&source=$source&id=" + URLEncoder.encode(id, "UTF-8")
            extractUrl(httpGet(url))
        }

    /** 拉取 LRC 歌词文本（喂给 LrcParser.parseLrc）；失败返回 null */
    suspend fun lyric(server: String, source: String, id: String): String? =
        withContext(Dispatchers.IO) {
            val url = normalizeServer(server) +
                "/?type=lyric&source=$source&id=" + URLEncoder.encode(id, "UTF-8")
            extractLyric(httpGet(url))
        }

    /**
     * 取封面大图 URL（P5 封面刮削，Meting pic 端点）：
     *   GET /?type=pic&source=...&id={pic_id}&size=500
     * 解析复用 extractUrl，宽容兼容 {url:...} / [{"url":...}] / 纯 URL 文本；
     * 请求失败或认不出 URL 时抛异常，由调用方（CoverScraper）兜底为 null。
     */
    suspend fun pic(server: String, source: String, picId: String): String? =
        withContext(Dispatchers.IO) {
            val url = normalizeServer(server) +
                "/?type=pic&source=$source&id=" + URLEncoder.encode(picId, "UTF-8") +
                "&size=500"
            extractUrl(httpGet(url))
        }

    // ——————————————————— 请求 ———————————————————

    private fun searchSource(server: String, source: String, keyword: String): List<OnlineSong> {
        val url = normalizeServer(server) +
            "/?type=search&source=$source" +
            "&name=" + URLEncoder.encode(keyword, "UTF-8") +
            "&s=0&n=$PAGE_SIZE"
        return parseSearchList(httpGet(url), source)
    }

    private fun normalizeServer(server: String): String = server.trim().trimEnd('/')

    /** 同步 GET（只在 Dispatchers.IO 上被调用），8 秒连接/读取超时 */
    private fun httpGet(url: String): String {
        val conn = URL(url).openConnection() as? HttpURLConnection
            ?: throw IOException("unsupported url: $url")
        try {
            conn.connectTimeout = TIMEOUT_MS
            conn.readTimeout = TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("Accept", "application/json, text/plain, */*")
            conn.setRequestProperty("User-Agent", "Mozilla/5.0")
            val code = conn.responseCode
            if (code !in 200..299) throw IOException("HTTP $code for $url")
            return conn.inputStream.bufferedReader(charsetOf(conn.contentType))
                .use { it.readText() }
        } finally {
            conn.disconnect()
        }
    }

    private fun charsetOf(contentType: String?): Charset {
        val name = contentType
            ?.substringAfter("charset=", "")
            ?.trim()
            ?.substringBefore(";")
        if (!name.isNullOrBlank()) {
            runCatching { Charset.forName(name) }.getOrNull()?.let { return it }
        }
        return Charsets.UTF_8
    }

    // ——————————————————— 解析（单点可调） ———————————————————

    /** 搜索响应外壳的常见数组字段（逐层下钻，兼容 {data:{list:[...]}} 两层嵌套） */
    private val ARRAY_KEYS = listOf("data", "result", "list", "songs", "rows", "items", "records")

    /** 解析搜索响应 → OnlineSong 列表；外壳/字段名兼容常见变体，认不出就抛异常 */
    private fun parseSearchList(raw: String, source: String): List<OnlineSong> {
        val text = raw.trim()
        if (text.isEmpty()) return emptyList()
        val array = findArray(text) ?: throw IOException("unexpected search payload")
        val out = ArrayList<OnlineSong>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val id = pickScalar(
                o, listOf("id", "songid", "songId", "mid", "songmid")
            ) ?: continue
            val title = pickScalar(
                o, listOf("name", "song", "title", "songname", "songName")
            ) ?: continue
            val artist = pickText(
                o, listOf("artist", "artists", "artistName", "author", "singer", "ar")
            ) ?: ""
            val album = pickText(
                o, listOf("album", "albumname", "albumName", "al")
            ) ?: ""
            val pic = pickScalar(
                o, listOf("pic", "picUrl", "pic_url", "picurl", "cover", "artwork", "image", "img")
            )?.let { normalizeArtwork(it) }
            // P5：pic_id（封面刮削用），字段别名兼容常见变体
            val picId = pickScalar(o, listOf("pic_id", "picId", "picid", "picID"))
            out.add(
                OnlineSong(
                    source = source,
                    sourceId = id,
                    title = title,
                    artist = artist,
                    album = album,
                    artworkUrl = pic,
                    picId = picId
                )
            )
        }
        return out
    }

    /** 根文本 → JSONArray：根可为数组或含 data/result/list 等字段的对象 */
    private fun findArray(text: String): JSONArray? {
        val t = text.trim()
        return try {
            when {
                t.startsWith("[") -> JSONArray(t)
                t.startsWith("{") -> findArrayInObject(JSONObject(t), 0)
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun findArrayInObject(o: JSONObject, depth: Int): JSONArray? {
        if (depth > 3) return null
        for (key in ARRAY_KEYS) {
            when (val v = o.opt(key)) {
                is JSONArray -> return v
                is JSONObject -> findArrayInObject(v, depth + 1)?.let { return it }
            }
        }
        return null
    }

    /** 取标量字段（id/name/pic 等）：兼容字符串与数字 */
    private fun pickScalar(o: JSONObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key ->
            when (val v = o.opt(key)) {
                null, JSONObject.NULL -> null
                is String -> v.trim().takeIf { it.isNotEmpty() }
                is Number, is Boolean -> v.toString()
                else -> null
            }
        }

    /** 取文本字段：兼容字符串、数组（["A","B"] 或 [{name:...}]）、对象（{name:...}） */
    private fun pickText(o: JSONObject, keys: List<String>): String? =
        keys.firstNotNullOfOrNull { key -> textOf(o.opt(key)) }

    private fun textOf(v: Any?): String? = when (v) {
        null, JSONObject.NULL -> null
        is String -> v.trim().takeIf { it.isNotEmpty() }
        is Number, is Boolean -> v.toString()
        is JSONArray -> (0 until v.length())
            .mapNotNull { i -> textOf(v.opt(i)) }
            .filter { it.isNotEmpty() }
            .distinct()
            .takeIf { it.isNotEmpty() }
            ?.joinToString(" / ")
        is JSONObject -> textOf(v.opt("name")) ?: textOf(v.opt("title"))
        else -> null
    }

    /** 封面 URL 归一化：补全协议相对地址，非 http(s) 丢弃 */
    private fun normalizeArtwork(url: String): String? = when {
        url.startsWith("http://") || url.startsWith("https://") -> url
        url.startsWith("//") -> "https:$url"
        else -> null
    }

    /** 直链提取：兼容 {url:...} / [ {"url":...} ] / {data:{url}} / 响应即纯 URL / 混入其他文本 */
    private val URL_KEYS = listOf("url", "playUrl", "play_url", "musicUrl", "src", "data")
    private val HTTP_URL_REGEX = Regex("""https?://[^"'\s\\]+""")

    private fun extractUrl(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        if (t.startsWith("http")) return t.lineSequence().firstOrNull()?.trim()
        parseJsonOrNull(t)?.let { findUrlIn(it, 0)?.let { found -> return found } }
        return HTTP_URL_REGEX.find(t)?.value
    }

    private fun findUrlIn(v: Any?, depth: Int): String? {
        if (depth > 4) return null
        return when (v) {
            is String -> {
                val s = v.trim()
                when {
                    s.startsWith("http") -> s
                    s.startsWith("{") || s.startsWith("[") ->
                        parseJsonOrNull(s)?.let { findUrlIn(it, depth + 1) }
                    else -> null
                }
            }
            is JSONObject -> {
                for (key in URL_KEYS) {
                    findUrlIn(v.opt(key), depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until v.length()) {
                    findUrlIn(v.opt(i), depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    /** 歌词提取：兼容 {lrc:...} / {data:{lyric}} / 数组外壳 / 响应即 LRC 纯文本 */
    private val LRC_KEYS = listOf("lrc", "lyric", "lyrics", "qrc", "geci", "content", "data")
    private val LRC_TIME_TAG = Regex("""\[\d{1,3}:\d{1,2}""")

    private fun extractLyric(raw: String): String? {
        val t = raw.trim()
        if (t.isEmpty()) return null
        val json = when {
            t.startsWith("{") -> runCatching { JSONObject(t) }.getOrNull()
            // LRC 常以 [ti:]/[00:00] 开头：合法 JSON 数组才按 JSON 处理，否则当纯文本
            t.startsWith("[") -> runCatching { JSONArray(t) }.getOrNull() ?: return t
            else -> return t
        } ?: return t
        return findLyricIn(json, 0)
            ?: t.takeIf { LRC_TIME_TAG.containsMatchIn(it) }
    }

    private fun findLyricIn(v: Any?, depth: Int): String? {
        if (depth > 4) return null
        return when (v) {
            is String -> {
                val s = v.trim()
                when {
                    s.isEmpty() -> null
                    s.startsWith("{") || (s.startsWith("[") && LRC_TIME_TAG.containsMatchIn(s).not() &&
                        runCatching { JSONObject(s) }.isSuccess) -> parseJsonOrNull(s)
                        ?.let { findLyricIn(it, depth + 1) }
                    else -> s
                }
            }
            is JSONObject -> {
                for (key in LRC_KEYS) {
                    findLyricIn(v.opt(key), depth + 1)?.let { return it }
                }
                null
            }
            is JSONArray -> {
                for (i in 0 until v.length()) {
                    findLyricIn(v.opt(i), depth + 1)?.let { return it }
                }
                null
            }
            else -> null
        }
    }

    private fun parseJsonOrNull(t: String): Any? = try {
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> null
        }
    } catch (e: Exception) {
        null
    }

    /**
     * 按「歌名+歌手」规范化去重（小写、去空白），保留先出现的来源 ——
     * 输入已按来源优先级排好序，故同名曲留下的必是 migu → netease → kuwo 中首个命中的。
     */
    private fun dedupeByTitleArtist(songs: List<OnlineSong>): List<OnlineSong> {
        val seen = HashSet<String>()
        val blank = Regex("""\s+""")
        return songs.filter { song ->
            val title = song.title.lowercase().replace(blank, "")
            val artist = song.artist.lowercase().replace(blank, "")
            title.isNotEmpty() && seen.add("$title|$artist")
        }
    }
}
