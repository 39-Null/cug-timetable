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
import io.local.timetable.model.Exam;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;
import io.local.timetable.widget.WidgetUpdater;

/** 考试导入预览：确认时间/地点/座位后再写入。 */
public class ExamPreviewActivity extends Activity {

    private static final String EXTRA_EXAMS = "exams";
    private static final String EXTRA_SOURCE = "source";

    private final List<Exam> exams = new ArrayList<>();
    private String source = "";

    public static void start(Context ctx, List<Exam> list, String source) {
        Intent intent = new Intent(ctx, ExamPreviewActivity.class);
        intent.putExtra(EXTRA_EXAMS, new ArrayList<>(list));
        intent.putExtra(EXTRA_SOURCE, source);
        ctx.startActivity(intent);
    }

    @Override
    @SuppressWarnings("unchecked")
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        Object extra = getIntent().getSerializableExtra(EXTRA_EXAMS);
        if (extra instanceof ArrayList) {
            exams.addAll((ArrayList<Exam>) extra);
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

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 12), Ui.dpInt(this, 16), Ui.dpInt(this, 8));
        bar.setBackgroundColor(p.card);
        TextView title = new TextView(this);
        title.setText("考试导入预览");
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
        summary.setText("共解析到 " + exams.size() + " 场考试"
                + (source == null || source.isEmpty() ? "" : "（来源：" + source + "）"));
        summary.setTextSize(12);
        summary.setTextColor(p.sub);
        summary.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 8), Ui.dpInt(this, 16), Ui.dpInt(this, 8));
        root.addView(summary);

        ScrollView scroll = new ScrollView(this);
        LinearLayout list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(Ui.dpInt(this, 12), 0, Ui.dpInt(this, 12), Ui.dpInt(this, 12));

        if (exams.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText("没有解析到考试。\n确认页面是「考试信息查询」且已点过「查询」。");
            empty.setTextSize(14);
            empty.setTextColor(p.sub);
            empty.setPadding(Ui.dpInt(this, 8), Ui.dpInt(this, 24), 0, 0);
            list.addView(empty);
        }

        for (Exam e : exams) {
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
            name.setText(e.course);
            name.setTextSize(15);
            name.setTypeface(null, android.graphics.Typeface.BOLD);
            name.setTextColor(p.text);

            TextView time = new TextView(this);
            time.setText("时间：" + (e.time.isEmpty() ? "Null" : e.time));
            time.setTextSize(12);
            time.setTextColor(p.sub);
            time.setPadding(0, Ui.dpInt(this, 4), 0, 0);

            TextView place = new TextView(this);
            place.setText("地点：" + (e.place.isEmpty() ? "Null" : e.place)
                    + (e.seat.isEmpty() ? "" : " · 座位 " + e.seat));
            place.setTextSize(12);
            place.setTextColor(p.sub);
            place.setPadding(0, Ui.dpInt(this, 2), 0, 0);

            card.addView(name);
            card.addView(time);
            card.addView(place);
            list.addView(card);
        }
        scroll.addView(list);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

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
        if (exams.isEmpty()) {
            Toast.makeText(this, "没有可保存的考试", Toast.LENGTH_SHORT).show();
            return;
        }
        if (replace) {
            new AlertDialog.Builder(this)
                    .setTitle("覆盖当前考试？")
                    .setMessage("会先删除现有考试记录，再写入这 " + exams.size() + " 场。")
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
        for (Exam e : exams) {
            e.semesterId = sem;
        }
        if (replace) {
            db.replaceExams(exams, sem);
        } else {
            for (Exam e : exams) {
                e.id = 0;
                e.semesterId = sem;
                db.saveExam(e);
            }
        }
        Toast.makeText(this, (replace ? "已覆盖保存 " : "已追加 ") + exams.size() + " 场",
                Toast.LENGTH_SHORT).show();
        WidgetUpdater.refresh(this);
        finish();
    }
}
