package com.codex.wordpkhelper.ui.pages

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.codex.wordpkhelper.core.VocabMatcher
import com.codex.wordpkhelper.ui.glass.GlassButton
import com.codex.wordpkhelper.ui.glass.GlassCard
import com.codex.wordpkhelper.ui.glass.GlassFlatSurface
import com.codex.wordpkhelper.ui.glass.GlassText
import com.codex.wordpkhelper.ui.glass.SectionTitle
import com.codex.wordpkhelper.ui.theme.GlassColors
import com.codex.wordpkhelper.ui.theme.GlassDimens
import com.codex.wordpkhelper.ui.theme.GlassType

/**
 * 词表管理页。
 *
 * 用 [LazyColumn] 而不是 `verticalScroll`：内置词表已经有近 200 条，
 * 用户导入后可能上千条，一次性组合会卡。
 */
@Composable
fun VocabPage(
    vocabRevision: Int,
    onImport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var query by remember { mutableStateOf("") }

    val matcher = remember(vocabRevision) { VocabMatcher(context) }
    val builtinSize = remember(vocabRevision) { matcher.builtinSize }
    val customSize = remember(vocabRevision) { matcher.customSize }
    val all = remember(vocabRevision) { matcher.snapshotEntries() }

    val filtered = remember(query, all) {
        if (query.isBlank()) {
            all
        } else {
            val q = query.trim().lowercase()
            all.filter { entry ->
                entry.first.contains(q) || entry.second.any { it.contains(q) }
            }
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(
            start = GlassDimens.ScreenPadding,
            end = GlassDimens.ScreenPadding,
            top = GlassDimens.TopBarSpace,
            bottom = GlassDimens.BottomBarSpace,
        ),
        verticalArrangement = Arrangement.spacedBy(GlassDimens.ItemGap),
    ) {
        item {
            GlassCard {
                GlassText("内置词表 $builtinSize 条", style = GlassType.Headline)
                Spacer(Modifier.height(4.dp))
                GlassText(
                    text = if (customSize > 0) "已导入自定义 $customSize 条，同名单词覆盖内置释义。"
                    else "还没有导入过自定义词表。",
                    style = GlassType.Body,
                )
                Spacer(Modifier.height(10.dp))
                GlassText(
                    text = "格式：每行「单词<TAB>释义」，多个释义用半角分号隔开，UTF-8，" +
                        "# 开头的行会被忽略。上限 512 KB。",
                    style = GlassType.Caption,
                )
            }
        }

        item {
            GlassFlatSurface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(GlassDimens.ControlRadius),
            ) {
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 13.dp)
                ) {
                    if (query.isEmpty()) {
                        GlassText(
                            text = "搜索单词或释义",
                            style = GlassType.Body.copy(color = GlassColors.TextTertiary),
                        )
                    }
                    BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = GlassType.Body.copy(color = GlassColors.TextPrimary),
                        cursorBrush = SolidColor(GlassColors.Cyan),
                        singleLine = true,
                    )
                }
            }
        }

        item {
            GlassButton(
                label = "导入自定义词表（TSV）",
                onClick = onImport,
                primary = true,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        item {
            Spacer(Modifier.height(GlassDimens.SectionGap - GlassDimens.ItemGap))
            SectionTitle(if (query.isBlank()) "全部词条 · ${all.size}" else "匹配 ${filtered.size} 条")
        }

        if (filtered.isEmpty()) {
            item {
                GlassCard {
                    GlassText("没有匹配的词条。", style = GlassType.Body)
                }
            }
        }

        items(filtered, key = { it.first }) { entry ->
            VocabRow(entry.first, entry.second)
        }
    }
}

/** 单条词条。列表里数据量大，用不打折射的轻量玻璃。 */
@Composable
private fun VocabRow(word: String, meanings: List<String>) {
    GlassFlatSurface(modifier = Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            GlassText(word, style = GlassType.Headline)
            Spacer(Modifier.height(3.dp))
            GlassText(
                text = meanings.joinToString("；"),
                style = GlassType.Label,
                maxLines = 2,
            )
        }
    }
}
