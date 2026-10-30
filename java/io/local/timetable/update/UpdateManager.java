package io.local.timetable.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageInstaller;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.pm.SigningInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;

import io.local.timetable.util.Prefs;
import io.local.timetable.util.Ui;

/** 从 GitHub Releases 检查并安装更新。只使用 GitHub 直链。 */
public final class UpdateManager {

    private static final String API_URL =
            "https://api.github.com/repos/39-Null/cug-timetable/releases/latest";
    private static final String RELEASE_URL =
            "https://github.com/39-Null/cug-timetable/releases/latest";
    private static final long AUTO_INTERVAL_MS = 6L * 60L * 60L * 1000L;

    private static boolean checking;
    private static boolean downloading;
    private static boolean dialogShowing;
    private static boolean permissionToastShown;
    private static UpdateInfo pendingInfo;
    private static File pendingApk;

    private UpdateManager() {
    }

    public static final class UpdateInfo {
        public String version = "";
        public String notes = "";
        public String url = "";
        public String digest = "";
        public long size;
        public String downloadedDigest = "";

        boolean hasApk() {
            return url != null && !url.isEmpty();
        }
    }

    /** 启动时自动检查；6 小时内不重复请求，避免触发 GitHub 限流。 */
    public static void autoCheck(Activity activity) {
        Prefs prefs = new Prefs(activity);
        long now = System.currentTimeMillis();
        if (now - prefs.lastUpdateCheck() < AUTO_INTERVAL_MS) {
            return;
        }
        check(activity, false);
    }

    /** 设置页里的手动检查。 */
    public static void manualCheck(Activity activity) {
        check(activity, true);
    }

    /** 从“安装未知应用”设置页返回后继续下载或安装。 */
    public static void onResume(Activity activity) {
        if (pendingInfo == null) {
            permissionToastShown = false;
            return;
        }
        if (!canInstall(activity)) {
            if (!permissionToastShown) {
                permissionToastShown = true;
                Toast.makeText(activity, "尚未允许安装未知应用", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        permissionToastShown = false;
        if (pendingApk != null && pendingApk.isFile()) {
            File apk = pendingApk;
            pendingApk = null;
            pendingInfo = null;
            install(activity, apk);
        } else {
            UpdateInfo info = pendingInfo;
            pendingInfo = null;
            download(activity, info);
        }
    }

    private static void check(final Activity activity, final boolean manual) {
        if (checking) {
            if (manual) {
                Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
            }
            return;
        }
        checking = true;
        if (manual) {
            Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show();
        }
        new Thread(() -> {
            UpdateInfo info = null;
            String error = null;
            try {
                info = fetchLatest();
            } catch (Exception e) {
                error = friendlyNetworkError(e);
            }
            final UpdateInfo found = info;
            final String failure = error;
            activity.runOnUiThread(() -> {
                checking = false;
                if (activity.isFinishing()) {
                    return;
                }
                new Prefs(activity).setLastUpdateCheck(System.currentTimeMillis());
                if (failure != null) {
                    if (manual) {
                        showError(activity, "检查更新失败", failure);
                    }
                    return;
                }
                if (found == null || !found.hasApk()) {
                    if (manual) {
                        Toast.makeText(activity, "Release 中没有找到 APK", Toast.LENGTH_LONG).show();
                    }
                    return;
                }
                String current = currentVersion(activity);
                if (compareVersion(found.version, current) <= 0) {
                    if (manual) {
                        Toast.makeText(activity, "已是最新版本 " + current, Toast.LENGTH_SHORT).show();
                    }
                    return;
                }
                if (!manual && found.version.equals(new Prefs(activity).skipUpdateVersion())) {
                    return;
                }
                showUpdateDialog(activity, found, manual);
            });
        }, "update-check").start();
    }

    private static UpdateInfo fetchLatest() throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(API_URL).openConnection();
            conn.setConnectTimeout(12000);
            conn.setReadTimeout(20000);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("Accept", "application/vnd.github+json");
            conn.setRequestProperty("X-GitHub-Api-Version", "2022-11-28");
            conn.setRequestProperty("User-Agent", "cug-timetable-updater");
            int code = conn.getResponseCode();
            if (code != 200) {
                String detail = readText(conn.getErrorStream());
                throw new IOException("GitHub 返回 HTTP " + code
                        + (detail.isEmpty() ? "" : "：" + detail));
            }
            JSONObject root = new JSONObject(readText(conn.getInputStream()));
            UpdateInfo info = new UpdateInfo();
            info.version = cleanVersion(root.optString("tag_name", ""));
            if (info.version.isEmpty()) {
                info.version = cleanVersion(root.optString("name", ""));
            }
            info.notes = root.optString("body", "");
            if (info.notes.length() > 1800) {
                info.notes = info.notes.substring(0, 1800) + "…";
            }
            JSONArray assets = root.optJSONArray("assets");
            if (assets != null) {
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject a = assets.optJSONObject(i);
                    if (a == null) {
                        continue;
                    }
                    String name = a.optString("name", "");
                    if (!name.toLowerCase(Locale.US).endsWith(".apk")) {
                        continue;
                    }
                    String url = a.optString("browser_download_url", "");
                    if (url.isEmpty()) {
                        continue;
                    }
                    info.url = url;
                    info.size = a.optLong("size", 0L);
                    info.digest = a.isNull("digest") ? "" : a.optString("digest", "");
                    break;
                }
            }
            return info;
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static void showUpdateDialog(final Activity activity, final UpdateInfo info,
                                         final boolean manual) {
        if (dialogShowing || activity.isFinishing()) {
            return;
        }
        dialogShowing = true;
        String current = currentVersion(activity);
        StringBuilder msg = new StringBuilder();
        msg.append("当前版本 ").append(current)
                .append("\n最新版本 ").append(info.version);
        if (!info.notes.trim().isEmpty()) {
            msg.append("\n\n").append(info.notes.trim());
        }
        if (info.size > 0) {
            msg.append("\n\n安装包大小：").append(formatSize(info.size));
        }
        AlertDialog.Builder builder = new AlertDialog.Builder(activity)
                .setTitle("发现新版本")
                .setMessage(msg.toString())
                .setPositiveButton("立即更新", (d, w) -> startUpdate(activity, info))
                .setNegativeButton("稍后", null);
        if (!manual) {
            builder.setNeutralButton("跳过此版本", (d, w) ->
                    new Prefs(activity).setSkipUpdateVersion(info.version));
        }
        AlertDialog dialog = builder.create();
        dialog.setOnDismissListener(d -> dialogShowing = false);
        dialog.show();
    }

    private static void startUpdate(final Activity activity, final UpdateInfo info) {
        // 更新说明对话框的按钮回调里，dismiss 监听可能还没执行；先释放占位。
        dialogShowing = false;
        if (!canInstall(activity)) {
            pendingInfo = info;
            pendingApk = null;
            showPermissionDialog(activity);
            return;
        }
        pendingInfo = null;
        pendingApk = null;
        download(activity, info);
    }

    private static void showPermissionDialog(final Activity activity) {
        if (dialogShowing || activity.isFinishing()) {
            return;
        }
        dialogShowing = true;
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("Android 需要你允许「泥地课表」安装未知应用，才能完成更新。")
                .setNegativeButton("取消", (d, w) -> {
                    pendingInfo = null;
                    pendingApk = null;
                })
                .setPositiveButton("去设置", (d, w) -> openInstallSettings(activity))
                .create();
        dialog.setOnDismissListener(d -> dialogShowing = false);
        dialog.show();
    }

    private static void download(final Activity activity, final UpdateInfo info) {
        if (downloading) {
            Toast.makeText(activity, "正在下载更新…", Toast.LENGTH_SHORT).show();
            return;
        }
        downloading = true;
        final File out = new File(activity.getCacheDir(), "cug-timetable-update.apk");
        if (out.exists() && !out.delete()) {
            downloading = false;
            showError(activity, "无法写入更新文件", "请清理应用缓存后重试。");
            return;
        }

        LinearLayout box = new LinearLayout(activity);
        box.setOrientation(LinearLayout.VERTICAL);
        int pad = Ui.dpInt(activity, 20);
        box.setPadding(pad, Ui.dpInt(activity, 8), pad, 0);
        TextView status = new TextView(activity);
        status.setTextSize(13);
        status.setTextColor(io.local.timetable.util.Theme.p(activity).sub);
        status.setText("正在连接 GitHub…");
        ProgressBar bar = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
        bar.setMax(100);
        bar.setProgress(0);
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, Ui.dpInt(activity, 8));
        blp.topMargin = Ui.dpInt(activity, 12);
        bar.setLayoutParams(blp);
        box.addView(status);
        box.addView(bar);

        final AtomicBoolean canceled = new AtomicBoolean(false);
        AlertDialog dialog = new AlertDialog.Builder(activity)
                .setTitle("正在下载更新")
                .setView(box)
                .setNegativeButton("取消", null)
                .setCancelable(false)
                .create();
        dialog.setOnShowListener(d -> {
            dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v -> {
                canceled.set(true);
                dialog.dismiss();
            });
        });
        dialog.show();

        new Thread(() -> {
            String error = null;
            try {
                downloadFile(info, out, canceled, (read, total) -> activity.runOnUiThread(() -> {
                    if (dialog.isShowing()) {
                        if (total > 0) {
                            int pct = (int) Math.min(100, read * 100 / total);
                            bar.setProgress(pct);
                            status.setText("已下载 " + formatSize(read) + " / " + formatSize(total)
                                    + "（" + pct + "%）");
                        } else {
                            status.setText("已下载 " + formatSize(read));
                        }
                    }
                }));
            } catch (Exception e) {
                error = canceled.get() ? null : friendlyNetworkError(e);
            }
            final String failure = error;
            activity.runOnUiThread(() -> {
                downloading = false;
                if (dialog.isShowing()) {
                    dialog.dismiss();
                }
                if (canceled.get()) {
                    out.delete();
                    return;
                }
                if (failure != null) {
                    out.delete();
                    showError(activity, "下载失败", failure);
                    return;
                }
                String verifyError = verifyApk(activity, out, info);
                if (verifyError != null) {
                    out.delete();
                    showError(activity, "安装包校验失败", verifyError);
                    return;
                }
                if (!canInstall(activity)) {
                    pendingInfo = info;
                    pendingApk = out;
                    showPermissionDialog(activity);
                    return;
                }
                install(activity, out);
            });
        }, "update-download").start();
    }

    private interface Progress {
        void onProgress(long read, long total);
    }

    private static void downloadFile(UpdateInfo info, File out, AtomicBoolean canceled,
                                     Progress progress) throws Exception {
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(info.url).openConnection();
            conn.setConnectTimeout(15000);
            conn.setReadTimeout(60000);
            conn.setInstanceFollowRedirects(true);
            conn.setRequestMethod("GET");
            conn.setRequestProperty("User-Agent", "cug-timetable-updater");
            conn.setRequestProperty("Accept", "application/octet-stream");
            int code = conn.getResponseCode();
            if (code != 200) {
                String detail = readText(conn.getErrorStream());
                throw new IOException("HTTP " + code
                        + (detail.isEmpty() ? "" : "：" + detail));
            }
            long total = info.size > 0 ? info.size : conn.getContentLength();
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            File part = new File(out.getAbsolutePath() + ".part");
            if (part.exists()) {
                part.delete();
            }
            InputStream in = conn.getInputStream();
            OutputStream os = new FileOutputStream(part);
            long read = 0;
            try {
                byte[] buf = new byte[64 * 1024];
                long lastNotify = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (canceled.get()) {
                        throw new IOException("已取消");
                    }
                    os.write(buf, 0, n);
                    digest.update(buf, 0, n);
                    read += n;
                    long now = System.currentTimeMillis();
                    if (now - lastNotify > 180) {
                        lastNotify = now;
                        progress.onProgress(read, total);
                    }
                }
                os.flush();
                progress.onProgress(read, total);
            } finally {
                try {
                    os.close();
                } catch (Exception ignored) {
                }
                try {
                    in.close();
                } catch (Exception ignored) {
                }
            }
            if (total > 0 && read != total) {
                part.delete();
                throw new IOException("下载不完整：期望 " + total + " 字节，实际 " + read + " 字节");
            }
            if (out.exists() && !out.delete()) {
                part.delete();
                throw new IOException("无法替换旧更新文件");
            }
            if (!part.renameTo(out)) {
                part.delete();
                throw new IOException("无法保存更新文件");
            }
            info.downloadedDigest = hex(digest.digest());
        } finally {
            if (conn != null) {
                conn.disconnect();
            }
        }
    }

    private static String verifyApk(Activity activity, File apk, UpdateInfo info) {
        if (!apk.isFile() || apk.length() == 0) {
            return "下载的文件为空";
        }
        if (info.size > 0 && apk.length() != info.size) {
            return "文件大小不一致：期望 " + info.size + " 字节，实际 " + apk.length() + " 字节";
        }
        if (info.digest != null && !info.digest.isEmpty()) {
            String expected = info.digest;
            int colon = expected.indexOf(':');
            if (colon >= 0) {
                expected = expected.substring(colon + 1);
            }
            if (!expected.equalsIgnoreCase(info.downloadedDigest)) {
                return "SHA-256 校验不通过";
            }
        }
        PackageManager pm = activity.getPackageManager();
        int flags = Build.VERSION.SDK_INT >= 28
                ? PackageManager.GET_SIGNING_CERTIFICATES
                : PackageManager.GET_SIGNATURES;
        PackageInfo archive;
        try {
            archive = pm.getPackageArchiveInfo(apk.getAbsolutePath(), flags);
        } catch (Exception e) {
            return "无法读取安装包信息";
        }
        if (archive == null) {
            return "无法读取安装包信息";
        }
        if (!activity.getPackageName().equals(archive.packageName)) {
            return "安装包包名不匹配";
        }
        long archiveCode = Build.VERSION.SDK_INT >= 28
                ? archive.getLongVersionCode() : archive.versionCode;
        long currentCode = currentVersionCode(activity);
        if (archiveCode <= currentCode) {
            return "安装包版本不高于当前版本";
        }
        String currentSigner = signerSet(pm, activity.getPackageName(), flags);
        String archiveSigner = signerSet(archive, flags);
        if (currentSigner == null || archiveSigner == null) {
            return "无法读取签名信息";
        }
        if (!currentSigner.equals(archiveSigner)) {
            return "安装包签名与当前应用不一致。当前版本若来自旧分发渠道，需要卸载后重新安装。";
        }
        return null;
    }

    private static String signerSet(PackageManager pm, String packageName, int flags) {
        try {
            return signerSet(pm.getPackageInfo(packageName, flags), flags);
        } catch (Exception e) {
            return null;
        }
    }

    private static String signerSet(PackageInfo info, int flags) {
        Signature[] signatures;
        if (Build.VERSION.SDK_INT >= 28 && info.signingInfo != null) {
            SigningInfo signing = info.signingInfo;
            signatures = signing.getApkContentsSigners();
            if (signatures == null || signatures.length == 0) {
                signatures = signing.getSigningCertificateHistory();
            }
        } else {
            signatures = info.signatures;
        }
        if (signatures == null || signatures.length == 0) {
            return null;
        }
        String[] hashes = new String[signatures.length];
        try {
            for (int i = 0; i < signatures.length; i++) {
                MessageDigest md = MessageDigest.getInstance("SHA-256");
                hashes[i] = hex(md.digest(signatures[i].toByteArray()));
            }
        } catch (Exception e) {
            return null;
        }
        Arrays.sort(hashes);
        StringBuilder sb = new StringBuilder();
        for (String h : hashes) {
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(h);
        }
        return sb.toString();
    }

    private static void install(Activity activity, File apk) {
        try {
            PackageManager pm = activity.getPackageManager();
            PackageInstaller installer = pm.getPackageInstaller();
            PackageInstaller.SessionParams params =
                    new PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL);
            params.setAppPackageName(activity.getPackageName());
            if (Build.VERSION.SDK_INT >= 31) {
                params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED);
            }
            int sessionId = installer.createSession(params);
            PackageInstaller.Session session = installer.openSession(sessionId);
            try {
                OutputStream out = session.openWrite("base.apk", 0, apk.length());
                FileInputStream in = new FileInputStream(apk);
                try {
                    byte[] buf = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    out.flush();
                    session.fsync(out);
                } finally {
                    try {
                        in.close();
                    } catch (Exception ignored) {
                    }
                    try {
                        out.close();
                    } catch (Exception ignored) {
                    }
                }
                Intent result = new Intent(activity, InstallResultReceiver.class);
                result.setAction(InstallResultReceiver.ACTION_INSTALL_RESULT);
                int piFlags = PendingIntent.FLAG_UPDATE_CURRENT;
                if (Build.VERSION.SDK_INT >= 31) {
                    piFlags |= PendingIntent.FLAG_MUTABLE;
                }
                PendingIntent pending = PendingIntent.getBroadcast(
                        activity, sessionId, result, piFlags);
                session.commit(pending.getIntentSender());
            } finally {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
            pendingInfo = null;
            pendingApk = null;
            Toast.makeText(activity, "正在安装更新…", Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            showError(activity, "无法启动安装", String.valueOf(e.getMessage()));
        }
    }

    private static boolean canInstall(Activity activity) {
        if (Build.VERSION.SDK_INT < 26) {
            return true;
        }
        return activity.getPackageManager().canRequestPackageInstalls();
    }

    private static void openInstallSettings(Activity activity) {
        try {
            Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:" + activity.getPackageName()));
            activity.startActivity(intent);
        } catch (Exception e) {
            try {
                activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
            } catch (Exception ignored) {
                Toast.makeText(activity, "请在系统设置中允许安装未知应用", Toast.LENGTH_LONG).show();
            }
        }
    }

    private static void showError(final Activity activity, String title, String message) {
        if (activity.isFinishing()) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(title)
                .setMessage(message == null ? "未知错误" : message)
                .setPositiveButton("知道了", null)
                .setNeutralButton("打开下载页", (d, w) -> openReleasePage(activity))
                .show();
    }

    private static void openReleasePage(Activity activity) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASE_URL)));
        } catch (Exception e) {
            Toast.makeText(activity, "没有可用的浏览器", Toast.LENGTH_SHORT).show();
        }
    }

    public static String currentVersion(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return info.versionName == null ? "" : info.versionName;
        } catch (Exception e) {
            return "";
        }
    }

    private static long currentVersionCode(Context context) {
        try {
            PackageInfo info = context.getPackageManager()
                    .getPackageInfo(context.getPackageName(), 0);
            return Build.VERSION.SDK_INT >= 28 ? info.getLongVersionCode() : info.versionCode;
        } catch (Exception e) {
            return 0;
        }
    }

    private static String cleanVersion(String raw) {
        if (raw == null) {
            return "";
        }
        String v = raw.trim();
        if (v.startsWith("v") || v.startsWith("V")) {
            v = v.substring(1);
        }
        int space = v.indexOf(' ');
        if (space > 0) {
            v = v.substring(0, space);
        }
        return v;
    }

    private static int compareVersion(String a, String b) {
        String[] x = cleanVersion(a).split("[^0-9]+");
        String[] y = cleanVersion(b).split("[^0-9]+");
        int n = Math.max(x.length, y.length);
        for (int i = 0; i < n; i++) {
            int xi = i < x.length ? parseInt(x[i]) : 0;
            int yi = i < y.length ? parseInt(y[i]) : 0;
            if (xi != yi) {
                return xi > yi ? 1 : -1;
            }
        }
        return 0;
    }

    private static int parseInt(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static String formatSize(long bytes) {
        if (bytes >= 1024L * 1024L) {
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0));
        }
        if (bytes >= 1024L) {
            return String.format(Locale.US, "%.0f KB", bytes / 1024.0);
        }
        return bytes + " B";
    }

    private static String friendlyNetworkError(Exception e) {
        String msg = e == null ? "" : String.valueOf(e.getMessage());
        if (msg.contains("HTTP 403")) {
            return "GitHub 接口暂时不可用，可能触发了访问频率限制，请稍后再试。";
        }
        if (msg.contains("HTTP 404")) {
            return "没有找到 Release，请确认仓库已发布版本。";
        }
        if (msg.toLowerCase(Locale.US).contains("timeout")) {
            return "连接 GitHub 超时，请检查网络后重试。";
        }
        if (msg.isEmpty()) {
            return "网络请求失败，请检查网络后重试。";
        }
        return msg;
    }

    private static String readText(InputStream in) {
        if (in == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, "UTF-8"));
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) > 0) {
                sb.append(buf, 0, n);
            }
            reader.close();
        } catch (Exception ignored) {
        }
        return sb.toString();
    }

    private static String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format(Locale.US, "%02x", b & 0xff));
        }
        return sb.toString();
    }
}
