package io.local.timetable;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;

import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;

/** 主题：深色模式 + 本地图片背景。 */
public class ThemeActivity extends Activity {

    private static final int REQ_IMAGE = 1001;
    private static final int REQ_CROP = 1002;

    private Prefs prefs;
    private TextView dimValue;
    private ImageView preview;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        Theme.ensureSource(this);
        setContentView(build());
        Theme.decorate(this, findViewById(android.R.id.content));
    }

    private LinearLayout build() {
        Theme.Palette p = Theme.p(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Theme.hasBackground(this) ? Color.TRANSPARENT : p.bg);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 16), Ui.dpInt(this, 12), Ui.dpInt(this, 12), Ui.dpInt(this, 12));
        bar.setBackgroundColor(Theme.hasBackground(this)
                ? Theme.withTransparency(p.card, prefs.topBarTransparency()) : p.card);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(p.sub);
        back.setPadding(0, 0, Ui.dpInt(this, 12), 0);
        back.setOnClickListener(v -> finish());

        TextView title = new TextView(this);
        title.setText("主题");
        title.setTextSize(17);
        title.setTypeface(null, android.graphics.Typeface.BOLD);
        title.setTextColor(p.text);
        title.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView done = new TextView(this);
        done.setText("完成");
        done.setTextSize(14);
        done.setTextColor(p.primary);
        done.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 6), 0, Ui.dpInt(this, 6));
        done.setOnClickListener(v -> finish());

        bar.addView(back);
        bar.addView(title);
        bar.addView(done);
        root.addView(bar);

        ScrollView scroll = new ScrollView(this);
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(this, 16);
        box.setPadding(pad, pad, pad, Ui.dpInt(this, 32));

        box.addView(section("外观"));
        LinearLayout darkRow = new LinearLayout(this);
        darkRow.setOrientation(LinearLayout.HORIZONTAL);
        darkRow.setGravity(Gravity.CENTER_VERTICAL);
        darkRow.setPadding(0, Ui.dpInt(this, 10), 0, Ui.dpInt(this, 10));
        TextView darkLabel = new TextView(this);
        darkLabel.setText("深色主题");
        darkLabel.setTextSize(15);
        darkLabel.setTextColor(p.text);
        darkLabel.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        Switch darkSwitch = new Switch(this);
        darkSwitch.setChecked(prefs.dark());
        darkSwitch.setOnCheckedChangeListener((buttonView, isChecked) -> {
            if (prefs.dark() == isChecked) {
                return;
            }
            prefs.setDark(isChecked);
            recreate();
        });
        darkRow.addView(darkLabel);
        darkRow.addView(darkSwitch);
        box.addView(darkRow);

        box.addView(section("背景图片"));
        TextView hint = new TextView(this);
        hint.setText("选一张本地图片作为课表背景，图片会复制到应用私有目录，仅本机使用。");
        hint.setTextSize(12);
        hint.setTextColor(p.sub);
        hint.setPadding(0, Ui.dpInt(this, 2), 0, Ui.dpInt(this, 8));
        box.addView(hint);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        TextView pick = Ui.button(this, "选择图片");
        pick.setTextSize(12);
        TextView recrop = Ui.ghostButton(this, "重新裁剪");
        recrop.setTextSize(12);
        TextView clear = Ui.ghostButton(this, "移除背景");
        clear.setTextSize(12);
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp1.rightMargin = Ui.dpInt(this, 6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        lp2.rightMargin = Ui.dpInt(this, 6);
        btnRow.addView(pick, lp1);
        btnRow.addView(recrop, lp2);
        btnRow.addView(clear, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        pick.setOnClickListener(v -> pickImage());
        recrop.setOnClickListener(v -> {
            if (!Theme.hasSource(this)) {
                Toast.makeText(this, "还没有原图，请先选择图片", Toast.LENGTH_SHORT).show();
                return;
            }
            startCrop(Theme.sourceFile(this).getAbsolutePath());
        });
        clear.setOnClickListener(v -> {
            Theme.clearBackground(this);
            prefs.setBgImage("");
            refreshPreview();
            Toast.makeText(this, "已移除背景", Toast.LENGTH_SHORT).show();
        });
        box.addView(btnRow);

        preview = new ImageView(this);
        preview.setScaleType(ImageView.ScaleType.CENTER_CROP);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, Ui.dpInt(this, 170));
        plp.topMargin = Ui.dpInt(this, 12);
        preview.setLayoutParams(plp);
        preview.setBackground(Ui.roundRect(p.card, 12, this));
        preview.setClipToOutline(true);
        box.addView(preview);

        box.addView(section("背景遮罩"));
        LinearLayout dimRow = new LinearLayout(this);
        dimRow.setOrientation(LinearLayout.HORIZONTAL);
        dimRow.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar dim = new SeekBar(this);
        dim.setMax(80);
        dim.setProgress(prefs.bgDim());
        dimValue = new TextView(this);
        dimValue.setText(prefs.bgDim() + "%");
        dimValue.setTextSize(13);
        dimValue.setTextColor(p.sub);
        dimValue.setMinWidth(Ui.dpInt(this, 48));
        dimValue.setGravity(Gravity.END);
        dim.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                dimValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setBgDim(seekBar.getProgress());
                recreate();
            }
        });
        dimRow.addView(dim, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        dimRow.addView(dimValue);
        box.addView(dimRow);

        TextView note = new TextView(this);
        note.setText("遮罩越浓，网格和文字越清楚；越淡，图片越明显。");
        note.setTextSize(12);
        note.setTextColor(p.faint);
        note.setPadding(0, Ui.dpInt(this, 2), 0, 0);
        box.addView(note);

        box.addView(section("上边栏透明度"));
        LinearLayout topRow = new LinearLayout(this);
        topRow.setOrientation(LinearLayout.HORIZONTAL);
        topRow.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar topBar = new SeekBar(this);
        topBar.setMax(100);
        topBar.setProgress(prefs.topBarTransparency());
        TextView topValue = new TextView(this);
        topValue.setText(prefs.topBarTransparency() + "%");
        topValue.setTextSize(13);
        topValue.setTextColor(p.sub);
        topValue.setMinWidth(Ui.dpInt(this, 48));
        topValue.setGravity(Gravity.END);
        topBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                topValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setTopBarTransparency(seekBar.getProgress());
                recreate();
            }
        });
        topRow.addView(topBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        topRow.addView(topValue);
        box.addView(topRow);

        box.addView(section("侧边栏透明度"));
        LinearLayout sideRow = new LinearLayout(this);
        sideRow.setOrientation(LinearLayout.HORIZONTAL);
        sideRow.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar sideBar = new SeekBar(this);
        sideBar.setMax(100);
        sideBar.setProgress(prefs.sideBarTransparency());
        TextView sideValue = new TextView(this);
        sideValue.setText(prefs.sideBarTransparency() + "%");
        sideValue.setTextSize(13);
        sideValue.setTextColor(p.sub);
        sideValue.setMinWidth(Ui.dpInt(this, 48));
        sideValue.setGravity(Gravity.END);
        sideBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                sideValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setSideBarTransparency(seekBar.getProgress());
                recreate();
            }
        });
        sideRow.addView(sideBar, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        sideRow.addView(sideValue);
        box.addView(sideRow);

        TextView barNote = new TextView(this);
        barNote.setText("0% 完全不透明，100% 完全透明；仅在设置背景图片时生效。上边栏包含顶部标题和课表星期表头。");
        barNote.setTextSize(12);
        barNote.setTextColor(p.faint);
        barNote.setPadding(0, Ui.dpInt(this, 2), 0, 0);
        box.addView(barNote);

        box.addView(section("网格线清晰度"));
        LinearLayout gridRow = new LinearLayout(this);
        gridRow.setOrientation(LinearLayout.HORIZONTAL);
        gridRow.setGravity(Gravity.CENTER_VERTICAL);
        SeekBar grid = new SeekBar(this);
        grid.setMax(100);
        grid.setProgress(prefs.gridAlpha());
        TextView gridValue = new TextView(this);
        gridValue.setText(prefs.gridAlpha() + "%");
        gridValue.setTextSize(13);
        gridValue.setTextColor(p.sub);
        gridValue.setMinWidth(Ui.dpInt(this, 48));
        gridValue.setGravity(Gravity.END);
        grid.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                gridValue.setText(progress + "%");
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
                prefs.setGridAlpha(seekBar.getProgress());
                recreate();
            }
        });
        gridRow.addView(grid, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));
        gridRow.addView(gridValue);
        box.addView(gridRow);

        TextView gridNote = new TextView(this);
        gridNote.setText("0% 完全隐藏网格线，100% 最清晰；配合背景图片调整。");
        gridNote.setTextSize(12);
        gridNote.setTextColor(p.faint);
        gridNote.setPadding(0, Ui.dpInt(this, 2), 0, 0);
        box.addView(gridNote);

        scroll.addView(box);
        root.addView(scroll, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        refreshPreview();
        return root;
    }

    private TextView section(String text) {
        Theme.Palette p = Theme.p(this);
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(12);
        t.setTextColor(p.primary);
        t.setPadding(0, Ui.dpInt(this, 18), 0, Ui.dpInt(this, 2));
        return t;
    }

    private void refreshPreview() {
        String path = prefs.bgImage();
        if (path != null && !path.isEmpty() && new File(path).exists()) {
            Drawable d = Theme.loadBackground(this, path);
            preview.setImageDrawable(d);
            preview.setAlpha(1f);
        } else {
            preview.setImageDrawable(null);
            preview.setBackground(Ui.roundRect(Theme.p(this).card, 12, this));
        }
    }

    private void pickImage() {
        // 优先图库：Android 13+ 系统照片选择器，其次相册 ACTION_PICK，
        // 最后才退回文件选择器。
        if (Build.VERSION.SDK_INT >= 33) {
            Intent picker = new Intent(MediaStore.ACTION_PICK_IMAGES);
            picker.setType("image/*");
            if (tryPick(picker)) {
                return;
            }
        }
        Intent gallery = new Intent(Intent.ACTION_PICK,
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI);
        gallery.setType("image/*");
        if (tryPick(gallery)) {
            return;
        }
        Intent getContent = new Intent(Intent.ACTION_GET_CONTENT);
        getContent.addCategory(Intent.CATEGORY_OPENABLE);
        getContent.setType("image/*");
        if (tryPick(getContent)) {
            return;
        }
        Intent openDocument = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        openDocument.addCategory(Intent.CATEGORY_OPENABLE);
        openDocument.setType("image/*");
        if (tryPick(openDocument)) {
            return;
        }
        Toast.makeText(this, "没有可用的图片选择器", Toast.LENGTH_SHORT).show();
    }

    private boolean tryPick(Intent intent) {
        try {
            // 不要先用 resolveActivity 预检：Android 11+ 的包可见性可能让系统图库
            // 查不到，但直接 startActivity 仍能正常打开。
            startActivityForResult(intent, REQ_IMAGE);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMAGE && resultCode == RESULT_OK && data != null) {
            Uri uri = data.getData();
            if (uri == null) {
                return;
            }
            Toast.makeText(this, "正在读取图片…", Toast.LENGTH_SHORT).show();
            final android.content.Context app = getApplicationContext();
            new Thread(() -> {
                String path = Theme.importBackground(app, uri);
                runOnUiThread(() -> {
                    if (isFinishing()) {
                        return;
                    }
                    if (path == null) {
                        Toast.makeText(this, "图片读取失败", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    startCrop(path);
                });
            }, "background-import").start();
            return;
        }
        if (requestCode == REQ_CROP && resultCode == RESULT_OK) {
            refreshPreview();
        }
    }

    private void startCrop(String path) {
        Intent intent = new Intent(this, CropActivity.class);
        intent.putExtra(CropActivity.EXTRA_SRC, path);
        startActivityForResult(intent, REQ_CROP);
    }
}
