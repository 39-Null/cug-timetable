package io.local.timetable.util;

import android.net.Uri;
import android.os.Message;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

/**
 * 教务菜单常用 window.open / target=_blank 打开新窗口；
 * WebView 默认不处理多窗口请求，表现为“点击没反应”。
 * 这里把新窗口的 URL 接回主 WebView 打开。
 */
public final class WebViewHelper {

    private WebViewHelper() {
    }

    /** 只允许 http/https 在主 WebView 内导航，避免 file/content/intent 等 scheme 注入。 */
    public static boolean isHttpUrl(String url) {
        try {
            String scheme = Uri.parse(url).getScheme();
            return "http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme);
        } catch (Exception e) {
            return false;
        }
    }

    public static WebViewClient httpOnlyClient() {
        return new WebViewClient() {
            @Override
            public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                return !isHttpUrl(request.getUrl().toString());
            }

            @Override
            public boolean shouldOverrideUrlLoading(WebView view, String url) {
                return !isHttpUrl(url);
            }
        };
    }

    public static void enablePopups(final WebView web) {
        WebSettings settings = web.getSettings();
        settings.setSupportMultipleWindows(true);
        settings.setJavaScriptCanOpenWindowsAutomatically(true);
        web.setWebChromeClient(new WebChromeClient() {
            @Override
            public boolean onCreateWindow(WebView view, boolean isDialog,
                                          boolean isUserGesture, Message resultMsg) {
                if (resultMsg == null || !(resultMsg.obj instanceof WebView.WebViewTransport)) {
                    return false;
                }
                final WebView popup = new WebView(view.getContext());
                popup.setWebViewClient(new WebViewClient() {
                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, WebResourceRequest request) {
                        forward(v, view, request.getUrl().toString());
                        return true;
                    }

                    @Override
                    public boolean shouldOverrideUrlLoading(WebView v, String url) {
                        forward(v, view, url);
                        return true;
                    }
                });
                ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup);
                resultMsg.sendToTarget();
                return true;
            }
        });
    }

    private static void forward(final WebView popup, final WebView main, String url) {
        if (isHttpUrl(url)) {
            main.loadUrl(url);
        }
        // 只借用弹窗拿到第一个 URL，随后释放，避免累积 WebView 实例。
        popup.stopLoading();
        new android.os.Handler(android.os.Looper.getMainLooper()).post(new Runnable() {
            @Override
            public void run() {
                popup.destroy();
            }
        });
    }
}
