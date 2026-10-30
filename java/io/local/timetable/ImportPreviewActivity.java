package io.local.timetable;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

import io.local.timetable.db.Db;
import io.local.timetable.model.Course;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;
import io.local.timetable.widget.WidgetUpdater;

/** 导入预览：先看清楚解析结果，再决定覆盖/追加。 */
public class ImportPreviewActivity extends Activity {

    private static final String EXTRA_COURSES = "courses";
    private static final String EXTRA_SOURCE = "source";

    private final List<Course> courses = new ArrayList<>();
    private String source = "";

    public static void start(Context ctx, List<Course> list, String source) {
        Intent intent = new Intent(ctx, ImportPreviewActivity.class);
        intent.putExtra(EXTRA_COURSES, new ArrayList<>(list));
        intent.putExtra(EXTRA_SOURCE, source);
        ctx.startActivity(intent);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        Object extra = getIntent().getSerializableExtra(EXTRA_COURSES);
        if (extra instanceof ArrayList) {
            courses.addAll((ArrayList<Course>) extra);
        }
        source = getIntent().getStringExtra(EXTRA_SOURCE);
        setContentView(build());
        Theme.decorate(this, findViewById(android.R.id.content));
    }

    private LinearLayout build() {
        Theme.Palette p = Theme.p(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        // 顶部
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 12), Ui.dpInt(this, 16), Ui.dpInt(this, 8));
        bar.setBackgroundColor(p.card);
        TextView title = new TextView(this);
        title.setText("导入预览");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(p.text);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        TextView close = new TextView(this);
        close.setText("取消");
        close.setTextSize(14);
        close.setTextColor(p.sub);
        close.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 6), 0, Ui.dpInt(this, 6));
        close.setOnClickListener(v -> finish());
        bar.addView(title);
        bar.addView(close);
        root.addView(bar);

        TextView summary = new TextView(this);
        summary.setText("共解析到 " + courses.size() + " 条课程"
                + (source == null || source.isEmpty() ? "" : "（来源：" + source + "）"));
        summary.setTextSize(12);
        summary.setTextColor(p.sub);
        summary.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 8), Ui.dpInt(this, 16), Ui.dpInt(this, 8));
        root.addView(summary);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dpInt(this, 12), 0, Ui.dpInt(this, 12), Ui.dpInt(this, 12));

        if (courses.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有解析到课程。\n可以返回上一页调整文本/页面，或改用手动添加。");
            empty.setTextSize(14);
            empty.setTextColor(p.sub);
            empty.setPadding(Ui.dpInt(this, 8), Ui.dpInt(this, 24), Ui.dpInt(this, 8), 0);
            list.addView(empty);
        }

        for (Course c : courses) {
            LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setBackground(Ui.card(this));
            int pad = Ui.dpInt(this, 12);
            card.setPadding(pad, pad, pad, pad);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.bottomMargin = Ui.dpInt(this, 8);
            card.setLayoutParams(lp);

            TextView name = new TextView(this);
            name.setText(c.name);
            name.setTextSize(15);
            name.setTextColor(p.text);
            name.setTypeface(null, android.graphics.Typeface.BOLD);

            TextView meta = new TextView(this);
            String[] days = {"", "周一", "周二", "周三", "周四", "周五", "周六", "周日"};
            String d = (c.day >= 1 && c.day <= 7) ? days[c.day] : "?";
            meta.setText(d + " 第" + c.start + "-" + c.end + "节 · " + c.weeks + "周"
                    + (c.campus.isEmpty() ? "" : " · " + c.campus));
            meta.setTextSize(12);
            meta.setTextColor(p.sub);
            meta.setPadding(0, Ui.dpInt(this, 4), 0, 0);

            TextView place = new TextView(this);
            place.setText("教室：" + (c.room.isEmpty() ? "Null" : c.room));
            place.setTextSize(12);
            place.setTextColor(p.sub);
            place.setPadding(0, Ui.dpInt(this, 2), 0, 0);

            TextView who = new TextView(this);
            who.setText("教师：" + (c.teacher.isEmpty() ? "Null" : c.teacher));
            who.setTextSize(12);
            who.setTextColor(p.sub);
            who.setPadding(0, Ui.dpInt(this, 2), 0, 0);

            card.addView(name);
            card.addView(meta);
            card.addView(place);
            card.addView(who);
            list.addView(card);
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        // 底部按钮
        LinearLayout bottom = new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 10), Ui.dpInt(this, 16), Ui.dpInt(this, 16));
        bottom.setBackgroundColor(p.card);

        TextView append = Ui.ghostButton(this, "追加");
        TextView replace = Ui.button(this, "覆盖保存");
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        blp.rightMargin = Ui.dpInt(this, 10);
        bottom.addView(append, blp);
        bottom.addView(replace, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        append.setOnClickListener(v -> save(false));
        replace.setOnClickListener(v -> save(true));
        root.addView(bottom);
        return root;
    }

    private void save(boolean replace) {
        if (courses.isEmpty()) {
            Toast.makeText(this, "没有可保存的课程", Toast.LENGTH_SHORT).show();
            return;
        }
        if (replace) {
            new AlertDialog.Builder(this)
                    .setTitle("覆盖当前课表？")
                    .setMessage("会先删除现有课程，再写入这 " + courses.size() + " 条。")
                    .setNegativeButton("取消", null)
                    .setPositiveButton("覆盖", (d, w) -> doSave(true))
                    .show();
        } else {
            doSave(false);
        }
    }

    private void doSave(boolean replace) {
        Db db = Db.get(this);
        long sem = db.currentSemesterId();
        for (Course c : courses) {
            c.semesterId = sem;
        }
        int saved = courses.size();
        if (replace) {
            String conflict = db.conflictIn(courses);
            if (conflict != null) {
                new AlertDialog.Builder(this)
                        .setTitle("课程时间有重叠")
                        .setMessage(conflict + "\n\n同一格子只能绑定一门课；请先在预览里核对。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            db.replaceCourses(courses, sem);
        } else {
            String conflict = db.conflictIn(courses);
            if (conflict != null) {
                new AlertDialog.Builder(this)
                        .setTitle("课程时间有重复")
                        .setMessage(conflict + "\n\n同一格子只能绑定一门课；请先在预览里核对。")
                        .setPositiveButton("知道了", null)
                        .show();
                return;
            }
            int added = db.appendCourses(courses, sem);
            saved = added;
            if (added < courses.size()) {
                Toast.makeText(this, "已跳过 " + (courses.size() - added) + " 条重复课程",
                        Toast.LENGTH_SHORT).show();
            }
        }
        Toast.makeText(this, (replace ? "已覆盖保存 " : "已追加 ") + saved + " 条", Toast.LENGTH_SHORT).show();
        WidgetUpdater.refresh(this);
        finish();
    }
}
