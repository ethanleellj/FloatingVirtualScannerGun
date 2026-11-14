package com.virtualscanner.gun;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.SystemClock;
import android.util.Log;

import java.util.Arrays;

/**
 * 注入路由：按配置档挑通道，失败逐级降级，最后兜到剪贴板。
 *
 * 降级顺序遵循规格书的三通道设计：
 *   A 无障碍（快、无需 Shizuku） → B Shizuku shell（无输入框的 POS 只能走这条） → C 剪贴板（人工粘贴）
 *
 * Shizuku 只是可选增强，所以 A 不可用时会直接尝试 B；两条都不可用才用 C，
 * 并在结果里明确写出"已复制到剪贴板，请手动粘贴"，不留"发了没反应"的黑箱。
 */
public final class InjectRouter {

    private static final String TAG = "VSG-Router";

    /** 实际使用了哪条通道；-1 表示因弹窗拦截等原因没有发送。 */
    public static final int CHANNEL_NONE = -1;

    public static final class Result {
        public final boolean ok;
        /** 是否退到了剪贴板 */
        public final boolean clipboard;
        public final int channel;
        public final long costMs;
        public final int messageRes;
        private final Object[] args;
        public final String detail;

        Result(boolean ok, boolean clipboard, int channel, long costMs,
               int messageRes, Object[] args, String detail) {
            this.ok = ok;
            this.clipboard = clipboard;
            this.channel = channel;
            this.costMs = costMs;
            this.messageRes = messageRes;
            this.args = args;
            this.detail = detail;
        }

        public String describe(Context ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append(ctx.getString(ok ? R.string.inject_ok : R.string.inject_failed));
            // 没发送时没有"通道"可言，省掉这一段免得出现「Failed · not sent」这种叠词
            if (channel != CHANNEL_NONE) {
                sb.append(" · ").append(channelName(ctx, channel));
            }
            if (costMs > 0) {
                sb.append(" (").append(costMs).append(" ms)");
            }
            if (messageRes != 0) {
                sb.append(": ").append(args == null
                        ? ctx.getString(messageRes) : ctx.getString(messageRes, args));
            }
            if (detail != null && !detail.isEmpty()) {
                sb.append(" | ").append(detail);
            }
            return sb.toString();
        }
    }

    private InjectRouter() {
    }

    public static String channelName(Context ctx, int channel) {
        switch (channel) {
            case Profile.CHANNEL_A11Y:
                return ctx.getString(R.string.channel_a11y);
            case Profile.CHANNEL_SHIZUKU:
                return ctx.getString(R.string.channel_shizuku);
            case Profile.CHANNEL_CLIPBOARD:
                return ctx.getString(R.string.channel_clipboard);
            case Profile.CHANNEL_NET:
                return ctx.getString(R.string.channel_net);
            case Profile.CHANNEL_HID:
                return ctx.getString(R.string.channel_hid);
            case CHANNEL_NONE:
                return ctx.getString(R.string.channel_none);
            default:
                return ctx.getString(R.string.channel_auto);
        }
    }

    /** 扫码路径：套用当前配置档的前后缀；按配置档的 appendMode 决定追加还是替换。 */
    public static Result inject(Context ctx, Profile profile, String code) {
        return dispatch(ctx, profile, Payload.of(profile, code), profile.appendMode);
    }

    /**
     * 批量录入路径（整页扫描 / 图库整页）：与单发完全相同，只是无障碍通道改成"追加"。
     *
     * <p>一次几十条，替换语义会让前一条被后一条冲掉，最后只剩最后一个序列号。
     */
    public static Result injectAppending(Context ctx, Profile profile, String code) {
        return dispatch(ctx, profile, Payload.of(profile, code), true);
    }

    /** 手动发送 / 自检：不套前后缀，但沿用通道与弹窗策略。 */
    public static Result send(Context ctx, Profile profile, String text, boolean enter) {
        return dispatch(ctx, profile, Payload.raw(text, enter), false);
    }

    private static Result dispatch(Context ctx, Profile profile, Payload payload, boolean append) {
        long t0 = SystemClock.elapsedRealtime();

        // 1. 先看顶层有没有模态弹窗——被吞掉的按键比失败更糟
        ModalGuard.Modal modal = ModalGuard.check(ctx);
        if (modal.detected) {
            switch (profile.modalPolicy) {
                case Profile.MODAL_AUTO_CLOSE:
                    ModalGuard.dismiss(modal.dismissNode);
                    sleep(350);
                    modal = ModalGuard.check(ctx);
                    break;
                case Profile.MODAL_WAIT:
                    for (int i = 0; i < 10 && modal.detected; i++) {
                        sleep(200);
                        modal = ModalGuard.check(ctx);
                    }
                    break;
                default:
                    break;
            }
            if (modal.detected) {
                return new Result(false, false, CHANNEL_NONE,
                        SystemClock.elapsedRealtime() - t0,
                        R.string.inject_err_modal, new Object[]{modal.title}, null);
            }
        }

        // 2. 定通道顺序
        int[] order = channelOrder(profile);
        Log.i(TAG, "dispatch len=" + payload.text.length() + " enter=" + payload.enter
                + " append=" + append + " order=" + Arrays.toString(order));

        int lastError = R.string.inject_err_no_channel;
        String detail = null;

        for (int channel : order) {
            if (channel == Profile.CHANNEL_CLIPBOARD) {
                break;
            }
            if (channel == Profile.CHANNEL_A11Y) {
                int r = A11y.inject(payload.text, payload.enter, append);
                Log.i(TAG, "a11y -> " + r);
                if (r == A11y.OK) {
                    return new Result(true, false, Profile.CHANNEL_A11Y,
                            SystemClock.elapsedRealtime() - t0, R.string.inject_via_a11y,
                            null, null);
                }
                if (r == A11y.OK_NO_ENTER) {
                    // 文本已写入，只是回车没发出去；再降级会把内容重复一遍
                    return new Result(true, false, Profile.CHANNEL_A11Y,
                            SystemClock.elapsedRealtime() - t0, R.string.inject_warn_no_enter,
                            null, null);
                }
                if (r == A11y.PASSWORD) {
                    // 密码框直接放弃，不要往剪贴板泄内容
                    return new Result(false, false, Profile.CHANNEL_A11Y,
                            SystemClock.elapsedRealtime() - t0,
                            R.string.inject_err_password, null, null);
                }
                lastError = a11yError(r);
                continue;
            }
            if (channel == Profile.CHANNEL_SHIZUKU) {
                if (!Injector.isBinderAlive()) {
                    lastError = R.string.inject_err_no_binder;
                    continue;
                }
                if (!Injector.hasPermission()) {
                    lastError = R.string.inject_err_no_permission;
                    continue;
                }
                Injector.Result r = Injector.inject(payload.text, payload.enter);
                Log.i(TAG, "shizuku -> ok=" + r.ok + " detail=" + r.detail);
                if (r.ok) {
                    // 无障碍掉线时这条路会静默接管，而 shell 的按键注入在中文输入法下
                    // 会被做全角转换、截断甚至只提交半个串。真机上出现过
                    // PDA-6901236347102-ENDPDA 这种被追加半截的结果——报"成功"却是错码。
                    // 这种情况必须显式告警，不能安静降级。
                    boolean degraded = !A11y.isConnected();
                    if (degraded) {
                        Log.w(TAG, "shizuku used while accessibility is disconnected");
                    }
                    return new Result(true, false, Profile.CHANNEL_SHIZUKU,
                            SystemClock.elapsedRealtime() - t0,
                            degraded ? R.string.inject_via_shizuku_no_a11y
                                    : R.string.inject_via_shizuku,
                            null, null);
                }
                lastError = r.messageRes == 0 ? R.string.inject_err_exit_code : r.messageRes;
                detail = r.detail;
                continue;
            }
            if (channel == Profile.CHANNEL_NET) {
                String host = Prefs.netHost(ctx);
                int port = Prefs.netPort(ctx);
                if (host.isEmpty() || port <= 0) {
                    lastError = R.string.inject_err_net_unset;
                    continue;
                }
                NetForward.Result r = NetForward.send(host, port, payload.text);
                Log.i(TAG, "net -> ok=" + r.ok + " target=" + host + ":" + port
                        + " detail=" + r.detail);
                if (r.ok) {
                    return new Result(true, false, Profile.CHANNEL_NET,
                            SystemClock.elapsedRealtime() - t0, R.string.inject_via_net,
                            null, null);
                }
                lastError = R.string.inject_err_net;
                detail = r.detail;
                continue;
            }
            if (channel == Profile.CHANNEL_HID) {
                HidKeyboard.Result r = HidKeyboard.send(ctx, payload.text, payload.enter);
                Log.i(TAG, "hid -> ok=" + r.ok + " detail=" + r.detail);
                if (r.ok) {
                    return new Result(true, false, Profile.CHANNEL_HID,
                            SystemClock.elapsedRealtime() - t0, R.string.inject_via_hid,
                            null, null);
                }
                lastError = r.errorRes == 0 ? R.string.inject_err_hid : r.errorRes;
                detail = r.detail;
            }
        }

        // 3. 兜底：写剪贴板，提示人工粘贴
        if (copy(ctx, payload.text)) {
            Log.i(TAG, "clipboard fallback");
            // 把"为什么没用成通道"一起带出去。只报"已复制"的话，人会以为蓝牙/网络是通的，
            // 下次还是白等一遍，永远不知道真正卡在哪。
            String why = lastError == 0 ? null : ctx.getString(lastError);
            if (detail != null && !detail.isEmpty()) {
                why = why == null ? detail : why + ": " + detail;
            }
            return new Result(true, true, Profile.CHANNEL_CLIPBOARD,
                    SystemClock.elapsedRealtime() - t0, R.string.inject_copied, null, why);
        }
        Log.w(TAG, "failed, lastError=" + lastError);
        return new Result(false, false, CHANNEL_NONE,
                SystemClock.elapsedRealtime() - t0, lastError, null, detail);
    }

    /** 按配置档与当前界面能力排定尝试顺序。 */
    private static int[] channelOrder(Profile profile) {
        switch (profile.channel) {
            case Profile.CHANNEL_A11Y:
                return new int[]{Profile.CHANNEL_A11Y, Profile.CHANNEL_CLIPBOARD};
            case Profile.CHANNEL_SHIZUKU:
                return new int[]{Profile.CHANNEL_SHIZUKU, Profile.CHANNEL_CLIPBOARD};
            case Profile.CHANNEL_CLIPBOARD:
                return new int[]{Profile.CHANNEL_CLIPBOARD};
            case Profile.CHANNEL_NET:
                return new int[]{Profile.CHANNEL_NET, Profile.CHANNEL_CLIPBOARD};
            case Profile.CHANNEL_HID:
                return new int[]{Profile.CHANNEL_HID, Profile.CHANNEL_CLIPBOARD};
            default:
                break;
        }
        // 自动：已开无障碍 / 已授权 Shizuku 就优先走它们，不要因为「此刻找不到焦点」
        // 就把人打发到剪贴板——注入时（A11y.inject）会再找一次焦点，比这里预判更准。
        // 顺序：无障碍 > Shizuku > 剪贴板；两者都没开时才落到剪贴板。
        boolean a11y = A11y.isConnected();
        boolean shizuku = Injector.hasPermission();
        if (a11y || shizuku) {
            return new int[]{Profile.CHANNEL_A11Y, Profile.CHANNEL_SHIZUKU,
                    Profile.CHANNEL_CLIPBOARD};
        }
        return new int[]{Profile.CHANNEL_CLIPBOARD};
    }

    private static int a11yError(int code) {
        switch (code) {
            case A11y.NO_SERVICE:
                return R.string.inject_err_a11y_off;
            case A11y.NO_TARGET:
                return R.string.inject_err_no_input;
            case A11y.ENTER_UNSUPPORTED:
                return R.string.inject_err_enter_unsupported;
            default:
                return R.string.inject_err_set_failed;
        }
    }

    private static boolean copy(Context ctx, String text) {
        try {
            ClipboardManager cm = (ClipboardManager)
                    ctx.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.app_name), text));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static void sleep(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
