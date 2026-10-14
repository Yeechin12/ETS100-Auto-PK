package com.codex.wordpkhelper.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.settings.RunMode
import com.codex.wordpkhelper.settings.SettingsStore
import com.codex.wordpkhelper.state.CaptureBus
import com.codex.wordpkhelper.ui.AppActions
import com.codex.wordpkhelper.ui.glass.GlassButton
import com.codex.wordpkhelper.ui.glass.GlassCard
import com.codex.wordpkhelper.ui.glass.GlassText
import com.codex.wordpkhelper.ui.glass.SectionTitle
import com.codex.wordpkhelper.ui.glass.StatusDot
import com.codex.wordpkhelper.ui.glass.liquid.LiquidBottomTab
import com.codex.wordpkhelper.ui.glass.liquid.LiquidBottomTabs
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType
import com.kyant.backdrop.Backdrop

@Composable
fun HomePage(
    actions: AppActions,
    glassBackdrop: Backdrop,
    modifier: Modifier = Modifier,
) {
    val settings by SettingsStore.state.collectAsState()
    val running by CaptureBus.running.collectAsState()
    val status by CaptureBus.status.collectAsState()
    val answer by CaptureBus.answer.collectAsState()
    val hits by CaptureBus.hitCount.collectAsState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = GlassDimens.ScreenPadding),
        verticalArrangement = Arrangement.spacedBy(GlassDimens.ItemGap),
    ) {
        // 顶部留白：让首屏内容从玻璃栏下方开始；滚动后内容会滑到栏下面被折射
        Spacer(Modifier.height(GlassDimens.TopBarSpace))

        GlassCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(active = running)
                Spacer(Modifier.width(8.dp))
                GlassText(
                    text = if (running) "识别中" else "未开始",
                    style = GlassType.Caption,
                )
                Spacer(Modifier.weight(1f))
                if (hits > 0) {
                    GlassText("已命中 $hits 题", style = GlassType.Caption)
                }
            }
            Spacer(Modifier.height(14.dp))
            GlassText(
                text = answer.ifEmpty { "—" },
                style = if (answer.isEmpty()) {
                    GlassType.Display.copy(color = GlassColors.TextDisabled)
                } else {
                    GlassType.Display
                },
                maxLines = 1,
            )
            Spacer(Modifier.height(6.dp))
            GlassText(
                text = status.ifEmpty { "开始后切到 e听说中学的单词 PK 页面即可" },
                style = GlassType.Body,
            )
        }

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        SectionTitle("运行模式")
        // 用库里的 LiquidBottomTabs 当二选一：和底部导航是同一套液态玻璃，
        // 指示器同样可以点、可以拖。Catalog 里它本来就被当作通用选项条用。
        LiquidBottomTabs(
            selectedTabIndex = { if (settings.runMode == RunMode.Auto) 0 else 1 },
            onTabSelected = { index ->
                actions.setRunMode(if (index == 0) RunMode.Auto else RunMode.ShowOnly)
            },
            backdrop = glassBackdrop,
            tabsCount = 2,
            modifier = Modifier.fillMaxWidth(),
        ) {
            listOf("自动点击", "仅显示答案").forEachIndexed { index, label ->
                val selected = (settings.runMode == RunMode.Auto) == (index == 0)
                LiquidBottomTab(
                    index = index,
                    onClick = {
                        actions.setRunMode(if (index == 0) RunMode.Auto else RunMode.ShowOnly)
                    },
                ) {
                    GlassText(
                        text = label,
                        style = GlassType.Headline.copy(
                            color = if (selected) GlassColors.TextPrimary
                            else GlassColors.TextTertiary
                        ),
                    )
                }
            }
        }
        GlassText(
            text = if (settings.runMode == RunMode.Auto) {
                "识别到答案后会代你点选；点击失败会在状态里提示。"
            } else {
                "只把答案显示在悬浮条上，不代点。适合自己作答时对答案。"
            },
            modifier = Modifier.padding(horizontal = 6.dp),
            style = GlassType.Caption,
        )

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        GlassButton(
            label = if (running) "停止识别" else "开始识别",
            onClick = { if (running) actions.stop() else actions.start() },
            primary = !running,
            accent = if (running) GlassColors.Danger else GlassColors.Accent,
            modifier = Modifier.fillMaxWidth(),
        )
        GlassButton(
            label = "打开无障碍设置",
            onClick = actions.openAccessibilitySettings,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
        SectionTitle("使用步骤")
        GlassCard {
            StepRow(1, "在系统设置里启用「单词 PK 助手」的无障碍服务")
            Spacer(Modifier.height(12.dp))
            StepRow(2, "回到这里点「开始识别」，同意录屏授权")
            Spacer(Modifier.height(12.dp))
            StepRow(3, "切到 e听说中学的单词 PK 页面，看悬浮条上的答案")
            Spacer(Modifier.height(14.dp))
            GlassText(
                text = "识别全部在本机完成，截屏内容不会上传。",
                style = GlassType.Caption,
                align = TextAlign.Start,
            )
        }

        Spacer(Modifier.height(GlassDimens.BottomBarSpace))
    }
}

@Composable
private fun StepRow(index: Int, text: String) {
    Row(verticalAlignment = Alignment.Top) {
        GlassText(
            text = index.toString().padStart(2, '0'),
            style = GlassType.Mono.copy(color = GlassColors.Cyan),
            modifier = Modifier.padding(end = 12.dp),
        )
        GlassText(text, style = GlassType.Body)
    }
}
