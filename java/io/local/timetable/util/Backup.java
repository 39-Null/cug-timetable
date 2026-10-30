package io.local.timetable.util;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Reader;
import java.io.InputStreamReader;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import io.local.timetable.db.Db;
import io.local.timetable.model.Course;
import io.local.timetable.model.Exam;
import io.local.timetable.model.Semester;

/** 整库导出/导入：学期、课程、考试、设置和背景图。导入前完整解析，失败不会清空原数据。 */
public final class Backup {

    public static final String APP = "io.local.timetable";
    public static final int SCHEMA = 1;

    public static final class Result {
        public int semesters;
        public int courses;
        public int exams;
    }

    private Backup() {
    }

    public static void export(Context ctx, OutputStream out) throws Exception {
        Db db = Db.get(ctx);
        Prefs prefs = new Prefs(ctx);
        JSONObject root = new JSONObject();
        root.put("app", APP);
        root.put("schema", SCHEMA);
        root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
                .format(new Date()));
        root.put("versionName", versionName(ctx));
        root.put("currentSemesterId", prefs.currentSemesterId());

        JSONObject p = new JSONObject();
        p.put("totalWeeks", prefs.totalWeeks());
        p.put("currentWeek", prefs.currentWeek());
        p.put("sections", prefs.sections());
        p.put("campus", prefs.campus());
        p.put("semesterStart", prefs.semesterStart());
        p.put("schoolUrl", prefs.schoolUrl());
        p.put("dark", prefs.dark());
        p.put("bgDim", prefs.bgDim());
        p.put("gridAlpha", prefs.gridAlpha());
        JSONObject times = new JSONObject();
        SharedPreferences sp = ctx.getSharedPreferences(Prefs.FILE, Context.MODE_PRIVATE);
        for (Map.Entry<String, ?> e : sp.getAll().entrySet()) {
            if (e.getKey().startsWith("time_")) {
                times.put(e.getKey(), String.valueOf(e.getValue()));
            }
        }
        p.put("times", times);
        root.put("prefs", p);

        JSONArray semesters = new JSONArray();
        JSONArray courses = new JSONArray();
        JSONArray exams = new JSONArray();
        for (Semester s : db.semesters()) {
            JSONObject o = new JSONObject();
            o.put("id", s.id);
            o.put("name", s.name);
            o.put("start", s.start);
            o.put("created", s.created);
            o.put("sort", s.sort);
            semesters.put(o);
            for (Course c : db.courses(s.id)) {
                courses.put(courseJson(c));
            }
            for (Exam e : db.exams(s.id)) {
                exams.put(examJson(e));
            }
        }
        root.put("semesters", semesters);
        root.put("courses", courses);
        root.put("exams", exams);
        putFile(root, "bgImage", Theme.bgFile(ctx));
        putFile(root, "bgSource", Theme.sourceFile(ctx));

        out.write(root.toString().getBytes("UTF-8"));
        out.flush();
    }

    public static Result restore(Context ctx, InputStream in) throws Exception {
        String json = readText(in);
        JSONObject root = new JSONObject(json);
        if (!APP.equals(root.optString("app", ""))) {
            throw new Exception("这不是「泥地课表」的备份文件");
        }
        int schema = root.optInt("schema", 0);
        if (schema != SCHEMA) {
            throw new Exception("备份格式版本不支持，请使用新版应用导入");
        }

        JSONArray semestersJson = root.optJSONArray("semesters");
        if (semestersJson == null || semestersJson.length() == 0) {
            throw new Exception("备份中没有学期数据");
        }
        List<Semester> semesters = new ArrayList<>();
        Set<Long> semesterIds = new HashSet<>();
        long maxSemesterId = 0;
        for (int i = 0; i < semestersJson.length(); i++) {
            JSONObject o = semestersJson.getJSONObject(i);
            Semester s = new Semester();
            s.id = o.optLong("id", 0);
            s.name = o.optString("name", "").trim();
            s.start = o.optString("start", "").trim();
            s.created = o.optLong("created", System.currentTimeMillis());
            s.sort = o.optInt("sort", i + 1);
            if (s.name.isEmpty()) {
                s.name = "未命名学期";
            }
            if (s.id <= 0) {
                s.id = maxSemesterId + 1;
            }
            if (semesterIds.contains(s.id)) {
                throw new Exception("备份中的学期 ID 重复");
            }
            semesterIds.add(s.id);
            maxSemesterId = Math.max(maxSemesterId, s.id);
            semesters.add(s);
        }
        long firstSemesterId = semesters.get(0).id;

        List<Course> courses = new ArrayList<>();
        JSONArray coursesJson = root.optJSONArray("courses");
        if (coursesJson != null) {
            for (int i = 0; i < coursesJson.length(); i++) {
                JSONObject o = coursesJson.getJSONObject(i);
                Course c = new Course();
                c.id = o.optLong("id", 0);
                c.name = o.optString("name", "");
                c.teacher = o.optString("teacher", "");
                c.room = o.optString("room", "");
                c.day = o.optInt("day", 1);
                c.start = o.optInt("start", 1);
                c.end = o.optInt("end", c.start);
                c.weeks = o.optString("weeks", "1-20").trim();
                c.note = o.optString("note", "");
                c.color = o.optInt("color", -1);
                c.code = o.optString("code", "");
                c.clazz = o.optString("clazz", "");
                c.credit = o.optString("credit", "");
                c.campus = o.optString("campus", "");
                c.examType = o.optString("exam_type", o.optString("examType", ""));
                c.hours = o.optString("hours", "");
                c.type = o.optInt("type", 0);
                c.parent = o.optString("parent", "");
                c.semesterId = o.optLong("semesterId",
                        o.optLong("semester_id", firstSemesterId));
                if (c.day < 1 || c.day > 7 || c.start < 1 || c.end < c.start || c.end > 16) {
                    throw new Exception("备份中有无效的课程节次");
                }
                if (!semesterIds.contains(c.semesterId)) {
                    c.semesterId = firstSemesterId;
                }
                if (c.weeks.isEmpty()) {
                    c.weeks = "1-20";
                }
                courses.add(c);
            }
        }

        List<Exam> exams = new ArrayList<>();
        JSONArray examsJson = root.optJSONArray("exams");
        if (examsJson != null) {
            for (int i = 0; i < examsJson.length(); i++) {
                JSONObject o = examsJson.getJSONObject(i);
                Exam e = new Exam();
                e.id = o.optLong("id", 0);
                e.course = o.optString("course", "");
                e.time = o.optString("time", "");
                e.place = o.optString("place", "");
                e.seat = o.optString("seat", "");
                e.note = o.optString("note", "");
                e.semesterId = o.optLong("semesterId",
                        o.optLong("semester_id", firstSemesterId));
                if (!semesterIds.contains(e.semesterId)) {
                    e.semesterId = firstSemesterId;
                }
                exams.add(e);
            }
        }

        JSONObject prefsJson = root.optJSONObject("prefs");
        Db db = Db.get(ctx);
        // 先全量解析并校验，再在一个事务里替换，失败会回滚。
        db.restoreAll(semesters, courses, exams);

        Prefs prefs = new Prefs(ctx);
        if (prefsJson != null) {
            prefs.restoreFrom(prefsJson);
        }
        long currentId = root.optLong("currentSemesterId", firstSemesterId);
        Semester current = null;
        for (Semester s : semesters) {
            if (s.id == currentId) {
                current = s;
                break;
            }
        }
        if (current == null) {
            current = semesters.get(0);
        }
        prefs.setCurrentSemesterId(current.id);
        prefs.setSemesterStart(current.start);

        restoreFile(root, "bgImage", Theme.bgFile(ctx));
        restoreFile(root, "bgSource", Theme.sourceFile(ctx));
        if (Theme.bgFile(ctx).exists()) {
            prefs.setBgImage(Theme.bgFile(ctx).getAbsolutePath());
            Theme.ensureSource(ctx);
        } else {
            prefs.setBgImage("");
        }
        prefs.setBgStamp(System.currentTimeMillis());

        Result result = new Result();
        result.semesters = semesters.size();
        result.courses = courses.size();
        result.exams = exams.size();
        return result;
    }

    private static JSONObject courseJson(Course c) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", c.id);
        o.put("name", c.name);
        o.put("teacher", c.teacher);
        o.put("room", c.room);
        o.put("day", c.day);
        o.put("start", c.start);
        o.put("end", c.end);
        o.put("weeks", c.weeks);
        o.put("note", c.note);
        o.put("color", c.color);
        o.put("code", c.code);
        o.put("clazz", c.clazz);
        o.put("credit", c.credit);
        o.put("campus", c.campus);
        o.put("exam_type", c.examType);
        o.put("hours", c.hours);
        o.put("type", c.type);
        o.put("parent", c.parent);
        o.put("semesterId", c.semesterId);
        return o;
    }

    private static JSONObject examJson(Exam e) throws Exception {
        JSONObject o = new JSONObject();
        o.put("id", e.id);
        o.put("course", e.course);
        o.put("time", e.time);
        o.put("place", e.place);
        o.put("seat", e.seat);
        o.put("note", e.note);
        o.put("semesterId", e.semesterId);
        return o;
    }

    private static void putFile(JSONObject root, String key, File file) throws Exception {
        if (!file.isFile() || file.length() == 0) {
            return;
        }
        byte[] data = readBytes(file);
        root.put(key, Base64.encodeToString(data, Base64.NO_WRAP));
    }

    private static void restoreFile(JSONObject root, String key, File file) throws Exception {
        String encoded = root.optString(key, "");
        if (encoded == null || encoded.isEmpty()) {
            if (file.exists()) {
                file.delete();
            }
            return;
        }
        byte[] data = Base64.decode(encoded, Base64.DEFAULT);
        FileOutputStream out = new FileOutputStream(file);
        try {
            out.write(data);
            out.flush();
        } finally {
            out.close();
        }
    }

    private static byte[] readBytes(File file) throws Exception {
        InputStream in = new java.io.FileInputStream(file);
        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[32 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } finally {
            in.close();
        }
    }

    private static String readText(InputStream in) throws Exception {
        Reader reader = new InputStreamReader(in, "UTF-8");
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[16 * 1024];
        int n;
        while ((n = reader.read(buf)) > 0) {
            sb.append(buf, 0, n);
        }
        reader.close();
        return sb.toString();
    }

    private static String versionName(Context ctx) {
        try {
            PackageInfo info = ctx.getPackageManager()
                    .getPackageInfo(ctx.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }
}
