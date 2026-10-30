package io.local.timetable;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONTokener;

import java.util.List;

import io.local.timetable.model.Course;
import io.local.timetable.parse.HtmlCourseParser;
import io.local.timetable.parse.TextCourseParser;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;
import io.local.timetable.util.WebViewHelper;

/**
 * 教务网页导入：内嵌 WebView，用户自己登录，登录后点「提取课表」。
 * 全程本地完成，不经过任何第三方服务器。
 */
public class WebImportActivity extends Activity {

    private static final String DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0 Safari/537.36";

    private WebView web;
    private EditText urlInput;
    private TextView status;
    private Prefs prefs;
    private boolean desktopMode = true;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        Theme.prepare(this);
        super.onCreate(savedInstanceState);
        prefs = new Prefs(this);
        setContentView(build());
        Theme.decorate(this, findViewById(android.R.id.content));
        web.loadUrl(prefs.schoolUrl());
    }

    private LinearLayout build() {
        Theme.Palette p = Theme.p(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(p.bg);

        // 第一行：返回 + 地址 + 打开
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 8), Ui.dpInt(this, 12), Ui.dpInt(this, 6));
        bar.setBackgroundColor(p.card);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(Theme.p(this).sub);
        back.setPadding(0, 0, Ui.dpInt(this, 8), 0);
        back.setOnClickListener(v -> finish());

        urlInput = new EditText(this);
        urlInput.setText(prefs.schoolUrl());
        urlInput.setTextSize(12);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(Theme.p(this).text);
        urlInput.setHintTextColor(Theme.p(this).faint);
        urlInput.setBackground(Ui.roundRect(Theme.p(this).bg, 10, this));
        urlInput.setPadding(Ui.dpInt(this, 10), Ui.dpInt(this, 6), Ui.dpInt(this, 10), Ui.dpInt(this, 6));
        LinearLayout.LayoutParams ulp = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1);
        ulp.rightMargin = Ui.dpInt(this, 8);

        TextView open = Ui.button(this, "打开");
        open.setTextSize(13);
        open.setOnClickListener(v -> {
            String u = urlInput.getText().toString().trim();
            if (!u.startsWith("http")) {
                u = "https://" + u;
            }
            prefs.setSchoolUrl(u);
            web.loadUrl(u);
        });

        bar.addView(back);
        bar.addView(urlInput, ulp);
        bar.addView(open);
        root.addView(bar);

        // 第二行：提示 + 电脑模式 + 提取
        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.setGravity(Gravity.CENTER_VERTICAL);
        bar2.setPadding(Ui.dpInt(this, 12), 0, Ui.dpInt(this, 12), Ui.dpInt(this, 8));
        bar2.setBackgroundColor(p.card);

        status = new TextView(this);
        status.setText("先选学期并点「查询」，再点「提取课表」");
        status.setTextSize(11);
        status.setTextColor(Theme.p(this).sub);
        status.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));

        TextView ua = Ui.ghostButton(this, "电脑模式");
        ua.setTextSize(12);
        LinearLayout.LayoutParams ualp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        ualp.rightMargin = Ui.dpInt(this, 8);
        ua.setOnClickListener(v -> {
            desktopMode = !desktopMode;
            applyUserAgent();
            web.reload();
            Toast.makeText(this, desktopMode ? "已切换电脑模式" : "已切换手机模式", Toast.LENGTH_SHORT).show();
        });

        TextView extract = Ui.button(this, "提取课表");
        extract.setTextSize(13);
        extract.setOnClickListener(v -> extract());

        bar2.addView(status);
        bar2.addView(ua, ualp);
        bar2.addView(extract);
        root.addView(bar2);

        web = new WebView(this);
        WebSettings s = web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setLoadWithOverviewMode(true);
        s.setUseWideViewPort(true);
        s.setBuiltInZoomControls(true);
        s.setDisplayZoomControls(false);
        s.setSupportZoom(true);
        s.setMixedContentMode(WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE);
        CookieManager.getInstance().setAcceptCookie(true);
        CookieManager.getInstance().setAcceptThirdPartyCookies(web, true);
        web.setWebViewClient(new WebViewClient());
        WebViewHelper.enablePopups(web);
        web.setBackgroundColor(p.bg);
        applyUserAgent();

        root.addView(web, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));
        return root;
    }

    private void applyUserAgent() {
        WebSettings s = web.getSettings();
        s.setUserAgentString(desktopMode ? DESKTOP_UA : null);
    }

    private void extract() {
        status.setText("正在提取当前页面…");
        web.evaluateJavascript("(function(){try{return document.documentElement.outerHTML;}catch(e){return '';}})()",
                htmlValue -> {
                    String html = decodeJsString(htmlValue);
                    if (html == null || html.length() < 200) {
                        status.setText("页面内容为空");
                        return;
                    }
                    if (!looksLikeTimetable(html)) {
                        status.setText("当前页面不像课表页");
                        Toast.makeText(this,
                                "这不是课表页：请先进入「学生个人课表」，选好学年/学期并点「查询」，再提取",
                                Toast.LENGTH_LONG).show();
                        return;
                    }
                    List<Course> parsed = HtmlCourseParser.parse(html);
                    if (parsed.size() >= 2) {
                        status.setText("解析到 " + parsed.size() + " 条");
                        ImportPreviewActivity.start(this, parsed, "教务网页");
                        return;
                    }
                    // 兜底：取可见文本再解析
                    web.evaluateJavascript("(function(){try{return document.body.innerText;}catch(e){return '';}})()",
                            textValue -> {
                                String text = decodeJsString(textValue);
                                List<Course> parsed2 = TextCourseParser.parse(text);
                                status.setText(parsed2.isEmpty() ? "没有解析到课程" : "解析到 " + parsed2.size() + " 条");
                                if (parsed2.isEmpty()) {
                                    Toast.makeText(this,
                                            "没解析到课程：确认已在「学生个人课表」页，并已点「查询」显示表格",
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    ImportPreviewActivity.start(this, parsed2, "教务网页(文本)");
                                }
                            });
                });
    }

    /** 判断当前 HTML 是否像课表：至少 4 个星期名 + 节次/周次痕迹。 */
    private static boolean looksLikeTimetable(String html) {
        int days = 0;
        for (String d : new String[]{"周一", "周二", "周三", "周四", "周五", "周六", "周日",
                "星期一", "星期二", "星期三", "星期四", "星期五", "星期六", "星期日"}) {
            if (html.contains(d)) {
                days++;
            }
        }
        return days >= 4 && (html.contains("节") || html.contains("周"));
    }

    private static String decodeJsString(String value) {
        if (value == null) {
            return null;
        }
        try {
            Object o = new JSONTokener(value).nextValue();
            return o == null ? null : o.toString();
        } catch (Exception e) {
            return value;
        }
    }

    @Override
    public void onBackPressed() {
        if (web != null && web.canGoBack()) {
            web.goBack();
        } else {
            super.onBackPressed();
        }
    }
}
