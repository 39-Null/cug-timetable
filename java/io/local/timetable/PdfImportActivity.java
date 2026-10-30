package io.local.timetable;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import com.tom_roush.pdfbox.android.PDFBoxResourceLoader;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.text.PDFTextStripper;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import io.local.timetable.model.Course;
import io.local.timetable.parse.GridCourseParser;
import io.local.timetable.parse.PdfTable;
import io.local.timetable.parse.TextCourseParser;
import io.local.timetable.util.Theme;

/** PDF 导入：系统文件选择器（SAF）→ 本地解析 → 预览。不上传任何数据。 */
public class PdfImportActivity extends Activity {

    private static final int REQ_PICK = 1001;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        PDFBoxResourceLoader.init(getApplicationContext());
        pick();
    }

    private void pick() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/pdf");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            startActivityForResult(intent, REQ_PICK);
        } catch (Exception e) {
            Toast.makeText(this, "没有可用的文件选择器", Toast.LENGTH_LONG).show();
            finish();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PICK) {
            return;
        }
        if (resultCode != RESULT_OK || data == null || data.getData() == null) {
            finish();
            return;
        }
        Uri uri = data.getData();
        final ProgressDialog progress = ProgressDialog.show(this, "", "正在解析 PDF…", true, false);
        new Thread(() -> {
            List<Course> parsed = new ArrayList<>();
            int rowsTotal = 0;
            String rawText = "";
            String error = null;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                PDDocument doc = PDDocument.load(in);
                try {
                    int pages = Math.min(doc.getNumberOfPages(), 3);
                    for (int i = 0; i < pages; i++) {
                        List<List<String>> rows = PdfTable.extract(doc, i);
                        rowsTotal += rows.size();
                        List<Course> pageCourses = GridCourseParser.parse(rows);
                        if (!pageCourses.isEmpty()) {
                            parsed.addAll(pageCourses);
                        }
                    }
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    String text = stripper.getText(doc);
                    rawText = text;
                    if (parsed.isEmpty()) {
                        parsed.addAll(TextCourseParser.parse(text));
                    }
                } finally {
                    doc.close();
                }
            } catch (Throwable e) {
                error = e.getClass().getSimpleName() + ": " + e.getMessage();
            }
            final List<Course> result = parsed;
            final String err = error;
            final int rows = rowsTotal;
            final String raw = rawText;
            runOnUiThread(() -> {
                progress.dismiss();
                if (err != null) {
                    Toast.makeText(this, "解析失败：" + err, Toast.LENGTH_LONG).show();
                    finish();
                    return;
                }
                if (result.isEmpty()) {
                    Toast.makeText(this, "没解析到课程，已把提取到的文本放进编辑页（rows=" + rows + "）", Toast.LENGTH_LONG).show();
                    Intent intent = new Intent(this, TextImportActivity.class);
                    intent.putExtra("text", raw);
                    intent.putExtra("source", "PDF文本");
                    startActivity(intent);
                    finish();
                    return;
                }
                ImportPreviewActivity.start(this, result, "PDF");
                finish();
            });
        }).start();
    }
}
