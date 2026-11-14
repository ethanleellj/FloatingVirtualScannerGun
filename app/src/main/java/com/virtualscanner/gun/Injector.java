package com.virtualscanner.gun;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;
import rikka.shizuku.Shizuku;

/**
 * 通道 B：通过 Shizuku 以 shell 身份执行 input 命令。
 *
 * 为什么不用无障碍：目标应用（思迅自助收银）没有 EditText，
 * 无障碍的 ACTION_SET_TEXT 无处可写，只能走真实的按键注入。
 *
 * 本类不持有 Context，所有面向用户的文案以字符串资源 ID 返回，
 * 由调用方在 UI 线程用 describe(Context) / statusText(Context) 解析，
 * 保证多语言切换后旧消息也能正确显示。
 */
public final class Injector {

    private static final String TAG = "VSG-Inject";

    private Injector() {
    }

    public static class Result {
        public final boolean ok;
        public final long costMs;
        /** 文案资源 ID，0 表示无附加说明 */
        public final int messageRes;
        private final Object[] messageArgs;
        /** 技术细节（异常信息、stderr 等），不翻译 */
        public final String detail;

        Result(boolean ok, long costMs, int messageRes, Object[] messageArgs, String detail) {
            this.ok = ok;
            this.costMs = costMs;
            this.messageRes = messageRes;
            this.messageArgs = messageArgs;
            this.detail = detail;
        }

        public String describe(Context ctx) {
            StringBuilder sb = new StringBuilder();
            sb.append(ctx.getString(ok ? R.string.inject_ok : R.string.inject_failed));
            sb.append(" (").append(costMs).append(" ms)");
            if (messageRes != 0) {
                sb.append(": ").append(messageArgs == null
                        ? ctx.getString(messageRes)
                        : ctx.getString(messageRes, messageArgs));
            }
            if (detail != null && !detail.isEmpty()) {
                sb.append(" | ").append(detail);
            }
            return sb.toString();
        }
    }

    public static boolean isBinderAlive() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean hasPermission() {
        try {
            return Shizuku.pingBinder()
                    && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(0);
        } catch (Throwable t) {
            Log.w(TAG, "requestPermission failed", t);
        }
    }

    public static String statusText(Context ctx) {
        if (!isBinderAlive()) {
            return ctx.getString(R.string.shizuku_status_disconnected);
        }
        if (!hasPermission()) {
            return ctx.getString(R.string.shizuku_status_unauthorized);
        }
        try {
            return ctx.getString(R.string.shizuku_status_ready_uid, Shizuku.getUid());
        } catch (Throwable t) {
            return ctx.getString(R.string.shizuku_status_ready);
        }
    }

    /**
     * 阻塞执行一次注入，必须在子线程调用。
     */
    public static Result inject(String text, boolean enter) {
        long t0 = System.currentTimeMillis();

        if (!isBinderAlive()) {
            return new Result(false, 0, R.string.inject_err_no_binder, null, null);
        }
        if (!hasPermission()) {
            return new Result(false, 0, R.string.inject_err_no_permission, null, null);
        }

        String cmd = buildCommand(text, enter);
        if (cmd.isEmpty()) {
            // 内容与回车都没有，等同于空操作
            return new Result(true, 0, R.string.inject_chars, new Object[]{0}, null);
        }
        Log.i(TAG, "exec: " + cmd);

        try {
            IShizukuService service = IShizukuService.Stub.asInterface(Shizuku.getBinder());
            IRemoteProcess process = service.newProcess(new String[]{"sh", "-c", cmd}, null, null);
            int code = process.waitFor();
            String err = readAll(process.getErrorStream());
            process.destroy();

            long cost = System.currentTimeMillis() - t0;
            if (code == 0) {
                return new Result(true, cost, R.string.inject_chars,
                        new Object[]{text.length()}, null);
            }
            return new Result(false, cost, R.string.inject_err_exit_code,
                    new Object[]{code}, err.trim());
        } catch (Throwable t) {
            Log.w(TAG, "exec failed", t);
            return new Result(false, System.currentTimeMillis() - t0,
                    R.string.inject_err_exception,
                    new Object[]{t.getClass().getSimpleName(), String.valueOf(t.getMessage())},
                    null);
        }
    }

    /**
     * 把待发送文本翻译成 shell 命令序列。
     *
     * `input text` 只能送可见字符，换行/回车/Tab 必须走 `input keyevent`。
     * 前后缀里出现转义控制符时（如后缀 \r\n），这里会拆成
     * `input text ...` 与 `input keyevent 66` 交替执行，顺序与内容都保持原样。
     * CRLF 只发一次回车，否则会多提交一行。
     */
    static String buildCommand(String text, boolean enter) {
        StringBuilder command = new StringBuilder();
        StringBuilder segment = new StringBuilder();
        char prev = 0;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\n' || c == '\r' || c == '\t') {
                appendText(command, segment);
                if (c == '\t') {
                    append(command, "input keyevent 61");
                } else if (!(c == '\n' && prev == '\r')) {
                    append(command, "input keyevent 66");
                }
            } else {
                segment.append(c);
            }
            prev = c;
        }
        appendText(command, segment);

        if (enter) {
            // 给目标应用一点处理时间，再补回车（等价扫描枪扫完后的确认）
            append(command, "sleep 0.12");
            append(command, "input keyevent 66");
        }
        return command.toString();
    }

    private static void appendText(StringBuilder command, StringBuilder segment) {
        if (segment.length() == 0) {
            return;
        }
        // input text 里 %s 代表空格，因此普通空格要替换掉；
        // 单引号需要按 shell 规则闭合转义。
        String forInput = segment.toString().replace(" ", "%s").replace("'", "'\\''");
        append(command, "input text '" + forInput + "'");
        segment.setLength(0);
    }

    private static void append(StringBuilder command, String piece) {
        if (command.length() > 0) {
            command.append("; ");
        }
        command.append(piece);
    }

    private static String readAll(ParcelFileDescriptor pfd) {
        if (pfd == null) {
            return "";
        }
        try (InputStream in = new ParcelFileDescriptor.AutoCloseInputStream(pfd)) {
            ByteArrayOutputStream bos = new ByteArrayOutputStream();
            byte[] buf = new byte[1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                bos.write(buf, 0, n);
            }
            return bos.toString("UTF-8");
        } catch (Throwable t) {
            return "";
        }
    }
}
