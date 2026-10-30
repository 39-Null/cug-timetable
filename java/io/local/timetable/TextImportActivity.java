package io.local.timetable;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.List;

import io.local.timetable.model.Course;
import io.local.timetable.parse.TextCourseParser;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;

/** 文本导入：既用于"粘贴文本"，也用于 PDF 解析失败后的兜底编辑。 */
public class TextImportActivity extends Activity {

    private static final String EXTRA_TEXT = "text";
    private static final String EXTRA_SOURCE = "source";

    private EditText input;
    private String source = "文本";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        String preset = getIntent().getStringExtra(EXTRA_TEXT);
        String s = getIntent().getStringExtra(EXTRA_SOURCE);
        if (s != null && !s.isEmpty()) {
            source = s;
        }
        setContentView(build(preset));
        Theme.decorate(this, findViewById(android.R.id.content));
    }

    private LinearLayout build(String preset) {
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
        title.setText("文本导入（" + source + "）");
        title.setTextSize(16);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(p.text);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        bar.addView(title);

        TextView parse = Ui.button(this, "解析");
        parse.setOnClickListener(v -> doParse());
        bar.addView(parse);
        root.addView(bar);

        TextView hint = new TextView(this);
        hint.setText("每行一门课，尽量包含：课程名 老师 周几 第几节 周次 地点\n例如：高等数学 张三 周一 1-2节 1-16周 教1-101");
        hint.setTextSize(11);
        hint.setTextColor(p.sub);
        hint.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 8), Ui.dpInt(this, 16), Ui.dpInt(this, 6));
        root.addView(hint);

        input = new EditText(this);
        input.setGravity(Gravity.TOP | Gravity.START);
        input.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        input.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        input.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12));
        input.setBackgroundColor(p.card);
        input.setTextColor(p.text);
        input.setHintTextColor(p.faint);
        input.setText(preset == null ? "" : preset);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1);
        lp.setMargins(Ui.dpInt(this, 12), 0, Ui.dpInt(this, 12), Ui.dpInt(this, 12));
        root.addView(input, lp);
        return root;
    }

    private void doParse() {
        String text = input.getText().toString();
        List<Course> parsed = TextCourseParser.parse(text);
        if (parsed.isEmpty()) {
            Toast.makeText(this, "没有解析到课程，检查是否包含「周几」「第几节」", Toast.LENGTH_LONG).show();
            return;
        }
        ImportPreviewActivity.start(this, parsed, source);
    }
}
