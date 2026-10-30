package io.local.timetable.parse;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.model.Exam;

/**
 * 教务「考试信息查询」页解析。
 *
 * <p>正方各版本列名不完全一致，这里按表头找列；找不到表头时退化为
 * 「整行找日期 + 时间 + 考场」的启发式。</p>
 */
public final class HtmlExamParser {

    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t[dh][^>]*>(.*?)</t[dh]>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>|</p>|</div>|</li>|</tr>");

    private static final Pattern DATE = Pattern.compile(
            "(20\\d{2})\\s*[-/年.]\\s*(\\d{1,2})\\s*[-/月.]\\s*(\\d{1,2})\\s*日?");
    private static final Pattern CLOCK = Pattern.compile(
            "(\\d{1,2})\\s*[:：]\\s*(\\d{2})\\s*(?:[-~—至]\\s*(\\d{1,2})\\s*[:：]\\s*(\\d{2}))?");

    private static final Pattern SEAT = Pattern.compile("(?:座位号?|座号)\\s*[:：]?\\s*([0-9A-Za-z\\-]+)");
    private static final Pattern ROOM_WORD = Pattern.compile(
            "(?:考场|地点|教室)\\s*[:：]?\\s*([^\\s,，;；]{2,20})");
    /** 正方 jqGrid：td 带 aria-describedby="tabGrid_xxx"。 */
    private static final Pattern GRID_CELL = Pattern.compile(
            "(?is)<td([^>]*aria-describedby=\"tabGrid_([^\"]+)\"[^>]*)>(.*?)</td>");

    private HtmlExamParser() {
    }

    public static List<Exam> parse(String html) {
        List<Exam> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        out.addAll(parseJqGrid(html));
        if (out.isEmpty()) {
            Matcher tm = TABLE.matcher(html);
            while (tm.find() && out.isEmpty()) {
                out.addAll(parseTable(tm.group(1)));
            }
        }
        if (out.isEmpty()) {
            out.addAll(parseRows(html));
        }
        if (out.isEmpty()) {
            out.addAll(parseText(strip(html)));
        }
        return dedupe(out);
    }

    // ---------------- 正方 jqGrid ----------------

    /** 考试查询页常用 jqGrid：每个 td 的 aria-describedby 就是字段名。 */
    private static List<Exam> parseJqGrid(String html) {
        List<Exam> out = new ArrayList<>();
        Matcher rm = ROW.matcher(html);
        while (rm.find()) {
            Map<String, String> m = new LinkedHashMap<>();
            Matcher cm = GRID_CELL.matcher(rm.group(1));
            while (cm.find()) {
                String key = cm.group(2);
                String text = cellText(cm.group(3));
                if (text.isEmpty()) {
                    Matcher tm = Pattern.compile("(?is)\\stitle=\"([^\"]*)\"").matcher(cm.group(1));
                    if (tm.find()) {
                        text = tm.group(1).replace("&nbsp;", " ").trim();
                    }
                }
                if (key.startsWith("tabGrid_")) {
                    key = key.substring("tabGrid_".length());
                }
                m.put(key, text);
            }
            String course = m.get("kcmc");
            String time = m.get("kssj");
            if (course == null || course.trim().isEmpty() || time == null || time.trim().isEmpty()) {
                continue;
            }
            String norm = normalizeTime(time);
            if (norm.isEmpty()) {
                continue;
            }
            Exam e = new Exam();
            e.course = cleanCourse(course);
            e.time = norm;
            e.place = cleanPlace(m.get("cdmc"));
            if (e.place.isEmpty()) {
                e.place = cleanPlace(m.get("cdxqmc"));
            }
            e.seat = cleanSeat(m.get("zwh"));
            StringBuilder note = new StringBuilder();
            String campus = m.get("cdxqmc");
            if (campus != null && !campus.trim().isEmpty() && !campus.equals(e.place)) {
                note.append(campus.trim());
            }
            String mode = m.get("ksfs");
            if (mode != null && !mode.trim().isEmpty()) {
                if (note.length() > 0) {
                    note.append(" · ");
                }
                note.append(mode.trim());
            }
            e.note = note.toString();
            out.add(e);
        }
        return out;
    }

    // ---------------- 表格 ----------------

    private static List<Exam> parseTable(String tableHtml) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rm = ROW.matcher(tableHtml);
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = CELL.matcher(rm.group(1));
            while (cm.find()) {
                cells.add(cellText(cm.group(1)));
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        List<Exam> out = new ArrayList<>();
        int header = -1;
        Map<String, Integer> col = new LinkedHashMap<>();
        for (int i = 0; i < Math.min(rows.size(), 6); i++) {
            Map<String, Integer> map = headerMap(rows.get(i));
            if (map.containsKey("course") && (map.containsKey("time")
                    || map.containsKey("date") || map.containsKey("clock"))) {
                header = i;
                col = map;
                break;
            }
        }
        if (header < 0) {
            // 没有表头：整表按行启发式
            for (List<String> row : rows) {
                Exam e = fromCells(row);
                if (e != null) {
                    out.add(e);
                }
            }
            return out;
        }
        for (int r = header + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.isEmpty()) {
                continue;
            }
            String course = cell(row, col.get("course"));
            String date = cell(row, col.get("date"));
            String clock = cell(row, col.get("clock"));
            String time = cell(row, col.get("time"));
            String place = cell(row, col.get("place"));
            String seat = cell(row, col.get("seat"));
            String note = cell(row, col.get("note"));

            if (course.isEmpty() || isHeaderLike(course)) {
                continue;
            }
            String normalized = "";
            if (!time.isEmpty()) {
                normalized = normalizeTime(time);
            }
            if (normalized.isEmpty()) {
                normalized = normalizeTime((date + " " + clock).trim());
            }
            if (normalized.isEmpty()) {
                // 整行再兜一次
                normalized = normalizeTime(join(row));
            }
            if (normalized.isEmpty()) {
                continue;
            }
            Exam e = new Exam();
            e.course = cleanCourse(course);
            e.time = normalized;
            e.place = cleanPlace(place);
            e.seat = cleanSeat(seat.isEmpty() ? join(row) : seat);
            e.note = note;
            out.add(e);
        }
        return out;
    }

    private static Map<String, Integer> headerMap(List<String> row) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (int i = 0; i < row.size(); i++) {
            String h = row.get(i).replaceAll("\\s", "");
            if (h.isEmpty()) {
                continue;
            }
            if ((h.contains("课程") || h.contains("科目")) && !map.containsKey("course")) {
                map.put("course", i);
            } else if (h.contains("考试时间") && !map.containsKey("time")) {
                map.put("time", i);
            } else if (h.equals("日期") || h.contains("考试日期")) {
                map.put("date", i);
            } else if ((h.equals("时间") || h.contains("时段") || h.contains("考试时段"))
                    && !map.containsKey("clock")) {
                map.put("clock", i);
            } else if ((h.contains("考场") || h.contains("地点") || h.contains("教室"))
                    && !map.containsKey("place")) {
                map.put("place", i);
            } else if (h.contains("座位") && !map.containsKey("seat")) {
                map.put("seat", i);
            } else if ((h.contains("形式") || h.contains("方式") || h.contains("备注"))
                    && !map.containsKey("note")) {
                map.put("note", i);
            }
        }
        return map;
    }

    // ---------------- 无表头 ----------------

    private static List<Exam> parseRows(String html) {
        List<Exam> out = new ArrayList<>();
        Matcher rm = ROW.matcher(html);
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = CELL.matcher(rm.group(1));
            while (cm.find()) {
                cells.add(cellText(cm.group(1)));
            }
            Exam e = fromCells(cells);
            if (e != null) {
                out.add(e);
            }
        }
        return out;
    }

    private static Exam fromCells(List<String> cells) {
        if (cells.isEmpty()) {
            return null;
        }
        String all = join(cells);
        String time = normalizeTime(all);
        if (time.isEmpty()) {
            return null;
        }
        String course = "";
        String place = "";
        String seat = "";
        for (String c : cells) {
            String t = c.trim();
            if (t.isEmpty() || isHeaderLike(t)) {
                continue;
            }
            if (normalizeTime(t).equals(time)) {
                continue;
            }
            if (t.matches("^\\d{1,2}$")) {
                continue;
            }
            Matcher sm = SEAT.matcher(t);
            if (sm.find()) {
                seat = sm.group(1);
                continue;
            }
            Matcher rm = ROOM_WORD.matcher(t);
            if (rm.find()) {
                if (place.isEmpty()) {
                    place = rm.group(1);
                }
                continue;
            }
            if (looksLikeRoom(t)) {
                if (place.isEmpty()) {
                    place = t;
                }
                continue;
            }
            if (course.isEmpty() && t.length() >= 2 && !t.matches("[\\d\\s:：\\-/]+")) {
                course = t;
            }
        }
        if (course.isEmpty()) {
            return null;
        }
        Exam e = new Exam();
        e.course = cleanCourse(course);
        e.time = time;
        e.place = cleanPlace(place);
        e.seat = cleanSeat(seat);
        return e;
    }

    // ---------------- 纯文本 ----------------

    public static List<Exam> parseText(String text) {
        List<Exam> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\n")) {
            String s = line.trim();
            if (s.length() < 6) {
                continue;
            }
            String time = normalizeTime(s);
            if (time.isEmpty()) {
                continue;
            }
            String before = s.substring(0, Math.max(0, firstTimeIndex(s)));
            String after = s.substring(Math.min(s.length(), lastTimeEnd(s)));
            String course = before.replaceAll("^[\\d\\s.、]+", "").trim();
            if (course.isEmpty()) {
                continue;
            }
            Exam e = new Exam();
            e.course = cleanCourse(course);
            e.time = time;
            Matcher rm = ROOM_WORD.matcher(after);
            if (rm.find()) {
                e.place = cleanPlace(rm.group(1));
            } else if (looksLikeRoom(after.trim())) {
                e.place = cleanPlace(after.trim());
            }
            e.seat = cleanSeat(after);
            out.add(e);
        }
        return dedupe(out);
    }

    // ---------------- 工具 ----------------

    /** 把各种日期/时间写法归一为 "yyyy-MM-dd HH:mm-HH:mm"（结束时间可缺）。 */
    public static String normalizeTime(String raw) {
        if (raw == null) {
            return "";
        }
        String s = raw.replace('\u00a0', ' ').replaceAll("\\s+", " ");
        Matcher dm = DATE.matcher(s);
        if (!dm.find()) {
            return "";
        }
        int y = Integer.parseInt(dm.group(1));
        int mo = Integer.parseInt(dm.group(2));
        int d = Integer.parseInt(dm.group(3));
        if (mo < 1 || mo > 12 || d < 1 || d > 31) {
            return "";
        }
        String tail = s.substring(dm.end());
        Matcher cm = CLOCK.matcher(tail);
        if (!cm.find()) {
            // 有的页面日期和时间分两列，整串里再找一次
            cm = CLOCK.matcher(s);
            if (!cm.find()) {
                return String.format("%04d-%02d-%02d", y, mo, d);
            }
        }
        int h1 = Integer.parseInt(cm.group(1));
        int m1 = Integer.parseInt(cm.group(2));
        if (h1 < 0 || h1 > 23 || m1 < 0 || m1 > 59) {
            return String.format("%04d-%02d-%02d", y, mo, d);
        }
        String start = String.format("%02d:%s", h1, cm.group(2));
        String result = String.format("%04d-%02d-%02d %s", y, mo, d, start);
        if (cm.group(3) != null) {
            int h2 = Integer.parseInt(cm.group(3));
            int m2 = Integer.parseInt(cm.group(4));
            if (h2 >= 0 && h2 <= 23 && m2 >= 0 && m2 <= 59) {
                result += "-" + String.format("%02d:%s", h2, cm.group(4));
            }
        }
        return result;
    }

    private static int firstTimeIndex(String s) {
        Matcher m = DATE.matcher(s);
        return m.find() ? m.start() : 0;
    }

    private static int lastTimeEnd(String s) {
        int end = 0;
        Matcher dm = DATE.matcher(s);
        if (dm.find()) {
            end = dm.end();
        }
        Matcher cm = CLOCK.matcher(s);
        int clockEnd = 0;
        while (cm.find()) {
            clockEnd = cm.end();
        }
        return Math.max(end, clockEnd);
    }

    private static String cell(List<String> row, Integer index) {
        if (index == null || index < 0 || index >= row.size()) {
            return "";
        }
        return row.get(index) == null ? "" : row.get(index);
    }

    private static boolean isHeaderLike(String s) {
        String t = s.replaceAll("\\s", "");
        return t.equals("序号") || t.equals("课程") || t.equals("课程名称")
                || t.contains("考试时间") || t.equals("时间") || t.equals("考场")
                || t.equals("地点") || t.equals("座位号") || t.equals("备注")
                || t.equals("考试形式") || t.equals("操作") || t.equals("学生姓名");
    }

    private static boolean looksLikeRoom(String s) {
        String t = s.trim();
        return t.length() >= 3 && t.length() <= 24
                && t.matches(".*(楼|教|室|馆|中心|校区|机房).*")
                && !t.contains("考试时间");
    }

    private static String cleanCourse(String s) {
        String t = s == null ? "" : s.trim();
        t = t.replaceAll("^[\\d\\s.、]+", "")
                .replaceAll("[\\s*＊]+$", "");
        if (t.length() > 40) {
            t = t.substring(0, 40);
        }
        return t;
    }

    private static String cleanPlace(String s) {
        String t = s == null ? "" : s.trim();
        t = t.replaceAll("^(考试地点|考场|地点|教室)\\s*[:：]?\\s*", "");
        t = t.replaceAll("[\\s;；]+$", "");
        if (t.length() > 30) {
            t = t.substring(0, 30);
        }
        return t;
    }

    private static String cleanSeat(String s) {
        if (s == null) {
            return "";
        }
        Matcher m = SEAT.matcher(s);
        if (m.find()) {
            return m.group(1);
        }
        String t = s.trim();
        if (t.matches("[0-9A-Za-z\\-]{1,10}")) {
            return t;
        }
        return "";
    }

    private static String join(List<String> cells) {
        StringBuilder sb = new StringBuilder();
        for (String c : cells) {
            if (c == null || c.trim().isEmpty()) {
                continue;
            }
            if (sb.length() > 0) {
                sb.append(' ');
            }
            sb.append(c.trim());
        }
        return sb.toString();
    }

    private static List<Exam> dedupe(List<Exam> in) {
        List<Exam> out = new ArrayList<>();
        for (Exam e : in) {
            boolean dup = false;
            for (Exam o : out) {
                if (o.course.equals(e.course) && o.time.equals(e.time)) {
                    dup = true;
                    break;
                }
            }
            if (!dup) {
                out.add(e);
            }
        }
        out.sort((a, b) -> a.time.compareTo(b.time));
        return out;
    }

    private static String cellText(String html) {
        String s = BR.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"");
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String strip(String html) {
        String s = BR.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = s.replace("&nbsp;", " ").replace("&amp;", "&")
                .replace("&lt;", "<").replace("&gt;", ">");
        return s.replaceAll("[ \\t]{2,}", " ");
    }
}
