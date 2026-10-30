package io.local.timetable.util;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.util.Calendar;
import java.util.Iterator;

/** 本地设置（全部存 SharedPreferences，不上传）。 */
public class Prefs {

    public static final String FILE = "settings";

    /** 校区：未来城 / 南望山 */
    public static final String CAMPUS_FUTURE = "future";
    public static final String CAMPUS_NANWANG = "nanwang";

    private final SharedPreferences sp;

    public Prefs(Context ctx) {
        sp = ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public int totalWeeks() {
        return sp.getInt("totalWeeks", 20);
    }

    public void setTotalWeeks(int v) {
        sp.edit().putInt("totalWeeks", Math.max(1, v)).apply();
    }

    public int currentWeek() {
        return sp.getInt("currentWeek", 1);
    }

    public void setCurrentWeek(int v) {
        sp.edit().putInt("currentWeek", Math.max(1, v)).apply();
    }

    public int sections() {
        int def = CAMPUS_NANWANG.equals(campus()) ? 10 : 12;
        return sp.getInt("sections", def);
    }

    public void setSections(int v) {
        sp.edit().putInt("sections", Math.max(2, Math.min(16, v))).apply();
    }

    public String campus() {
        return sp.getString("campus", CAMPUS_FUTURE);
    }

    public void setCampus(String v) {
        sp.edit().putString("campus", v).apply();
    }

    public String campusName() {
        return CAMPUS_FUTURE.equals(campus()) ? "未来城" : "南望山";
    }

    /** 每小节的时间段，长度 = sections()。 */
    public String[] sectionTimes() {
        int n = sections();
        String[] defaults = defaultTimes(campus());
        String[] out = new String[n];
        for (int i = 0; i < n; i++) {
            String def = i < defaults.length ? defaults[i] : "";
            out[i] = sp.getString("time_" + campus() + "_" + i, def);
        }
        return out;
    }

    public void setSectionTime(int index, String value) {
        sp.edit().putString("time_" + campus() + "_" + index, value).apply();
    }

    /** 恢复当前校区的默认时间。 */
    public void resetTimes() {
        String campus = campus();
        SharedPreferences.Editor e = sp.edit();
        for (int i = 0; i < 16; i++) {
            e.remove("time_" + campus + "_" + i);
        }
        e.apply();
    }

    /**
     * 校区默认作息。
     *
     * 未来城：08:30 开始，45 分钟一节，大节内 5 分钟课间，12 小节。
     * 南望山：08:00 开始，10 小节；5-8 节与 9-10 节分夏/冬两套时间：
     *   夏季 5/1-9/30：下午 14:30 开始，晚上 19:30 开始
     *   冬季 10/1-次年 4/30：下午 14:00 开始，晚上 19:00 开始
     * 均可在侧边栏逐节修改。
     */
    public static String[] defaultTimes(String campus) {
        if (CAMPUS_NANWANG.equals(campus)) {
            return nanwangTimes(isSummer());
        }
        return new String[]{
                "08:30-09:15", "09:20-10:05",
                "10:15-11:00", "11:05-11:50",
                "14:00-14:45", "14:50-15:35",
                "15:45-16:30", "16:35-17:20",
                "18:30-19:15", "19:20-20:05",
                "20:15-21:00", "21:05-21:50"
        };
    }

    /** 南望山夏季：5 月 1 日 - 9 月 30 日。 */
    private static boolean isSummer() {
        Calendar c = Calendar.getInstance();
        int m = c.get(Calendar.MONTH) + 1;
        int d = c.get(Calendar.DAY_OF_MONTH);
        return (m > 5 || (m == 5 && d >= 1)) && m <= 9;
    }

    /** 南望山只有 10 小节，11/12 留空。 */
    private static String[] nanwangTimes(boolean summer) {
        if (summer) {
            return new String[]{
                    "08:00-08:45", "08:50-09:35",
                    "10:05-10:50", "10:55-11:40",
                    "14:30-15:15", "15:20-16:05",
                    "16:35-17:20", "17:25-18:10",
                    "19:30-20:15", "20:20-21:05",
                    "", ""
            };
        }
        return new String[]{
                "08:00-08:45", "08:50-09:35",
                "10:05-10:50", "10:55-11:40",
                "14:00-14:45", "14:50-15:35",
                "16:05-16:50", "16:55-17:40",
                "19:00-19:45", "19:50-20:35",
                "", ""
        };
    }

    /** 开学日期 yyyy-MM-dd，空 = 手动周次。 */
    public String semesterStart() {
        return sp.getString("semesterStart", "");
    }

    public void setSemesterStart(String v) {
        sp.edit().putString("semesterStart", v == null ? "" : v.trim()).apply();
    }

    /** 教务系统地址（网页导入时预填）。 */
    public String schoolUrl() {
        return sp.getString("schoolUrl", "https://i.cug.edu.cn");
    }

    public void setSchoolUrl(String v) {
        sp.edit().putString("schoolUrl", v).apply();
    }

    public String lastImport() {
        return sp.getString("lastImport", "");
    }

    public void setLastImport(String v) {
        sp.edit().putString("lastImport", v).apply();
    }

    // ---------------- 主题 ----------------

    public boolean dark() {
        return sp.getBoolean("dark", false);
    }

    public void setDark(boolean v) {
        sp.edit().putBoolean("dark", v).apply();
    }

    /** 自定义背景图（应用私有目录里的绝对路径），空 = 无背景。 */
    public String bgImage() {
        return sp.getString("bgImage", "");
    }

    public void setBgImage(String path) {
        sp.edit().putString("bgImage", path == null ? "" : path).apply();
    }

    /** 上边栏透明度 0~100；0=不透明，100=完全透明。 */
    public int topBarTransparency() {
        return Math.max(0, Math.min(100, sp.getInt("topBarTransparency", 10)));
    }

    public void setTopBarTransparency(int v) {
        sp.edit().putInt("topBarTransparency", Math.max(0, Math.min(100, v))).apply();
    }

    /** 侧边栏透明度 0~100；0=不透明，100=完全透明。 */
    public int sideBarTransparency() {
        return Math.max(0, Math.min(100, sp.getInt("sideBarTransparency", 10)));
    }

    public void setSideBarTransparency(int v) {
        sp.edit().putInt("sideBarTransparency", Math.max(0, Math.min(100, v))).apply();
    }

    /** 背景遮罩浓度 0~80（百分比），越大文字越清楚。 */
    public int bgDim() {
        return sp.getInt("bgDim", 55);
    }

    public void setBgDim(int v) {
        sp.edit().putInt("bgDim", Math.max(0, Math.min(80, v))).apply();
    }

    /** 网格线清晰度 0~100（100 = 最明显，0 = 隐藏）。 */
    public int gridAlpha() {
        return sp.getInt("gridAlpha", 100);
    }

    public void setGridAlpha(int v) {
        sp.edit().putInt("gridAlpha", Math.max(0, Math.min(100, v))).apply();
    }

    /** 背景图版本号：每次裁剪/更换后变化，用来触发界面重建。 */
    public long bgStamp() {
        return sp.getLong("bgStamp", 0L);
    }

    public void setBgStamp(long v) {
        sp.edit().putLong("bgStamp", v).apply();
    }

    /** 当前选中的学期档位。 */
    public long currentSemesterId() {
        return sp.getLong("semesterId", 0L);
    }

    public void setCurrentSemesterId(long v) {
        sp.edit().putLong("semesterId", v).apply();
    }

    /** 上次检查更新的时间，用于限制自动检查频率。 */
    public long lastUpdateCheck() {
        return sp.getLong("lastUpdateCheck", 0L);
    }

    public void setLastUpdateCheck(long v) {
        sp.edit().putLong("lastUpdateCheck", v).apply();
    }

    /** 自动检查时用户选择“跳过此版本”的版本号。 */
    public String skipUpdateVersion() {
        return sp.getString("skipUpdateVersion", "");
    }

    public void setSkipUpdateVersion(String v) {
        sp.edit().putString("skipUpdateVersion", v == null ? "" : v).apply();
    }

    /** 从备份恢复设置；背景图和当前学期由 Backup 单独处理。 */
    public void restoreFrom(JSONObject o) throws Exception {
        SharedPreferences.Editor e = sp.edit();
        if (o.has("totalWeeks")) {
            e.putInt("totalWeeks", Math.max(1, o.optInt("totalWeeks", 20)));
        }
        if (o.has("currentWeek")) {
            e.putInt("currentWeek", Math.max(1, o.optInt("currentWeek", 1)));
        }
        if (o.has("sections")) {
            e.putInt("sections", Math.max(2, Math.min(16, o.optInt("sections", 12))));
        }
        String campus = o.optString("campus", CAMPUS_FUTURE);
        if (!CAMPUS_FUTURE.equals(campus) && !CAMPUS_NANWANG.equals(campus)) {
            campus = CAMPUS_FUTURE;
        }
        e.putString("campus", campus);
        if (o.has("semesterStart")) {
            e.putString("semesterStart", o.optString("semesterStart", ""));
        }
        if (o.has("schoolUrl")) {
            e.putString("schoolUrl", o.optString("schoolUrl", "https://i.cug.edu.cn"));
        }
        e.putBoolean("dark", o.optBoolean("dark", false));
        e.putInt("bgDim", Math.max(0, Math.min(80, o.optInt("bgDim", 55))));
        e.putInt("gridAlpha", Math.max(0, Math.min(100, o.optInt("gridAlpha", 100))));

        for (String key : sp.getAll().keySet()) {
            if (key.startsWith("time_")) {
                e.remove(key);
            }
        }
        JSONObject times = o.optJSONObject("times");
        if (times != null) {
            Iterator<String> it = times.keys();
            while (it.hasNext()) {
                String key = it.next();
                if (key.startsWith("time_")) {
                    e.putString(key, times.optString(key, ""));
                }
            }
        }
        e.commit();
    }
}
