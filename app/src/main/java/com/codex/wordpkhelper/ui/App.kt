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
import com.codex.wordpkhelper.ui.glass.ProvideGlassBackdrop
import com.codex.wordpkhelper.ui.glass.StatusDot
import com.codex.wordpkhelper.ui.glass.glassClickable
import com.codex.wordpkhelper.ui.pages.HomePage
import com.codex.wordpkhelper.ui.pages.SettingsPage
import com.codex.wordpkhelper.ui.pages.VocabPage
import com.codex.wordpkhelper.ui.theme.AuroraBackground
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop

/**
 * 应用根布局。
 *
 * 结构（对应 Backdrop 的标准用法）：
 * 1. `rememberLayerBackdrop { drawContent() }` 建立一个可被折射的录制层；
 * 2. 极光背景 + 页面内容放进 `Modifier.layerBackdrop(backdrop)` 的子树里，成为层内容；
 * 3. 顶部栏、底部导航栏是这一子树的**兄弟节点**，用 `drawBackdrop` 折射层内容，
 *    所以滚动时文字会真的从玻璃下面穿过。
 *
 * **不要**把 [ProvideGlassBackdrop] 放到 `layerBackdrop` 的子树里。层内的 `drawBackdrop`
 * 会去采样「正在录制的这一层」自身，形成无界的 RenderNode 嵌套，最后在 RenderThread 里
 * 以栈溢出崩掉。实测：`SIGSEGV / SEGV_ACCERR`，tombstone 里写着
 * `Cause: stack pointer is close to top of stack; likely stack overflow`，
 * backtrace 是 `RenderNode::prepareTreeImpl` 无限递归。所以页面内的玻璃
 * （`GlassCard` / `GlassButton` / `GlassSegmented` …）走 `GlassSurface` 的手绘分支 ——
 * 半透明底 + 上亮下暗的高光描边，在极光背景上观感一致，只是不做折射；
 * 真正的折射只留给层内容的兄弟节点。
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
        val backdrop = rememberLayerBackdrop { drawContent() }

        // 录制层：极光背景 + 页面内容。这一子树里不能提供 LocalGlassBackdrop（理由见上方 KDoc）。
        Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
            AuroraBackground()
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
                    AppPage.Home -> HomePage(actions)
                    AppPage.Vocab -> VocabPage(vocabRevision, actions.importVocab)
                    AppPage.Settings -> SettingsPage(vocabRevision)
                }
            }
        }

        // 玻璃浮层：层内容的兄弟节点，只有这里做真实折射。
        ProvideGlassBackdrop(backdrop) {
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
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = GlassDimens.ScreenPadding, vertical = 12.dp)
    ) {
        GlassSurface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(GlassDimens.BarRadius),
            tint = Color.White.copy(alpha = 0.13f),
            blurRadius = 18.dp,
            refraction = 24.dp,
        ) {
            Row(Modifier.fillMaxWidth().padding(6.dp)) {
                AppPage.entries.forEachIndexed { index, item ->
                    val selected = index == selectedIndex
                    val interaction = remember { MutableInteractionSource() }
                    Column(
                        Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(GlassDimens.ControlRadius))
                            .background(
                                if (selected) Color.White.copy(alpha = 0.16f)
                                else Color.Transparent
                            )
                            .glassClickable(interaction) { onSelect(index) }
                            .padding(vertical = 9.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Glyph(
                            glyph = item.glyph,
                            color = if (selected) GlassColors.TextPrimary
                            else GlassColors.TextTertiary,
                            modifier = Modifier.size(20.dp),
                        )
                        Spacer(Modifier.height(5.dp))
                        GlassText(
                            text = item.label,
                            style = GlassType.Caption.copy(
                                color = if (selected) GlassColors.TextPrimary
                                else GlassColors.TextTertiary
                            ),
                        )
                    }
                }
            }
        }
    }
}
