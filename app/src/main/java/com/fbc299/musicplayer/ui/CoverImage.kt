package com.fbc299.musicplayer.ui

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import coil.compose.AsyncImage

/**
 * P5 刮削封面映射（key → 封面 URL）：由 MainActivity 在组合根提供，
 * 歌曲列表行 / 专辑格 / 专辑详情头 / 播放页大封面 / 迷你条 统一读取。
 */
val LocalScrapedCovers = staticCompositionLocalOf<Map<String, String>>(emptyMap())

/**
 * 统一封面组件：本地封面 → 刮削封面 → 占位图 三级 fallback。
 *
 * - stage 0：优先加载 localModel（本地 artworkUri；在线曲目则为封面 URL）；
 * - stage 1：本地加载失败 → 有刮削 URL 则加载（Coil 自带磁盘缓存），否则直接占位；
 *   若刮削结果后到，会在占位时自动补加载；
 * - stage 2 / 无刮削可试：占位图（与原实现一致）。
 *
 * @param localModel 首选模型（本地 artworkUri，或在线曲目的封面 URL）
 * @param coverKey 刮削映射键（本地 song id / 播放 mediaId）；null 表示不查刮削
 * @param modifier 由调用方决定尺寸/圆角/背景（与原 AsyncImage 用法一致）
 */
@Composable
fun CoverImage(
    localModel: Any?,
    coverKey: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop
) {
    // 0=本地 1=刮削 2=占位；键变化（行内容变化）时重置
    var stage by remember(localModel, coverKey) { mutableIntStateOf(0) }
    val scrapedUrl = coverKey?.let { LocalScrapedCovers.current[it] }
    val model: Any? = when {
        stage == 0 && localModel != null -> localModel
        stage <= 1 && scrapedUrl != null -> scrapedUrl
        else -> null
    }
    if (model == null) {
        // 三级兜底：占位图（原 3 处封面的 error 占位保持不变）
        Image(
            painter = painterResource(android.R.drawable.ic_media_play),
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier
        )
    } else {
        AsyncImage(
            model = model,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier,
            onError = { if (stage < 2) stage++ }
        )
    }
}
