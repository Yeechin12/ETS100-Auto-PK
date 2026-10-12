package com.codex.wordpkhelper.core

import android.graphics.Rect

/**
 * 从整屏 OCR 行框推断出来的两个识别区域，坐标都是整屏像素。
 *
 * [question] 是题干带，[options] 是选项带，两者不重叠。
 */
class ScreenRegions(val question: Rect, val options: Rect)
