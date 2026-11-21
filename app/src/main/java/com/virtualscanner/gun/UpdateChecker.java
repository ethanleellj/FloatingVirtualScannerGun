package com.virtualscanner.gun;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * GitHub Releases 更新检查 / 下载 / 安装。
 *
 * <p>设计约束：
 * <ul>
 *   <li>只在用户主动点「检查更新」或每天首次启动时联网，失败一律静默降级，不影响扫码主流程；</li>
 *   <li>所有网络操作在后台线程，结果回到 UI 线程再弹窗；</li>
 *   <li>下载到应用私有 cache，安装走 FileProvider；没有「未知来源」权限时先引到系统设置，
 *       已下载的 APK 记在 Prefs 里，用户授权回来后还能接着装。</li>
 * </ul>
 */
public final class UpdateChecker {

    private static final String TAG = "VSG-Update";
    private static final String API =
            "https://api.github.com/repos/ethanleellj/FloatingVirtualScannerGun/releases/latest";
    private static final String FALLBACK_PAGE =
            "https://github.com/ethanleellj/FloatingVirtualScannerGun/releases";
    private static final long CHECK_INTERVAL_MS = 24L * 60 * 60 * 1000;

    private static final ExecutorService IO = Executors.newSingleThreadExecutor();
    private static volatile boolean autoCheckedThisProcess;

    private UpdateChecker() {
    }

    /** 启动时调用：默认开、距上次检查超过一天才查，一个进程内只查一次。 */
    public static void maybeAutoCheck(Activity activity) {
        if (autoCheckedThisProcess) {
            return;
        }
        autoCheckedThisProcess = true;
        // 已经装上新版了就别再留着上一次的待安装提示
        String pendingVersion = Prefs.pendingVersion(activity);
        if (!pendingVersion.isEmpty()
                && Versions.compare(pendingVersion, BuildConfig.VERSION_NAME) <= 0) {
            String path = Prefs.pendingApk(activity);
            if (!path.isEmpty()) {
                new File(path).delete();
            }
            Prefs.clearPendingUpdate(activity);
        }
        if (!Prefs.autoUpdate(activity)) {
            return;
        }
        if (System.currentTimeMillis() - Prefs.lastUpdateCheckMs(activity) < CHECK_INTERVAL_MS) {
            return;
        }
        Prefs.setLastUpdateCheckMs(activity, System.currentTimeMillis());
        check(activity, true);
    }

    /**
     * @param silent true = 启动时的自动检查：只有发现新版本才提示，失败与"已是最新"都不打扰用户
     */
    public static void check(Activity activity, boolean silent) {
        // 上次下载完但还没授权安装：回来后接着走，不必重新下载一遍
        String pending = Prefs.pendingApk(activity);
        if (!silent && pending != null && new File(pending).exists()) {
            if (canInstall(activity)) {
                confirmInstall(activity, new File(pending), Prefs.pendingVersion(activity));
            } else {
                askInstallPermission(activity);
            }
            return;
        }

        if (!silent) {
            toast(activity, activity.getString(R.string.update_checking), false);
        }
        Log.d(TAG, "check start, silent=" + silent);
        IO.execute(() -> {
            String body = null;
            String error = null;
            boolean noRelease = false;
            HttpURLConnection conn = null;
            try {
                conn = (HttpURLConnection) new URL(API).openConnection();
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(12000);
                conn.setRequestProperty("Accept", "application/vnd.github+json");
                conn.setRequestProperty("User-Agent", "FloatingVirtualScannerGun-Update");
                int code = conn.getResponseCode();
                if (code == 200) {
                    body = readText(conn.getInputStream(), 200_000);
                } else if (code == 404) {
                    // 仓库还没发过 Release，不是故障
                    noRelease = true;
                } else {
                    error = "HTTP " + code;
                }
                Log.d(TAG, "check http=" + code + " noRelease=" + noRelease);
            } catch (Exception e) {
                error = message(e);
                Log.w(TAG, "check failed: " + error);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final String json = body;
            final String failure = error;
            final boolean empty = noRelease;
            ui(activity, () -> {
                if (finishing(activity)) {
                    return;
                }
                if (empty) {
                    if (!silent) {
                        toast(activity, activity.getString(R.string.update_no_release), false);
                    }
                    return;
                }
                if (json == null) {
                    if (!silent) {
                        toast(activity, activity.getString(R.string.update_error, failure), false);
                    }
                    return;
                }
                try {
                    JSONObject release = new JSONObject(json);
                    String tag = release.optString("tag_name", "");
                    String notes = release.optString("body", "");
                    String page = release.optString("html_url", FALLBACK_PAGE);
                    String apkUrl = null;
                    JSONArray assets = release.optJSONArray("assets");
                    if (assets != null) {
                        for (int i = 0; i < assets.length(); i++) {
                            JSONObject asset = assets.getJSONObject(i);
                            String name = asset.optString("name", "");
                            if (name.toLowerCase(Locale.US).endsWith(".apk")) {
                                apkUrl = asset.optString("browser_download_url", "");
                                break;
                            }
                        }
                    }
                    Log.d(TAG, "release tag=" + tag + " local=" + BuildConfig.VERSION_NAME
                            + " compare=" + Versions.compare(tag, BuildConfig.VERSION_NAME));
                    if (Versions.compare(tag, BuildConfig.VERSION_NAME) <= 0) {
                        if (!silent) {
                            toast(activity, activity.getString(R.string.update_latest), false);
                        }
                        return;
                    }
                    showFound(activity, tag, notes, apkUrl, page);
                } catch (Exception e) {
                    if (!silent) {
                        toast(activity, activity.getString(R.string.update_error, message(e)), false);
                    }
                }
            });
        });
    }

    private static void showFound(Activity a, String tag, String notes, String apkUrl, String page) {
        String detail = notes == null ? "" : notes.trim();
        if (detail.length() > 500) {
            detail = detail.substring(0, 500) + "…";
        }
        AlertDialog.Builder b = new AlertDialog.Builder(a)
                .setTitle(a.getString(R.string.update_found_title, tag))
                .setMessage(a.getString(R.string.update_found_msg, tag, detail))
                .setNegativeButton(android.R.string.cancel, null);
        if (apkUrl == null || apkUrl.isEmpty()) {
            b.setPositiveButton(R.string.update_open_page, (d, w) -> openPage(a, page));
        } else {
            b.setPositiveButton(R.string.update_download, (d, w) -> download(a, apkUrl, tag));
            b.setNeutralButton(R.string.update_open_page, (d, w) -> openPage(a, page));
        }
        b.show();
    }

    private static void download(Activity activity, String url, String version) {
        ProgressDialog progress = new ProgressDialog(activity);
        progress.setCancelable(false);
        progress.setMessage(activity.getString(R.string.update_downloading));
        progress.show();

        IO.execute(() -> {
            File saved = null;
            String error = null;
            HttpURLConnection conn = null;
            try {
                File dir = new File(activity.getCacheDir(), "updates");
                if (!dir.exists() && !dir.mkdirs()) {
                    throw new IllegalStateException("cannot create update dir");
                }
                File target = new File(dir, "vsg-" + safe(version) + ".apk");
                conn = (HttpURLConnection) new URL(url).openConnection();
                conn.setInstanceFollowRedirects(true);
                conn.setConnectTimeout(8000);
                conn.setReadTimeout(20000);
                conn.setRequestProperty("User-Agent", "FloatingVirtualScannerGun-Update");
                int code = conn.getResponseCode();
                if (code != 200) {
                    throw new IllegalStateException("HTTP " + code);
                }
                int total = conn.getContentLength();
                final boolean known = total > 0;
                ui(activity, () -> {
                    if (!finishing(activity)) {
                        progress.setIndeterminate(!known);
                        progress.setMax(100);
                    }
                });
                try (InputStream in = conn.getInputStream();
                     FileOutputStream out = new FileOutputStream(target)) {
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    int done = 0;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        done += n;
                        int percent = total > 0 ? (int) (done * 100L / total) : 0;
                        final int p = percent;
                        ui(activity, () -> {
                            if (!finishing(activity)) {
                                progress.setProgress(p);
                            }
                        });
                    }
                }
                if (target.length() < 100_000) {
                    throw new IllegalStateException("apk too small");
                }
                saved = target;
            } catch (Exception e) {
                error = message(e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }
            }
            final File result = saved;
            final String failure = error;
            ui(activity, () -> {
                if (finishing(activity)) {
                    return;
                }
                if (progress.isShowing()) {
                    progress.dismiss();
                }
                if (result == null) {
                    toast(activity, activity.getString(R.string.update_download_failed, failure), false);
                    return;
                }
                Prefs.setPendingUpdate(activity, result.getAbsolutePath(), version);
                if (canInstall(activity)) {
                    confirmInstall(activity, result, version);
                } else {
                    askInstallPermission(activity);
                }
            });
        });
    }

    private static void confirmInstall(Activity activity, File apk, String version) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_ready_title)
                .setMessage(activity.getString(R.string.update_ready_msg, version))
                .setPositiveButton(R.string.update_install, (d, w) -> install(activity, apk))
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void install(Activity activity, File apk) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                    activity.getPackageName() + ".fileprovider", apk);
            Intent intent = new Intent(Intent.ACTION_INSTALL_PACKAGE);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.putExtra(Intent.EXTRA_NOT_UNKNOWN_SOURCE, true);
            activity.startActivity(intent);
        } catch (Exception e) {
            toast(activity, activity.getString(R.string.update_install_failed, message(e)), false);
        }
    }

    private static void askInstallPermission(Activity activity) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.update_permission_title)
                .setMessage(R.string.update_permission_msg)
                .setPositiveButton(R.string.update_open_settings, (d, w) -> {
                    try {
                        Intent intent = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(intent);
                    } catch (ActivityNotFoundException e) {
                        activity.startActivity(new Intent(Settings.ACTION_SECURITY_SETTINGS));
                    }
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
    }

    private static void openPage(Activity activity, String page) {
        try {
            activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(page)));
        } catch (ActivityNotFoundException e) {
            toast(activity, activity.getString(R.string.update_no_browser), false);
        }
    }

    private static boolean canInstall(Context ctx) {
        return ctx.getPackageManager().canRequestPackageInstalls();
    }

    private static String safe(String v) {
        return v.replaceAll("[^A-Za-z0-9._-]", "_");
    }

    private static String readText(InputStream in, int limit) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > limit) {
                throw new IllegalStateException("release json too large");
            }
        }
        return out.toString("UTF-8");
    }

    private static String message(Exception e) {
        String s = e.getMessage();
        return s == null || s.isEmpty() ? e.getClass().getSimpleName() : s;
    }

    private static void ui(Activity activity, Runnable r) {
        activity.runOnUiThread(r);
    }

    private static boolean finishing(Activity activity) {
        return activity.isFinishing() || activity.isDestroyed();
    }

    private static void toast(Activity activity, String text, boolean silent) {
        if (silent || finishing(activity)) {
            return;
        }
        Toast.makeText(activity, text, Toast.LENGTH_SHORT).show();
    }
}
