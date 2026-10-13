package com.codex.wordpkhelper.core

import android.graphics.Rect
import com.google.mlkit.vision.text.Text

/**
 * 从整屏 OCR 的行框推断 PK 题面的版面：题干区域与选项区域。
 *
 * 只用几何信息，不依赖屏幕比例：先把行框按纵向重叠聚成“行”，再找一段连续的、
 * 字号相近、行距正常的行作为选项区，选项区上方紧邻的文字就是题干。
 * 结构不够明确时返回 null，调用方沿用固定比例区域（旧行为）。
 */
object ScreenLayout {
    /** 选项区至少要有这么多行（正常是 4 个选项，容忍 OCR 漏掉一行）。 */
    private const val MIN_OPTION_ROWS = 3

    /** 题干最多取选项区上方的这么多行（中文题干可能折行）。 */
    private const val MAX_PROMPT_ROWS = 2

    fun detect(lines: List<Text.Line>?, screenWidth: Int, screenHeight: Int): ScreenRegions? {
        if (screenWidth <= 0 || screenHeight <= 0 || lines == null) return null
        val rows = buildRows(lines)
        if (rows.size < MIN_OPTION_ROWS + 1) return null

        var bestStart = -1
        var bestEnd = -1
        for (start in rows.indices) {
            var end = start + MIN_OPTION_ROWS - 1
            while (end < rows.size) {
                if (!looksLikeOptionRun(rows, start, end, screenHeight)) break
                if (bestStart < 0 || (end - start) > (bestEnd - bestStart)) {
                    bestStart = start
                    bestEnd = end
                }
                end++
            }
        }
        if (bestStart < 0) return null

        val first = rows[bestStart]
        val last = rows[bestEnd]
        val rowHeight = maxOf(1, averageHeight(rows, bestStart, bestEnd))
        val margin = maxOf(4, Math.round(rowHeight * 0.6f))
        val optionsRegion = Rect(
            0,
            maxOf(0, first.top - margin),
            screenWidth,
            minOf(screenHeight, last.bottom + margin)
        )

        // 题干和选项之间通常有一段留白，容差给得宽一些；真正会误收的是顶部标题栏，
        // 所以下面用 8% 屏高把它挡掉。
        val promptGap = maxOf(margin * 6, Math.round(screenHeight * 0.08f))
        var questionTop = Int.MAX_VALUE
        var questionBottom = 0
        var promptRows = 0
        var previousTop = first.top
        var i = bestStart - 1
        while (i >= 0 && promptRows < MAX_PROMPT_ROWS) {
            val row = rows[i]
            if (previousTop - row.bottom > promptGap) break   // 离选项太远，不是同一道题的题干
            if (row.bottom < screenHeight * 0.08f) break      // 顶部标题栏区域不算题干
            questionTop = minOf(questionTop, row.top)
            questionBottom = maxOf(questionBottom, row.bottom)
            previousTop = row.top
            promptRows++
            i--
        }
        if (promptRows == 0) return null

        val questionRegion = Rect(
            0,
            maxOf(0, questionTop - margin),
            screenWidth,
            minOf(screenHeight, questionBottom + margin)
        )
        if (questionRegion.bottom > optionsRegion.top) questionRegion.bottom = optionsRegion.top
        if (questionRegion.height() <= 0) return null
        return ScreenRegions(questionRegion, optionsRegion)
    }

    /** 一“行”：纵向重叠的行框会被并到一起，用于把并排的文字当成同一行。 */
    private class Row {
        var top = Int.MAX_VALUE
        var bottom = 0

        fun add(box: Rect) {
            top = minOf(top, box.top)
            bottom = maxOf(bottom, box.bottom)
        }

        fun height(): Int = bottom - top
    }

    private fun buildRows(lines: List<Text.Line>): List<Row> {
        val boxes = ArrayList<Rect>(lines.size)
        for (line in lines) {
            val box = line.boundingBox ?: continue
            if (box.width() <= 0 || box.height() <= 0) continue
            val text = line.text
            if (text.isNullOrBlank()) continue
            boxes.add(box)
        }
        boxes.sortBy { it.top }

        val rows = ArrayList<Row>(boxes.size)
        for (box in boxes) {
            val current = rows.lastOrNull()
            if (current != null && box.top < current.bottom - maxOf(1, current.height() / 4)) {
                current.add(box)
            } else {
                Row().also { it.add(box); rows.add(it) }
            }
        }
        return rows
    }

    private fun looksLikeOptionRun(rows: List<Row>, start: Int, end: Int, screenHeight: Int): Boolean {
        val first = rows[start]
        val last = rows[end]
        if (first.top < screenHeight * 0.18f) return false           // 选项不会紧贴屏幕顶端
        if (last.bottom > screenHeight * 0.96f) return false
        if (last.bottom - first.top < screenHeight * 0.08f) return false  // 整段太矮，不像一组选项
        var minHeight = Int.MAX_VALUE
        var maxHeight = 0
        for (i in start..end) {
            val height = rows[i].height()
            minHeight = minOf(minHeight, height)
            maxHeight = maxOf(maxHeight, height)
        }
        if (maxHeight > minHeight * 2) return false                  // 字号差太多，不像同一组选项
        for (i in start + 1..end) {
            val gap = rows[i].top - rows[i - 1].bottom
            if (gap > maxHeight * 2) return false                    // 行距过大，中间断开了
        }
        return true
    }

    private fun averageHeight(rows: List<Row>, start: Int, end: Int): Int {
        var total = 0
        for (i in start..end) total += rows[i].height()
        return total / maxOf(1, end - start + 1)
    }
}
