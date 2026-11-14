package com.virtualscanner.gun;

import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Bundle;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

/**
 * 关于：版本、作者与联系方式、项目说明入口。
 *
 * <p>刻意保持短——这一页回答"这是什么、谁做的、怎么联系"。
 * 权限说明、隐私政策、开源组件在帮助页；完整的项目文档在
 * {@link ReadmeActivity}（README 全文，打包进 App，离线可看）。
 */
public class AboutActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_about);
        Ui.edgeToEdge(this);

        TextView tvVersion = findViewById(R.id.tvVersion);
        ApplicationInfo app = getApplicationInfo();
        String version = "?";
        long versionCode = 0;
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), 0);
            version = info.versionName;
            versionCode = info.getLongVersionCode();
        } catch (Throwable ignored) {
            // 取不到版本号不影响这一页的其他内容
        }
        tvVersion.setText(getString(R.string.about_version,
                version, versionCode, app.targetSdkVersion, app.minSdkVersion));

        // 编译时间：来自 build.gradle 注入的 BuildConfig.BUILD_TIME（毫秒时间戳）
        TextView tvBuildTime = findViewById(R.id.tvBuildTime);
        java.text.SimpleDateFormat fmt =
                new java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.getDefault());
        tvBuildTime.setText(getString(R.string.about_build_time,
                fmt.format(new java.util.Date(BuildConfig.BUILD_TIME))));

        // 点邮箱直接发信。没装邮件客户端时不能静默失败：把地址亮出来并放进剪贴板
        TextView tvEmail = findViewById(R.id.tvEmail);
        tvEmail.setOnClickListener(v -> mail());

        findViewById(R.id.btnReadme).setOnClickListener(
                v -> startActivity(new Intent(this, ReadmeActivity.class)));
    }

    private void mail() {
        String address = getString(R.string.about_email);
        Intent intent = new Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:" + address));
        intent.putExtra(Intent.EXTRA_SUBJECT, getString(R.string.about_mail_subject));
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            ClipboardManager cm = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cm != null) {
                cm.setPrimaryClip(ClipData.newPlainText("email", address));
            }
            new AlertDialog.Builder(this)
                    .setTitle(R.string.about_email_no_app_title)
                    .setMessage(getString(R.string.about_email_no_app, address))
                    .setPositiveButton(android.R.string.ok, null)
                    .show();
        }
    }
}
