package com.codex.wordpkhelper.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.core.VocabRemote
import com.codex.wordpkhelper.core.VocabStore
import com.codex.wordpkhelper.settings.SettingsStore
import com.codex.wordpkhelper.ui.components.VocabRemoteSection
import com.codex.wordpkhelper.ui.glass.GlassCard
import com.codex.wordpkhelper.ui.glass.GlassDivider
import com.codex.wordpkhelper.ui.glass.GlassRow
import com.codex.wordpkhelper.ui.glass.GlassText
import com.codex.wordpkhelper.ui.glass.SectionTitle
import com.codex.wordpkhelper.ui.glass.liquid.LiquidSlider
import com.codex.wordpkhelper.ui.glass.liquid.LiquidToggle
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType
import com.kyant.backdrop.Backdrop

/**
 * 设置页。
 *
 * [glassBackdrop] 必须是**只录了极光背景的那一层**（见 `WordPkApp`）：本页位于内容录制层
 * 内部，若拿内容层当折射源，滑杆就会采样「包含它自己的那一层」，RenderNode 会无限嵌套。
 */
@Composable
fun SettingsPage(
    vocabRevision: Int,
    glassBackdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val settings by SettingsStore.state.collectAsState()
    val context = LocalContext.current
    val remoteState by VocabRemote.state.collectAsState()
    val summary = remember(vocabRevision, remoteState.revision) { VocabStore.summary(context) }
    val remoteLastModified = remember(vocabRevision, remoteState.revision) {
        VocabRemote.lastModified(context)
    }
    val versionName = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "—"
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlassDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(GlassDimens.ItemGap),
    ) {
        Spacer(Modifier.height(GlassDimens.TopBarSpace))

        SectionTitle("识别节奏")
        GlassCard {
            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "识别间隔",
                valueText = "${settings.frameIntervalMs} ms",
                value = settings.frameIntervalMs.toFloat(),
                range = 250f..2500f,
                subtitle = "两帧之间至少间隔这么久。调小更跟手，但更耗电。",
            ) { SettingsStore.update { s -> s.copy(frameIntervalMs = it.toLong()) } }

            GlassDivider()

            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "点击间隔",
                valueText = "${settings.tapIntervalMs} ms",
                value = settings.tapIntervalMs.toFloat(),
                range = 500f..6000f,
                subtitle = "两次点击之间的最短间隔，防止同一帧被反复点。",
            ) { SettingsStore.update { s -> s.copy(tapIntervalMs = it.toLong()) } }

            GlassDivider()

            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "同答案冷却",
                valueText = "${settings.sameAnswerCooldownMs} ms",
                value = settings.sameAnswerCooldownMs.toFloat(),
                range = 0f..20000f,
                subtitle = "答案与位置都没变时的重复点击抑制时长。",
            ) { SettingsStore.update { s -> s.copy(sameAnswerCooldownMs = it.toLong()) } }

            GlassDivider()

            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "位移容差",
                valueText = "${settings.moveTolerancePx} px",
                value = settings.moveTolerancePx.toFloat(),
                range = 0f..200f,
                subtitle = "答案框移动超过这个距离就当作换了新题，允许立刻再点。",
            ) { SettingsStore.update { s -> s.copy(moveTolerancePx = it.toInt()) } }
        }

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        SectionTitle("悬浮玻璃条")
        GlassCard {
            GlassRow(
                title = "显示悬浮条",
                subtitle = "关闭后不再占用屏幕空间",
                trailing = {
                    LiquidToggle(
                        selected = { settings.overlayEnabled },
                        onSelect = { v ->
                            SettingsStore.update { it.copy(overlayEnabled = v) }
                        },
                        backdrop = glassBackdrop,
                    )
                },
            )
            GlassDivider()
            GlassRow(
                title = "空闲时也显示",
                subtitle = "没有识别结果时保留一条「已就绪」提示",
                trailing = {
                    LiquidToggle(
                        selected = { settings.overlayKeepVisible },
                        onSelect = { v ->
                            SettingsStore.update { it.copy(overlayKeepVisible = v) }
                        },
                        backdrop = glassBackdrop,
                    )
                },
            )
            GlassDivider()
            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "表面不透明度",
                valueText = "${(settings.overlayAlpha * 100).toInt()}%",
                value = settings.overlayAlpha,
                range = 0.1f..1f,
                subtitle = "越透明越能看清背后的题目，越不透明文字越清楚。",
            ) { SettingsStore.update { s -> s.copy(overlayAlpha = it) } }

            GlassDivider()
            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "距状态栏",
                valueText = "${settings.overlayTopDp} dp",
                value = settings.overlayTopDp.toFloat(),
                range = 0f..200f,
            ) { SettingsStore.update { s -> s.copy(overlayTopDp = it.toInt()) } }
        }

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        SectionTitle("玻璃效果")
        GlassCard {
            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "折射强度",
                valueText = "${settings.glassRefractionDp.toInt()} dp",
                value = settings.glassRefractionDp,
                range = 0f..40f,
                subtitle = "界面里玻璃面板对背景的弯折程度，0 表示只做模糊。",
            ) { SettingsStore.update { s -> s.copy(glassRefractionDp = it) } }

            GlassDivider()
            SettingSlider(
                glassBackdrop = glassBackdrop,
                title = "模糊半径",
                valueText = "${settings.glassBlurDp.toInt()} dp",
                value = settings.glassBlurDp,
                range = 0f..20f,
            ) { SettingsStore.update { s -> s.copy(glassBlurDp = it) } }
        }

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        SectionTitle("在线词表")
        VocabRemoteSection(
            state = remoteState,
            installedCount = summary.remoteSize,
            lastModified = remoteLastModified,
            sourceUrl = settings.vocabUrl,
            autoDownload = settings.autoDownloadVocab,
            glassBackdrop = glassBackdrop,
            onToggleAuto = { enabled ->
                SettingsStore.update { it.copy(autoDownloadVocab = enabled) }
            },
            onUpdate = { VocabRemote.refresh(context, settings.vocabUrl.ifBlank { null }) },
            onClear = { VocabRemote.clear(context) },
            onUrlChange = { value -> SettingsStore.update { it.copy(vocabUrl = value) } },
        )

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        SectionTitle("关于")
        GlassCard {
            InfoRow("版本", versionName)
            GlassDivider()
            InfoRow("内置词表", "${summary.builtinSize} 条")
            GlassDivider()
            InfoRow("自定义词表", "${summary.customSize} 条")
            Spacer(Modifier.height(12.dp))
            GlassText(
                text = "截屏只在本机解码并交给 ML Kit 识别，应用没有申请网络权限，" +
                    "图片不会离开设备。无障碍权限仅用于代点选项。",
                style = GlassType.Caption,
            )
        }

        Spacer(Modifier.height(GlassDimens.BottomBarSpace))
    }
}

@Composable
private fun SettingSlider(
    glassBackdrop: Backdrop,
    title: String,
    valueText: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    subtitle: String? = null,
    onChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            GlassText(title, style = GlassType.Headline, modifier = Modifier.weight(1f))
            GlassText(valueText, style = GlassType.Mono)
        }
        if (subtitle != null) {
            Spacer(Modifier.height(3.dp))
            GlassText(subtitle, style = GlassType.Caption)
        }
        // LiquidSlider 内部用 snapshotFlow 监听 value，所以这里必须传一个「读快照状态」的
        // lambda。直接把 Float 参数捕进去会闭包住首次组合的值，外部改动就同步不进来了。
        val latestValue by rememberUpdatedState(value)
        LiquidSlider(
            value = { latestValue },
            onValueChange = onChange,
            valueRange = range,
            // 阈值取量程的千分之一：太小会让回弹动画一直停不下来。
            visibilityThreshold = (range.endInclusive - range.start) / 1000f,
            backdrop = glassBackdrop,
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
        )
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlassText(label, style = GlassType.Headline, modifier = Modifier.weight(1f))
        GlassText(value, style = GlassType.Mono.copy(color = GlassColors.Cyan))
    }
}
