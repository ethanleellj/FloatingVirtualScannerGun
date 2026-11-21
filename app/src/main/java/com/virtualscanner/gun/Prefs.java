package com.virtualscanner.gun;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 全局配置读写。
 *
 * 前后缀/回车/去重现在归属于"当前配置档"（见 {@link Profiles}），
 * 这里保留同名入口，调用方不必关心存储结构；摄像头选择仍是全局的，
 * 换配置档不该把镜头也换掉。
 */
public final class Prefs {

    private static final String FILE = "vsg_prefs";

    /** 仅用于旧版本数据迁移，运行期读写请走 {@link Profiles} */
    public static final String KEY_PREFIX = "prefix";
    public static final String KEY_SUFFIX = "suffix";
    public static final String KEY_ENTER = "enter";
    public static final String KEY_DEDUP = "dedup_ms";
    public static final String KEY_CAMERA_ID = "camera_id";
    /** 一帧里出现多个条码时的采信策略，见 {@link BarcodePick} */
    public static final String KEY_MULTI_CODE = "multi_code_policy";
    /** 扫码成功后是否播放提示音 */
    public static final String KEY_SOUND = "sound_on_success";
    /** 采信哪类码：一维条码 / 二维码 / 全部，见 {@link CodeTypes} */
    public static final String KEY_CODE_TYPE = "code_type";
    /** 网络转发的目标主机与端口（全局设置：换配置档通常不该换电脑） */
    public static final String KEY_NET_HOST = "net_host";
    public static final String KEY_NET_PORT = "net_port";
    /** 蓝牙 HID 键盘要连的那台主机的 MAC（全局设置） */
    public static final String KEY_HID_MAC = "hid_mac";

    /** 「自检 / Test」按钮发送的内容：现场排障时经常要换成对方系统能接受的样例 */
    public static final String KEY_TEST_TEXT = "test_text";
    /** 自动更新：开 / 上次检查时间 / 已下载待安装的 APK 与版本 */
    public static final String KEY_AUTO_UPDATE = "auto_update";
    public static final String KEY_LAST_UPDATE_CHECK = "last_update_check_ms";
    public static final String KEY_PENDING_APK = "pending_update_apk";
    public static final String KEY_PENDING_VERSION = "pending_update_version";
    /** 悬浮球/面板共用的位置（屏幕坐标 px）；不存 dp，WindowManager 的 x/y 本身就是 px */
    public static final String KEY_FLOAT_X = "float_x";
    public static final String KEY_FLOAT_Y = "float_y";
    /** 首次启动时的出厂位置，与原来的写死值一致 */
    public static final int DEFAULT_FLOAT_X = 24;
    public static final int DEFAULT_FLOAT_Y = 360;
    /** 测试按钮的出厂内容；改这里等于改默认值，不要动旧键名 */
    public static final String DEFAULT_TEST_TEXT = "TEST123";

    private Prefs() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static String prefix(Context ctx) {
        return Profiles.current(ctx).prefix;
    }

    public static String suffix(Context ctx) {
        return Profiles.current(ctx).suffix;
    }

    public static boolean enter(Context ctx) {
        return Profiles.current(ctx).enter;
    }

    public static int dedupMs(Context ctx) {
        return Profiles.current(ctx).dedupMs;
    }

    public static boolean appendMode(Context ctx) {
        return Profiles.current(ctx).appendMode;
    }

    public static boolean ignoreDup(Context ctx) {
        return Profiles.current(ctx).ignoreDup;
    }

    /** 修改当前配置档的字段并立即落盘（服务与界面读的是同一份）。 */
    public static void updateCurrent(Context ctx, String prefix, String suffix,
                                     boolean enter, int dedupMs) {
        Profile p = Profiles.current(ctx).copy();
        if (prefix != null) {
            p.prefix = prefix;
        }
        if (suffix != null) {
            p.suffix = suffix;
        }
        p.enter = enter;
        if (dedupMs > 0) {
            p.dedupMs = dedupMs;
        }
        Profiles.save(ctx, p);
    }

    /** 追加/覆盖、忽略重复两个开关，单独落盘。 */
    public static void setAppendMode(Context ctx, boolean append) {
        Profile p = Profiles.current(ctx).copy();
        p.appendMode = append;
        Profiles.save(ctx, p);
    }

    public static void setIgnoreDup(Context ctx, boolean ignore) {
        Profile p = Profiles.current(ctx).copy();
        p.ignoreDup = ignore;
        Profiles.save(ctx, p);
    }

    public static void setChannel(Context ctx, int channel) {
        Profile p = Profiles.current(ctx).copy();
        p.channel = channel;
        Profiles.save(ctx, p);
    }

    public static void setModalPolicy(Context ctx, int policy) {
        Profile p = Profiles.current(ctx).copy();
        p.modalPolicy = policy;
        Profiles.save(ctx, p);
    }

    /** 识别成功注入后是否自动折叠面板；归属配置档，默认开。 */
    public static boolean autoCollapse(Context ctx) {
        return Profiles.current(ctx).autoCollapse;
    }

    public static void setAutoCollapse(Context ctx, boolean enabled) {
        Profile p = Profiles.current(ctx).copy();
        p.autoCollapse = enabled;
        Profiles.save(ctx, p);
    }

    /** 选定的摄像头 cameraId；空串 = 系统默认后置。 */
    public static String cameraId(Context ctx) {
        return sp(ctx).getString(KEY_CAMERA_ID, Cameras.DEFAULT);
    }

    public static void setCameraId(Context ctx, String cameraId) {
        sp(ctx).edit().putString(KEY_CAMERA_ID, cameraId == null ? Cameras.DEFAULT : cameraId)
                .apply();
    }

    /** 多码同框策略，默认"只取第一个"。 */
    public static int multiCodePolicy(Context ctx) {
        return sp(ctx).getInt(KEY_MULTI_CODE, BarcodePick.POLICY_FIRST);
    }

    public static void setMultiCodePolicy(Context ctx, int policy) {
        sp(ctx).edit().putInt(KEY_MULTI_CODE, policy).apply();
    }

    /** 扫码成功提示音，默认开。 */
    public static boolean soundEnabled(Context ctx) {
        return sp(ctx).getBoolean(KEY_SOUND, true);
    }

    public static void setSoundEnabled(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_SOUND, enabled).apply();
    }

    /** 采信哪类码，默认"全部"。 */
    public static int codeType(Context ctx) {
        return CodeTypes.normalize(sp(ctx).getInt(KEY_CODE_TYPE, CodeTypes.ALL));
    }

    public static void setCodeType(Context ctx, int type) {
        sp(ctx).edit().putInt(KEY_CODE_TYPE, CodeTypes.normalize(type)).apply();
    }

    /**
     * 「自检 / Test」按钮发送的内容。空白时回落到出厂值——按钮点了却什么都不发，
     * 现场会被误判成"注入链路坏了"。
     */
    public static String testText(Context ctx) {
        String s = sp(ctx).getString(KEY_TEST_TEXT, DEFAULT_TEST_TEXT);
        if (s == null || s.trim().isEmpty()) {
            return DEFAULT_TEST_TEXT;
        }
        return s;
    }

    public static String netHost(Context ctx) {
        String s = sp(ctx).getString(KEY_NET_HOST, "");
        return s == null ? "" : s.trim();
    }

    public static int netPort(Context ctx) {
        return sp(ctx).getInt(KEY_NET_PORT, 9100);
    }

    public static void setNet(Context ctx, String host, int port) {
        sp(ctx).edit()
                .putString(KEY_NET_HOST, host == null ? "" : host.trim())
                .putInt(KEY_NET_PORT, port)
                .apply();
    }

    public static String hidMac(Context ctx) {
        String s = sp(ctx).getString(KEY_HID_MAC, "");
        return s == null ? "" : s;
    }

    public static void setHidMac(Context ctx, String mac) {
        sp(ctx).edit().putString(KEY_HID_MAC, mac == null ? "" : mac).apply();
    }

    /** 传空串/纯空白等于恢复出厂值（按钮永远有内容可发）。 */
    public static void setTestText(Context ctx, String text) {
        sp(ctx).edit().putString(KEY_TEST_TEXT,
                text == null ? "" : text).apply();
    }

    public static boolean autoUpdate(Context ctx) {
        return sp(ctx).getBoolean(KEY_AUTO_UPDATE, true);
    }

    public static void setAutoUpdate(Context ctx, boolean enabled) {
        sp(ctx).edit().putBoolean(KEY_AUTO_UPDATE, enabled).apply();
    }

    public static long lastUpdateCheckMs(Context ctx) {
        return sp(ctx).getLong(KEY_LAST_UPDATE_CHECK, 0L);
    }

    public static void setLastUpdateCheckMs(Context ctx, long ms) {
        sp(ctx).edit().putLong(KEY_LAST_UPDATE_CHECK, ms).apply();
    }

    /** 下载完但还没安装成功的 APK：授权「未知来源」回来后接着装。 */
    public static String pendingApk(Context ctx) {
        String s = sp(ctx).getString(KEY_PENDING_APK, "");
        return s == null ? "" : s;
    }

    public static String pendingVersion(Context ctx) {
        String s = sp(ctx).getString(KEY_PENDING_VERSION, "");
        return s == null ? "" : s;
    }

    public static void setPendingUpdate(Context ctx, String path, String version) {
        sp(ctx).edit()
                .putString(KEY_PENDING_APK, path == null ? "" : path)
                .putString(KEY_PENDING_VERSION, version == null ? "" : version)
                .apply();
    }

    public static void clearPendingUpdate(Context ctx) {
        setPendingUpdate(ctx, "", "");
    }

    /** 上次退出时悬浮球/面板的位置；未设置过则回到出厂位置。 */
    public static int floatX(Context ctx) {
        return sp(ctx).getInt(KEY_FLOAT_X, DEFAULT_FLOAT_X);
    }

    public static int floatY(Context ctx) {
        return sp(ctx).getInt(KEY_FLOAT_Y, DEFAULT_FLOAT_Y);
    }

    public static void setFloatPosition(Context ctx, int x, int y) {
        sp(ctx).edit().putInt(KEY_FLOAT_X, x).putInt(KEY_FLOAT_Y, y).apply();
    }
}
