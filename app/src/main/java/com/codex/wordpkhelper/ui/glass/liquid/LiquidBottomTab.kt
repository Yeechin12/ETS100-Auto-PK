/*
 * 移植自 Kyant0/AndroidLiquidGlass（Apache License 2.0）的 Catalog：
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/components/LiquidBottomTab.kt
 *
 * 改动（相对上游）：
 * - 增加 `index` 参数，并在点击时通过 [LocalLiquidBottomTabSelect] 直接驱动指示器。
 *   上游只靠 `snapshotFlow { selectedTabIndex() }` 同步，而调用方传进来的索引通常是
 *   普通 Int（不是快照状态），snapshotFlow 收不到变化，于是「点按切换」时玻璃指示器
 *   不会跟着走，只有拖动才生效。
 */
package com.codex.wordpkhelper.ui.glass.liquid

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.kyant.shapes.Capsule

/** 按下时单个 Tab 的缩放；由 [LiquidBottomTabs] 注入。 */
internal val LocalLiquidBottomTabScale =
    staticCompositionLocalOf<() -> Float> { { 1f } }

/** 点击某个 Tab 时把指示器滑过去；由 [LiquidBottomTabs] 注入。 */
internal val LocalLiquidBottomTabSelect =
    staticCompositionLocalOf<(Int) -> Unit> { {} }

@Composable
fun RowScope.LiquidBottomTab(
    index: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    val scale = LocalLiquidBottomTabScale.current
    val select = LocalLiquidBottomTabSelect.current
    Column(
        modifier
            .clip(Capsule())
            .clickable(
                interactionSource = null,
                indication = null,
                role = Role.Tab,
                onClick = {
                    select(index)
                    onClick()
                }
            )
            .fillMaxHeight()
            .weight(1f)
            .graphicsLayer {
                val currentScale = scale()
                scaleX = currentScale
                scaleY = currentScale
            },
        verticalArrangement = Arrangement.spacedBy(2f.dp, Alignment.CenterVertically),
        horizontalAlignment = Alignment.CenterHorizontally,
        content = content
    )
}
