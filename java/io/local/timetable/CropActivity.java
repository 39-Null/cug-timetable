package io.local.timetable;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import io.local.timetable.ui.CropView;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;

/** 背景图手动裁剪：拖动/缩放选择可见区域，保存为屏幕比例的背景图。 */
public class CropActivity extends Activity {

    public static final String EXTRA_SRC = "src";

    private CropView cropView;
    private String sourcePath;
    private boolean temp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        sourcePath = getIntent().getStringExtra(EXTRA_SRC);
        if (sourcePath == null || !new File(sourcePath).exists()) {
            Toast.makeText(this, "图片不存在", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        temp = sourcePath.equals(Theme.pickFile(this).getAbsolutePath());
        Bitmap bmp = decode(sourcePath);
        if (bmp == null) {
            Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
            finish();
            return;
        }
        setContentView(build(bmp));
        Theme.decorate(this, findViewById(android.R.id.content));
    }

    private LinearLayout build(Bitmap bmp) {
        Theme.Palette p = Theme.p(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF000000);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12));
        bar.setBackgroundColor(p.card);

        TextView cancel = new TextView(this);
        cancel.setText("取消");
        cancel.setTextSize(14);
        cancel.setTextColor(p.sub);
        cancel.setPadding(0, Ui.dpInt(this, 6), Ui.dpInt(this, 12), Ui.dpInt(this, 6));
        cancel.setOnClickListener(v -> doCancel());

        TextView title = new TextView(this);
        title.setText("裁剪背景");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(p.text);
        title.setGravity(Gravity.CENTER);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView save = Ui.button(this, "保存");
        save.setTextSize(13);
        save.setOnClickListener(v -> doSave());

        bar.addView(cancel);
        bar.addView(title);
        bar.addView(save);
        root.addView(bar);

        cropView = new CropView(this, bmp);
        root.addView(cropView, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        TextView hint = new TextView(this);
        hint.setText("拖动图片调整位置 · 双指缩放 · 框内就是最终背景");
        hint.setTextSize(12);
        hint.setTextColor(0xFFB8C0CC);
        hint.setGravity(Gravity.CENTER);
        hint.setPadding(0, Ui.dpInt(this, 10), 0, Ui.dpInt(this, 16));
        root.addView(hint);
        return root;
    }

    private void doSave() {
        Bitmap out = cropView == null ? null : cropView.crop();
        if (out == null) {
            Toast.makeText(this, "裁剪失败", Toast.LENGTH_SHORT).show();
            return;
        }
        String path = Theme.saveCropped(this, out);
        if (path == null) {
            Toast.makeText(this, "保存失败", Toast.LENGTH_SHORT).show();
            return;
        }
        new Prefs(this).setBgImage(path);
        new Prefs(this).setBgStamp(System.currentTimeMillis());
        Theme.promoteSource(this);
        setResult(RESULT_OK);
        Toast.makeText(this, "背景已更新", Toast.LENGTH_SHORT).show();
        finish();
    }

    private void doCancel() {
        if (temp) {
            try {
                Theme.pickFile(this).delete();
            } catch (Exception ignored) {
            }
        }
        setResult(RESULT_CANCELED);
        finish();
    }

    @Override
    public void onBackPressed() {
        doCancel();
    }

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, bounds);
            int max = Math.max(bounds.outWidth, bounds.outHeight);
            int sample = 1;
            while (max / sample > 2048) {
                sample *= 2;
            }
            BitmapFactory.Options opts = new BitmapFactory.Options();
            opts.inSampleSize = sample;
            return BitmapFactory.decodeFile(path, opts);
        } catch (Throwable e) {
            return null;
        }
    }
}
