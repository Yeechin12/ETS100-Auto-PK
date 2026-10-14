package com.codex.wordpkhelper.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.state.CaptureBus
import com.codex.wordpkhelper.ui.glass.GlassSurface
import com.codex.wordpkhelper.ui.glass.GlassText
import com.codex.wordpkhelper.ui.glass.Glyph
import com.codex.wordpkhelper.ui.glass.LocalGlassBackdrop
import com.codex.wordpkhelper.ui.glass.ProvideGlassBackdrop
import com.codex.wordpkhelper.ui.glass.StatusDot
import com.codex.wordpkhelper.ui.glass.liquid.LiquidBottomTab
import com.codex.wordpkhelper.ui.glass.liquid.LiquidBottomTabs
import com.codex.wordpkhelper.ui.pages.HomePage
import com.codex.wordpkhelper.ui.pages.SettingsPage
import com.codex.wordpkhelper.ui.pages.VocabPage
import com.codex.wordpkhelper.ui.theme.AuroraBackground
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberCombinedBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 应用根布局。
 *
 * 用**两条**录制层，而不是一条：
 *
 * - 图层 A（[auroraBackdrop]）只录极光背景。页面内的玻璃 —— 比如设置页的液体滑杆 ——
 *   折射它。因为它不包含页面内容，所以在页面里采样它不会变成「采样自己所在的层」。
 * - 图层 B（[contentBackdrop]）录页面内容。
 * - 顶栏与底栏是这两层的**兄弟节点**，折射 `rememberCombinedBackdrop(A, B)`，
 *   既有背景又有滚动的文字，所以滚动时文字会真的从玻璃下面穿过。
 *
 * **绝对不要**让某个 `drawBackdrop` 去采样「包含它自己的那一层」。
 * 那会形成无界的 RenderNode 嵌套，最后在 RenderThread 里以栈溢出崩掉。
 * 实测 tombstone：`SIGSEGV / SEGV_ACCERR`，
 * `Cause: stack pointer is close to top of stack; likely stack overflow`，
 * backtrace 是 `RenderNode::prepareTreeImpl` 无限递归。
 * 拆成两条层、并且只把「不含自己的那条」交给页面内玻璃，就是为了兑现这条约束。
 *
 * 页面内容仍走 `GlassSurface` 的手绘分支（半透明底 + 高光描边）：只有需要折射的
 * 组件（液体滑杆、底部 Tab）才显式接收 backdrop，避免每张卡片都挂一个折射节点。
 */
@Composable
fun WordPkApp(
    actions: AppActions,
    vocabRevision: Int,
    modifier: Modifier = Modifier,
) {
    var pageIndex by rememberSaveable { mutableStateOf(0) }
    val page = AppPage.entries[pageIndex]

    Box(modifier.fillMaxSize().background(GlassColors.Ink)) {
        // 图层 A：只有极光背景。页面内的玻璃折射它（它不含页面内容，安全）。
        val auroraBackdrop = rememberLayerBackdrop { drawContent() }
        Box(Modifier.fillMaxSize().layerBackdrop(auroraBackdrop)) {
            AuroraBackground()
        }

        // 图层 B：页面内容。
        val contentBackdrop = rememberLayerBackdrop { drawContent() }
        Box(Modifier.fillMaxSize().layerBackdrop(contentBackdrop)) {
            Box(
                Modifier
                    .fillMaxSize()
                    // 只让出状态栏与导航栏；顶部玻璃栏的留白放在各页可滚动内容的最前面。
                    // 若在这里 padding(top = TopBarSpace)，视口就从玻璃栏下方开始，
                    // 内容永远滑不到栏下面，玻璃的折射也就没有意义了。
                    .statusBarsPadding()
                    .navigationBarsPadding()
            ) {
                when (page) {
                    AppPage.Home -> HomePage(actions, auroraBackdrop)
                    AppPage.Vocab -> VocabPage(vocabRevision, actions.importVocab)
                    AppPage.Settings -> SettingsPage(vocabRevision, auroraBackdrop)
                }
            }
        }

        // 玻璃浮层：上面两层的兄弟节点，折射「极光 + 页面内容」。
        ProvideGlassBackdrop(rememberCombinedBackdrop(auroraBackdrop, contentBackdrop)) {
            TopBar(page, Modifier.align(Alignment.TopCenter))
            BottomBar(
                selectedIndex = pageIndex,
                modifier = Modifier.align(Alignment.BottomCenter),
            ) { pageIndex = it }
        }
    }
}

@Composable
private fun TopBar(page: AppPage, modifier: Modifier = Modifier) {
    val running by CaptureBus.running.collectAsState()
    val status by CaptureBus.status.collectAsState()

    Box(
        modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = GlassDimens.ScreenPadding, vertical = 10.dp)
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(GlassDimens.BarRadius),
            // 顶栏压在滚动的正文上，模糊要足够大才能让背后的文字「化开」成一团光，
            // 否则读起来像文字重影，很脏。
            tint = Color.White.copy(alpha = 0.13f),
            blurRadius = 18.dp,
            refraction = 24.dp,
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    GlassText(page.label, style = GlassType.Title)
                    Spacer(Modifier.height(2.dp))
                    GlassText(
                        text = status.ifEmpty { "单词 PK 助手" },
                        style = GlassType.Caption,
                        maxLines = 1,
                    )
                }
                StatusDot(active = running)
            }
        }
    }
}

@Composable
private fun BottomBar(
    selectedIndex: Int,
    modifier: Modifier = Modifier,
    onSelect: (Int) -> Unit,
) {
    val backdrop = LocalGlassBackdrop.current
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = GlassDimens.ScreenPadding, vertical = 12.dp)
    ) {
        val glass = backdrop ?: return@Box
        // 库自带的 LiquidBottomTabs：可以按住放大、也可以横向拖动切换，
        // 指示器会跟着手指滑动并在松手时回弹到最近的 Tab。
        LiquidBottomTabs(
            selectedTabIndex = { selectedIndex },
            onTabSelected = onSelect,
            backdrop = glass,
            tabsCount = AppPage.entries.size,
            modifier = Modifier.fillMaxWidth(),
        ) {
            AppPage.entries.forEachIndexed { index, item ->
                LiquidBottomTab(index = index, onClick = { onSelect(index) }) {
                    Glyph(
                        glyph = item.glyph,
                        color = if (index == selectedIndex) GlassColors.TextPrimary
                        else GlassColors.TextTertiary,
                        modifier = Modifier.size(22.dp),
                    )
                    GlassText(
                        text = item.label,
                        style = GlassType.Caption.copy(
                            color = if (index == selectedIndex) GlassColors.TextPrimary
                            else GlassColors.TextTertiary
                        ),
                    )
                }
            }
        }
    }
}
