package com.virtualscanner.gun;

/**
 * 悬浮窗位置的纯逻辑，只依赖整数，便于在 {@code tools/sim} 里离线回放。
 *
 * <p>保存的是屏幕坐标 px。旋转横竖屏、分屏、换设备（哪怕从云备份恢复设置）之后，
 * 上次的坐标可能已经落在屏幕外，直接用会让悬浮球"启动后找不到"。恢复时按当前窗口
 * 尺寸夹回可见范围。
 */
public final class Position {

    private Position() {
    }

    /**
     * 把 {@code value} 限制在 {@code [0, screen - window]} 内。
     * 窗口比屏幕还大（极端分屏）时整个范围退化为 0。
     */
    public static int clamp(int value, int screen, int window) {
        int max = screen - window;
        if (max < 0) {
            max = 0;
        }
        if (value < 0) {
            return 0;
        }
        return value > max ? max : value;
    }
}
