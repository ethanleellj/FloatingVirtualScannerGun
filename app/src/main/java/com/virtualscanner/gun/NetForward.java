package com.virtualscanner.gun;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

/**
 * 网络转发：把扫到的内容当成一行文本，TCP 发到用户自己填的 PC 上。
 *
 * <p>协议故意做到最笨：连上 → 写一行（UTF-8，以 \n 结束）→ 断开。
 * 这样 PC 端用 netcat、Python 十几行、甚至一个串口转接盒都能接，
 * 不需要在这个 App 里维护一套私有协议。
 *
 * <p>超时都压得很短（连接 1.2 s、写 1.5 s）：目标机器不在线时，
 * 收银员等不起，宁可快速失败落到剪贴板兜底。
 */
public final class NetForward {

    private static final int CONNECT_TIMEOUT_MS = 1200;
    private static final int WRITE_TIMEOUT_MS = 1500;
    /** 写完内容后排空对端响应的时间：只为确认数据真的出去了，不做业务处理 */
    private static final int DRAIN_TIMEOUT_MS = 600;

    public static final class Result {
        public final boolean ok;
        /** 失败原因，给日志和界面看 */
        public final String detail;

        private Result(boolean ok, String detail) {
            this.ok = ok;
            this.detail = detail;
        }
    }

    private NetForward() {
    }

    /** 纯阻塞调用，只能在后台线程里用（注入线程本身就是后台）。 */
    public static Result send(String host, int port, String text) {
        if (host == null || host.trim().isEmpty() || port <= 0 || port > 65535) {
            return new Result(false, "host/port not set");
        }
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(host.trim(), port), CONNECT_TIMEOUT_MS);
            socket.setSoTimeout(WRITE_TIMEOUT_MS);
            OutputStream out = socket.getOutputStream();
            out.write((text + "\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            // 写完立刻 close 会被对端当成"连接被重置"，缓冲区里的数据可能直接丢掉
            // （adb reverse 下实测就是这样：对端只看到 0 字节）。先半关写，等对方读到
            // EOF 再关，数据才算真正送达。
            socket.shutdownOutput();
            socket.setSoTimeout(DRAIN_TIMEOUT_MS);
            java.io.InputStream in = socket.getInputStream();
            byte[] sink = new byte[64];
            long deadline = System.currentTimeMillis() + DRAIN_TIMEOUT_MS;
            while (System.currentTimeMillis() < deadline) {
                try {
                    if (in.read(sink) < 0) {
                        break;
                    }
                } catch (java.net.SocketTimeoutException timeout) {
                    break;
                }
            }
            return new Result(true, null);
        } catch (Throwable t) {
            String message = t.getMessage() == null ? t.getClass().getSimpleName() : t.getMessage();
            return new Result(false, message);
        } finally {
            try {
                socket.close();
            } catch (Throwable ignored) {
            }
        }
    }
}
