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
 * 页面内的玻璃卡片也在层子树里，它们折射的是「自己之前画在同一位置的像素」，
 * 也就是极光背景。若在某些设备上观感异常，把 [ProvideGlassBackdrop] 从层子树里摘掉即可 ——
 * 那样卡片会自动退化成手绘玻璃（见 `GlassSurface` 的无 backdrop 分支）。
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

        ProvideGlassBackdrop(backdrop) {
            Box(Modifier.fillMaxSize().layerBackdrop(backdrop)) {
                AuroraBackground()
                Box(
                    Modifier
                        .fillMaxSize()
                        .padding(top = GlassDimens.TopBarSpace)
                ) {
                    when (page) {
                        AppPage.Home -> HomePage(actions)
                        AppPage.Vocab -> VocabPage(vocabRevision, actions.importVocab)
                        AppPage.Settings -> SettingsPage(vocabRevision)
                    }
                }
            }

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
