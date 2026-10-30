package io.local.timetable.model;

import java.io.Serializable;

/** 一门课（按"一次上课"存储：同一天同一时段的连续课是一个 Course）。 */
public class Course implements Serializable {
    public long id;
    public String name = "";
    public String teacher = "";
    public String room = "";
    /** 1=周一 … 7=周日 */
    public int day = 1;
    /** 起始节次（1 起） */
    public int start = 1;
    /** 结束节次（含） */
    public int end = 1;
    /** 周次表达式，如 "1-16,18" */
    public String weeks = "1-20";
    public String note = "";
    /** 课程号，如 数学建模-0002 */
    public String code = "";
    /** 上课班级（学号/班级串） */
    public String clazz = "";
    /** 学分/学时，如 2.5 */
    public String credit = "";
    /** 校区，如 未来城校区 */
    public String campus = "";
    /** 考核方式：考试/考查 */
    public String examType = "";
    /** 总学时，如 40 */
    public String hours = "";
    /** 0=课程 1=实验 */
    public int type = 0;
    /** 实验所属的课程名 */
    public String parent = "";
    /** 所属学期 */
    public long semesterId = 0;
    /** 颜色索引，-1 表示按名称自动分配 */
    public int color = -1;

    public Course() {
    }

    public Course(String name, int day, int start, int end, String weeks) {
        this.name = name;
        this.day = day;
        this.start = start;
        this.end = end;
        this.weeks = weeks;
    }

    /** 该课程在第 week 周是否上课。 */
    public boolean activeIn(int week) {
        return parseWeeks(weeks).contains(week);
    }

    public static java.util.Set<Integer> parseWeeks(String expr) {
        java.util.Set<Integer> set = new java.util.HashSet<>();
        if (expr == null) {
            return set;
        }
        String[] parts = expr.replace('\uFF0C', ',').replace(" ", "").split(",");
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            // 支持 "1-16|1"(单周) / "2-16|2"(双周)
            int parity = 0;
            int bar = part.indexOf('|');
            if (bar > 0) {
                String suffix = part.substring(bar + 1);
                if ("1".equals(suffix) || "单".equals(suffix)) {
                    parity = 1;
                } else if ("2".equals(suffix) || "双".equals(suffix)) {
                    parity = 2;
                }
                part = part.substring(0, bar);
            }
            int a;
            int b;
            int dash = part.indexOf('-');
            if (dash > 0) {
                try {
                    a = Integer.parseInt(part.substring(0, dash));
                    b = Integer.parseInt(part.substring(dash + 1));
                } catch (NumberFormatException e) {
                    continue;
                }
            } else {
                try {
                    a = Integer.parseInt(part);
                    b = a;
                } catch (NumberFormatException e) {
                    continue;
                }
            }
            if (a > b) {
                int t = a;
                a = b;
                b = t;
            }
            for (int i = Math.max(1, a); i <= b && i <= 60; i++) {
                if (parity == 1 && i % 2 == 0) {
                    continue;
                }
                if (parity == 2 && i % 2 == 1) {
                    continue;
                }
                set.add(i);
            }
        }
        return set;
    }

    /** 单周/双周过滤：单周 "1-15|1"，双周 "2-16|2" 这种写法也支持。 */
    public static String normalizeWeeks(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim();
    }
}
