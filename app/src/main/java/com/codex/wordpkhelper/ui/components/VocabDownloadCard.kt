package com.codex.wordpkhelper.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.core.VocabRemote
import com.codex.wordpkhelper.ui.glass.GlassButton
import com.codex.wordpkhelper.ui.glass.GlassCard
import com.codex.wordpkhelper.ui.glass.GlassDivider
import com.codex.wordpkhelper.ui.glass.GlassRow
import com.codex.wordpkhelper.ui.glass.GlassText
import com.codex.wordpkhelper.ui.glass.GlassToggle
import com.codex.wordpkhelper.ui.glass.StatusDot
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 一条细进度条；fraction < 0 表示服务器没给总长度，画成不确定态。 */
@Composable
private fun DownloadBar(fraction: Float, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(GlassColors.Hairline)
    ) {
        val widthFraction = if (fraction < 0f) 0.25f else fraction.coerceIn(0f, 1f)
        Box(
            Modifier
                .fillMaxWidth(widthFraction)
                .height(5.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(GlassColors.Accent)
        )
    }
}

/**
 * 主页上的紧凑提示：只在「正在下载」「下载失败」「还没下载」时出现。
 * 下载失败不影响识别 —— 内置的 198 条仍然可用。
 */
@Composable
fun VocabDownloadBanner(
    state: VocabRemote.State,
    installedCount: Int,
    builtinCount: Int,
    onUpdate: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloading = state.phase == VocabRemote.Phase.Downloading
    val failed = state.phase == VocabRemote.Phase.Failed
    val missing = installedCount == 0
    if (!downloading && !failed && !missing) return

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                active = downloading,
                color = if (failed) GlassColors.Danger else GlassColors.Accent,
            )
            Spacer(Modifier.width(8.dp))
            GlassText(
                text = when {
                    downloading -> "正在下载词表"
                    failed -> "词表下载失败"
                    else -> "目前只有内置的 $builtinCount 条词表"
                },
                style = GlassType.Headline,
            )
        }
        if (downloading) {
            Spacer(Modifier.height(10.dp))
            DownloadBar(state.progress)
            Spacer(Modifier.height(6.dp))
            GlassText(
                text = if (state.progress >= 0f) {
                    "已下载 ${(state.progress * 100).toInt()}%"
                } else {
                    "已下载 ${state.downloadedBytes / 1024} KB"
                },
                style = GlassType.Caption,
            )
        } else {
            Spacer(Modifier.height(6.dp))
            GlassText(
                text = if (failed) {
                    "${state.message}。可以重试，内置词表仍可正常使用。"
                } else {
                    "下载完整初中词表（约 3200 条），失败也不影响使用。"
                },
                style = GlassType.Caption,
            )
            Spacer(Modifier.height(10.dp))
            GlassButton(
                label = if (failed) "重试下载" else "下载完整词表",
                onClick = onUpdate,
                primary = failed,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** 设置页里的完整区块：状态、自动下载开关、立即更新、来源地址。 */
@Composable
fun VocabRemoteSection(
    state: VocabRemote.State,
    installedCount: Int,
    lastModified: Long,
    sourceUrl: String,
    autoDownload: Boolean,
    onToggleAuto: (Boolean) -> Unit,
    onUpdate: () -> Unit,
    onClear: () -> Unit,
    onUrlChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val downloading = state.phase == VocabRemote.Phase.Downloading

    GlassCard(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(
                active = installedCount > 0,
                color = if (installedCount > 0) GlassColors.Success else GlassColors.Amber,
            )
            Spacer(Modifier.width(8.dp))
            GlassText(
                text = if (installedCount > 0) "在线词表 $installedCount 条" else "还没有在线词表",
                style = GlassType.Headline,
            )
        }

        Spacer(Modifier.height(6.dp))
        val updated = if (lastModified > 0) {
            // 显式给定常量 Locale：在 composable 里读默认 Locale 属于非可观察读取
            // （lint: NonObservableLocale），而且 MM-dd HH:mm 用固定 Locale 结果才稳定
            "，更新于 " + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(lastModified))
        } else {
            ""
        }
        GlassText(
            text = "${VocabRemote.SOURCE_LABEL} · ${VocabRemote.SOURCE_LICENSE}$updated",
            style = GlassType.Caption,
        )

        if (downloading) {
            Spacer(Modifier.height(10.dp))
            DownloadBar(state.progress)
        } else if (state.phase == VocabRemote.Phase.Failed) {
            Spacer(Modifier.height(6.dp))
            GlassText(
                state.message,
                style = GlassType.Caption.copy(color = GlassColors.Danger),
            )
        } else if (state.phase == VocabRemote.Phase.Succeeded) {
            Spacer(Modifier.height(6.dp))
            GlassText(
                state.message,
                style = GlassType.Caption.copy(color = GlassColors.Success),
            )
        }

        Spacer(Modifier.height(12.dp))
        GlassButton(
            label = if (downloading) "正在下载…"
            else if (installedCount > 0) "检查更新" else "下载词表",
            onClick = onUpdate,
            enabled = !downloading,
            primary = installedCount == 0,
            modifier = Modifier.fillMaxWidth(),
        )
        if (installedCount > 0) {
            Spacer(Modifier.height(GlassDimens.ItemGap))
            GlassButton(
                label = "移除在线词表",
                onClick = onClear,
                accent = GlassColors.Danger,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(Modifier.height(GlassDimens.ItemGap))
        GlassDivider()
        GlassRow(
            title = "首次使用自动下载",
            subtitle = "装好第一次打开时自己拉一次词表；关掉就只手动更新",
            trailing = { GlassToggle(checked = autoDownload, onCheckedChange = onToggleAuto) },
        )
        GlassDivider()

        GlassText(
            text = "来源地址（留空用内置默认源）：",
            style = GlassType.Caption,
            modifier = Modifier.padding(top = 10.dp),
        )
        Spacer(Modifier.height(6.dp))
        GlassUrlField(url = sourceUrl, onUrlChange = onUrlChange)
    }
}

/** 极简 URL 输入框：不引入 material，用 BasicTextField 加玻璃底。 */
@Composable
private fun GlassUrlField(url: String, onUrlChange: (String) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(GlassDimens.ControlRadius))
            .background(GlassColors.GlassTint)
            .padding(horizontal = 14.dp, vertical = 12.dp)
    ) {
        if (url.isEmpty()) {
            GlassText(
                text = VocabRemote.DEFAULT_URL,
                style = GlassType.Caption.copy(color = GlassColors.TextDisabled),
                maxLines = 2,
            )
        }
        BasicTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            textStyle = GlassType.Caption.copy(color = GlassColors.TextPrimary),
            cursorBrush = SolidColor(GlassColors.Cyan),
            maxLines = 3,
        )
    }
}
