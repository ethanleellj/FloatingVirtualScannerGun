package com.virtualscanner.gun;

import android.media.AudioManager;
import android.media.ToneGenerator;
import android.os.Handler;
import android.os.Looper;

/**
 * 扫码成功的提示音。
 *
 * <p>用 {@link ToneGenerator} 合成，不带音频资源：收银台环境嘈杂，一段干脆的
 * "嘀"比任何旋律都更容易被听成"扫到了"。音量为 0 或静音模式下会自然无声，
 * 这是系统行为，不做特殊处理——强行走铃声通道反而会在静音时突然炸响。
 */
public final class Beep {

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    /** 0~100，相对媒体音量。 */
    private static final int VOLUME = 90;
    private static final int DURATION_MS = 140;
    /** 发完音再等一拍释放，避免把尾音掐掉。 */
    private static final long RELEASE_DELAY_MS = 400L;

    private Beep() {
    }

    /** 扫到并成功注入时调用；可在任意线程调用。 */
    public static void success() {
        MAIN.post(() -> {
            ToneGenerator tone = null;
            try {
                tone = new ToneGenerator(AudioManager.STREAM_MUSIC, VOLUME);
                tone.startTone(ToneGenerator.TONE_PROP_BEEP, DURATION_MS);
                final ToneGenerator toRelease = tone;
                MAIN.postDelayed(toRelease::release, RELEASE_DELAY_MS);
            } catch (Throwable t) {
                if (tone != null) {
                    try {
                        tone.release();
                    } catch (Throwable ignored) {
                    }
                }
            }
        });
    }
}
