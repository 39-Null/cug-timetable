package io.local.timetable.util;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 周次解析与"当前第几周"计算。 */
public final class Weeks {

    private static final Pattern NUMBERS = Pattern.compile("([0-9]+(?:\\s*[-~]\\s*[0-9]+)?(?:\\s*[,，、]\\s*[0-9]+(?:\\s*[-~]\\s*[0-9]+)?)*)\\s*周");

    private Weeks() {
    }

    /**
     * 从任意文本中抽取周次，返回规范化表达式：
     * "1-16"、"1-16|1"(单周)、"1-8,10-16|2"(双周)。解析不到返回 null。
     */
    public static String extract(String text) {
        if (text == null) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        Matcher m = NUMBERS.matcher(text);
        while (m.find()) {
            String block = m.group(1);
            int end = m.end();
            String tail = text.substring(end, Math.min(text.length(), end + 6));
            String parity = null;
            if (tail.contains("单")) {
                parity = "1";
            } else if (tail.contains("双")) {
                parity = "2";
            }
            for (String seg : block.split("[,，、]")) {
                String s = seg.replaceAll("\\s", "").replace('~', '-');
                if (s.isEmpty()) {
                    continue;
                }
                if ("1".equals(parity) || "2".equals(parity)) {
                    s = s + "|" + parity;
                }
                if (!parts.contains(s)) {
                    parts.add(s);
                }
            }
        }
        if (parts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String p : parts) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(p);
        }
        return sb.toString();
    }

    /** 计算 today 是第几周（1 起）；日期非法返回 -1。 */
    public static int weekOf(String semesterStart, String today) {
        try {
            SimpleDateFormat f = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            f.setLenient(false);
            Date start = f.parse(semesterStart);
            Date now = today == null ? new Date() : f.parse(today);
            if (start == null || now == null) {
                return -1;
            }
            long days = (now.getTime() - start.getTime()) / (24L * 3600L * 1000L);
            if (days < 0) {
                return 1;
            }
            return (int) (days / 7) + 1;
        } catch (Exception e) {
            return -1;
        }
    }

    public static String today() {
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
    }

    public static boolean contains(String expr, int week) {
        return io.local.timetable.model.Course.parseWeeks(expr).contains(week);
    }

    /** 本周周一（用于设置页默认值）。 */
    public static String mondayOfThisWeek() {
        Calendar c = Calendar.getInstance();
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int delta = dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow;
        c.add(Calendar.DAY_OF_MONTH, delta);
        return new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(c.getTime());
    }
}
