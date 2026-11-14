package com.virtualscanner.gun;

import android.app.Activity;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

/**
 * 边到边适配。
 *
 * <p>目标 API 35 起系统强制边到边：窗口不再自动给状态栏/导航栏留位置，
 * 内容会直接画到栏底下——标题被时间盖住、最后一排按钮被手势条压住。
 * 这里统一把系统栏与输入法的高度补成根视图的内边距，并把栏内图标改成深色
 * （本 App 是浅色主题，深色图标才看得见）。
 *
 * <p>悬浮窗（{@link OverlayService}）不走这里：它是 overlay 窗口，
 * 位置由用户拖动决定，系统栏与它无关。
 */
public final class Ui {

    private Ui() {
    }

    public static void edgeToEdge(Activity activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
        WindowInsetsControllerCompat controller =
                WindowCompat.getInsetsController(activity.getWindow(),
                        activity.getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(true);
        controller.setAppearanceLightNavigationBars(true);

        View root = activity.findViewById(android.R.id.content);
        if (root instanceof ViewGroup && ((ViewGroup) root).getChildCount() > 0) {
            root = ((ViewGroup) root).getChildAt(0);
        }
        padByInsets(root);
    }

    /** 给任一窗口内容视图补系统栏/输入法边距（保留布局里原有的 padding）。 */
    public static void padByInsets(View root) {
        if (root == null) {
            return;
        }
        final int left = root.getPaddingLeft();
        final int top = root.getPaddingTop();
        final int right = root.getPaddingRight();
        final int bottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, windowInsets) -> {
            Insets bars = windowInsets.getInsets(
                    WindowInsetsCompat.Type.systemBars()
                            | WindowInsetsCompat.Type.displayCutout());
            Insets ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime());
            view.setPadding(left + bars.left,
                    top + bars.top,
                    right + bars.right,
                    bottom + Math.max(bars.bottom, ime.bottom));
            return windowInsets;
        });
        ViewCompat.requestApplyInsets(root);
    }
}
