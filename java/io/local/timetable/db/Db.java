package io.local.timetable.db;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import io.local.timetable.model.Course;
import io.local.timetable.model.Exam;
import io.local.timetable.model.Semester;
import io.local.timetable.util.Prefs;

/** 本地数据库：学期 + 课程 + 考试。数据只在本机。 */
public class Db extends SQLiteOpenHelper {

    private static final String NAME = "timetable.db";
    private static final int VERSION = 3;

    private static Db instance;
    private final Context app;

    public static synchronized Db get(Context ctx) {
        if (instance == null) {
            instance = new Db(ctx.getApplicationContext());
        }
        return instance;
    }

    private Db(Context ctx) {
        super(ctx, NAME, null, VERSION);
        app = ctx;
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL("CREATE TABLE courses(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT,teacher TEXT,room TEXT," +
                "day INTEGER,start INTEGER,\"end\" INTEGER," +
                "weeks TEXT,note TEXT,color INTEGER,code TEXT,clazz TEXT,credit TEXT," +
                "campus TEXT,exam_type TEXT,hours TEXT,type INTEGER DEFAULT 0,parent TEXT," +
                "semester_id INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE exams(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "course TEXT,time TEXT,place TEXT,seat TEXT,note TEXT," +
                "semester_id INTEGER DEFAULT 0)");
        db.execSQL("CREATE TABLE semesters(" +
                "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                "name TEXT,start TEXT,created INTEGER,sort INTEGER DEFAULT 0)");
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        ensureColumns(db);
        ensureSemesters(db);
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        ensureColumns(db);
        ensureSemesters(db);
    }

    /** 兼容旧库：缺列就补上（每一列单独 try，避免一条失败影响其它）。 */
    private static void ensureColumns(SQLiteDatabase db) {
        Set<String> cols = COLUMNS(db, "courses");
        String[] add = {"code", "clazz", "credit", "campus", "exam_type", "hours", "parent"};
        for (String col : add) {
            if (!cols.contains(col)) {
                try {
                    db.execSQL("ALTER TABLE courses ADD COLUMN " + col + " TEXT");
                } catch (Exception ignored) {
                }
            }
        }
        if (!cols.contains("semester_id")) {
            try {
                db.execSQL("ALTER TABLE courses ADD COLUMN semester_id INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }
        }
        if (!cols.contains("type")) {
            try {
                db.execSQL("ALTER TABLE courses ADD COLUMN type INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }
        }
        Set<String> examCols = COLUMNS(db, "exams");
        if (!examCols.contains("semester_id")) {
            try {
                db.execSQL("ALTER TABLE exams ADD COLUMN semester_id INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }
        }
    }

    private static Set<String> COLUMNS(SQLiteDatabase db, String table) {
        Set<String> cols = new HashSet<>();
        Cursor c = null;
        try {
            c = db.rawQuery("PRAGMA table_info(" + table + ")", null);
            while (c.moveToNext()) {
                cols.add(c.getString(c.getColumnIndex("name")));
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return cols;
    }

    /** 没有学期时创建默认学期，并把旧数据挂到它下面。 */
    private void ensureSemesters(SQLiteDatabase db) {
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS semesters(" +
                    "id INTEGER PRIMARY KEY AUTOINCREMENT," +
                    "name TEXT,start TEXT,created INTEGER,sort INTEGER DEFAULT 0)");
        } catch (Exception ignored) {
        }
        if (!COLUMNS(db, "semesters").contains("sort")) {
            try {
                db.execSQL("ALTER TABLE semesters ADD COLUMN sort INTEGER DEFAULT 0");
            } catch (Exception ignored) {
            }
        }
        long id = 0;
        Cursor c = null;
        try {
            c = db.rawQuery("SELECT id FROM semesters ORDER BY id LIMIT 1", null);
            if (c.moveToNext()) {
                id = c.getLong(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        if (id == 0) {
            Prefs prefs = new Prefs(app);
            ContentValues v = new ContentValues();
            v.put("name", defaultSemesterName());
            v.put("start", prefs.semesterStart());
            v.put("created", System.currentTimeMillis());
            try {
                id = db.insert("semesters", null, v);
            } catch (Exception ignored) {
            }
            if (id > 0) {
                try {
                    db.execSQL("UPDATE courses SET semester_id=" + id
                            + " WHERE semester_id IS NULL OR semester_id=0");
                    db.execSQL("UPDATE exams SET semester_id=" + id
                            + " WHERE semester_id IS NULL OR semester_id=0");
                } catch (Exception ignored) {
                }
            }
        }
        Prefs prefs = new Prefs(app);
        if (prefs.currentSemesterId() <= 0 && id > 0) {
            prefs.setCurrentSemesterId(id);
        }
    }

    private static String defaultSemesterName() {
        Calendar c = Calendar.getInstance();
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        if (m >= 7) {
            return y + "-" + (y + 1) + " 第1学期";
        }
        return (y - 1) + "-" + y + " 第2学期";
    }

    public long currentSemesterId() {
        long id = new Prefs(app).currentSemesterId();
        if (id > 0) {
            return id;
        }
        List<Semester> list = semesters();
        if (!list.isEmpty()) {
            id = list.get(0).id;
            new Prefs(app).setCurrentSemesterId(id);
            return id;
        }
        return 0;
    }

    // ---------- 学期 ----------

    public List<Semester> semesters() {
        List<Semester> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,name,start,created,sort FROM semesters ORDER BY sort ASC, start DESC, id DESC", null);
        while (c.moveToNext()) {
            Semester s = new Semester();
            s.id = c.getLong(0);
            s.name = safe(c.getString(1));
            s.start = safe(c.getString(2));
            s.created = c.getLong(3);
            s.sort = c.getInt(4);
            s.courseCount = countCourses(s.id);
            list.add(s);
        }
        c.close();
        return list;
    }

    public Semester semester(long id) {
        for (Semester s : semesters()) {
            if (s.id == id) {
                return s;
            }
        }
        return null;
    }

    public long addSemester(String name, String start) {
        int max = 0;
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery("SELECT IFNULL(MAX(sort),0) FROM semesters", null);
            if (c.moveToNext()) {
                max = c.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        ContentValues v = new ContentValues();
        v.put("name", name == null ? "" : name.trim());
        v.put("start", start == null ? "" : start.trim());
        v.put("created", System.currentTimeMillis());
        v.put("sort", max + 1);
        return getWritableDatabase().insert("semesters", null, v);
    }

    /** 按传入顺序重写学期排序（用于拖拽排序）。 */
    public void reorderSemesters(List<Long> ids) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            for (int i = 0; i < ids.size(); i++) {
                ContentValues v = new ContentValues();
                v.put("sort", i + 1);
                db.update("semesters", v, "id=?", new String[]{String.valueOf(ids.get(i))});
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    public void updateSemester(Semester s) {
        ContentValues v = new ContentValues();
        v.put("name", s.name);
        v.put("start", s.start);
        getWritableDatabase().update("semesters", v, "id=?", new String[]{String.valueOf(s.id)});
    }

    public void deleteSemester(long id) {
        SQLiteDatabase db = getWritableDatabase();
        db.delete("courses", "semester_id=?", new String[]{String.valueOf(id)});
        db.delete("exams", "semester_id=?", new String[]{String.valueOf(id)});
        db.delete("semesters", "id=?", new String[]{String.valueOf(id)});
    }

    public int countCourses(long semesterId) {
        Cursor c = null;
        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT COUNT(*) FROM courses WHERE semester_id=?",
                    new String[]{String.valueOf(semesterId)});
            if (c.moveToNext()) {
                return c.getInt(0);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return 0;
    }

    // ---------- 课程 ----------

    public List<Course> courses() {
        return courses(currentSemesterId());
    }

    public List<Course> courses(long semesterId) {
        List<Course> list = new ArrayList<>();
        SQLiteDatabase db = getReadableDatabase();
        Cursor c = db.rawQuery("SELECT id,name,teacher,room,day,start,\"end\",weeks,note,color,"
                        + "code,clazz,credit,campus,exam_type,hours,type,parent,semester_id "
                        + "FROM courses WHERE semester_id=? ORDER BY day,start",
                new String[]{String.valueOf(semesterId)});
        while (c.moveToNext()) {
            Course item = new Course();
            item.id = c.getLong(0);
            item.name = safe(c.getString(1));
            item.teacher = safe(c.getString(2));
            item.room = safe(c.getString(3));
            item.day = c.getInt(4);
            item.start = c.getInt(5);
            item.end = c.getInt(6);
            item.weeks = safe(c.getString(7));
            item.note = safe(c.getString(8));
            item.color = c.getInt(9);
            item.code = str(c, "code");
            item.clazz = str(c, "clazz");
            item.credit = str(c, "credit");
            item.campus = str(c, "campus");
            item.examType = str(c, "exam_type");
            item.hours = str(c, "hours");
            int ti = c.getColumnIndex("type");
            item.type = ti >= 0 ? c.getInt(ti) : 0;
            item.parent = str(c, "parent");
            item.semesterId = semesterId;
            list.add(item);
        }
        c.close();
        return list;
    }

    public long saveCourse(Course item) {
        if (item.semesterId <= 0) {
            item.semesterId = currentSemesterId();
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("name", item.name);
        v.put("teacher", item.teacher);
        v.put("room", item.room);
        v.put("day", item.day);
        v.put("start", item.start);
        v.put("end", item.end);
        v.put("weeks", item.weeks);
        v.put("note", item.note);
        v.put("color", item.color);
        v.put("code", item.code);
        v.put("clazz", item.clazz);
        v.put("credit", item.credit);
        v.put("campus", item.campus);
        v.put("exam_type", item.examType);
        v.put("hours", item.hours);
        v.put("type", item.type);
        v.put("parent", item.parent);
        v.put("semester_id", item.semesterId);
        if (item.id > 0) {
            db.update("courses", v, "id=?", new String[]{String.valueOf(item.id)});
            return item.id;
        }
        long id = db.insert("courses", null, v);
        item.id = id;
        return id;
    }

    public void deleteCourse(long id) {
        getWritableDatabase().delete("courses", "id=?", new String[]{String.valueOf(id)});
    }

    /** 覆盖式导入：先清空该学期再写入。 */
    public void replaceCourses(List<Course> items, long semesterId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("courses", "semester_id=?", new String[]{String.valueOf(semesterId)});
            for (Course item : items) {
                ContentValues v = new ContentValues();
                v.put("name", item.name);
                v.put("teacher", item.teacher);
                v.put("room", item.room);
                v.put("day", item.day);
                v.put("start", item.start);
                v.put("end", item.end);
                v.put("weeks", item.weeks);
                v.put("note", item.note);
                v.put("color", item.color);
                v.put("code", item.code);
                v.put("clazz", item.clazz);
                v.put("credit", item.credit);
                v.put("campus", item.campus);
                v.put("exam_type", item.examType);
                v.put("hours", item.hours);
                v.put("type", item.type);
                v.put("parent", item.parent);
                v.put("semester_id", semesterId);
                db.insert("courses", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 同一天、节次重叠且周次有交集 → 视为冲突；返回冲突描述，没有冲突返回 null。 */
    public String conflict(Course item) {
        long semesterId = item.semesterId > 0 ? item.semesterId : currentSemesterId();
        Set<Integer> weeks = Course.parseWeeks(item.weeks);
        if (weeks.isEmpty()) {
            weeks.add(1);
        }
        Cursor c = null;        try {
            c = getReadableDatabase().rawQuery(
                    "SELECT name,start,\"end\",weeks FROM courses WHERE semester_id=? AND day=? AND id<>?",
                    new String[]{String.valueOf(semesterId), String.valueOf(item.day),
                            String.valueOf(item.id)});
            while (c.moveToNext()) {
                int s = c.getInt(1);
                int e = c.getInt(2);
                if (item.start > e || item.end < s) {
                    continue;
                }
                Set<Integer> other = Course.parseWeeks(safe(c.getString(3)));
                if (other.isEmpty()) {
                    other.add(1);
                }
                for (int w : weeks) {
                    if (other.contains(w)) {
                        String[] days = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
                        return safe(c.getString(0)) + "（" + days[item.day] + " 第" + s + "-" + e
                                + "节，第" + w + "周）";
                    }
                }
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) {
                c.close();
            }
        }
        return null;
    }

    /** 检查一组待导入课程内部是否有冲突；返回冲突描述或 null。 */
    public String conflictIn(List<Course> items) {
        for (int i = 0; i < items.size(); i++) {
            Course a = items.get(i);
            Set<Integer> wa = Course.parseWeeks(a.weeks);
            if (wa.isEmpty()) {
                wa.add(1);
            }
            for (int j = i + 1; j < items.size(); j++) {
                Course b = items.get(j);
                if (a.day != b.day || a.start > b.end || a.end < b.start) {
                    continue;
                }
                Set<Integer> wb = Course.parseWeeks(b.weeks);
                if (wb.isEmpty()) {
                    wb.add(1);
                }
                for (int w : wa) {
                    if (wb.contains(w)) {
                        return a.name + " 与 " + b.name + "（同一天同一节，第" + w + "周）";
                    }
                }
            }
        }
        return null;
    }

    // ---------- 考试 ----------

    public List<Exam> exams() {
        return exams(currentSemesterId());
    }

    public List<Exam> exams(long semesterId) {
        List<Exam> list = new ArrayList<>();
        Cursor c = getReadableDatabase().rawQuery(
                "SELECT id,course,time,place,seat,note FROM exams WHERE semester_id=? ORDER BY time",
                new String[]{String.valueOf(semesterId)});
        while (c.moveToNext()) {
            Exam item = new Exam();
            item.id = c.getLong(0);
            item.course = safe(c.getString(1));
            item.time = safe(c.getString(2));
            item.place = safe(c.getString(3));
            item.seat = safe(c.getString(4));
            item.note = safe(c.getString(5));
            item.semesterId = semesterId;
            list.add(item);
        }
        c.close();
        return list;
    }

    public long saveExam(Exam item) {
        if (item.semesterId <= 0) {
            item.semesterId = currentSemesterId();
        }
        SQLiteDatabase db = getWritableDatabase();
        ContentValues v = new ContentValues();
        v.put("course", item.course);
        v.put("time", item.time);
        v.put("place", item.place);
        v.put("seat", item.seat);
        v.put("note", item.note);
        v.put("semester_id", item.semesterId);
        if (item.id > 0) {
            db.update("exams", v, "id=?", new String[]{String.valueOf(item.id)});
            return item.id;
        }
        long id = db.insert("exams", null, v);
        item.id = id;
        return id;
    }

    public void deleteExam(long id) {
        getWritableDatabase().delete("exams", "id=?", new String[]{String.valueOf(id)});
    }

    /** 覆盖式导入考试：只清空该学期。 */
    public void replaceExams(List<Exam> items, long semesterId) {
        SQLiteDatabase db = getWritableDatabase();
        db.beginTransaction();
        try {
            db.delete("exams", "semester_id=?", new String[]{String.valueOf(semesterId)});
            for (Exam item : items) {
                ContentValues v = new ContentValues();
                v.put("course", item.course);
                v.put("time", item.time);
                v.put("place", item.place);
                v.put("seat", item.seat);
                v.put("note", item.note);
                v.put("semester_id", semesterId);
                db.insert("exams", null, v);
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
    }

    /** 只清空当前学期的课程与考试。 */
    public void clearCurrentSemester() {
        long id = currentSemesterId();
        SQLiteDatabase db = getWritableDatabase();
        db.delete("courses", "semester_id=?", new String[]{String.valueOf(id)});
        db.delete("exams", "semester_id=?", new String[]{String.valueOf(id)});
    }

    private static String safe(String s) {
        return s == null ? "" : s;
    }

    /** 按列名取值，列不存在时返回空串（兼容旧数据库）。 */
    private static String str(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0) {
            return "";
        }
        return safe(c.getString(i));
    }
}
