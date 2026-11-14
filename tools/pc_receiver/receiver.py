#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""PC 端接收器：接住手机「网络转发」通道发来的扫码内容。

协议就是一行一条文本（UTF-8，以 \\n 结束），没有握手、没有加密，
所以用 netcat 也能收：

    # Linux / macOS
    nc -l 9100
    # Windows（装了 ncat）
    ncat -l 9100

用法：

    python receiver.py                 # 只听 9100，打印到终端
    python receiver.py --port 9200
    python receiver.py --type          # 顺便把内容"敲"进当前焦点窗口（需 pip install pynput）
    python receiver.py --clipboard     # 顺便复制到剪贴板（需 pip install pyperclip）
    python receiver.py --log scans.txt # 同时追加写入文件

手机端设置：
    1. 手机与电脑连同一个 Wi-Fi（或用 USB 时执行 adb reverse tcp:9100 tcp:9100，
       手机里填 127.0.0.1:9100）
    2. App 主界面「注入通道」选「网络转发」
    3. 「网络转发」里填电脑 IP 与端口（默认 9100）
    4. 扫码，本窗口应打印出内容

注意：--type 是把内容当作键盘输入打进当前焦点窗口，所以脚本运行时
**别把焦点放在这个终端里**，切到你要填单的那个窗口（Excel、ERP、记事本）。
"""

import argparse
import datetime
import socket
import sys
import threading

try:
    import pyperclip  # type: ignore
except Exception:  # 没装就当没这个功能
    pyperclip = None

try:
    from pynput.keyboard import Controller as KeyboardController  # type: ignore
except Exception:
    KeyboardController = None


def stamp() -> str:
    return datetime.datetime.now().strftime("%H:%M:%S")


def handle(conn: socket.socket, addr, args, keyboard):
    peer = f"{addr[0]}:{addr[1]}"
    buffer = b""
    with conn:
        conn.settimeout(30)
        while True:
            try:
                chunk = conn.recv(4096)
            except socket.timeout:
                break
            except OSError:
                break
            if not chunk:
                break
            buffer += chunk
            while b"\n" in buffer:
                line, buffer = buffer.split(b"\n", 1)
                text = line.decode("utf-8", errors="replace").rstrip("\r")
                if not text:
                    continue
                print(f"[{stamp()}] {text}", flush=True)
                if args.log:
                    with open(args.log, "a", encoding="utf-8") as f:
                        f.write(text + "\n")
                if args.clipboard and pyperclip is not None:
                    pyperclip.copy(text)
                if keyboard is not None:
                    keyboard.type(text)
                    keyboard.press("enter")
                    keyboard.release("enter")
    print(f"[{stamp()}] {peer} disconnected", flush=True)


def main() -> int:
    ap = argparse.ArgumentParser(description="接收手机扫码枪发来的条码内容")
    ap.add_argument("--host", default="0.0.0.0", help="监听地址，默认全部网卡")
    ap.add_argument("--port", type=int, default=9100, help="监听端口，默认 9100")
    ap.add_argument("--log", help="把收到的内容追加写入该文件")
    ap.add_argument("--clipboard", action="store_true", help="复制到剪贴板（需 pyperclip）")
    ap.add_argument("--type", action="store_true", help="敲进当前焦点窗口（需 pynput）")
    args = ap.parse_args()

    keyboard = None
    if args.type:
        if KeyboardController is None:
            print("!! 需要先 pip install pynput 才能用 --type", file=sys.stderr)
            return 2
        keyboard = KeyboardController()
        print("!! --type 已开启：请把焦点切到要填写的窗口，别停在终端里")

    if args.clipboard and pyperclip is None:
        print("!! 需要先 pip install pyperclip 才能用 --clipboard", file=sys.stderr)

    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((args.host, args.port))
    srv.listen(8)
    print(f"listening on {args.host}:{args.port}  (Ctrl+C to quit)", flush=True)

    try:
        while True:
            conn, addr = srv.accept()
            threading.Thread(target=handle, args=(conn, addr, args, keyboard),
                             daemon=True).start()
    except KeyboardInterrupt:
        print("\nbye", flush=True)
    finally:
        srv.close()
    return 0


if __name__ == "__main__":
    sys.exit(main())
