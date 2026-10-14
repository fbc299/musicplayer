package com.fbc299.musicplayer.playback

import android.content.ComponentName
import android.content.Context
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.fbc299.musicplayer.data.Song
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.time.Duration.Companion.milliseconds

/** 循环模式三态（对应 Media3 REPEAT_MODE_*） */
enum class RepeatMode { OFF, ALL, ONE }

object PlayerConnection {

    @Volatile
    var controller: MediaController? = null
        private set

    private var future: ListenableFuture<MediaController>? = null

    /** P15-2 播放状态周期保存协程作用域（IO 线程，不随 UI 生命周期取消） */
    private val saverScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saverJob: Job? = null
    private var started = false

    @Synchronized
    fun connect(context: Context) {
        if (future != null) return
        val token = SessionToken(
            context.applicationContext,
            ComponentName(context.applicationContext, PlaybackService::class.java)
        )
        val f = MediaController.Builder(context.applicationContext, token).buildAsync()
        future = f
        f.addListener({
            try {
                controller = f.get()
                // P15-2：controller 首次就绪后启动周期保存
                startStateSaver(context.applicationContext)
            } catch (_: Exception) {
                future = null
            }
        }, MoreExecutors.directExecutor())
    }

    /**
     * P15-2 等待 controller 就绪（冷启动恢复用）：
     * 已就绪立即返回；否则挂起直到 future 完成或超时（超时返回 false，调用方跳过恢复）。
     */
    suspend fun whenReady(timeoutMs: Long = 10_000): Boolean {
        controller?.let { return true }
        val f = future ?: return false
        var completed = false
        val result = withTimeoutOrNull(timeoutMs.milliseconds) {
            suspendCancellableCoroutine { cont ->
                if (f.isDone) {
                    completed = true
                    cont.resume(true)
                    return@suspendCancellableCoroutine
                }
                f.addListener({
                    try {
                        controller = f.get()
                        completed = true
                    } catch (_: Exception) {
                        completed = false
                    }
                    cont.resume(completed)
                }, MoreExecutors.directExecutor())
            }
        } ?: false
        return result
    }

    /**
     * P15-2 冷启动恢复播放：等 controller 就绪 → 读持久化状态 → 有数据则重放队列并续播。
     * 恢复失败（无数据 / 无效 URI / 超时）静默跳过，绝不影响冷启动。
     * 调用方在应用冷启动时跑一次即可（lifecycleScope + 进程级一次性守卫）。
     * @return 是否成功恢复了播放（供调用方决定要不要打开播放页等，当前仅返回结果）
     */
    suspend fun restorePlayback(context: Context, timeoutMs: Long = 10_000): Boolean {
        if (!whenReady(timeoutMs)) return false
        val saved = PlaybackStateStore.load(context) ?: return false
        val items = PlaybackStateStore.toMediaItems(saved)
        if (items.isEmpty()) return false
        return runCatching {
            val c = controller ?: return false
            c.setMediaItems(items, saved.index, saved.positionMs)
            c.prepare()
            c.play()
        }.getOrElse {
            // 恢复路径出错（如本地 content:// 已失效）：保持静默，App 正常冷启动
            false
        }
    }

    /**
     * P15-2 启动播放状态周期保存：每 15 秒取一次当前队列/位置落盘。
     * 幂等（started 守卫）；协程持有于 saverScope（应用进程级，杀进程才真正停）。
     */
    private fun startStateSaver(context: Context) {
        if (started) return
        started = true
        saverJob = saverScope.launch {
            while (true) {
                delay(15_000)
                val c = controller ?: continue
                if (c.mediaItemCount == 0) continue
                try {
                    PlaybackStateStore.save(
                        context,
                        c.mediaItems,
                        c.currentMediaItemIndex,
                        c.currentPosition
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // 落盘失败静默（尽力而为，不影响播放）
                }
            }
        }
    }

    fun playSongs(songs: List<Song>, startIndex: Int) {
        val c = controller ?: return
        val items = songs.map { it.toMediaItem() }
        c.setMediaItems(items, startIndex, 0)
        c.prepare()
        c.play()
    }

    /**
     * P4 在线曲目播放：把 [items] 插入当前队列的下一位并立即从 [startIndex] 播放。
     * 队列为空时插入位为 0（等价于直接作为整个队列）；
     * 队列非空时保留用户已有曲目上下文，播完在线曲目按「下一首」可回到原队列。
     */
    fun playOnline(items: List<MediaItem>, startIndex: Int) {
        val c = controller ?: return
        if (items.isEmpty()) return
        // 队列为空时 currentMediaItemIndex 为 INDEX_UNSET（Int.MIN_VALUE），coerceIn 归到 0
        val base = (c.currentMediaItemIndex + 1).coerceIn(0, c.mediaItemCount)
        c.addMediaItems(base, items)
        c.seekTo(base + startIndex, 0L)
        c.prepare()
        c.play()
    }

    fun togglePlay() {
        val c = controller ?: return
        if (c.isPlaying) c.pause() else c.play()
    }

    fun seekTo(positionMs: Long) {
        controller?.seekTo(positionMs)
    }

    fun next() {
        controller?.seekToNextMediaItem()
    }

    fun previous() {
        controller?.seekToPreviousMediaItem()
    }

    fun shuffle(enabled: Boolean) {
        val c = controller ?: return
        c.shuffleModeEnabled = enabled
    }

    /** 设置循环模式 */
    fun setRepeatMode(mode: RepeatMode) {
        val c = controller ?: return
        c.repeatMode = when (mode) {
            RepeatMode.OFF -> Player.REPEAT_MODE_OFF
            RepeatMode.ALL -> Player.REPEAT_MODE_ALL
            RepeatMode.ONE -> Player.REPEAT_MODE_ONE
        }
    }

    /** 按 关闭 → 列表循环 → 单曲循环 → 关闭 的顺序切换，返回切换后的模式 */
    fun cycleRepeatMode(): RepeatMode {
        val c = controller ?: return RepeatMode.OFF
        val next = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
        c.repeatMode = next
        return when (next) {
            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
            else -> RepeatMode.OFF
        }
    }

    /** 跳转到播放队列中的指定位置 */
    fun jumpTo(index: Int) {
        val c = controller ?: return
        if (index < 0 || index >= c.mediaItemCount) return
        c.seekTo(index, 0L)
        c.play()
    }

    private fun Song.toMediaItem(): MediaItem =
        MediaItem.Builder()
            .setMediaId(id.toString())
            .setUri(uri)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setArtist(artist)
                    .setAlbumTitle(album)
                    .setArtworkUri(artworkUri)
                    .build()
            )
            .build()
}
