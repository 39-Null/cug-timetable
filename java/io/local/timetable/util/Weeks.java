package io.local.timetable.util;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 周次解析与"当前第几周"计算。 */
public final class Weeks {

    private static final Pattern NUMBERS = Pattern.compile("([0-9]+(?:\\s*[-~]\\s*[0-9]+)?(?:\\s*[,，、]\\s*[0-9]+(?:\\s*[-~]\\s*[0-9]+)?)*)\\s*周");
    private static final long DAY_MS = 24L * 60L * 60L * 1000L;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private Weeks() {
    }

    /** 统一用公历，避免少数 locale 下默认 Calendar 不是 GregorianCalendar。 */
    public static Calendar now() {
        return Calendar.getInstance(Locale.US);
    }

    public static String today() {
        return today(now());
    }

    public static String today(Calendar c) {
        return String.format(Locale.US, "%04d-%02d-%02d",
                c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH));
    }

    /** 1=周一 … 7=周日。 */
    public static int weekday(Calendar c) {
        int dow = c.get(Calendar.DAY_OF_WEEK);
        return dow == Calendar.SUNDAY ? 7 : dow - 1;
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

    /** 计算 today 是第几周（1 起）；日期非法返回 -1。开学日之前返回 1。 */
    public static int weekOf(String semesterStart, String today) {
        Calendar now = today == null ? now() : parseLocalDate(today);
        if (now == null) {
            return -1;
        }
        return weekOf(semesterStart, now);
    }

    /**
     * 按日历日计算，不用毫秒差除以一天。
     * 内部把日期归一化到 UTC 零点，避免夏令时/系统时间校准造成差一天。
     */
    public static int weekOf(String semesterStart, Calendar today) {
        Calendar start = parseUtcDate(semesterStart);
        if (start == null || today == null) {
            return -1;
        }
        Calendar t = toUtcDate(today);
        long days = (t.getTimeInMillis() - start.getTimeInMillis()) / DAY_MS;
        if (days < 0) {
            return 1;
        }
        return (int) (days / 7) + 1;
    }

    /** date 距 today 的日历天数；date 非法返回 Long.MIN_VALUE。 */
    public static long daysBetween(String date, Calendar today) {
        Calendar d = parseUtcDate(date);
        if (d == null || today == null) {
            return Long.MIN_VALUE;
        }
        return (toUtcDate(today).getTimeInMillis() - d.getTimeInMillis()) / DAY_MS;
    }

    /** 今天是否早于开学日。 */
    public static boolean isBefore(String semesterStart, Calendar today) {
        Calendar start = parseUtcDate(semesterStart);
        if (start == null || today == null) {
            return false;
        }
        return toUtcDate(today).before(start);
    }

    /** 解析 yyyy-MM-dd / yyyy-M-d / yyyy/MM/dd；失败返回 null。 */
    public static Calendar parseLocalDate(String date) {
        int[] p = dateParts(date);
        if (p == null) {
            return null;
        }
        try {
            Calendar c = Calendar.getInstance(Locale.US);
            c.setLenient(false);
            c.clear();
            c.set(p[0], p[1] - 1, p[2], 12, 0, 0);
            c.getTimeInMillis();
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    /** 与 parseLocalDate 相同，但使用 UTC 零点，专门用于日期差计算。 */
    private static Calendar parseUtcDate(String date) {
        int[] p = dateParts(date);
        if (p == null) {
            return null;
        }
        try {
            Calendar c = Calendar.getInstance(UTC, Locale.US);
            c.setLenient(false);
            c.clear();
            c.set(p[0], p[1] - 1, p[2], 0, 0, 0);
            c.getTimeInMillis();
            return c;
        } catch (Exception e) {
            return null;
        }
    }

    private static Calendar toUtcDate(Calendar local) {
        Calendar c = Calendar.getInstance(UTC, Locale.US);
        c.clear();
        c.set(local.get(Calendar.YEAR), local.get(Calendar.MONTH),
                local.get(Calendar.DAY_OF_MONTH), 0, 0, 0);
        return c;
    }

    private static int[] dateParts(String date) {
        if (date == null) {
            return null;
        }
        String s = date.trim().replace('/', '-').replace('.', '-');
        String[] p = s.split("-");
        if (p.length != 3) {
            return null;
        }
        try {
            int y = Integer.parseInt(p[0]);
            int m = Integer.parseInt(p[1]);
            int d = Integer.parseInt(p[2]);
            if (y < 1970 || m < 1 || m > 12 || d < 1 || d > 31) {
                return null;
            }
            return new int[]{y, m, d};
        } catch (Exception e) {
            return null;
        }
    }

    public static boolean contains(String expr, int week) {
        return io.local.timetable.model.Course.parseWeeks(expr).contains(week);
    }

    /** 本周周一（用于设置页默认值）。 */
    public static String mondayOfThisWeek() {
        Calendar c = now();
        int dow = c.get(Calendar.DAY_OF_WEEK);
        int delta = dow == Calendar.SUNDAY ? -6 : Calendar.MONDAY - dow;
        c.add(Calendar.DAY_OF_MONTH, delta);
        return today(c);
    }
}
