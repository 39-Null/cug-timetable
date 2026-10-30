package io.local.timetable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import io.local.timetable.db.Db;
import io.local.timetable.model.Exam;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;
import io.local.timetable.util.Weeks;
import io.local.timetable.widget.WidgetUpdater;

/** 考试安排：列表、手动添加、粘贴导入、教务网页导入入口。 */
public class ExamActivity extends Activity {

    private static final String EXTRA_PASTE = "paste";

    private Db db;
    private LinearLayout listView;
    private boolean openPaste = false;

    public static Intent intentFor(Context ctx, boolean paste) {
        Intent intent = new Intent(ctx, ExamActivity.class);
        intent.putExtra(EXTRA_PASTE, paste);
        return intent;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        db = Db.get(this);
        openPaste = getIntent().getBooleanExtra(EXTRA_PASTE, false);
        setContentView(build());
        Theme.decorate(this, findViewById(android.R.id.content));
        reload();
        if (openPaste) {
            openPaste = false;
            pasteImport();
        }
    }

    private LinearLayout build() {
        Theme.Palette p = Theme.p(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12));
        bar.setBackgroundColor(p.card);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(p.sub);
        back.setPadding(0, 0, Ui.dpInt(this, 12), 0);
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("考试安排");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(p.text);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView add = Ui.button(this, "+ 添加");
        add.setTextSize(13);
        add.setOnClickListener(v -> edit(null));

        bar.addView(back);
        bar.addView(title);
        bar.addView(add);
        root.addView(bar);

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 8), Ui.dpInt(this, 12), Ui.dpInt(this, 8));
        tools.setBackgroundColor(p.card);

        TextView web = Ui.ghostButton(this, "教务导入");
        web.setTextSize(12);
        TextView paste = Ui.ghostButton(this, "粘贴导入");
        paste.setTextSize(12);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp.rightMargin = Ui.dpInt(this, 8);
        tools.addView(web, lp);
        tools.addView(paste, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        web.setOnClickListener(v -> startActivity(new Intent(this, ExamWebImportActivity.class)));
        paste.setOnClickListener(v -> pasteImport());
        root.addView(tools);

        ScrollView scroll = new ScrollView(this);
        listView = new LinearLayout(this);
        listView.setOrientation(LinearLayout.VERTICAL);
        listView.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12));
        scroll.addView(listView);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void reload() {
        Theme.Palette p = Theme.p(this);
        listView.removeAllViews();
        WidgetUpdater.refresh(this);
        List<Exam> exams = db.exams();
        if (exams.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("还没有考试安排\n可以「教务导入」「粘贴导入」或点右上角「+ 添加」");
            empty.setTextSize(14);
            empty.setTextColor(p.sub);
            empty.setPadding(Ui.dpInt(this, 8), Ui.dpInt(this, 24), 0, 0);
            listView.addView(empty);
            return;
        }
        if (new Prefs(this).semesterStart().isEmpty()) {
            TextView tip = new TextView(this);
            tip.setText("还没设置开学日期，考试暂不会显示在课表网格里（设置 → 开学第一周周一）。");
            tip.setTextSize(12);
            tip.setTextColor(p.sub);
            tip.setPadding(0, 0, 0, Ui.dpInt(this, 10));
            listView.addView(tip);
        }
        for (Exam e : exams) {
            LinearLayout card = new LinearLayout(this);            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.card(this));
            int pad = Ui.dpInt(this, 12);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dpInt(this, 8);
            card.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(e.course);
            name.setTextSize(15);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(p.text);

            TextView meta = new TextView(this);
            StringBuilder sb = new StringBuilder();
            if (!e.time.isEmpty()) {
                sb.append(e.time);
            }
            if (!e.place.isEmpty()) {
                sb.append(sb.length() > 0 ? " · " : "").append(e.place);
            }
            if (!e.seat.isEmpty()) {
                sb.append(sb.length() > 0 ? " · " : "").append("座位 ").append(e.seat);
            }
            meta.setText(sb.toString());
            meta.setTextSize(12);
            meta.setTextColor(p.sub);
            meta.setPadding(0, Ui.dpInt(this, 4), 0, 0);

            card.addView(name);
            card.addView(meta);
            card.setOnClickListener(v -> edit(e));
            listView.addView(card);
        }
    }

    private void edit(Exam existing) {
        Theme.Palette p = Theme.p(this);
        Exam e = existing == null ? new Exam() : existing;
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 20);
        box.setPadding(pad, Ui.dpInt(this, 8), pad, 0);

        EditText course = Ui.input(this, "课程名称", e.course);
        EditText time = Ui.input(this, "时间，如 2026-11-15 09:00-11:00", e.time);
        EditText place = Ui.input(this, "地点", e.place);
        EditText seat = Ui.input(this, "座位号（可选）", e.seat);
        EditText note = Ui.input(this, "备注（可选）", e.note);

        box.addView(label("课程", p));
        box.addView(course);
        box.addView(label("时间", p));
        box.addView(time);
        box.addView(label("地点", p));
        box.addView(place);
        box.addView(label("座位", p));
        box.addView(seat);
        box.addView(label("备注", p));
        box.addView(note);

        AlertDialog.Builder b = new AlertDialog.Builder(this)
                .setTitle(existing == null ? "添加考试" : "编辑考试")
                .setView(box)
                .setNegativeButton("取消", null)
                .setPositiveButton("保存", (d, w) -> {
                    e.course = course.getText().toString().trim();
                    e.time = HtmlTime.normalize(time.getText().toString().trim());
                    e.place = place.getText().toString().trim();
                    e.seat = seat.getText().toString().trim();
                    e.note = note.getText().toString().trim();
                    if (!e.course.isEmpty()) {
                        db.saveExam(e);
                        reload();
                    }
                });
        if (existing != null) {
            b.setNeutralButton("删除", (d, w) -> {
                db.deleteExam(existing.id);
                reload();
            });
        }
        b.show();
    }

    private TextView label(String text, Theme.Palette p) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(p.sub);
        t.setPadding(0, Ui.dpInt(this, 8), 0, 0);
        return t;
    }

    private void pasteImport() {
        EditText input = new EditText(this);
        input.setHint("每行一场考试，例如：\n高等数学 2026-11-15 09:00-11:00 教1-101\n大学物理 2026-11-20 14:00 教2-203");
        input.setMinLines(6);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setTextColor(Theme.p(this).text);
        input.setHintTextColor(Theme.p(this).faint);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        new AlertDialog.Builder(this)
                .setTitle("粘贴导入考试")
                .setView(input)
                .setNegativeButton("取消", null)
                .setPositiveButton("导入", (d, w) -> {
                    List<Exam> parsed = parseExams(input.getText().toString());
                    if (parsed.isEmpty()) {
                        Toast.makeText(this, "没有识别到考试", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    for (Exam e : parsed) {
                        db.saveExam(e);
                    }
                    reload();
                    Toast.makeText(this, "已导入 " + parsed.size() + " 场", Toast.LENGTH_SHORT).show();
                })
                .show();
    }

    private static final Pattern DATE = Pattern.compile("(20\\d{2})[-/年.](\\d{1,2})[-/月.](\\d{1,2})");
    private static final Pattern TIME = Pattern.compile("(\\d{1,2})[:：](\\d{2})");

    static List<Exam> parseExams(String text) {
        List<Exam> out = new ArrayList<>();
        if (text == null) {
            return out;
        }
        for (String line : text.split("\\n")) {
            String s = line.trim();
            if (s.isEmpty()) {
                continue;
            }
            Matcher dm = DATE.matcher(s);
            if (!dm.find()) {
                continue;
            }
            int year = Integer.parseInt(dm.group(1));
            int month = Integer.parseInt(dm.group(2));
            int dayOfMonth = Integer.parseInt(dm.group(3));
            if (month < 1 || month > 12 || dayOfMonth < 1 || dayOfMonth > 31) {
                continue;
            }
            String date = String.format("%04d-%02d-%02d", year, month, dayOfMonth);
            if (Weeks.parseLocalDate(date) == null) {
                continue;
            }
            String after = s.substring(dm.end()).trim();
            Matcher tm = TIME.matcher(after);
            String time = date;
            if (tm.find()) {
                int hour = Integer.parseInt(tm.group(1));
                int minute = Integer.parseInt(tm.group(2));
                if (hour >= 0 && hour <= 23 && minute >= 0 && minute <= 59) {
                    time = date + " " + String.format("%02d:%s", hour, tm.group(2));
                    after = after.substring(Math.min(after.length(), tm.end())).trim();
                }
            }
            String before = s.substring(0, dm.start()).trim();
            Exam e = new Exam();
            e.course = before.isEmpty() ? "考试" : before;
            e.time = time;
            Matcher pm = Pattern.compile("[\\u4e00-\\u9fa5A-Za-z0-9\\-]{0,12}(?:楼|教|室|馆|中心)[\\u4e00-\\u9fa5A-Za-z0-9\\-]{0,12}").matcher(after);
            if (pm.find()) {
                e.place = pm.group();
            }
            out.add(e);
        }
        return out;
    }

    /** 只在这里做一次轻量归一，避免 Exam 模型依赖解析器。 */
    private static final class HtmlTime {
        static String normalize(String s) {
            return io.local.timetable.parse.HtmlExamParser.normalizeTime(s).isEmpty()
                    ? s : io.local.timetable.parse.HtmlExamParser.normalizeTime(s);
        }
    }
}
