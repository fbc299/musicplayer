package com.fbc299.musicplayer.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.fbc299.musicplayer.BuildConfig
import com.fbc299.musicplayer.R
import com.fbc299.musicplayer.data.OnlineSettings
import com.fbc299.musicplayer.data.ScanSettings

/** 设置 Tab：最短时长过滤 + 文件大小过滤 + 在线音源 + 关于 */
@Composable
fun SettingsTab(
    settings: ScanSettings,
    onFilterChanged: (Boolean) -> Unit,
    onThresholdChanged: (Int) -> Unit,
    onSizeFilterChanged: (Boolean) -> Unit,
    onSizeThresholdChanged: (Int) -> Unit,
    onlineSettings: OnlineSettings,
    onOnlineEnabledChanged: (Boolean) -> Unit,
    onServerUrlChanged: (String) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp)
    ) {
        SectionTitle(text = stringResource(R.string.settings_library))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.filter_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.filter_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(0.05f))
            Switch(
                checked = settings.filterEnabled,
                onCheckedChange = onFilterChanged,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }

        if (settings.filterEnabled) {
            var pending by remember(settings.minDurationSec) {
                mutableFloatStateOf(settings.minDurationSec.toFloat())
            }
            Text(
                text = stringResource(R.string.threshold_value, settings.minDurationSec),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            Slider(
                value = pending,
                onValueChange = { pending = it },
                onValueChangeFinished = { onThresholdChanged(pending.toInt()) },
                valueRange = 1f..60f,
                steps = 58,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }

        Spacer(Modifier.height(16.dp))

        // —— 文件大小过滤 ——
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.size_filter_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.size_filter_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(0.05f))
            Switch(
                checked = settings.sizeFilterEnabled,
                onCheckedChange = onSizeFilterChanged,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }

        if (settings.sizeFilterEnabled) {
            var pendingKb by remember(settings.minSizeKb) {
                mutableFloatStateOf(settings.minSizeKb.toFloat())
            }
            Text(
                text = stringResource(
                    R.string.size_threshold_value,
                    formatSizeKb(pendingKb)
                ),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 8.dp)
            )
            // 非线性滑杆：0–1 MB 占 60% 行程（小值好调），1–10 MB 占 40%
            Slider(
                value = sizePositionFromKb(pendingKb),
                onValueChange = { pos ->
                    pendingKb = snapSizeKb(sizeKbFromPosition(pos))
                },
                onValueChangeFinished = {
                    onSizeThresholdChanged(snapSizeKb(pendingKb).toInt())
                },
                valueRange = 0f..1f,
                colors = SliderDefaults.colors(
                    thumbColor = MaterialTheme.colorScheme.primary,
                    activeTrackColor = MaterialTheme.colorScheme.primary,
                    inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }

        Spacer(Modifier.height(16.dp))

        // —— 在线音源（P4） ——
        SectionTitle(text = stringResource(R.string.settings_online))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.online_enabled),
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onBackground
                )
                Text(
                    text = stringResource(R.string.online_enabled_desc),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Spacer(Modifier.weight(0.05f))
            Switch(
                checked = onlineSettings.enabled,
                onCheckedChange = onOnlineEnabledChanged,
                colors = SwitchDefaults.colors(
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary
                )
            )
        }

        if (onlineSettings.enabled) {
            // 本地状态不随每次回写重建，避免逐字输入时光标跳动
            var serverText by remember { mutableStateOf(onlineSettings.serverUrl) }
            OutlinedTextField(
                value = serverText,
                onValueChange = { text ->
                    serverText = text
                    onServerUrlChanged(text)
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                label = {
                    Text(stringResource(R.string.online_server_label))
                },
                placeholder = {
                    Text(stringResource(R.string.online_server_hint))
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedBorderColor = MaterialTheme.colorScheme.surfaceVariant,
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface
                )
            )
        }

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.surfaceVariant)
        Spacer(Modifier.height(16.dp))

        SectionTitle(text = stringResource(R.string.settings_about))
        Text(
            text = stringResource(R.string.about_version, BuildConfig.VERSION_NAME),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(vertical = 4.dp)
        )
        Text(
            text = stringResource(R.string.about_text),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 16.dp, bottom = 4.dp)
    )
}

// —— 文件大小滑杆映射（KB ↔ 0..1 位置）——
// 分段线性：0–1 MB（1024 KB）占前 60% 行程，1–10 MB 占后 40%，小阈值更易微调
private const val SIZE_SEGMENT_A_POS = 0.6f
private const val SIZE_SEGMENT_A_KB = 1024f // 1 MB
private const val SIZE_MAX_KB = 10240f // 10 MB

private fun sizePositionFromKb(kb: Float): Float =
    if (kb <= SIZE_SEGMENT_A_KB) {
        kb / SIZE_SEGMENT_A_KB * SIZE_SEGMENT_A_POS
    } else {
        SIZE_SEGMENT_A_POS +
            (kb - SIZE_SEGMENT_A_KB) / (SIZE_MAX_KB - SIZE_SEGMENT_A_KB) *
            (1f - SIZE_SEGMENT_A_POS)
    }

private fun sizeKbFromPosition(pos: Float): Float =
    if (pos <= SIZE_SEGMENT_A_POS) {
        pos / SIZE_SEGMENT_A_POS * SIZE_SEGMENT_A_KB
    } else {
        SIZE_SEGMENT_A_KB +
            (pos - SIZE_SEGMENT_A_POS) / (1f - SIZE_SEGMENT_A_POS) *
            (SIZE_MAX_KB - SIZE_SEGMENT_A_KB)
    }

// 吸附到 4 KB：200 KB / 500 KB / 1 MB 等常用阈值均可精确命中
private fun snapSizeKb(kb: Float): Float =
    (Math.round(kb / 4f) * 4f).coerceIn(0f, SIZE_MAX_KB)

/** 阈值展示：「500 KB」「1.5 MB」 */
private fun formatSizeKb(kb: Float): String =
    if (kb >= SIZE_SEGMENT_A_KB) "%.1f MB".format(kb / 1024f)
    else "%d KB".format(Math.round(kb))
