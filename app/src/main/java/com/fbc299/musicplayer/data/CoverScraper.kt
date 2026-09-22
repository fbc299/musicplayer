package com.fbc299.musicplayer.data

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/**
 * P5 本地封面刮削：按「歌名 歌手」经在线 Meting 客户端搜 netease，
 * 取相似度最高一条的 pic_id，再走 pic 端点换封面大图 URL。
 *
 * 尽力而为：任何失败返回 null、不抛异常（绝不影响本地播放）；
 * 唯独取消（CancellationException）正常传播 —— 串行刮削队列依赖它停止。
 * 网络通道完全复用 OnlineMusicSource（HttpURLConnection + Dispatchers.IO + 8s 超时）。
 */
object CoverScraper {

    /**
     * 刮削一首歌的封面 URL。
     * @param server 聚合服务地址（与在线音源共用同一服务器）
     * @return 封面 http URL；搜不到 / 解析失败 / 网络失败 → null
     */
    suspend fun scrape(server: String, title: String, artist: String): String? {
        if (server.isBlank()) return null
        val keyword = listOf(title, artist)
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .joinToString(" ")
        if (keyword.isEmpty()) return null

        // 只搜 netease：pic_id 语义与 pic 端点一致（QQ 源封面走别的端点，不混用）
        val searched = runCatching {
            OnlineMusicSource.search(server, keyword, listOf("netease"))
        }
        // runCatching 会吞掉取消异常：重新抛出，串行队列才能被取消
        currentCoroutineContext().ensureActive()
        val best = pickBest(searched.getOrNull().orEmpty(), title, artist) ?: return null

        // 搜索结果已带封面直链时直接用；否则拿 pic_id 换大图 URL
        best.artworkUrl?.let { return it }
        val picId = best.picId ?: return null
        val pic = runCatching { OnlineMusicSource.pic(server, best.source, picId) }
        currentCoroutineContext().ensureActive()
        return pic.getOrNull()?.takeIf { it.isNotBlank() }
    }

    /**
     * 选相似度最高的一条；同分取先出现的（maxByOrNull 稳定），
     * 全 0 分时退化为首条 —— 搜索结果本就按相关度排序，首条通常即目标。
     */
    private fun pickBest(results: List<OnlineSong>, title: String, artist: String): OnlineSong? {
        if (results.isEmpty()) return null
        val wantTitle = normalize(title)
        val wantArtist = normalize(artist)
        return results.maxByOrNull { song ->
            var score = 0
            val songTitle = normalize(song.title)
            if (wantTitle.isNotEmpty()) {
                when {
                    songTitle == wantTitle -> score += 4
                    songTitle.isNotEmpty() &&
                        (songTitle.contains(wantTitle) || wantTitle.contains(songTitle)) -> score += 2
                }
            }
            val songArtist = normalize(song.artist)
            if (wantArtist.isNotEmpty() && songArtist.isNotEmpty() &&
                (songArtist.contains(wantArtist) || wantArtist.contains(songArtist))
            ) {
                score += 2
            }
            score
        }
    }

    /** 归一化：小写 + 去空白与常见分隔/括号字符，便于标题相等与包含比较 */
    private fun normalize(text: String): String = text
        .lowercase()
        .replace(Regex("""[\s\-_·・.。，,、:：!！?？()（）\[\]【】'’“”"]+"""), "")
}
