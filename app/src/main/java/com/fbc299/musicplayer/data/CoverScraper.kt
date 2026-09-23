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

    /** 刮削结果：url 非空即成功；失败时 reason 说明原因（写进日志便于定位） */
    data class ScrapeOutcome(val url: String?, val reason: String)

    /**
     * 刮削一首歌的封面 URL（只要结果的简化入口）。
     * @param server 聚合服务地址（与在线音源共用同一服务器）
     * @return 封面 http URL；搜不到 / 解析失败 / 网络失败 → null
     */
    suspend fun scrape(server: String, title: String, artist: String): String? =
        scrapeDetailed(server, title, artist).url

    /**
     * P9 刮削主流程：
     * 1) 标题清洗 —— 去行首音轨前缀（01 - / 03.）与包裹括号备注（(伴奏)/【Live】），
     *    清洗后非空才搜（本地文件名常常带这些，直接搜搜不到）；
     * 2) 降级搜索 —— 先「清洗后 title + artist」；结果空 → 再只搜「清洗后 title」；仍空 → 失败；
     * 3) 打分取最相似一条：带封面直链直接用，否则拿 pic_id 走 pic 端点换大图。
     *
     * 尽力而为：任何失败返回 url=null + reason，不抛异常（绝不影响本地播放）；
     * 唯独取消（CancellationException）正常传播 —— 串行刮削队列依赖它停止。
     */
    suspend fun scrapeDetailed(server: String, title: String, artist: String): ScrapeOutcome {
        if (server.isBlank()) return ScrapeOutcome(null, "server blank")

        // 标题清洗：清洗后非空才搜
        val cleaned = cleanTitle(title)
        if (cleaned.isEmpty()) return ScrapeOutcome(null, "empty title after clean")
        val artistTrim = artist.trim()
        val keyword = listOf(cleaned, artistTrim)
            .filter { it.isNotEmpty() }
            .joinToString(" ")

        // ① 组合关键词搜索 → ② 结果空则降级只搜清洗后的歌名
        val (first, firstError) = searchNetease(server, keyword)
        var results = first
        var reason = firstError ?: "no result: $keyword"
        if (results.isEmpty() && keyword != cleaned) {
            val (fallback, fallbackError) = searchNetease(server, cleaned)
            if (fallback.isNotEmpty()) {
                results = fallback
                reason = ""
            } else {
                reason = fallbackError ?: "no result (title only): $cleaned"
            }
        }
        if (results.isEmpty()) return ScrapeOutcome(null, reason)

        // 只搜 netease：pic_id 语义与 pic 端点一致（QQ 源封面走别的端点，不混用）
        val best = pickBest(results, cleaned, artistTrim)
            ?: return ScrapeOutcome(null, "no match: $keyword")

        // 搜索结果已带封面直链时直接用；否则拿 pic_id 换大图 URL
        best.artworkUrl?.let { return ScrapeOutcome(it, "") }
        val picId = best.picId ?: return ScrapeOutcome(null, "no pic_id: $keyword")
        val pic = runCatching { OnlineMusicSource.pic(server, best.source, picId) }
        currentCoroutineContext().ensureActive()
        val url = pic.getOrNull()?.takeIf { it.isNotBlank() }
        return if (url != null) ScrapeOutcome(url, "")
        else ScrapeOutcome(null, pic.exceptionOrNull()?.message ?: "pic empty: $keyword")
    }

    /** 单次搜索（只搜 netease）：失败不抛（取消除外），返回 结果 + 失败原因 */
    private suspend fun searchNetease(
        server: String,
        keyword: String
    ): Pair<List<OnlineSong>, String?> {
        val result = runCatching {
            OnlineMusicSource.search(server, keyword, listOf("netease"))
        }
        // runCatching 会吞掉取消异常：重新抛出，串行队列才能被取消
        currentCoroutineContext().ensureActive()
        val list = result.getOrNull()
        return if (list != null) list to null
        else emptyList<OnlineSong>() to (result.exceptionOrNull()?.message ?: "search failed")
    }

    /** 行首音轨前缀：「01 - 」「03.」「1_」「02 」等；纯数字标题（1984 / 100）不会被误伤 */
    private val TRACK_PREFIX_REGEX = Regex("""^\d{1,3}(?:\s*[-._·]\s*|\s+)""")

    /** 包裹歌名的括号备注：「(伴奏)」「【Live】」「（2019 remaster）」等 */
    private val BRACKET_NOTE_REGEX = Regex("""[\(\[【（][^\)\]】）]*[\)\]】）]""")

    /**
     * P9 标题清洗（用于构造搜索关键词，打分比对同样用清洗后的标题）：
     * 先去行首音轨前缀，再去包裹括号备注（最多剥 3 轮防呆）。
     */
    private fun cleanTitle(raw: String): String {
        var title = TRACK_PREFIX_REGEX.replace(raw.trim(), "")
        var previous = ""
        for (attempt in 0 until 3) {
            if (title == previous) break
            previous = title
            title = BRACKET_NOTE_REGEX.replace(title, "")
        }
        return title.trim()
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
