package io.local.timetable.parse;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;
import com.tom_roush.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 把 PDF 的文本定位信息还原成"表格行 × 单元格"。
 *
 * 做法：先按表头（周一…周日）确定列锚点，再用最左侧"节次数字"确定行锚点，
 * 然后把每个文本块按"最近的列 × 最近的行"归位。多行单元格也能正确合并。
 */
public final class PdfTable {

    private static final Pattern DAY = Pattern.compile("^(周|星期)[一二三四五六日天]$");
    private static final Pattern NUM = Pattern.compile("^\\d{1,2}$");

    private PdfTable() {
    }

    public static List<List<String>> extract(PDDocument doc, int pageIndex) throws IOException {
        Collector collector = new Collector();
        collector.setSortByPosition(true);
        collector.setStartPage(pageIndex + 1);
        collector.setEndPage(pageIndex + 1);
        collector.getText(doc);
        return collector.rows();
    }

    private static final class Item {
        final float x;
        final float y;
        final float w;
        final String text;

        Item(float x, float y, float w, String text) {
            this.x = x;
            this.y = y;
            this.w = w;
            this.text = text;
        }
    }

    private static final class Collector extends PDFTextStripper {

        private final List<Item> items = new ArrayList<>();

        Collector() throws IOException {
            super();
            setSortByPosition(true);
        }

        @Override
        protected void writeString(String text, List<TextPosition> positions) {
            if (text == null || text.trim().isEmpty() || positions == null) {
                return;
            }
            StringBuilder sb = new StringBuilder();
            float x = Float.MAX_VALUE;
            float y = 0;
            float end = 0;
            for (TextPosition p : positions) {
                if (p.getUnicode() == null || p.getUnicode().isEmpty()) {
                    continue;
                }
                sb.append(p.getUnicode());
                x = Math.min(x, p.getXDirAdj());
                y = p.getYDirAdj();
                end = Math.max(end, p.getXDirAdj() + p.getWidthDirAdj());
            }
            String s = sb.toString().trim();
            if (s.isEmpty()) {
                return;
            }
            items.add(new Item(x == Float.MAX_VALUE ? 0 : x, y, Math.max(1, end - x), s));
        }

        List<List<String>> rows() {
            List<List<String>> out = new ArrayList<>();
            if (items.isEmpty()) {
                return out;
            }

            // 1) 列锚点：表头里的 周一…周日
            List<Item> dayItems = new ArrayList<>();
            for (Item it : items) {
                if (DAY.matcher(it.text).matches()) {
                    dayItems.add(it);
                }
            }
            if (dayItems.size() < 5) {
                return fallbackLines();
            }
            Collections.sort(dayItems, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Float.compare(a.x, b.x);
                }
            });
            float[] colX = new float[dayItems.size()];
            for (int i = 0; i < dayItems.size(); i++) {
                colX[i] = dayItems.get(i).x;
            }
            float headerY = dayItems.get(0).y;
            float firstColX = colX[0];

            // 2) 行锚点：表头下方、最左列里的 1..20 数字
            List<Item> anchors = new ArrayList<>();
            for (Item it : items) {
                if (it.y < headerY - 5f && it.x < firstColX - 5f && NUM.matcher(it.text).matches()) {
                    try {
                        int n = Integer.parseInt(it.text);
                        if (n >= 1 && n <= 20) {
                            anchors.add(it);
                        }
                    } catch (NumberFormatException ignored) {
                    }
                }
            }
            if (anchors.size() < 2) {
                return fallbackLines();
            }
            Collections.sort(anchors, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    return Float.compare(b.y, a.y);
                }
            });

            // 3) 归位：行按最近锚点，列按最近列中心
            List<List<List<Item>>> grid = new ArrayList<>();
            for (int i = 0; i < anchors.size(); i++) {
                List<List<Item>> row = new ArrayList<>();
                for (int c = 0; c <= colX.length; c++) {
                    row.add(new ArrayList<Item>());
                }
                grid.add(row);
            }
            for (Item it : items) {
                if (it.y >= headerY - 5f) {
                    continue;
                }
                int row = nearestRow(anchors, it.y);
                if (row < 0) {
                    continue;
                }
                int col;
                if (it.x < firstColX - 5f) {
                    col = 0; // 节次列
                } else {
                    col = 1 + nearestCol(colX, it.x + it.w / 2f);
                }
                grid.get(row).get(col).add(it);
            }

            // 4) 每行拼接：节次 + 7 个单元格
            for (int r = 0; r < anchors.size(); r++) {
                List<String> cells = new ArrayList<>();
                for (List<Item> cell : grid.get(r)) {
                    Collections.sort(cell, new Comparator<Item>() {
                        @Override
                        public int compare(Item a, Item b) {
                            return Float.compare(b.y, a.y);
                        }
                    });
                    StringBuilder sb = new StringBuilder();
                    for (Item it : cell) {
                        if (sb.length() > 0) {
                            sb.append('\n');
                        }
                        sb.append(it.text);
                    }
                    cells.add(sb.toString());
                }
                out.add(cells);
            }
            return out;
        }

        private int nearestRow(List<Item> anchors, float y) {
            int best = -1;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < anchors.size(); i++) {
                float d = Math.abs(anchors.get(i).y - y);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            // 超过一节高度（约 40pt）就不归任何行
            return bestD <= 60f ? best : -1;
        }

        private int nearestCol(float[] xs, float x) {
            int best = 0;
            float bestD = Float.MAX_VALUE;
            for (int i = 0; i < xs.length; i++) {
                float d = Math.abs(xs[i] - x);
                if (d < bestD) {
                    bestD = d;
                    best = i;
                }
            }
            return best;
        }

        /** 兜底：按 y 聚行（每行一个或多个文本块）。 */
        private List<List<String>> fallbackLines() {
            List<Item> sorted = new ArrayList<>(items);
            Collections.sort(sorted, new Comparator<Item>() {
                @Override
                public int compare(Item a, Item b) {
                    int c = Float.compare(b.y, a.y);
                    if (Math.abs(a.y - b.y) > 3.5f) {
                        return c;
                    }
                    return Float.compare(a.x, b.x);
                }
            });
            List<List<String>> rows = new ArrayList<>();
            List<String> line = new ArrayList<>();
            float lineY = sorted.get(0).y;
            for (Item it : sorted) {
                if (Math.abs(it.y - lineY) > 3.5f) {
                    if (!line.isEmpty()) {
                        rows.add(line);
                    }
                    line = new ArrayList<>();
                    lineY = it.y;
                }
                line.add(it.text);
            }
            if (!line.isEmpty()) {
                rows.add(line);
            }
            return rows;
        }
    }
}
