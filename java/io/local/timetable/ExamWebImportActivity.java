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

import io.local.timetable.model.Exam;
import io.local.timetable.parse.HtmlExamParser;
import io.local.timetable.util.Prefs;
import io.local.timetable.util.Theme;
import io.local.timetable.util.Ui;

/**
 * 教务「考试信息查询」导入：WebView 里由用户自己登录、查询，
 * 再读取当前页 DOM 本地解析。仅考试模块使用。
 */
public class ExamWebImportActivity extends Activity {

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

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(Ui.dpInt(this, 12), Ui.dpInt(this, 8), Ui.dpInt(this, 12), Ui.dpInt(this, 6));
        bar.setBackgroundColor(p.card);

        TextView back = new TextView(this);
        back.setText("‹");
        back.setTextSize(26);
        back.setTextColor(p.sub);
        back.setPadding(0, 0, Ui.dpInt(this, 8), 0);
        back.setOnClickListener(v -> finish());

        urlInput = new EditText(this);
        urlInput.setText(prefs.schoolUrl());
        urlInput.setTextSize(12);
        urlInput.setSingleLine(true);
        urlInput.setTextColor(p.text);
        urlInput.setHintTextColor(p.faint);
        urlInput.setBackground(Ui.roundRect(p.bg, 10, this));
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

        LinearLayout bar2 = new LinearLayout(this);
        bar2.setOrientation(LinearLayout.HORIZONTAL);
        bar2.setGravity(Gravity.CENTER_VERTICAL);
        bar2.setPadding(Ui.dpInt(this, 12), 0, Ui.dpInt(this, 12), Ui.dpInt(this, 8));
        bar2.setBackgroundColor(p.card);

        status = new TextView(this);
        status.setText("进入「考试信息查询」并点「查询」，再提取");
        status.setTextSize(11);
        status.setTextColor(p.sub);
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

        TextView extract = Ui.button(this, "提取考试");
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
        applyUserAgent();
        web.setBackgroundColor(p.bg);

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
                    List<Exam> parsed = HtmlExamParser.parse(html);
                    if (!parsed.isEmpty()) {
                        status.setText("解析到 " + parsed.size() + " 场考试");
                        ExamPreviewActivity.start(this, parsed, "教务网页");
                        return;
                    }
                    web.evaluateJavascript("(function(){try{return document.body.innerText;}catch(e){return '';}})()",
                            textValue -> {
                                String text = decodeJsString(textValue);
                                List<Exam> parsed2 = HtmlExamParser.parseText(text);
                                status.setText(parsed2.isEmpty() ? "没有解析到考试" : "解析到 " + parsed2.size() + " 场");
                                if (parsed2.isEmpty()) {
                                    Toast.makeText(this,
                                            "没解析到考试：确认已进「考试信息查询」页并点过「查询」",
                                            Toast.LENGTH_LONG).show();
                                } else {
                                    ExamPreviewActivity.start(this, parsed2, "教务网页(文本)");
                                }
                            });
                });
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
