package io.local.timetable.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.model.Course;

/** 把"行 × 单元格"的表格（PDF / HTML 通用）映射成课程。 */
public final class GridCourseParser {

    private static final Pattern DAY = Pattern.compile("(?:周|星期)([一二三四五六日天])");
    private static final Pattern NUM = Pattern.compile("^(\\d{1,2})(?:\\s*[-~]\\s*(\\d{1,2}))?$");

    private GridCourseParser() {
    }

    public static List<Course> parse(List<List<String>> rows) {
        List<Course> out = new ArrayList<>();
        if (rows == null || rows.isEmpty()) {
            return out;
        }
        int headerRow = -1;
        Map<Integer, Integer> col2day = new LinkedHashMap<>();
        for (int r = 0; r < Math.min(rows.size(), 6); r++) {
            Map<Integer, Integer> map = new LinkedHashMap<>();
            List<String> row = rows.get(r);
            for (int c = 0; c < row.size(); c++) {
                Matcher m = DAY.matcher(row.get(c));
                if (m.find()) {
                    map.put(c, TextCourseParser.dayIndex(m.group(1)));
                }
            }
            if (map.size() >= 5) {
                headerRow = r;
                col2day = map;
                break;
            }
        }
        if (headerRow < 0) {
            return out;
        }

        int section = 0;
        for (int r = headerRow + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            int start = -1;
            int end = -1;
            for (int c = 0; c < Math.min(2, row.size()); c++) {
                String cell = row.get(c).replaceAll("\\s", "");
                Matcher m = NUM.matcher(cell);
                if (m.matches()) {
                    start = Integer.parseInt(m.group(1));
                    end = m.group(2) == null ? start : Integer.parseInt(m.group(2));
                    break;
                }
            }
            if (start > 0) {
                section = start;
                end = Math.max(start, end);
            } else {
                // rowspan 合并单元格：沿用上一行节次
                start = section;
                end = Math.max(section, section);
            }
            if (start < 1) {
                continue;
            }
            for (Map.Entry<Integer, Integer> e : col2day.entrySet()) {
                int c = e.getKey();
                if (c >= row.size()) {
                    continue;
                }
                String cell = row.get(c);
                if (cell == null || cell.trim().isEmpty()) {
                    continue;
                }
                for (String block : splitBlocks(cell)) {
                    Course parsed = TextCourseParser.fromCell(block, e.getValue(), start, end);
                    if (parsed != null) {
                        out.add(parsed);
                    }
                }
            }
            section = end;
        }
        return dedup(out);
    }

    /** 一个格子里可能塞了多门课（不同周次），按空行 / 周次出现次数切块。 */
    private static List<String> splitBlocks(String cell) {
        List<String> blocks = new ArrayList<>();
        String normalized = cell.replace("\r", "");
        String[] parts = normalized.split("\\n{2,}");
        if (parts.length > 1) {
            for (String p : parts) {
                if (!p.trim().isEmpty()) {
                    blocks.add(p.trim());
                }
            }
            return blocks;
        }
        // 单行 / 无空行：按"周"关键词切分
        String[] lines = normalized.split("\\n");
        if (lines.length > 1) {
            StringBuilder cur = new StringBuilder();
            int weekMarks = 0;
            for (String line : lines) {
                if (line.matches(".*\\d+\\s*[-~]?\\d*\\s*周.*") && weekMarks > 0) {
                    blocks.add(cur.toString().trim());
                    cur.setLength(0);
                    weekMarks = 0;
                }
                cur.append(line).append('\n');
                if (line.matches(".*\\d+\\s*[-~]?\\d*\\s*周.*")) {
                    weekMarks++;
                }
            }
            if (cur.length() > 0) {
                blocks.add(cur.toString().trim());
            }
            return blocks;
        }
        blocks.add(normalized.trim());
        return blocks;
    }

    private static List<Course> dedup(List<Course> list) {
        List<Course> out = new ArrayList<>();
        for (Course c : list) {
            boolean dup = false;
            for (Course d : out) {
                if (d.name.equals(c.name) && d.day == c.day && d.start == c.start && d.end == c.end) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(c);
            }
        }
        return out;
    }
}
