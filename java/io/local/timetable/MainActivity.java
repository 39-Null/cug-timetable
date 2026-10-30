package io.local.timetable;

import android.app.Activity;
import android.app.AlertDialog;
import android.appwidget.AppWidgetManager;
import android.content.Intent;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.db.Db;
import io.local.timetable.model.Course;
import io.local.timetable.model.Exam;
import io.local.timetable.model.Semester;
import io.local.timetable.ui.TimetableView;
import io.local.timetable.update.UpdateManager;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;
import io.local.timetable.util.Weeks;
import io.local.timetable.widget.TimetableWidgetProvider;
import io.local.timetable.widget.WidgetUpdater;

public class MainActivity extends Activity implements TimetableView.Listener {

    private Db db;
    private Prefs prefs;
    private TimetableView timetable;
    private TextView weekLabel;
    private List<Course> courses;
    private String themeSig;
    private long semesterId;
    private View dragCard;
    private int dragFrom;
    private float dragDownY;
    private float dragTop;
    private final Handler midnightHandler = new Handler(Looper.getMainLooper());
    private final Runnable midnightRefresh = new Runnable() {
        @Override
        public void run() {
            refreshToToday();
            scheduleMidnightRefresh();
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        db = Db.get(this);
        prefs = new Prefs(this);
        // 清理旧版本遗留的页面快照（含学号/姓名），新版本不再落盘
        try {
            java.io.File dir = getExternalFilesDir(null);
            if (dir != null) {
                new java.io.File(dir, "last_page.html").delete();
                new java.io.File(dir, "last_exam_page.html").delete();
            }
        } catch (Exception ignored) {
        }
        themeSig = Theme.signature(this);
        semesterId = db.currentSemesterId();
        timetable = findViewById(R.id.timetable);
        timetable.setListener(this);
        weekLabel = findViewById(R.id.btnThisWeek);

        findViewById(R.id.btnPrevWeek).setOnClickListener(v -> changeWeek(-1));
        findViewById(R.id.btnNextWeek).setOnClickListener(v -> changeWeek(1));
        weekLabel.setOnClickListener(v -> changeWeek(0));

        findViewById(R.id.tabTimetable).setOnClickListener(v -> showSemesters());
        findViewById(R.id.tabImport).setOnClickListener(v -> showImport());
        findViewById(R.id.tabTheme).setOnClickListener(v -> startActivity(new Intent(this, ThemeActivity.class)));
        findViewById(R.id.tabSettings).setOnClickListener(v -> showSettings());

        applyThemeUi();
        refreshToToday();
        UpdateManager.autoCheck(this);
    }

    private void applyThemeUi() {
        Theme.Palette p = Theme.p(this);
        boolean hasBg = Theme.hasBackground(this);
        findViewById(R.id.root).setBackgroundColor(hasBg ? Color.TRANSPARENT : p.bg);
        findViewById(R.id.topBar).setBackgroundColor(hasBg ? p.cardAlpha : p.card);
        findViewById(R.id.bottomBar).setBackgroundColor(hasBg ? p.cardAlpha : p.card);
        ((TextView) findViewById(R.id.title)).setTextColor(p.text);
        ((TextView) findViewById(R.id.btnPrevWeek)).setTextColor(p.sub);
        ((TextView) findViewById(R.id.btnNextWeek)).setTextColor(p.sub);
        weekLabel.setTextColor(p.primary);
        int[] tabs = {R.id.tabTimetable, R.id.tabImport, R.id.tabTheme, R.id.tabSettings};
        for (int id : tabs) {
            TextView t = findViewById(id);
            t.setTextColor(p.sub);
        }
        timetable.setTransparentBackground(hasBg);
        timetable.refreshTheme();
        Theme.decorate(this, findViewById(android.R.id.content));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!Theme.signature(this).equals(themeSig)) {
            recreate();
            return;
        }
        // B：每次回到前台都重新定位到当前周。
        refreshToToday();
        scheduleMidnightRefresh();
        UpdateManager.onResume(this);
    }

    @Override
    protected void onPause() {
        midnightHandler.removeCallbacks(midnightRefresh);
        super.onPause();
    }

    private void refreshToToday() {
        refresh(true);
    }

    private void refresh() {
        refresh(false);
    }

    private void refresh(boolean forceToday) {
        semesterId = db.currentSemesterId();
        courses = db.courses(semesterId);
        String start = currentSemesterStart();
        Calendar now = Weeks.now();
        int totalWeeks = prefs.totalWeeks();
        int todayWeek = start.isEmpty() ? -1 : Weeks.weekOf(start, now);
        boolean beforeStart = !start.isEmpty() && Weeks.isBefore(start, now);
        int realWeek = beforeStart || todayWeek < 1 ? -1 : todayWeek;

        if (forceToday) {
            int target = 1;
            if (!beforeStart && todayWeek >= 1 && todayWeek <= totalWeeks) {
                target = todayWeek;
            }
            prefs.setCurrentWeek(target);
        }

        int currentWeek = Math.max(1, Math.min(totalWeeks, prefs.currentWeek()));
        prefs.setCurrentWeek(currentWeek);
        weekLabel.setText("第 " + currentWeek + " 周");
        timetable.setExams(db.exams(semesterId));
        timetable.setSemesterStart(start);
        timetable.setToday(Weeks.weekday(now), realWeek);
        timetable.setData(courses, prefs.sections(), currentWeek, totalWeeks,
                prefs.sectionTimes(), dayDates(start, currentWeek));
        WidgetUpdater.refresh(this);
    }

    /** 当前学期数据库里的开学日，并顺手修正 Prefs，避免两套数据不一致。 */
    private String currentSemesterStart() {
        Semester s = db.semester(semesterId);
        String start = s == null || s.start == null ? "" : s.start.trim();
        if (!start.equals(prefs.semesterStart())) {
            prefs.setSemesterStart(start);
        }
        return start;
    }

    /** 当前周 7 天的日期标签（M/d），没有开学日期则返回空数组。 */
    private String[] dayDates(String start, int week) {
        String[] out = new String[7];
        Calendar c = Weeks.parseLocalDate(start);
        if (c == null) {
            return out;
        }
        c.add(Calendar.DAY_OF_MONTH, (Math.max(1, week) - 1) * 7);
        for (int i = 0; i < 7; i++) {
            out[i] = (c.get(Calendar.MONTH) + 1) + "/" + c.get(Calendar.DAY_OF_MONTH);
            c.add(Calendar.DAY_OF_MONTH, 1);
        }
        return out;
    }

    private void scheduleMidnightRefresh() {
        midnightHandler.removeCallbacks(midnightRefresh);
        Calendar now = Weeks.now();
        Calendar next = (Calendar) now.clone();
        next.add(Calendar.DAY_OF_MONTH, 1);
        next.set(Calendar.HOUR_OF_DAY, 0);
        next.set(Calendar.MINUTE, 0);
        next.set(Calendar.SECOND, 5);
        next.set(Calendar.MILLISECOND, 0);
        long delay = next.getTimeInMillis() - now.getTimeInMillis();
        if (delay < 1000L) {
            delay = 1000L;
        }
        midnightHandler.postDelayed(midnightRefresh, delay);
    }

    private void changeWeek(int delta) {
        if (delta == 0) {
            refreshToToday();
            return;
        }
        int w = Math.max(1, Math.min(prefs.totalWeeks(), prefs.currentWeek() + delta));
        prefs.setCurrentWeek(w);
        refresh();
    }

    // ---------------- TimetableView.Listener ----------------

    @Override
    public void onCourseTap(Course course) {
        editCourse(course);
    }

    @Override
    public void onExamTap(Exam exam) {
        if (exam == null) {
            return;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("时间：").append(exam.time.isEmpty() ? "Null" : exam.time).append('\n');
        sb.append("地点：").append(exam.place.isEmpty() ? "Null" : exam.place);
        if (!exam.seat.isEmpty()) {
            sb.append("\n座位：").append(exam.seat);
        }
        if (!exam.note.isEmpty()) {
            sb.append("\n备注：").append(exam.note);
        }
        new AlertDialog.Builder(this)
                .setTitle(exam.course)
                .setMessage(sb.toString())
                .setNegativeButton("关闭", null)
                .setPositiveButton("管理考试", (d, w) ->
                        startActivity(new Intent(this, ExamActivity.class)))
                .show();
    }

    @Override
    public void onWeekChanged(int weekDelta) {
        int w = Math.max(1, Math.min(prefs.totalWeeks(), prefs.currentWeek() + weekDelta));
        prefs.setCurrentWeek(w);
        refresh();
    }

    @Override
    public void onSectionTimeTap(int sectionIndex) {
        editSectionTime(sectionIndex);
    }

    @Override
    public void onCourseEdited(Course course) {
        String conflict = db.conflict(course);
        if (conflict != null) {
            Toast.makeText(this, "这里已有：" + conflict, Toast.LENGTH_LONG).show();
            refresh();
            return;
        }
        db.saveCourse(course);
        refresh();
        Toast.makeText(this, "已调整：" + course.name, Toast.LENGTH_SHORT).show();
    }

    @Override
    public void onEmptyCellTap(int day, int section) {
        editCourse(null, day, section);
    }

    // ---------------- 侧边栏时间 ----------------

    private void editSectionTime(int index) {
        String[] times = prefs.sectionTimes();
        String current = index < times.length ? times[index] : "";
        EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(current);
        input.setHint("HH:mm-HH:mm，例如 08:30-09:15");
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        new AlertDialog.Builder(this)
                .setTitle("第 " + (index + 1) + " 节时间")
                .setMessage("未来城默认 08:30 开始、45 分钟一节；南望山请按实际改。")
                .setView(input)
                .setNegativeButton("取消", null)
                .setNeutralButton("恢复默认", (d, w) -> {
                    prefs.resetTimes();
                    refresh();
                })
                .setPositiveButton("保存", (d, w) -> {
                    String v = input.getText().toString().trim();
                    String err = validateTimeRange(v);
                    if (err != null) {
                        Toast.makeText(this, err, Toast.LENGTH_LONG).show();
                        return;
                    }
                    prefs.setSectionTime(index, v);
                    refresh();
                })
                .show();
    }

    /** 校验 "HH:mm-HH:mm"：格式、合法性、先后顺序、时长范围。 */
    static String validateTimeRange(String value) {
        if (value == null || value.trim().isEmpty()) {
            return "时间不能为空";
        }
        String v = value.trim().replace("—", "-").replace("~", "-").replace("－", "-");
        Matcher m = Pattern.compile("^([0-2]?\\d:[0-5]\\d)\\s*-\\s*([0-2]?\\d:[0-5]\\d)$").matcher(v);
        if (!m.matches()) {
            return "格式应为 HH:mm-HH:mm，例如 08:30-09:15";
        }
        int start = toMinutes(m.group(1));
        int end = toMinutes(m.group(2));
        if (start < 0 || end < 0) {
            return "时间不合法";
        }
        if (end <= start) {
            return "结束时间必须晚于开始时间";
        }
        int duration = end - start;
        if (duration < 20 || duration > 240) {
            return "单节时长应在 20~240 分钟之间（当前 " + duration + " 分钟）";
        }
        return null;
    }

    private static int toMinutes(String hhmm) {
        String[] parts = hhmm.split(":");
        try {
            int h = Integer.parseInt(parts[0]);
            int m = Integer.parseInt(parts[1]);
            if (h < 0 || h > 23 || m < 0 || m > 59) {
                return -1;
            }
            return h * 60 + m;
        } catch (Exception e) {
            return -1;
        }
    }

    // ---------------- 学期 ----------------

    private void showSemesters() {
        final AlertDialog[] holder = new AlertDialog[1];
        Theme.Palette p = Theme.p(this);
        long current = db.currentSemesterId();
        List<Semester> list = db.semesters();

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 18);
        box.setPadding(pad, Ui.dpInt(this, 4), pad, 0);

        Semester cur = null;
        for (Semester s : list) {
            if (s.id == current) {
                cur = s;
            }
        }

        box.addView(sectionTitle("当前学期"));
        if (cur != null) {
            box.addView(semesterCard(cur, true, holder, null, null));
        } else {
            box.addView(Ui.sub(this, "还没有学期"));
        }

        box.addView(sectionTitle("历史课表"));
        final List<Semester> history = new ArrayList<>();
        for (Semester s : list) {
            if (s.id != current) {
                history.add(s);
            }
        }
        final LinearLayout historyBox = new LinearLayout(this);
        historyBox.setOrientation(LinearLayout.VERTICAL);
        box.addView(historyBox);
        renderHistory(history, historyBox, holder);
        if (history.isEmpty()) {
            TextView none = Ui.sub(this, "还没有历史学期。新建学期后，旧课表会保留在这里。");
            none.setPadding(0, Ui.dpInt(this, 4), 0, Ui.dpInt(this, 6));
            box.addView(none);
        }

        TextView add = Ui.ghostButton(this, "+ 新建学期");
        add.setTextSize(13);
        LinearLayout.LayoutParams alp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        alp.topMargin = Ui.dpInt(this, 14);
        box.addView(add, alp);
        add.setOnClickListener(v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            newSemesterDialog();
        });

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);
        AlertDialog d = new AlertDialog.Builder(this)
                .setTitle("切换学期")
                .setView(scroll)
                .setNegativeButton("关闭", null)
                .create();
        holder[0] = d;
        d.show();
    }

    private View semesterCard(Semester s, boolean current, AlertDialog[] holder,
                              List<Semester> history, LinearLayout historyBox) {
        Theme.Palette p = Theme.p(this);
        LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.HORIZONTAL);
        card.setGravity(Gravity.CENTER_VERTICAL);
        card.setBackground(Ui.card(this));
        int pad = Ui.dpInt(this, 12);
        card.setPadding(pad, pad, Ui.dpInt(this, 8), pad);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = Ui.dpInt(this, 8);
        card.setLayoutParams(lp);

        if (!current && history != null && historyBox != null && history.size() > 1) {
            TextView handle = new TextView(this);
            handle.setText("≡");
            handle.setTextSize(24);
            handle.setTextColor(p.sub);
            handle.setGravity(Gravity.CENTER);
            int hw = Ui.dpInt(this, 36);
            handle.setLayoutParams(new LinearLayout.LayoutParams(
                    hw, ViewGroup.LayoutParams.WRAP_CONTENT));
            handle.setPadding(0, Ui.dpInt(this, 4), Ui.dpInt(this, 6), Ui.dpInt(this, 4));
            handle.setOnTouchListener((v, event) -> {
                int index = history.indexOf(s);
                return onHistoryDrag(card, index, event, history, historyBox, holder);
            });
            card.addView(handle);
        }

        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setLayoutParams(new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        LinearLayout titleRow = new LinearLayout(this);
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = new TextView(this);
        name.setText(s.name.isEmpty() ? "未命名学期" : s.name);
        name.setTextSize(15);
        name.setTypeface(null, android.graphics.Typeface.BOLD);
        name.setTextColor(p.text);
        name.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView menu = new TextView(this);
        menu.setText("⋯");
        menu.setTextSize(20);
        menu.setTextColor(p.sub);
        menu.setPadding(Ui.dpInt(this, 10), 0, 0, 0);
        menu.setOnClickListener(v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            editSemester(s);
        });
        titleRow.addView(name);
        titleRow.addView(menu);

        TextView meta = new TextView(this);
        meta.setText("开学 " + (s.start.isEmpty() ? "未设置" : s.start)
                + " · " + s.courseCount + " 门课" + (current ? " · 当前" : ""));
        meta.setTextSize(12);
        meta.setTextColor(current ? p.primary : p.sub);
        meta.setPadding(0, Ui.dpInt(this, 4), 0, 0);

        body.addView(titleRow);
        body.addView(meta);
        card.addView(body);
        card.setOnClickListener(v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            if (!current) {
                switchSemester(s);
            }
        });
        return card;
    }

    private void renderHistory(List<Semester> history, LinearLayout box, AlertDialog[] holder) {
        box.removeAllViews();
        for (Semester s : history) {
            box.addView(semesterCard(s, false, holder, history, box));
        }
    }

    /** 历史课表卡片拖拽：按住 ≡ 上下拖，松手落位并持久化。 */
    private boolean onHistoryDrag(View card, int index, MotionEvent event,
                                  List<Semester> history, LinearLayout box, AlertDialog[] holder) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                if (index < 0) {
                    return false;
                }
                dragCard = card;
                dragFrom = index;
                dragDownY = event.getRawY();
                dragTop = card.getTop();
                card.setAlpha(0.9f);
                card.setElevation(Ui.dp(this, 6));
                if (card.getParent() != null) {
                    card.getParent().requestDisallowInterceptTouchEvent(true);
                }
                return true;
            case MotionEvent.ACTION_MOVE:
                if (dragCard == card) {
                    card.setTranslationY(event.getRawY() - dragDownY);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                if (dragCard == card) {
                    float dy = event.getRawY() - dragDownY;
                    int step = card.getHeight() + Ui.dpInt(this, 8);
                    int target = step > 0 ? Math.round((dragTop + dy) / (float) step) : index;
                    target = Math.max(0, Math.min(history.size() - 1, target));
                    card.setTranslationY(0);
                    card.setAlpha(1f);
                    card.setElevation(0);
                    dragCard = null;
                    if (target != index) {
                        Semester moved = history.remove(index);
                        history.add(target, moved);
                        List<Long> ids = new ArrayList<>();
                        for (Semester x : history) {
                            ids.add(x.id);
                        }
                        db.reorderSemesters(ids);
                        renderHistory(history, box, holder);
                    }
                }
                return true;
        }
        return false;
    }

    private void switchSemester(Semester s) {
        prefs.setCurrentSemesterId(s.id);
        prefs.setSemesterStart(s.start);
        refreshToToday();
        Toast.makeText(this, "已切换到：" + s.name, Toast.LENGTH_SHORT).show();
    }

    private void newSemesterDialog() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 20);
        box.setPadding(pad, Ui.dpInt(this, 8), pad, 0);
        EditText name = Ui.input(this, "如 2026-2027 第2学期", defaultSemesterName());
        EditText start = Ui.input(this, "第一周周一 yyyy-MM-dd", Weeks.mondayOfThisWeek());
        box.addView(Ui.sub(this, "学期名称"));
        box.addView(name);
        box.addView(Ui.sub(this, "开学第一周周一"));
        box.addView(start);
        new AlertDialog.Builder(this)
                .setTitle("新建学期")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("创建", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "学期名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    long id = db.addSemester(n, start.getText().toString().trim());
                    if (id > 0) {
                        Semester s = db.semester(id);
                        if (s != null) {
                            switchSemester(s);
                        }
                    }
                })
                .show();
    }

    private void editSemester(Semester s) {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 20);
        box.setPadding(pad, Ui.dpInt(this, 8), pad, 0);
        EditText name = Ui.input(this, "学期名称", s.name);
        EditText start = Ui.input(this, "第一周周一 yyyy-MM-dd", s.start);
        box.addView(Ui.sub(this, "学期名称"));
        box.addView(name);
        box.addView(Ui.sub(this, "开学第一周周一"));
        box.addView(start);
        new AlertDialog.Builder(this)
                .setTitle("编辑学期")
                .setView(box)
                .setNegativeButton("取消", null)
                .setNeutralButton("删除", (d, w) -> confirmDeleteSemester(s))
                .setPositiveButton("保存", (d, w) -> {
                    String n = name.getText().toString().trim();
                    if (n.isEmpty()) {
                        Toast.makeText(this, "学期名称不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    s.name = n;
                    s.start = start.getText().toString().trim();
                    db.updateSemester(s);
                    if (s.id == db.currentSemesterId()) {
                        prefs.setSemesterStart(s.start);
                        refreshToToday();
                    } else {
                        refresh();
                    }
                })
                .show();
    }

    private void confirmDeleteSemester(final Semester s) {        if (db.semesters().size() <= 1) {
            Toast.makeText(this, "至少保留一个学期", Toast.LENGTH_SHORT).show();
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("删除学期？")
                .setMessage(s.name + " 的课程和考试都会被删除，且不可恢复。")
                .setNegativeButton("取消", null)
                .setPositiveButton("删除", (d, w) -> {
                    long current = db.currentSemesterId();
                    db.deleteSemester(s.id);
                    if (s.id == current) {
                        List<Semester> rest = db.semesters();
                        if (!rest.isEmpty()) {
                            Semester next = rest.get(0);
                            prefs.setCurrentSemesterId(next.id);
                            prefs.setSemesterStart(next.start);
                        }
                        refreshToToday();
                    } else {
                        refresh();
                    }
                    Toast.makeText(this, "已删除", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private String defaultSemesterName() {
        Calendar c = Weeks.now();
        int y = c.get(Calendar.YEAR);
        int m = c.get(Calendar.MONTH) + 1;
        String base = m >= 7 ? y + "-" + (y + 1) + " 第1学期"
                : (y - 1) + "-" + y + " 第2学期";
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Semester s : db.semesters()) {
            names.add(s.name);
        }
        if (!names.contains(base)) {
            return base;
        }
        String alt = base.contains("第1学期")
                ? base.replace("第1学期", "第2学期")
                : base.replace("第2学期", "第1学期");
        if (!names.contains(alt)) {
            return alt;
        }
        return base + " 副本";
    }

    // ---------------- 导入 ----------------

    private void showImport() {
        String[] items = {"导入课表", "导入考试"};
        new AlertDialog.Builder(this)
                .setTitle("导入")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        showCourseImport();
                    } else {
                        showExamImport();
                    }
                })
                .show();
    }

    private void showCourseImport() {
        String[] items = {"手动添加课程", "从教务网页导入", "从 PDF 导入", "粘贴文本导入"};
        new AlertDialog.Builder(this)
                .setTitle("导入课表")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        editCourse(null);
                    } else if (which == 1) {
                        startActivity(new Intent(this, WebImportActivity.class));
                    } else if (which == 2) {
                        startActivity(new Intent(this, PdfImportActivity.class));
                    } else {
                        startActivity(new Intent(this, TextImportActivity.class));
                    }
                })
                .show();
    }

    private void showExamImport() {
        String[] items = {"从教务网页导入", "粘贴文本导入", "手动添加考试", "查看考试列表"};
        new AlertDialog.Builder(this)
                .setTitle("导入考试")
                .setItems(items, (d, which) -> {
                    if (which == 0) {
                        startActivity(new Intent(this, ExamWebImportActivity.class));
                    } else if (which == 1) {
                        startActivity(ExamActivity.intentFor(this, true));
                    } else if (which == 2) {
                        startActivity(ExamActivity.intentFor(this, false));
                    } else {
                        startActivity(new Intent(this, ExamActivity.class));
                    }
                })
                .show();
    }

    // ---------------- 设置 ----------------

    private void showSettings() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 20);
        box.setPadding(pad, Ui.dpInt(this, 8), pad, 0);

        TextView campusLabel = Ui.sub(this, "校区");
        TextView campusValue = Ui.ghostButton(this, "当前：" + prefs.campusName());

        TextView weeksLabel = Ui.sub(this, "总周数");
        EditText weeks = Ui.input(this, "20", String.valueOf(prefs.totalWeeks()));
        weeks.setInputType(InputType.TYPE_CLASS_NUMBER);

        TextView sectionsLabel = Ui.sub(this, "每天小节数");
        EditText sections = Ui.input(this, "10", String.valueOf(prefs.sections()));
        sections.setInputType(InputType.TYPE_CLASS_NUMBER);

        campusValue.setOnClickListener(v -> {
            prefs.setCampus(Prefs.CAMPUS_FUTURE.equals(prefs.campus())
                    ? Prefs.CAMPUS_NANWANG : Prefs.CAMPUS_FUTURE);
            campusValue.setText("当前：" + prefs.campusName());
            sections.setText(String.valueOf(prefs.sections()));
            refresh();
        });

        TextView startLabel = Ui.sub(this, "开学第一周周一");
        EditText startDate = Ui.input(this, "2026-09-28", prefs.semesterStart());

        TextView urlLabel = Ui.sub(this, "教务系统地址");
        EditText url = Ui.input(this, "https://i.cug.edu.cn", prefs.schoolUrl());

        box.addView(campusLabel);
        box.addView(campusValue);
        box.addView(weeksLabel);
        box.addView(weeks);
        box.addView(sectionsLabel);
        box.addView(sections);
        box.addView(startLabel);
        box.addView(startDate);
        box.addView(urlLabel);
        box.addView(url);

        box.addView(sectionTitle("桌面小组件"));
        TextView pin = Ui.ghostButton(this, "添加「今日课程」小组件");
        pin.setTextSize(13);
        pin.setOnClickListener(v -> requestWidgetPin());
        box.addView(pin);
        TextView pinNote = Ui.sub(this, "4×2 起，可拉伸到 4×4\n显示今天课程和最近 30 天内的最近一场考试。");
        pinNote.setPadding(0, Ui.dpInt(this, 6), 0, 0);
        box.addView(pinNote);

        box.addView(sectionTitle("更新"));
        TextView version = Ui.sub(this, "当前版本 " + UpdateManager.currentVersion(this));
        box.addView(version);
        TextView checkUpdate = Ui.ghostButton(this, "检查更新");
        checkUpdate.setTextSize(13);
        box.addView(checkUpdate);

        box.addView(sectionTitle("反馈"));
        box.addView(feedbackRow("邮箱", "aila20181123@163.com", v -> openMail()));
        box.addView(feedbackRow("GitHub", "github.com/39-Null", v -> openUrl("https://github.com/39-Null")));

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        final AlertDialog[] holder = new AlertDialog[1];
        AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle("设置")
                .setView(scroll)
                .setNeutralButton("清空数据", (d, w) -> confirmClear())
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    try {
                        prefs.setTotalWeeks(Integer.parseInt(weeks.getText().toString().trim()));
                    } catch (Exception ignored) {
                    }
                    try {
                        prefs.setSections(Integer.parseInt(sections.getText().toString().trim()));
                    } catch (Exception ignored) {
                    }
                    prefs.setSemesterStart(startDate.getText().toString().trim());
                    Semester sem = db.semester(semesterId);
                    if (sem != null) {
                        sem.start = startDate.getText().toString().trim();
                        db.updateSemester(sem);
                    }
                    prefs.setSchoolUrl(url.getText().toString().trim());
                    refreshToToday();
                })
                .create();
        holder[0] = dialog;
        checkUpdate.setOnClickListener(v -> {
            if (holder[0] != null) {
                holder[0].dismiss();
            }
            UpdateManager.manualCheck(this);
        });
        dialog.show();
    }

    private View feedbackRow(String label, String value, View.OnClickListener click) {
        Theme.Palette p = Theme.p(this);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, Ui.dpInt(this, 8), 0, Ui.dpInt(this, 8));
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(p.sub);
        l.setLayoutParams(new LinearLayout.LayoutParams(
                Ui.dpInt(this, 72), ViewGroup.LayoutParams.WRAP_CONTENT));
        TextView v = new TextView(this);
        v.setText(value);
        v.setTextSize(13);
        v.setTextColor(p.primary);
        v.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(l);
        row.addView(v);
        row.setOnClickListener(click);
        return row;
    }

    private void requestWidgetPin() {
        AppWidgetManager manager = AppWidgetManager.getInstance(this);
        if (manager == null || Build.VERSION.SDK_INT < 26
                || !manager.isRequestPinAppWidgetSupported()) {
            Toast.makeText(this,
                    "桌面不支持一键添加：请长按桌面 → 窗口小工具 → 我的课表",
                    Toast.LENGTH_LONG).show();
            return;
        }
        manager.requestPinAppWidget(TimetableWidgetProvider.component(this), null, null);
    }

    private void openMail() {        try {
            Intent i = new Intent(Intent.ACTION_SENDTO,
                    android.net.Uri.parse("mailto:aila20181123@163.com"));
            i.putExtra(Intent.EXTRA_SUBJECT, "我的课表反馈");
            startActivity(i);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的邮件应用", Toast.LENGTH_SHORT).show();
        }
    }

    private void openUrl(String url) {
        try {
            startActivity(new Intent(Intent.ACTION_VIEW, android.net.Uri.parse(url)));
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    private void confirmClear() {
        new AlertDialog.Builder(this)
                .setTitle("清空当前学期？")
                .setMessage("只删除当前学期的课程与考试，历史学期不受影响。")
                .setNegativeButton("取消", null)
                .setPositiveButton("清空", (d, w) -> {
                    db.clearCurrentSemester();
                    refresh();
                    Toast.makeText(this, "已清空", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    // ---------------- 课程编辑 ----------------

    // ---------------- 课程编辑 ----------------

    private void editCourse(Course existing) {
        editCourse(existing, 0, 0);
    }

    private void editCourse(Course existing, int presetDay, int presetStart) {
        Course c = existing == null ? new Course() : existing;
        if (existing == null) {
            c.semesterId = semesterId;
            c.day = presetDay > 0 ? presetDay : 1;
            c.start = Math.max(1, presetStart > 0 ? presetStart : 1);
            c.end = Math.min(prefs.sections(), c.start + 1);
            // 新增课程默认只排本周：周次填当前周，例如第 9 周 → "9"。
            c.weeks = String.valueOf(Math.max(1,
                    Math.min(prefs.totalWeeks(), prefs.currentWeek())));
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 18);
        box.setPadding(pad, Ui.dpInt(this, 6), pad, 0);

        EditText name = Ui.input(this, "Null", c.name);
        EditText room = Ui.input(this, "Null", c.room);
        EditText teacher = Ui.input(this, "Null", c.teacher);

        box.addView(sectionTitle("简略信息"));
        box.addView(labelledRow("课程名", name));
        box.addView(labelledRow("教室", room));
        box.addView(labelledRow("老师", teacher));

        android.widget.Switch labSwitch = new android.widget.Switch(this);
        labSwitch.setText("实验（课程的下属）");
        labSwitch.setTextSize(13);
        labSwitch.setTextColor(Theme.p(this).text);
        labSwitch.setChecked(c.type == 1);
        box.addView(labSwitch);
        EditText parent = Ui.input(this, "所属课程名（可选）", c.parent);
        box.addView(labelledRow("所属课程", parent));

        EditText code = Ui.input(this, "Null", c.code);
        EditText clazz = Ui.input(this, "Null", c.clazz);
        EditText credit = Ui.input(this, "Null", c.credit);
        EditText campus = Ui.input(this, "Null", c.campus);
        EditText examType = Ui.input(this, "Null", c.examType);
        EditText hours = Ui.input(this, "Null", c.hours);
        EditText note = Ui.input(this, "Null", c.note);

        LinearLayout detailBox = new LinearLayout(this);
        detailBox.setOrientation(LinearLayout.VERTICAL);
        detailBox.addView(labelledRow("课程号", code));
        detailBox.addView(labelledRow("班级", clazz));
        detailBox.addView(labelledRow("学分", credit));
        detailBox.addView(labelledRow("校区", campus));
        detailBox.addView(labelledRow("考核", examType));
        detailBox.addView(labelledRow("学时", hours));
        detailBox.addView(labelledRow("备注", note));

        final boolean[] detailOpen = {existing != null};
        detailBox.setVisibility(detailOpen[0] ? View.VISIBLE : View.GONE);
        final TextView detailHeader = sectionTitle(detailOpen[0]
                ? "详细信息（点击收起）" : "详细信息（点击展开）");
        detailHeader.setOnClickListener(v -> {
            detailOpen[0] = !detailOpen[0];
            detailBox.setVisibility(detailOpen[0] ? View.VISIBLE : View.GONE);
            detailHeader.setText(detailOpen[0]
                    ? "详细信息（点击收起）" : "详细信息（点击展开）");
        });
        box.addView(detailHeader);
        box.addView(detailBox);

        EditText weeks = Ui.input(this, "如 1-5,7-11 或 1-16|1", c.weeks);
        EditText day = Ui.input(this, "1-7", String.valueOf(c.day));
        day.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText start = Ui.input(this, "开始节次", String.valueOf(c.start));
        start.setInputType(InputType.TYPE_CLASS_NUMBER);
        EditText end = Ui.input(this, "结束节次", String.valueOf(c.end));
        end.setInputType(InputType.TYPE_CLASS_NUMBER);

        box.addView(labelledRow("周次", weeks));
        LinearLayout dayRow = new LinearLayout(this);
        dayRow.setOrientation(LinearLayout.HORIZONTAL);
        dayRow.addView(labelledRow("星期", day, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dayRow.addView(labelledRow("开始", start, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dayRow.addView(labelledRow("结束", end, 0), new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        box.addView(dayRow);

        ScrollView scroll = new ScrollView(this);
        scroll.addView(box);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "新增课程 / 实验"
                        : (existing.type == 1 ? "实验详情" : "课程详情"))
                .setView(scroll)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    c.name = value(name);
                    c.room = value(room);
                    c.teacher = value(teacher);
                    c.code = value(code);
                    c.clazz = value(clazz);
                    c.credit = value(credit);
                    c.campus = value(campus);
                    c.examType = value(examType);
                    c.hours = value(hours);
                    c.note = value(note);
                    c.type = labSwitch.isChecked() ? 1 : 0;
                    c.parent = c.type == 1 ? value(parent) : "";
                    c.semesterId = semesterId;
                    c.weeks = weeks.getText().toString().trim();
                    if (c.weeks.isEmpty()) {
                        c.weeks = "1-" + prefs.totalWeeks();
                    }
                    try {
                        c.day = Math.max(1, Math.min(7, Integer.parseInt(day.getText().toString().trim())));
                    } catch (Exception ignored) {
                    }
                    try {
                        c.start = Math.max(1, Integer.parseInt(start.getText().toString().trim()));
                    } catch (Exception ignored) {
                    }
                    try {
                        c.end = Math.max(c.start, Integer.parseInt(end.getText().toString().trim()));
                    } catch (Exception ignored) {
                    }
                    if (c.name.isEmpty()) {
                        Toast.makeText(this, "课程名不能为空", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    String conflict = db.conflict(c);
                    if (conflict != null) {
                        Toast.makeText(this, "该时间段已有：" + conflict, Toast.LENGTH_LONG).show();
                        return;
                    }
                    db.saveCourse(c);
                    refresh();
                });
        if (existing != null) {
            b.setNeutralButton("删除", (d, w) -> {
                db.deleteCourse(existing.id);
                refresh();
            });
        }
        b.show();
    }

    private static String value(EditText e) {
        String s = e.getText().toString().trim();
        return "Null".equalsIgnoreCase(s) ? "" : s;
    }

    private TextView sectionTitle(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(Theme.p(this).primary);
        t.setPadding(0, Ui.dpInt(this, 12), 0, Ui.dpInt(this, 2));
        return t;
    }

    private View labelledRow(String label, EditText input) {
        return labelledRow(label, input, 72);
    }

    private View labelledRow(String label, EditText input, int labelWidthDp) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(13);
        l.setTextColor(Theme.p(this).sub);
        if (labelWidthDp > 0) {
            l.setLayoutParams(new LinearLayout.LayoutParams(Ui.dpInt(this, labelWidthDp), ViewGroup.LayoutParams.WRAP_CONTENT));
        } else {
            l.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            l.setPadding(0, 0, Ui.dpInt(this, 4), 0);
        }
        input.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        row.addView(l);
        row.addView(input);
        return row;
    }
}