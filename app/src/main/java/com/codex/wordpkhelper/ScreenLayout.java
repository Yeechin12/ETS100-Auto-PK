package com.codex.wordpkhelper;

import android.graphics.Rect;
import com.google.mlkit.vision.text.Text;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 从整屏 OCR 的行框推断 PK 题面的版面：题干区域与选项区域。
 *
 * <p>只用几何信息，不依赖屏幕比例：先把行框按纵向重叠聚成“行”，再找一段连续的、
 * 字号相近、行距正常的行作为选项区，选项区上方紧邻的文字就是题干。
 * 结构不够明确时返回 null，调用方沿用固定比例区域（旧行为）。
 */
final class ScreenLayout {
    /** 识别出来的两个区域，坐标都是整屏像素。 */
    static final class Model {
        final Rect questionRegion;
        final Rect optionsRegion;

        Model(Rect questionRegion, Rect optionsRegion) {
            this.questionRegion = questionRegion;
            this.optionsRegion = optionsRegion;
        }
    }

    /** 选项区至少要有这么多行（正常是 4 个选项，容忍 OCR 漏掉一行）。 */
    private static final int MIN_OPTION_ROWS = 3;
    /** 题干最多取选项区上方的这么多行（中文题干可能折行）。 */
    private static final int MAX_PROMPT_ROWS = 2;

    private ScreenLayout() { }

    static Model detect(List<Text.Line> lines, int screenWidth, int screenHeight) {
        if (screenWidth <= 0 || screenHeight <= 0 || lines == null) return null;
        List<Row> rows = buildRows(lines);
        if (rows.size() < MIN_OPTION_ROWS + 1) return null;

        int bestStart = -1;
        int bestEnd = -1;
        for (int start = 0; start < rows.size(); start++) {
            for (int end = start + MIN_OPTION_ROWS - 1; end < rows.size(); end++) {
                if (!looksLikeOptionRun(rows, start, end, screenHeight)) break;
                if (bestStart < 0 || (end - start) > (bestEnd - bestStart)) {
                    bestStart = start;
                    bestEnd = end;
                }
            }
        }
        if (bestStart < 0) return null;

        Row first = rows.get(bestStart);
        Row last = rows.get(bestEnd);
        int rowHeight = Math.max(1, averageHeight(rows, bestStart, bestEnd));
        int margin = Math.max(4, Math.round(rowHeight * 0.6f));
        Rect optionsRegion = new Rect(0, Math.max(0, first.top - margin), screenWidth,
                Math.min(screenHeight, last.bottom + margin));

        // 题干和选项之间通常有一段留白，容差给得宽一些；真正会误收的是顶部标题栏，
        // 所以下面用 8% 屏高把它挡掉。
        int promptGap = Math.max(margin * 6, Math.round(screenHeight * 0.08f));
        int questionTop = Integer.MAX_VALUE;
        int questionBottom = 0;
        int promptRows = 0;
        int previousTop = first.top;
        for (int i = bestStart - 1; i >= 0 && promptRows < MAX_PROMPT_ROWS; i--) {
            Row row = rows.get(i);
            if (previousTop - row.bottom > promptGap) break;   // 离选项太远，不是同一道题的题干
            if (row.bottom < screenHeight * 0.08f) break;     // 顶部标题栏区域不算题干
            questionTop = Math.min(questionTop, row.top);
            questionBottom = Math.max(questionBottom, row.bottom);
            previousTop = row.top;
            promptRows++;
        }
        if (promptRows == 0) return null;

        Rect questionRegion = new Rect(0, Math.max(0, questionTop - margin), screenWidth,
                Math.min(screenHeight, questionBottom + margin));
        if (questionRegion.bottom > optionsRegion.top) questionRegion.bottom = optionsRegion.top;
        if (questionRegion.height() <= 0) return null;
        return new Model(questionRegion, optionsRegion);
    }

    /** 一“行”：纵向重叠的行框会被并到一起，用于把并排的文字当成同一行。 */
    private static final class Row {
        int top = Integer.MAX_VALUE;
        int bottom = 0;

        void add(Rect box) {
            top = Math.min(top, box.top);
            bottom = Math.max(bottom, box.bottom);
        }

        int height() {
            return bottom - top;
        }
    }

    private static List<Row> buildRows(List<Text.Line> lines) {
        List<Rect> boxes = new ArrayList<>();
        for (Text.Line line : lines) {
            Rect box = line.getBoundingBox();
            if (box == null || box.width() <= 0 || box.height() <= 0) continue;
            String text = line.getText();
            if (text == null || text.trim().isEmpty()) continue;
            boxes.add(box);
        }
        Collections.sort(boxes, new Comparator<Rect>() {
            @Override
            public int compare(Rect left, Rect right) {
                return Integer.compare(left.top, right.top);
            }
        });
        List<Row> rows = new ArrayList<>();
        for (Rect box : boxes) {
            Row current = rows.isEmpty() ? null : rows.get(rows.size() - 1);
            if (current != null && box.top < current.bottom - Math.max(1, current.height() / 4)) {
                current.add(box);
            } else {
                Row created = new Row();
                created.add(box);
                rows.add(created);
            }
        }
        return rows;
    }

    private static boolean looksLikeOptionRun(List<Row> rows, int start, int end, int screenHeight) {
        Row first = rows.get(start);
        Row last = rows.get(end);
        if (first.top < screenHeight * 0.18f) return false;                // 选项不会紧贴屏幕顶端
        if (last.bottom > screenHeight * 0.96f) return false;
        if (last.bottom - first.top < screenHeight * 0.08f) return false;  // 整段太矮，不像一组选项
        int minHeight = Integer.MAX_VALUE;
        int maxHeight = 0;
        for (int i = start; i <= end; i++) {
            int height = rows.get(i).height();
            minHeight = Math.min(minHeight, height);
            maxHeight = Math.max(maxHeight, height);
        }
        if (maxHeight > minHeight * 2) return false;                       // 字号差太多，不像同一组选项
        for (int i = start + 1; i <= end; i++) {
            int gap = rows.get(i).top - rows.get(i - 1).bottom;
            if (gap > maxHeight * 2) return false;                         // 行距过大，中间断开了
        }
        return true;
    }

    private static int averageHeight(List<Row> rows, int start, int end) {
        int total = 0;
        for (int i = start; i <= end; i++) total += rows.get(i).height();
        return total / Math.max(1, end - start + 1);
    }
}
