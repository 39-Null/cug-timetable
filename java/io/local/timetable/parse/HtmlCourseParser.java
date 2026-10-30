package io.local.timetable.parse;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.model.Course;
import io.local.timetable.util.Weeks;

/**
 * 正方教务（jwglxt）课表页解析。
 *
 * <p>优先使用「格子视图」：每个课程格都带 id="天-节"（如 3-5 = 周三第 5 节），
 * 行高由 rowspan 决定。用 id 定位可以彻底避开 rowspan/colspan 造成的列错位。</p>
 *
 * <p>其次使用「列表视图」：td id="jc_天-起-止"。</p>
 *
 * <p>最后退回通用表格 + 纯文本解析，保证任何页面都不至于完全没结果。</p>
 */
public final class HtmlCourseParser {

    /** 格子视图：<td rowspan="2" id="3-5" ...>内容</td> */
    private static final Pattern GRID_CELL = Pattern.compile(
            "(?is)<td([^>]*\\sid=\"(\\d{1,2})-(\\d{1,2})\"[^>]*)>(.*?)</td>");
    /** 列表视图：<td id="jc_3-1-2" ...>内容</td> */
    private static final Pattern LIST_CELL = Pattern.compile(
            "(?is)<td([^>]*\\sid=\"jc_(\\d{1,2})-(\\d{1,2})-(\\d{1,2})\"[^>]*)>(.*?)</td>");
    /** 一个格子里可能并排放多门课（不同周次），各自一个 timetable_con。 */
    private static final Pattern COURSE_BLOCK = Pattern.compile(
            "(?is)<div[^>]*class=\"[^\"]*timetable_con[^\"]*\"[^>]*>(.*?)</div>");
    private static final Pattern TITLE = Pattern.compile(
            "(?is)<span[^>]*class=\"[^\"]*title[^\"]*\"[^>]*>(.*?)</span>");
    private static final Pattern PARA = Pattern.compile("(?is)<p([^>]*)>(.*?)</p>");
    private static final Pattern ATTR_TITLE = Pattern.compile("(?is)\\stitle=\"([^\"]*)\"");
    private static final Pattern ROWSPAN = Pattern.compile("(?i)rowspan\\s*=\\s*\"?(\\d+)");

    /** 列表视图把多个 "键：值" 塞进同一个 <p>，按这些键切开。 */
    private static final Pattern LABEL = Pattern.compile(
            "(周数|校区|上课地点|地点|教师|教学班组成|教学班名称|教学班|考核方式|选课备注|备注|课程学时组成|周学时|总学时|学分)\\s*[:：]");

    private static final Pattern BR = Pattern.compile("(?i)<br\\s*/?>|</div>|</p>|</li>|</tr>");
    private static final Pattern TAG = Pattern.compile("(?is)<[^>]+>");
    private static final Pattern DAY_HEAD = Pattern.compile("(?:周|星期)([一二三四五六日天])");
    private static final Pattern TABLE = Pattern.compile("(?is)<table[^>]*>(.*?)</table>");
    private static final Pattern ROW = Pattern.compile("(?is)<tr[^>]*>(.*?)</tr>");
    private static final Pattern CELL = Pattern.compile("(?is)<t[dh]([^>]*)>(.*?)</t[dh]>");
    private static final Pattern SECTION_CELL = Pattern.compile("(\\d{1,2})(?:\\s*[-~]\\s*(\\d{1,2}))?");

    private HtmlCourseParser() {
    }

    public static List<Course> parse(String html) {
        List<Course> out = new ArrayList<>();
        if (html == null || html.isEmpty()) {
            return out;
        }
        parseGrid(html, out);
        if (out.isEmpty()) {
            parseList(html, out);
        }
        if (out.isEmpty()) {
            parseLegacy(html, out);
        }
        if (out.isEmpty()) {
            out.addAll(TextCourseParser.parse(strip(html)));
        }
        return dedupe(out);
    }

    // ---------------- 格子视图 ----------------

    private static void parseGrid(String html, List<Course> out) {
        Matcher m = GRID_CELL.matcher(html);
        while (m.find()) {
            String attrs = m.group(1) == null ? "" : m.group(1);
            int day = parseInt(m.group(2), 0);
            int start = parseInt(m.group(3), 0);
            String body = m.group(4);
            if (day < 1 || day > 7 || start < 1) {
                continue;
            }
            int span = 1;
            Matcher rs = ROWSPAN.matcher(attrs);
            if (rs.find()) {
                span = Math.max(1, parseInt(rs.group(1), 1));
            }
            addBlocks(body, day, start, start + span - 1, out);
        }
    }

    // ---------------- 列表视图 ----------------

    private static void parseList(String html, List<Course> out) {
        Matcher m = LIST_CELL.matcher(html);
        while (m.find()) {
            int day = parseInt(m.group(2), 0);
            int start = parseInt(m.group(3), 0);
            int end = parseInt(m.group(4), start);
            if (day < 1 || day > 7 || start < 1) {
                continue;
            }
            addBlocks(m.group(5), day, start, Math.max(start, end), out);
        }
    }

    /** 从一段格子里抓出所有 timetable_con，逐门课解析。 */
    private static void addBlocks(String body, int day, int start, int end, List<Course> out) {
        if (body == null || body.isEmpty()) {
            return;
        }
        Matcher bm = COURSE_BLOCK.matcher(body);
        boolean found = false;
        while (bm.find()) {
            Course c = parseBlock(bm.group(1), day, start, end);
            if (c != null) {
                out.add(c);
                found = true;
            }
        }
        if (!found && body.contains("timetable_con")) {
            // 结构异常（比如 div 未闭合）：整段当格子文本解析
            Course c = TextCourseParser.fromCell(cellText(body), day, start, end);
            if (c != null) {
                out.add(c);
            }
        }
    }

    private static Course parseBlock(String block, int day, int start, int end) {
        Course c = new Course("", day, start, Math.max(start, end), "1-20");
        Matcher tm = TITLE.matcher(block);
        if (tm.find()) {
            c.name = cleanName(cellText(tm.group(1)));
        }

        StringBuilder extra = new StringBuilder();
        String time = "";
        Matcher pm = PARA.matcher(block);
        while (pm.find()) {
            String attrs = pm.group(1) == null ? "" : pm.group(1);
            String body = pm.group(2);
            String label = "";
            Matcher lm = ATTR_TITLE.matcher(attrs + "\n" + body);
            if (lm.find()) {
                label = lm.group(1).trim();
            }
            String value = cellText(body);
            if (label.isEmpty()) {
                // 列表视图：一个 <p> 里 "周数：… 校区：… 学分：…"
                Matcher gm = LABEL.matcher(value);
                List<int[]> marks = new ArrayList<>();
                List<String> keys = new ArrayList<>();
                while (gm.find()) {
                    marks.add(new int[]{gm.start(), gm.end()});
                    keys.add(gm.group(1));
                }
                if (marks.isEmpty()) {
                    appendExtra(extra, value);
                    continue;
                }
                for (int i = 0; i < marks.size(); i++) {
                    int vs = marks.get(i)[1];
                    int ve = i + 1 < marks.size() ? marks.get(i + 1)[0] : value.length();
                    String v = value.substring(vs, Math.min(ve, value.length())).trim();
                    apply(keys.get(i), v, c, extra);
                    if ("周数".equals(keys.get(i))) {
                        time = time.isEmpty() ? v : time + " " + v;
                    }
                }
            } else {
                apply(label, value, c, extra);
                if (label.contains("节") || label.contains("周数")) {
                    time = time.isEmpty() ? value : time + " " + value;
                }
            }
        }

        if (c.name.isEmpty()) {
            return null;
        }
        if (c.room != null && !c.room.trim().isEmpty()) {
            splitLocation(c.room, c);
        }
        // 节次以 id/rowspan 为准；列表视图同样来自 id
        c.start = start;
        c.end = Math.max(start, end);

        String weeks = Weeks.extract(time);
        if (weeks != null && !weeks.isEmpty()) {
            c.weeks = weeks;
        }
        if (extra.length() > 0) {
            c.note = c.note.isEmpty() ? extra.toString() : c.note + "；" + extra;
        }
        c.note = trimNote(c.note);
        c.note = c.note.replace("qq群", "QQ群").replace("QQ 群", "QQ群").trim();
        if (c.note.matches("\\d{5,}")) {
            c.note = "QQ群 " + c.note;
        }
        if (c.code != null) {
            c.code = c.code.replaceAll("\\s*[-－]\\s*", "-").replaceAll("[;；]+$", "").trim();
        }
        if (c.clazz != null) {
            c.clazz = c.clazz.replace(';', ' ').replace('；', ' ')
                    .replaceAll("\\s{2,}", " ").trim();
        }
        if (c.credit != null) {
            c.credit = c.credit.replaceAll("[^0-9.]", "").trim();
        }
        if (c.hours != null) {
            c.hours = c.hours.replaceAll("[^0-9]", "").trim();
        }
        return c;
    }

    /** 把 "键：值" 落到对应字段。 */
    private static void apply(String rawLabel, String value, Course c, StringBuilder extra) {
        if (value == null) {
            return;
        }
        String label = rawLabel == null ? "" : rawLabel.replaceAll("\\s", "");
        String v = value.trim();
        if (v.isEmpty()) {
            return;
        }
        if (label.contains("周数") || label.contains("节/周") || label.contains("节次")
                || label.equals("时间")) {
            // 周次在 parseBlock 里统一处理
            return;
        }
        if (label.contains("教学班组成")) {
            c.clazz = v;
        } else if (label.contains("教学班")) {
            c.code = v;
        } else if (label.contains("上课地点") || label.contains("地点")) {
            c.room = v;
        } else if (label.contains("校区")) {
            c.campus = v;
        } else if (label.contains("教师") || label.contains("老师")) {
            c.teacher = v;
        } else if (label.contains("考核方式")) {
            c.examType = v;
        } else if (label.contains("选课备注") || label.contains("备注")) {
            appendExtra(extra, v);
        } else if (label.contains("总学时")) {
            c.hours = v;
        } else if (label.contains("学分")) {
            c.credit = v;
        } else if (label.contains("课程学时组成") || label.contains("周学时")) {
            // 课时构成对课表展示意义不大，忽略
        } else {
            appendExtra(extra, v);
        }
    }

    /** "未来城校区  公教2-305" → campus + room。 */
    private static void splitLocation(String location, Course c) {
        String loc = location.replace('\u00a0', ' ').replaceAll("\\s{2,}", " ").trim();
        loc = loc.replaceAll("[;；]+$", "").trim();
        String campus = c.campus == null ? "" : c.campus.trim();
        Matcher m = Pattern.compile("^(未来城校区|南望山校区)\\s*(.*)$").matcher(loc);
        if (m.find()) {
            if (campus.isEmpty()) {
                c.campus = m.group(1);
            }
            c.room = m.group(2).trim();
            return;
        }
        // 没有单独给校区，但地点里可能带 "校区"
        Matcher cm = Pattern.compile("^(.{2,8}校区)\\s+(.+)$").matcher(loc);
        if (cm.find()) {
            if (campus.isEmpty()) {
                c.campus = cm.group(1);
            }
            c.room = cm.group(2).trim();
            return;
        }
        c.room = loc;
    }

    private static void appendExtra(StringBuilder sb, String v) {
        String t = v == null ? "" : v.trim();
        if (t.isEmpty()) {
            return;
        }
        if (sb.length() > 0) {
            sb.append("；");
        }
        sb.append(t);
    }

    private static String trimNote(String note) {
        if (note == null) {
            return "";
        }
        return note.replaceAll("[;；\\s]+$", "").replaceAll("^[;；\\s]+", "").trim();
    }

    private static String cleanName(String name) {
        if (name == null) {
            return "";
        }
        return name.replace('\u00a0', ' ').replace('\u3000', ' ')
                .replaceAll("[\\*＊\\s]+$", "")
                .replaceAll("^[\\s*＊]+", "")
                .trim();
    }

    // ---------------- 通用表格兜底 ----------------

    private static void parseLegacy(String html, List<Course> out) {
        Matcher tm = TABLE.matcher(html);
        while (tm.find() && out.isEmpty()) {
            List<List<String>> rows = rows(tm.group(1));
            if (rows.isEmpty()) {
                continue;
            }
            List<Course> grid = legacyGrid(rows);
            if (!grid.isEmpty()) {
                out.addAll(grid);
                continue;
            }
            List<Course> list = legacyList(rows);
            if (!list.isEmpty()) {
                out.addAll(list);
            }
        }
    }

    private static List<List<String>> rows(String tableHtml) {
        List<List<String>> rows = new ArrayList<>();
        Matcher rm = ROW.matcher(tableHtml);
        while (rm.find()) {
            List<String> cells = new ArrayList<>();
            Matcher cm = CELL.matcher(rm.group(1));
            while (cm.find()) {
                String attrs = cm.group(1) == null ? "" : cm.group(1);
                String text = cellText(cm.group(2));
                int colspan = 1;
                Matcher csm = Pattern.compile("(?i)colspan\\s*=\\s*\"?(\\d+)").matcher(attrs);
                if (csm.find()) {
                    colspan = Math.max(1, Math.min(10, parseInt(csm.group(1), 1)));
                }
                cells.add(text);
                for (int k = 1; k < colspan; k++) {
                    cells.add("");
                }
            }
            if (!cells.isEmpty()) {
                rows.add(cells);
            }
        }
        return rows;
    }

    private static List<Course> legacyGrid(List<List<String>> rows) {
        List<Course> out = new ArrayList<>();
        int headerIndex = -1;
        java.util.Map<Integer, Integer> col2day = new java.util.HashMap<>();
        for (int i = 0; i < Math.min(rows.size(), 6); i++) {
            java.util.Map<Integer, Integer> map = new java.util.HashMap<>();
            List<String> row = rows.get(i);
            for (int col = 0; col < row.size(); col++) {
                Matcher m = DAY_HEAD.matcher(row.get(col));
                if (m.find()) {
                    map.put(col, TextCourseParser.dayIndex(m.group(1)));
                }
            }
            if (map.size() >= 5) {
                headerIndex = i;
                col2day = map;
                break;
            }
        }
        if (headerIndex < 0) {
            return out;
        }
        for (int r = headerIndex + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.isEmpty()) {
                continue;
            }
            int start = -1;
            int end = -1;
            for (int col = 0; col < Math.min(2, row.size()); col++) {
                Matcher m = SECTION_CELL.matcher(row.get(col).trim());
                if (m.matches()) {
                    start = parseInt(m.group(1), -1);
                    end = m.group(2) == null ? start : parseInt(m.group(2), start);
                    break;
                }
            }
            if (start < 1) {
                continue;
            }
            for (java.util.Map.Entry<Integer, Integer> e : col2day.entrySet()) {
                int col = e.getKey();
                if (col >= row.size()) {
                    continue;
                }
                Course parsed = TextCourseParser.fromCell(row.get(col), e.getValue(), start, Math.max(start, end));
                if (parsed != null) {
                    out.add(parsed);
                }
            }
        }
        return out;
    }

    private static List<Course> legacyList(List<List<String>> rows) {
        List<Course> out = new ArrayList<>();
        int iName = -1;
        int iTeacher = -1;
        int iWeeks = -1;
        int iTime = -1;
        int iRoom = -1;
        int header = -1;
        for (int i = 0; i < Math.min(rows.size(), 5); i++) {
            List<String> row = rows.get(i);
            int n = -1;
            int t = -1;
            int w = -1;
            int tm = -1;
            int rm = -1;
            for (int col = 0; col < row.size(); col++) {
                String cell = row.get(col).replaceAll("\\s", "");
                if (cell.contains("课程") && n < 0) {
                    n = col;
                } else if ((cell.contains("教师") || cell.contains("老师")) && t < 0) {
                    t = col;
                } else if (cell.contains("周次") && w < 0) {
                    w = col;
                } else if ((cell.contains("节次") || cell.contains("时间")) && tm < 0) {
                    tm = col;
                } else if ((cell.contains("地点") || cell.contains("教室")) && rm < 0) {
                    rm = col;
                }
            }
            if (n >= 0 && t >= 0 && w >= 0 && tm >= 0) {
                header = i;
                iName = n;
                iTeacher = t;
                iWeeks = w;
                iTime = tm;
                iRoom = rm;
                break;
            }
        }
        if (header < 0) {
            return out;
        }
        for (int r = header + 1; r < rows.size(); r++) {
            List<String> row = rows.get(r);
            if (row.size() <= iName) {
                continue;
            }
            String name = row.get(iName).trim();
            if (name.isEmpty()) {
                continue;
            }
            String time = iTime < row.size() ? row.get(iTime) : "";
            Matcher dm = DAY_HEAD.matcher(time);
            Matcher sm = Pattern.compile("(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})").matcher(time);
            if (!dm.find()) {
                continue;
            }
            int start = sm.find() ? parseInt(sm.group(1), 1) : 1;
            int end = sm.reset().find() ? parseInt(sm.group(2), start) : start;
            String weeks = iWeeks < row.size() ? Weeks.extract(row.get(iWeeks)) : null;
            Course c = new Course(cleanName(name), TextCourseParser.dayIndex(dm.group(1)),
                    start, Math.max(start, end), weeks == null ? "1-20" : weeks);
            if (iTeacher < row.size()) {
                c.teacher = row.get(iTeacher).trim();
            }
            if (iRoom >= 0 && iRoom < row.size()) {
                c.room = row.get(iRoom).trim();
            }
            out.add(c);
        }
        return out;
    }

    // ---------------- 工具 ----------------

    private static List<Course> dedupe(List<Course> in) {
        List<Course> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Course c : in) {
            String key = c.name + "|" + c.day + "|" + c.start + "|" + c.end + "|" + c.weeks;
            if (seen.add(key)) {
                out.add(c);
            }
        }
        out.sort((a, b) -> a.day != b.day ? a.day - b.day : a.start - b.start);
        return out;
    }

    private static int parseInt(String s, int def) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return def;
        }
    }

    private static String cellText(String html) {
        if (html == null) {
            return "";
        }
        String s = BR.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll("");
        s = decode(s);
        StringBuilder sb = new StringBuilder();
        for (String line : s.split("\n")) {
            String t = line.replace('\u00a0', ' ').replaceAll("\\s+", " ").trim();
            if (!t.isEmpty()) {
                if (sb.length() > 0) {
                    sb.append('\n');
                }
                sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String decode(String s) {
        return s.replace("&nbsp;", " ").replace("&emsp;", " ").replace("&ensp;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&apos;", "'");
    }

    private static String strip(String html) {
        String s = BR.matcher(html).replaceAll("\n");
        s = TAG.matcher(s).replaceAll(" ");
        s = decode(s);
        return s.replaceAll("[ \\t]{2,}", " ");
    }
}
