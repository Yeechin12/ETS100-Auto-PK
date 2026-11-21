package com.codex.wordpkhelper.ui.overlay

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.settings.SettingsStore
import com.codex.wordpkhelper.state.CaptureBus
import com.codex.wordpkhelper.ui.glass.GlassSurface
import com.codex.wordpkhelper.ui.glass.GlassText
import com.codex.wordpkhelper.ui.glass.StatusDot
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType

/** 悬浮条两侧留白，避免玻璃边缘贴着屏幕边。 */
private val SideMargin = 12.dp

/**
 * 液态玻璃悬浮状态条，由无障碍服务挂在 `TYPE_ACCESSIBILITY_OVERLAY` 窗口里。
 *
 * 说明：这里**没有** `LocalGlassBackdrop` —— 悬浮窗的 Compose 内容自成一层，
 * Backdrop 的折射只能采样「同一个窗口里画在自己前面的像素」，在别人的 App 上面没有可采样的内容。
 * 所以这里走 [GlassSurface] 的手绘玻璃分支（半透明底 + 高光描边 + 阴影）。
 *
 * 也**不用**系统的跨窗口模糊（`FLAG_BLUR_BEHIND`）：浮窗是全屏宽的窄条，
 * 但 MIUI/HyperOS 会把它退化成整屏模糊 —— 一启用无障碍整块屏幕就糊了。
 * 详见 `WordPkAccessibilityService.applyOverlayLayout` 里的说明。
 * App 内部的界面才是真正用 Backdrop 做折射的地方。
 */
@Composable
fun OverlayGlassBar() {
    val settings by SettingsStore.state.collectAsState()
    val running by CaptureBus.running.collectAsState()
    val status by CaptureBus.status.collectAsState()
    val answer by CaptureBus.answer.collectAsState()
    val hits by CaptureBus.hitCount.collectAsState()

    val visible = settings.overlayEnabled &&
        (running || status.isNotEmpty() || settings.overlayKeepVisible)
    if (!visible) return

    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = SideMargin),
        contentAlignment = Alignment.Center,
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(GlassDimens.BarRadius),
            tint = GlassColors.Ink.copy(alpha = settings.overlayAlpha),
        ) {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                StatusDot(active = running)
                Spacer(Modifier.width(11.dp))
                Column(Modifier.weight(1f)) {
                    val headline = when {
                        answer.isNotEmpty() -> answer
                        status.isNotEmpty() -> status
                        else -> "已就绪，等待开始识别"
                    }
                    GlassText(
                        text = headline,
                        style = if (answer.isNotEmpty()) GlassType.Headline else GlassType.Label,
                        maxLines = 1,
                    )
                    val sub = when {
                        answer.isNotEmpty() && status.isNotEmpty() && status != answer -> status
                        running && hits > 0 -> "本次已命中 $hits 题"
                        else -> ""
                    }
                    if (sub.isNotEmpty()) {
                        Spacer(Modifier.height(2.dp))
                        GlassText(sub, style = GlassType.Caption, maxLines = 1)
                    }
                }
            }
        }
    }
}
