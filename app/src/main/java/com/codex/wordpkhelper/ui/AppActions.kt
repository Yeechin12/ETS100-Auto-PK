package com.codex.wordpkhelper.ui

import com.codex.wordpkhelper.settings.RunMode
import com.codex.wordpkhelper.ui.glass.NavGlyph

/**
 * 主界面需要向 Activity 借的能力：录屏授权、无障碍设置跳转、文件选择都需要
 * `registerForActivityResult`，只能在 Activity 里注册，所以用回调注进来。
 */
data class AppActions(
    /** 开始识别：检查无障碍 → 申请通知权限 → 申请录屏授权。 */
    val start: () -> Unit,
    /** 停止识别。 */
    val stop: () -> Unit,
    /** 跳到系统无障碍设置页。 */
    val openAccessibilitySettings: () -> Unit,
    /** 打开文件选择器导入自定义词表。 */
    val importVocab: () -> Unit,
    /** 切换运行模式。识别中切换会立即生效，不需要重新授权录屏。 */
    val setRunMode: (RunMode) -> Unit,
)

/** 三个页面。顺序即底部导航顺序。 */
enum class AppPage(val label: String, val glyph: NavGlyph) {
    Home("主页", NavGlyph.Home),
    Vocab("词表", NavGlyph.Vocab),
    Settings("设置", NavGlyph.Settings),
}
