package com.virtualscanner.gun;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothHidDevice;
import android.bluetooth.BluetoothHidDeviceAppSdpSettings;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;
import android.util.Log;

import androidx.annotation.RequiresApi;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * V2：把手机当成一台蓝牙键盘，直接"敲"进电脑。
 *
 * <p>和网络转发的区别是电脑侧不用装任何程序——配对一次之后，电脑把它当成一把普通键盘。
 * 代价是必须先注册 HID Device 这个 Profile（{@link BluetoothHidDevice}，API 28 起），
 * 而且只能打<b>美式键盘布局上打得出来的字符</b>：中文、全角标点、生僻符号没有对应的
 * HID usage，只能放弃并明确报错，不能发一半留一半。
 *
 * <p>类体本身刻意<b>不出现</b> API 28 的类型：Android 8.x 上加载这个类不能因为
 * {@code BluetoothHidDevice} 不存在就抛 {@code NoClassDefFoundError}。
 * 真正碰蓝牙的是内嵌的 {@link Impl}，只有系统版本够了才会被加载。
 */
public final class HidKeyboard {

    /** 注册回调、连接、发报文各自的时间上限。超时都要报失败，不能让扫码卡住 */
    private static final int REGISTER_TIMEOUT_MS = 2500;
    private static final int CONNECT_TIMEOUT_MS = 4000;
    /** 每个字符"按下 → 松开"之后的间隔。低于 8ms 时 Windows 会丢字符 */
    private static final int KEY_GAP_MS = 12;

    private static final int LEFT_SHIFT = 0x02;
    private static final int KEY_ENTER = 0x28;

    public static final class Result {
        public final boolean ok;
        /** 失败时的文案资源；0 表示用调用方的通用文案 */
        public final int errorRes;
        /** 给日志和界面看的细节（异常信息、打不出来的那个字符等） */
        public final String detail;

        Result(boolean ok, int errorRes, String detail) {
            this.ok = ok;
            this.errorRes = errorRes;
            this.detail = detail;
        }
    }

    private HidKeyboard() {
    }

    /**
     * 纯阻塞调用，只能在后台线程里用（注入线程本身就是后台）。
     *
     * @param text  要打的文本，不能含非 ASCII 可打印字符
     * @param enter 打完是否补一个回车
     */
    public static Result send(Context ctx, String text, boolean enter) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            return fail(R.string.inject_err_hid_api, "API " + Build.VERSION.SDK_INT);
        }
        if (!hasPermission(ctx)) {
            return fail(R.string.inject_err_hid_perm, null);
        }
        // 先整串预检字符集：打不出来的字符要提前拒绝，不能发一半让电脑上留下半截条码
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (keyFor(c) == null) {
                return fail(R.string.inject_err_hid_charset,
                        "unsupported char U+" + Integer.toHexString(c));
            }
        }
        return Impl.send(ctx, text, enter);
    }

    static Result fail(int res, String detail) {
        Log.w("VSG-Hid", "hid -> ok=false res=" + res + " detail=" + detail);
        return new Result(false, res, detail);
    }

    public static boolean hasPermission(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
            return true;
        }
        return ctx.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * 字符 → (修饰键, HID usage)，按美式布局。
     *
     * @return 打不出来时返回 null
     */
    static int[] keyFor(char c) {
        if (c >= 'a' && c <= 'z') {
            return new int[]{0, 0x04 + (c - 'a')};
        }
        if (c >= 'A' && c <= 'Z') {
            return new int[]{LEFT_SHIFT, 0x04 + (c - 'A')};
        }
        if (c >= '1' && c <= '9') {
            return new int[]{0, 0x1E + (c - '1')};
        }
        if (c == '0') {
            return new int[]{0, 0x27};
        }
        switch (c) {
            case '\n':
            case '\r':
                return new int[]{0, KEY_ENTER};
            case '\t':
                return new int[]{0, 0x2B};
            case ' ':
                return new int[]{0, 0x2C};
            case '-':
                return new int[]{0, 0x2D};
            case '_':
                return new int[]{LEFT_SHIFT, 0x2D};
            case '=':
                return new int[]{0, 0x2E};
            case '+':
                return new int[]{LEFT_SHIFT, 0x2E};
            case '[':
                return new int[]{0, 0x2F};
            case '{':
                return new int[]{LEFT_SHIFT, 0x2F};
            case ']':
                return new int[]{0, 0x30};
            case '}':
                return new int[]{LEFT_SHIFT, 0x30};
            case '\\':
                return new int[]{0, 0x31};
            case '|':
                return new int[]{LEFT_SHIFT, 0x31};
            case ';':
                return new int[]{0, 0x33};
            case ':':
                return new int[]{LEFT_SHIFT, 0x33};
            case '\'':
                return new int[]{0, 0x34};
            case '"':
                return new int[]{LEFT_SHIFT, 0x34};
            case '`':
                return new int[]{0, 0x35};
            case '~':
                return new int[]{LEFT_SHIFT, 0x35};
            case ',':
                return new int[]{0, 0x36};
            case '<':
                return new int[]{LEFT_SHIFT, 0x36};
            case '.':
                return new int[]{0, 0x37};
            case '>':
                return new int[]{LEFT_SHIFT, 0x37};
            case '/':
                return new int[]{0, 0x38};
            case '?':
                return new int[]{LEFT_SHIFT, 0x38};
            case '!':
                return new int[]{LEFT_SHIFT, 0x1E};
            case '@':
                return new int[]{LEFT_SHIFT, 0x1F};
            case '#':
                return new int[]{LEFT_SHIFT, 0x20};
            case '$':
                return new int[]{LEFT_SHIFT, 0x21};
            case '%':
                return new int[]{LEFT_SHIFT, 0x22};
            case '^':
                return new int[]{LEFT_SHIFT, 0x23};
            case '&':
                return new int[]{LEFT_SHIFT, 0x24};
            case '*':
                return new int[]{LEFT_SHIFT, 0x25};
            case '(':
                return new int[]{LEFT_SHIFT, 0x26};
            case ')':
                return new int[]{LEFT_SHIFT, 0x27};
            default:
                return null;
        }
    }

    static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 真正碰 {@code BluetoothHidDevice} 的部分。
     *
     * <p>注册与连接都很慢（秒级），所以缓存在静态字段里：扫码是一串一串来的，
     * 每条都重新注册会把连接窗口开开关关，电脑那边会不停弹"键盘已连接"。
     */
    @RequiresApi(Build.VERSION_CODES.P)
    private static final class Impl {

        /** 标准 Boot Keyboard 报文：1 字节修饰键 + 1 字节保留 + 6 字节按键数组 */
        private static final byte[] REPORT_DESC = {
                0x05, 0x01,        // Usage Page (Generic Desktop)
                0x09, 0x06,        // Usage (Keyboard)
                (byte) 0xA1, 0x01, // Collection (Application)
                0x05, 0x07,        //   Usage Page (Key Codes)
                0x19, (byte) 0xE0, //   Usage Minimum (224)
                0x29, (byte) 0xE7, //   Usage Maximum (231)
                0x15, 0x00,        //   Logical Minimum (0)
                0x25, 0x01,        //   Logical Maximum (1)
                0x75, 0x01,        //   Report Size (1)
                (byte) 0x95, 0x08,        //   Report Count (8)
                (byte) 0x81, 0x02,        //   Input (Data, Variable, Absolute) —— 修饰键字节
                (byte) 0x95, 0x01,        //   Report Count (1)
                0x75, 0x08,        //   Report Size (8)
                (byte) 0x81, 0x01,        //   Input (Constant) —— 保留字节
                (byte) 0x95, 0x06,        //   Report Count (6)
                0x75, 0x08,        //   Report Size (8)
                0x15, 0x00,        //   Logical Minimum (0)
                0x25, 0x65,        //   Logical Maximum (101)
                0x05, 0x07,        //   Usage Page (Key Codes)
                0x19, 0x00,        //   Usage Minimum (0)
                0x29, 0x65,        //   Usage Maximum (101)
                (byte) 0x81, 0x00,        //   Input (Data, Array) —— 6 字节按键数组
                (byte) 0xC0        // End Collection
        };

        private static final Object LOCK = new Object();
        private static BluetoothHidDevice sHid;
        private static BluetoothHidDevice.Callback sCallback;
        private static volatile boolean sRegistered;
        private static volatile String sConnectedMac;
        private static volatile CountDownLatch sConnectLatch;

        private Impl() {
        }

        static Result send(Context ctx, String text, boolean enter) {
            BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
            if (adapter == null) {
                return fail(R.string.inject_err_hid_off, "no adapter");
            }
            if (!adapter.isEnabled()) {
                return fail(R.string.inject_err_hid_off, "adapter off");
            }
            String mac = Prefs.hidMac(ctx);
            if (mac == null || mac.trim().isEmpty()) {
                return fail(R.string.inject_err_hid_unset, "hid_mac empty");
            }
            BluetoothDevice host;
            try {
                host = adapter.getRemoteDevice(mac.trim());
            } catch (IllegalArgumentException e) {
                return fail(R.string.inject_err_hid_unset, "bad mac " + mac);
            }

            BluetoothHidDevice hid = ensureProxy(ctx, adapter);
            if (hid == null) {
                return fail(R.string.inject_err_hid, "HID_DEVICE proxy unavailable");
            }
            if (!ensureRegistered(hid, ctx)) {
                return fail(R.string.inject_err_hid, "registerApp failed");
            }
            if (!ensureConnected(hid, host)) {
                return fail(R.string.inject_err_hid, "connect to " + mac + " failed");
            }

            int sent = 0;
            for (int i = 0; i < text.length(); i++) {
                int[] key = keyFor(text.charAt(i));
                if (!typeKey(hid, host, key[0], key[1])) {
                    return fail(R.string.inject_err_hid, "sendReport failed at char " + sent);
                }
                sent++;
            }
            if (enter && !typeKey(hid, host, 0, KEY_ENTER)) {
                return fail(R.string.inject_err_hid, "sendReport failed on enter");
            }

            Log.i("VSG-Hid", "hid -> ok=true host=" + mac + " chars=" + sent + " enter=" + enter);
            return new Result(true, 0, null);
        }

        private static BluetoothHidDevice ensureProxy(Context ctx, BluetoothAdapter adapter) {
            synchronized (LOCK) {
                if (sHid != null) {
                    return sHid;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                final BluetoothHidDevice[] holder = new BluetoothHidDevice[1];
                boolean requested = adapter.getProfileProxy(ctx,
                        new BluetoothProfile.ServiceListener() {
                            @Override
                            public void onServiceConnected(int profile, BluetoothProfile proxy) {
                                if (proxy instanceof BluetoothHidDevice) {
                                    holder[0] = (BluetoothHidDevice) proxy;
                                }
                                latch.countDown();
                            }

                            @Override
                            public void onServiceDisconnected(int profile) {
                                // 蓝牙关掉再打开会走到这里。缓存一并作废，下次重新取
                                sHid = null;
                                sRegistered = false;
                                sConnectedMac = null;
                            }
                        }, BluetoothProfile.HID_DEVICE);
                if (!requested) {
                    return null;
                }
                try {
                    if (!latch.await(REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        return null;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return null;
                }
                sHid = holder[0];
                return sHid;
            }
        }

        /** 注册 HID 应用：只有注册过，配对的那台电脑才会把手机认成键盘。 */
        private static boolean ensureRegistered(BluetoothHidDevice hid, Context ctx) {
            synchronized (LOCK) {
                if (sRegistered) {
                    return true;
                }
                final CountDownLatch latch = new CountDownLatch(1);
                sCallback = new BluetoothHidDevice.Callback() {
                    @Override
                    public void onAppStatusChanged(BluetoothDevice pluggedDevice, boolean registered) {
                        sRegistered = registered;
                        latch.countDown();
                    }

                    @Override
                    public void onConnectionStateChanged(BluetoothDevice device, int state) {
                        String addr = device == null ? null : device.getAddress();
                        if (state == BluetoothProfile.STATE_CONNECTED) {
                            sConnectedMac = addr;
                        } else if (state == BluetoothProfile.STATE_DISCONNECTED) {
                            if (addr != null && addr.equals(sConnectedMac)) {
                                sConnectedMac = null;
                            }
                        }
                        CountDownLatch waiting = sConnectLatch;
                        if (waiting != null) {
                            waiting.countDown();
                        }
                    }
                };
                BluetoothHidDeviceAppSdpSettings sdp = new BluetoothHidDeviceAppSdpSettings(
                        "Virtual Scanner Gun",
                        "Floating Virtual Scanner Gun",
                        "VirtualScannerGun",
                        BluetoothHidDevice.SUBCLASS1_KEYBOARD,
                        REPORT_DESC);
                if (!hid.registerApp(sdp, null, null, ctx.getMainExecutor(), sCallback)) {
                    return false;
                }
                try {
                    if (!latch.await(REGISTER_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                        return false;
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
                return sRegistered;
            }
        }

        private static boolean ensureConnected(BluetoothHidDevice hid, BluetoothDevice host) {
            String addr = host.getAddress();
            if (addr.equals(sConnectedMac)
                    && hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTED) {
                return true;
            }
            sConnectLatch = new CountDownLatch(1);
            try {
                if (!hid.connect(host)) {
                    // 电脑刚睡醒 / 刚断开时 connect 会先返回 false，等一会儿再试一次
                    sleep(300);
                    if (!hid.connect(host)) {
                        return false;
                    }
                }
                sConnectLatch.await(CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            } finally {
                sConnectLatch = null;
            }
            return addr.equals(sConnectedMac)
                    && hid.getConnectionState(host) == BluetoothProfile.STATE_CONNECTED;
        }

        /** 按下 + 松开。两个报文都要发，只发按下会让电脑以为这个键一直按着。 */
        private static boolean typeKey(BluetoothHidDevice hid, BluetoothDevice host,
                                       int modifier, int usage) {
            byte[] press = new byte[8];
            byte[] release = new byte[8];
            press[0] = (byte) modifier;
            press[2] = (byte) usage;
            if (!hid.sendReport(host, 0, press)) {
                return false;
            }
            sleep(KEY_GAP_MS);
            if (!hid.sendReport(host, 0, release)) {
                return false;
            }
            sleep(KEY_GAP_MS);
            return true;
        }
    }
}
