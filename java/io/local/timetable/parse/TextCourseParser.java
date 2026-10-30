package io.local.timetable.parse;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.model.Course;
import io.local.timetable.util.Weeks;

/**
 * 课表文本解析（用于网页 innerText、PDF 纯文本、剪贴板粘贴）。
 * 目标是"尽量解析 + 交给用户预览修正"，不追求 100% 自动化。
 */
public final class TextCourseParser {

    private static final Pattern DAY = Pattern.compile("(?:周|星期)([一二三四五六日天])");
    private static final Pattern SECTIONS = Pattern.compile(
            "第?\\s*(\\d{1,2})\\s*[-~至—]\\s*(\\d{1,2})\\s*节|\\[?\\s*(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})\\s*节\\]?|第\\s*(\\d{1,2})\\s*节");
    private static final Pattern WEEKS = Pattern.compile(
            "(\\d{1,2})\\s*[-~至—]\\s*(\\d{1,2})\\s*周(?:\\s*[（(]?\\s*([单双])\\s*[)）]?)?|(\\d{1,2})\\s*周(?:\\s*[（(]?\\s*([单双])\\s*[)）]?)?");
    private static final Pattern ROOM = Pattern.compile(
            "[\\u4e00-\\u9fa5A-Za-z0-9]{0,12}(?:楼|教|室|馆|机房|实验|中心|校区)[\\u4e00-\\u9fa5A-Za-z0-9\\-]{0,12}");

    private TextCourseParser() {
    }

    public static List<Course> parse(String raw) {
        List<Course> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        String text = raw.replace('\u00a0', ' ').replace('\u3000', ' ').replace("\r", "");
        String[] lines = text.split("\n");
        for (String line : lines) {
            Course c = parseLine(line);
            if (c != null) {
                out.add(c);
            }
        }
        // 去重（同名 + 同天 + 同节次）
        List<Course> dedup = new ArrayList<>();
        for (Course c : out) {
            boolean exists = false;
            for (Course d : dedup) {
                if (d.name.equals(c.name) && d.day == c.day && d.start == c.start && d.end == c.end) {
                    exists = true;
                    break;
                }
            }
            if (!exists) {
                dedup.add(c);
            }
        }
        return dedup;
    }

    /** 解析单行；无法识别返回 null。 */
    public static Course parseLine(String line) {
        if (line == null) {
            return null;
        }
        String s = line.trim();
        if (s.length() < 4) {
            return null;
        }
        Matcher dm = DAY.matcher(s);
        Matcher sm = SECTIONS.matcher(s);
        if (!dm.find() || !sm.find()) {
            return null;
        }
        int day = dayIndex(dm.group(1));
        int start;
        int end;
        if (sm.group(1) != null) {
            start = Integer.parseInt(sm.group(1));
            end = Integer.parseInt(sm.group(2));
        } else if (sm.group(3) != null) {
            start = Integer.parseInt(sm.group(3));
            end = Integer.parseInt(sm.group(4));
        } else {
            start = Integer.parseInt(sm.group(5));
            end = start;
        }
        if (start < 1 || start > 16) {
            return null;
        }
        end = Math.max(start, Math.min(16, end));

        String weeks = "1-20";
        Matcher wm = WEEKS.matcher(s);
        if (wm.find()) {
            if (wm.group(1) != null) {
                weeks = wm.group(1) + "-" + wm.group(2);
                if ("单".equals(wm.group(3))) {
                    weeks = weeks + "|1";
                } else if ("双".equals(wm.group(3))) {
                    weeks = weeks + "|2";
                }
            } else {
                weeks = wm.group(4);
                if ("单".equals(wm.group(5))) {
                    weeks = weeks + "|1";
                } else if ("双".equals(wm.group(5))) {
                    weeks = weeks + "|2";
                }
            }
        }

        String room = "";
        Matcher rm = ROOM.matcher(s);
        if (rm.find()) {
            room = rm.group().trim();
        }

        // 剩余文本作为课程名：去掉已识别片段
        String name = s;
        name = DAY.matcher(name).replaceAll(" ");
        name = SECTIONS.matcher(name).replaceAll(" ");
        name = WEEKS.matcher(name).replaceAll(" ");
        if (!room.isEmpty()) {
            name = name.replace(room, " ");
        }
        name = name.replace("【调】", " ")
                .replaceAll("[\\[\\]（）()【】]", " ")
                .replaceAll("\\s{2,}", " ")
                .trim();
        // 去掉教务标记和常见分隔符
        name = name.replaceAll("[\\s*＊]*[（(]$", "");
        name = name.replaceAll("^[|,，;；\\-\\s]+", "").replaceAll("[|,，;；\\-\\s]+$", "");
        String teacher = "";
        // 末尾 2~4 个汉字当作老师
        String[] tokens = name.split("\\s+");
        if (tokens.length >= 2 && tokens[tokens.length - 1].matches("[\\u4e00-\\u9fa5]{2,4}")) {
            teacher = tokens[tokens.length - 1];
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < tokens.length - 1; i++) {
                if (sb.length() > 0) {
                    sb.append(' ');
                }
                sb.append(tokens[i]);
            }
            name = sb.toString();
        }
        if (name.length() < 2) {
            // 允许英文课名
            name = s.replaceAll("[\\s]+", " ").trim();
            if (name.length() < 2) {
                return null;
            }
        }
        if (name.length() > 40) {
            name = name.substring(0, 40);
        }
        String wx = Weeks.extract(s);
        if (wx != null) {
            weeks = wx;
        }
        Course c = new Course(name, day, start, end, weeks);
        c.room = room;
        c.teacher = teacher;
        return c;
    }

    /** 单行格子：名称 教师 周次 地点 混排时尽力拆解。 */
    private static Course fromSingleLine(String raw, int day, int start, int end) {
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String weeks = "1-20";
        Matcher wm = WEEKS.matcher(s);
        if (wm.find()) {
            if (wm.group(1) != null) {
                weeks = wm.group(1) + "-" + wm.group(2);
                if ("单".equals(wm.group(3))) {
                    weeks += "|1";
                } else if ("双".equals(wm.group(3))) {
                    weeks += "|2";
                }
            } else {
                weeks = wm.group(4);
                if ("单".equals(wm.group(5))) {
                    weeks += "|1";
                } else if ("双".equals(wm.group(5))) {
                    weeks += "|2";
                }
            }
        }
        String room = "";
        Matcher rm = ROOM.matcher(s);
        if (rm.find()) {
            room = rm.group().trim();
        }
        String rest = s;
        rest = WEEKS.matcher(rest).replaceAll(" ");
        if (!room.isEmpty()) {
            rest = rest.replace(room, " ");
        }
        rest = rest.replace("【调】", " ")
                .replaceAll("[\\[\\]（）()【】|,，;；:：]", " ")
                .replaceAll("\\s{2,}", " ").trim();
        if (rest.isEmpty()) {
            return null;
        }
        String name = rest;
        String teacher = "";
        String[] tokens = rest.split("\\s+");
        if (tokens.length >= 2) {
            String last = tokens[tokens.length - 1];
            if (last.matches("[\\u4e00-\\u9fa5]{2,4}")) {
                teacher = last;
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < tokens.length - 1; i++) {
                    if (sb.length() > 0) {
                        sb.append(' ');
                    }
                    sb.append(tokens[i]);
                }
                name = sb.toString();
            }
        }
        if (name.isEmpty()) {
            return null;
        }
        String wx = Weeks.extract(s);
        if (wx != null) {
            weeks = wx;
        }
        Course c = new Course(name, day, start, end, weeks);
        c.room = room;
        c.teacher = teacher;
        return c;
    }

    public static int dayIndex(String cn) {
        switch (cn) {
            case "一":
                return 1;
            case "二":
                return 2;
            case "三":
                return 3;
            case "四":
                return 4;
            case "五":
                return 5;
            case "六":
                return 6;
            default:
                return 7;
        }
    }

    /** 从一段"课表格子文本"里提取：名称/教师/周次/地点/节次（兼容单行或多行）。 */
    public static Course fromCell(String cellText, int day, int start, int end) {
        if (cellText == null) {
            return null;
        }
        String text = cellText.replace('\u00a0', ' ').replace('\u3000', ' ').replace("\r", "\n");
        String flat = text.replaceAll("\\s+", " ").trim();
        if (flat.isEmpty()) {
            return null;
        }

        // 1) 节次：1-2节 / 第1-2节 / 第1节
        int s = start;
        int e = end;
        Matcher sm = Pattern.compile("第?\\s*(\\d{1,2})\\s*[-~]\\s*(\\d{1,2})\\s*节|第\\s*(\\d{1,2})\\s*节").matcher(flat);
        int sectionIdx = -1;
        if (sm.find()) {
            if (sm.group(1) != null) {
                s = Integer.parseInt(sm.group(1));
                e = Math.max(s, Integer.parseInt(sm.group(2)));
            } else {
                s = Integer.parseInt(sm.group(3));
                e = s;
            }
            sectionIdx = sm.start();
        }

        // 2) 周次
        String weeks = Weeks.extract(flat);
        if (weeks == null) {
            weeks = "1-20";
        }

        // 3) 地点 / 老师：在节次之后找
        String room = "";
        String teacher = "";
        String after = sectionIdx >= 0 ? flat.substring(sectionIdx) : flat;
        Matcher rm = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9\\-]{0,10}(?:楼|教|室|馆|机房|实验|校区|中心)[\\u4e00-\\u9fa5A-Za-z0-9\\-]{0,10}").matcher(after);
        if (rm.find()) {
            room = rm.group().trim();
            String rest = after.substring(rm.end()).trim();
            String[] tokens = rest.split("\\s+");
            if (tokens.length > 0 && tokens[0].matches("[\\u4e00-\\u9fa5]{2,4}")) {
                teacher = tokens[0];
            }
        }

        // 4) 课名：节次/周次/地点之前的部分
        int cut = flat.length();
        if (sectionIdx > 0) {
            cut = Math.min(cut, sectionIdx);
        }
        int weekIdx = flat.indexOf("周");
        if (weekIdx > 0) {
            cut = Math.min(cut, weekIdx);
        }
        if (!room.isEmpty()) {
            int ri = flat.indexOf(room);
            if (ri > 0) {
                cut = Math.min(cut, ri);
            }
        }
        String name = flat.substring(0, Math.max(0, cut)).trim();
        name = name.replace("【调】", " ").trim();
        name = name.replaceAll("[\\s*＊]*[（(]$", "");
        name = name.replaceAll("[\\*＊]+$", "").trim();
        name = name.replaceAll("[|,，;；:：\\s]+$", "").trim();
        if (name.length() < 2) {
            // 退化：取第一段非空
            String[] parts = text.split("\n");
            for (String p : parts) {
                String t = p.trim().replaceAll("[\\*＊]+$", "");
                if (t.length() >= 2) {
                    name = t;
                    break;
                }
            }
        }
        if (name.length() < 2) {
            return null;
        }
        if (name.length() > 30) {
            name = name.substring(0, 30);
        }

        Course c = new Course(name, day, s, Math.max(s, e), weeks);
        c.room = room;
        c.teacher = teacher;

        // 课程号：形如 数学建模-0002
        Matcher codeM = Pattern.compile("([\\u4e00-\\u9fa5A-Za-z][\\w\\u4e00-\\u9fa5]*[-－][0-9]{3,})").matcher(flat);
        if (codeM.find()) {
            c.code = codeM.group(1);
        }
        // 班级/学号串（6 位以上数字，最多取 4 个）
        Matcher clM = Pattern.compile("\\b(\\d{6,})\\b").matcher(flat);
        StringBuilder cl = new StringBuilder();
        int clCount = 0;
        while (clM.find() && clCount < 4) {
            if (cl.length() > 0) {
                cl.append(' ');
            }
            cl.append(clM.group(1));
            clCount++;
        }
        c.clazz = cl.toString();

        // 学分：取最后一个小数/整数（0.5~10 之间）
        Matcher crM = Pattern.compile("(\\d(?:\\.\\d)?)\\s*$").matcher(flat);
        if (crM.find()) {
            try {
                double v = Double.parseDouble(crM.group(1));
                if (v >= 0.5 && v <= 10) {
                    c.credit = crM.group(1);
                }
            } catch (NumberFormatException ignored) {
            }
        }
        // 备注：QQ群 / 雨课堂 / 考查 等
        StringBuilder note = new StringBuilder();
        Matcher qq = Pattern.compile("QQ\\s*群\\s*[:：]?\\s*(\\d{5,})").matcher(flat);
        if (qq.find()) {
            note.append("QQ群 ").append(qq.group(1));
        }
        if (flat.contains("雨课堂")) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append("雨课堂");
        }
        if (flat.contains("考查")) {
            if (note.length() > 0) {
                note.append(" · ");
            }
            note.append("考查");
        }
        c.note = note.toString();
        return c;
    }
}