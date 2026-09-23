package com.fbc299.musicplayer.data

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.fbc299.musicplayer.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL

/**
 * P13 下载结果（密封类三种终态，调用方 when 分支即可，不再混用 Result 与特判）：
 * - [Saved]：已落盘（path = 目标绝对路径）
 * - [AlreadyExists]：去重命中（MediaStore 同名或目标文件已存在），未重复写入
 * - [Failed]：失败原因（仅记日志，用户文案由 LibraryViewModel 映射 strings.xml）
 */
sealed class DownloadResult {
    data class Saved(val path: String) : DownloadResult()
    object AlreadyExists : DownloadResult()
    data class Failed(val reason: String) : DownloadResult()
}

/**
 * P13 在线歌曲下载器：把播放直链流式写入公共音乐目录 `Music/格格音乐/`，
 * 使文件进入系统媒体库 → 本应用重扫后即出现在本地歌曲列表（离线可播）。
 *
 * 落盘双路径：
 * - **≥29（主路径，免权限）**：`MediaStore.Audio.Media` 插入（不能用 Downloads 集合，
 *   否则进不了音频列表）+ `RELATIVE_PATH=Music/格格音乐/` + `IS_PENDING=1` 写入完成清 pending。
 *   本应用插入的条目无需任何存储权限即可见、可查询。
 * - **26–28（回退）**：`Environment.getExternalStoragePublicDirectory(DIRECTORY_MUSIC)/格格音乐/`
 *   直接写文件 + `MediaScannerConnection.scanFile` 入库；写前校验 WRITE_EXTERNAL_STORAGE
 *   （Manifest 声明 maxSdkVersion=28）已授权，未授权返回 Failed，UI toast 引导（不做复杂权限流程）。
 *
 * 网络用 HttpURLConnection（项目无 OkHttp）：15s 连接/读取超时 + UA/Referer 头
 * （部分 CDN 挑 UA；网易云音频域要 Referer，对其余源无害），HTTP 非 2xx 返回 Failed，
 * 协程取消（CancellationException）原样传播、不吞。
 */
object SongDownloader {


    private const val CONNECT_TIMEOUT_MS = 15_000
    private const val READ_TIMEOUT_MS = 15_000

    /** 拷贝缓冲区大小（8KB） */
    private const val BUFFER_SIZE = 8 * 1024

    /** 直链下载 UA：部分 CDN 按 UA 分发（空 UA / 异常 UA 直接 403） */
    private const val USER_AGENT = "Mozilla/5.0 (Linux; Android 13)"

    /** 网易云音频/图片域防盗链要 Referer；对其余数据源无害 */
    private const val REFERER = "http://music.163.com/"

    private const val TAG = "SongDownloader"

    /** 公共音乐目录下的子目录名（strings 非翻译项，避免 Kotlin 硬编码中文） */
    private fun subDirName(context: Context): String =
        context.getString(R.string.download_sub_dir)

    /** 文件名非法字符（文件系统保留）：/\?%*:|"<> */
    private val ILLEGAL_CHARS = Regex("""[/\\?%*:|"<>]""")

    /** ASCII 控制字符（0x00–0x1F 与 0x7F DEL） */
    private val CONTROL_CHARS = Regex("[\\u0000-\\u001F\\u007F]")

    /**
     * 下载一首歌到公共 Music/格格音乐/（Dispatchers.IO 上执行）。
     *
     * @param context 用于 MediaStore ContentResolver、权限检查与（≤28）媒体扫描
     * @param url 播放直链（LibraryViewModel 先经 OnlineMusicSource.resolveUrl 解析）
     * @return [DownloadResult]：Saved / AlreadyExists / Failed；
     *   协程被取消时 CancellationException 正常传播（不转成 Failed）
     */
    suspend fun save(
        context: Context,
        url: String,
        title: String,
        artist: String
    ): DownloadResult = withContext(Dispatchers.IO) {
        if (url.isBlank()) return@withContext DownloadResult.Failed("blank url")
        val fileName = fileNameOf(title, artist)

        // —— 去重：MediaStore 同名（DISPLAY_NAME 精确匹配）或目标文件已存在 → 不重复下载 ——
        if (existsInMediaStore(context, fileName) ||
            targetFile(context, fileName).exists()
        ) {
            return@withContext DownloadResult.AlreadyExists
        }
        // 26–28 回退路径要写公共目录：未授权如实降级（旧设备罕见，不做复杂权限流程）
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
            context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return@withContext DownloadResult.Failed("WRITE_EXTERNAL_STORAGE not granted")
        }

        var connection: HttpURLConnection? = null
        try {
            val conn = URL(url).openConnection() as? HttpURLConnection
                ?: return@withContext DownloadResult.Failed("unsupported url: $url")
            connection = conn
            conn.connectTimeout = CONNECT_TIMEOUT_MS
            conn.readTimeout = READ_TIMEOUT_MS
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.setRequestProperty("Referer", REFERER)
            conn.setRequestProperty("Accept", "*/*")
            val code = conn.responseCode
            if (code !in 200..299) {
                return@withContext DownloadResult.Failed("HTTP $code for $url")
            }
            // 分路径落盘（SDK_INT 分支内使用 RELATIVE_PATH / IS_PENDING，编译期均为 String 常量）
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                saveViaMediaStore(context, conn.inputStream, fileName)
            } else {
                saveViaFile(context, targetFile(context, fileName), conn.inputStream)
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "download failed: $url -> ${e.message}")
            DownloadResult.Failed(e.message ?: e.javaClass.simpleName)
        } finally {
            connection?.disconnect()
        }
    }

    /**
     * ≥29 主路径（免权限）：MediaStore 音频集合插入 pending 条目 → 流式写入 → 清 pending。
     * 失败/取消时删除 pending 残留行，不留半成品文件。
     */
    private suspend fun saveViaMediaStore(
        context: Context,
        input: InputStream,
        fileName: String
    ): DownloadResult {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, fileName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mpeg")
            put(
                MediaStore.Audio.Media.RELATIVE_PATH,
                "${Environment.DIRECTORY_MUSIC}/${subDirName(context)}"
            )
            put(MediaStore.Audio.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
            ?: return DownloadResult.Failed("mediastore insert returned null")
        var completed = false
        try {
            val output = resolver.openOutputStream(uri)
                ?: return DownloadResult.Failed("cannot open output stream")
            output.use { sink -> copyStream(input, sink) }
            values.clear()
            values.put(MediaStore.Audio.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            completed = true
        } finally {
            if (!completed) runCatching { resolver.delete(uri, null, null) }
        }
        return DownloadResult.Saved(targetFile(context, fileName).absolutePath)
    }

    /**
     * 26–28 回退：直接写公共目录文件（目录不存在先建）+ MediaScannerConnection 入库；
     * 写入失败/取消时删除残缺文件（写前已校验写权限）。
     */
    private suspend fun saveViaFile(
        context: Context,
        target: File,
        input: InputStream
    ): DownloadResult {
        val dir = target.parentFile ?: return DownloadResult.Failed("no target dir")
        if (!dir.isDirectory && !dir.mkdirs()) {
            return DownloadResult.Failed("cannot create dir: ${dir.absolutePath}")
        }
        var completed = false
        try {
            target.outputStream().use { sink -> copyStream(input, sink) }
            completed = true
        } finally {
            if (!completed) runCatching { target.delete() }
        }
        MediaScannerConnection.scanFile(
            context,
            arrayOf(target.absolutePath),
            arrayOf("audio/mpeg"),
            null
        )
        return DownloadResult.Saved(target.absolutePath)
    }

    /** 流式拷贝：8KB 缓冲循环；每次读取前检查取消，CancellationException 正常传播 */
    private suspend fun copyStream(input: InputStream, output: OutputStream) {
        val buffer = ByteArray(BUFFER_SIZE)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = input.read(buffer)
            if (read < 0) break
            output.write(buffer, 0, read)
        }
        output.flush()
    }

    /** MediaStore 同名去重查询（DISPLAY_NAME 精确匹配，随本应用音频读权限生效）；查询异常按不存在处理 */
    private fun existsInMediaStore(context: Context, fileName: String): Boolean =
        runCatching {
            context.contentResolver.query(
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
                arrayOf(MediaStore.Audio.Media._ID),
                "${MediaStore.Audio.Media.DISPLAY_NAME}=?",
                arrayOf(fileName),
                null
            )?.use { cursor -> cursor.count > 0 }
        }.getOrNull() == true

    /**
     * 目标文件绝对路径 `Music/格格音乐/{文件名}`：
     * ≥29 时 MediaStore 的 RELATIVE_PATH=Music/格格音乐 指向同一路径，
     * 供返回值（Saved.path）、文件存在性去重两处共用。
     */
    private fun targetFile(context: Context, fileName: String): File = File(
        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC),
        "${subDirName(context)}/$fileName"
    )

    /**
     * 目标文件名 `{标题} - {歌手}.mp3`：清洗非法字符与控制字符；
     * 标题空（或清洗后为空）回退 `download_{时间戳}`；歌手空则省略「 - 歌手」段。
     */
    private fun fileNameOf(title: String, artist: String): String {
        val stamp = System.currentTimeMillis()
        val raw = when {
            title.isBlank() -> "download_$stamp"
            artist.isBlank() -> title
            else -> "$title - $artist"
        }
        val cleaned = raw
            .replace(ILLEGAL_CHARS, "_")
            .replace(CONTROL_CHARS, "_")
            .trim()
            .trim('_')
            .trim()
        val base = cleaned.ifEmpty { "download_$stamp" }
        return "$base.mp3"
    }
}
