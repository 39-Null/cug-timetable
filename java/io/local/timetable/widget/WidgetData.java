package io.local.timetable.widget;

import android.content.Context;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

import io.local.timetable.db.Db;
import io.local.timetable.model.Course;
import io.local.timetable.model.Exam;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Weeks;

/** 小组件要展示的数据：今天课程 + 最近一个月内的考试。 */
public final class WidgetData {

    public static final class Row {
        public String time = "";
        public String name = "";
        public String room = "";
        public boolean current;
    }

    public String weekText = "";
    public String examText = "";
    public String emptyText = "今天没有课";
    public final List<Row> rows = new ArrayList<>();

    public static WidgetData load(Context ctx) {
        WidgetData d = new WidgetData();
        Prefs prefs = new Prefs(ctx);
        Db db = Db.get(ctx);
        long semesterId = db.currentSemesterId();
        String start = prefs.semesterStart();
        Calendar now = Weeks.now();

        int day = Weeks.weekday(now);
        String[] dayNames = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};

        boolean beforeStart = start != null && !start.isEmpty() && Weeks.isBefore(start, now);
        int week = 0;
        if (start != null && !start.isEmpty()) {
            week = Weeks.weekOf(start, now);
            if (beforeStart || week > prefs.totalWeeks() || week < 1) {
                week = 0;
            }
        }
        if (week > 0) {
            d.weekText = "第 " + week + " 周 · " + dayNames[day];
        } else if (start == null || start.isEmpty()) {
            d.weekText = "未设置开学日";
            d.emptyText = "打开 App 设置开学日期";
        } else if (beforeStart) {
            d.weekText = "学期未开始";
            d.emptyText = "学期未开始";
        } else {
            d.weekText = "学期已结束";
            d.emptyText = "学期已结束";
        }

        if (week > 0) {
            String[] times = prefs.sectionTimes();
            List<Course> todayCourses = new ArrayList<>();
            for (Course course : db.courses(semesterId)) {
                if (course.day == day && course.activeIn(week)) {
                    todayCourses.add(course);
                }
            }
            todayCourses.sort((a, b) -> a.start != b.start ? a.start - b.start : a.end - b.end);
            int nowMinutes = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
            for (Course course : todayCourses) {
                Row r = new Row();
                String s = startClock(times, course.start);
                String e = endClock(times, course.end);
                r.time = s.isEmpty() ? ("第" + course.start + "节") : s;
                r.name = (course.type == 1 ? "实验 · " : "") + course.name;
                r.room = course.room == null || course.room.isEmpty() ? "地点待定" : course.room;
                int a = minutes(s);
                int b = minutes(e);
                r.current = a >= 0 && b > a && nowMinutes >= a && nowMinutes <= b;
                d.rows.add(r);
            }
        }

        // 最近 30 天内最近的一场考试
        Exam best = null;
        long bestDiff = Long.MAX_VALUE;
        String bestDate = "";
        for (Exam exam : db.exams(semesterId)) {
            String date = dateOf(exam.time);
            long diff = Weeks.daysBetween(date, now);
            if (diff != Long.MIN_VALUE && diff >= 0 && diff <= 30 && diff < bestDiff) {
                best = exam;
                bestDiff = diff;
                bestDate = date;
            }
        }
        if (best != null) {
            String clock = clockOf(best.time);
            String when = bestDiff == 0
                    ? "今天有考试"
                    : (bestDiff + " 天后 · " + monthDay(bestDate));
            d.examText = when + "：" + best.course
                    + (clock.isEmpty() ? "" : " " + clock)
                    + (best.place == null || best.place.isEmpty() ? "" : " " + best.place);
        }
        return d;
    }

    private static String startClock(String[] times, int section) {
        if (section - 1 < 0 || section - 1 >= times.length) {
            return "";
        }
        String t = times[section - 1];
        int dash = t == null ? -1 : t.indexOf('-');
        return dash > 0 ? t.substring(0, dash) : "";
    }

    private static String endClock(String[] times, int section) {
        if (section - 1 < 0 || section - 1 >= times.length) {
            return "";
        }
        String t = times[section - 1];
        int dash = t == null ? -1 : t.indexOf('-');
        return dash > 0 ? t.substring(dash + 1) : "";
    }

    private static int minutes(String clock) {
        if (clock == null) {
            return -1;
        }
        int colon = clock.indexOf(':');
        if (colon <= 0) {
            return -1;
        }
        try {
            return Integer.parseInt(clock.substring(0, colon)) * 60
                    + Integer.parseInt(clock.substring(colon + 1));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String dateOf(String time) {
        if (time == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(20\\d{2}-\\d{2}-\\d{2})").matcher(time);
        return m.find() ? m.group(1) : "";
    }

    private static String clockOf(String time) {
        if (time == null) {
            return "";
        }
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("(\\d{1,2}:\\d{2}(?:-\\d{1,2}:\\d{2})?)").matcher(time);
        return m.find() ? m.group(1) : "";
    }

    private static String monthDay(String date) {
        if (date == null || date.length() < 10) {
            return date;
        }
        return date.substring(5);
    }
}
