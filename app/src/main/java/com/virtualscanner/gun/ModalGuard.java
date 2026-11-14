package com.virtualscanner.gun;

import android.content.Context;
import android.graphics.Rect;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.List;

/**
 * 注入前的窗口状态检查（FR-IN-17）。
 *
 * 现场最常见的事故：扫完码，目标 App 弹了个"商品不存在"的对话框，
 * 这时注入的按键其实落进了对话框，条码内容丢失，操作员却以为已经录入。
 * 所以发之前先看一眼，宁可拦住也不要静默发送。
 *
 * 判定用的是无障碍窗口列表：比当前焦点窗口更靠上、且带"确定/知道了"这类
 * 关闭按钮的窗口，视为模态对话框。这是一个保守的启发式——只在确实看到
 * 关闭按钮时才拦截，避免把带"确定"按钮的普通页面误判。
 *
 * 注意两个坑（都是真机上踩出来的）：
 * 1. 对话框会自己抢走焦点，此时它比"焦点窗口"更靠上这个比较恒为假，
 *    必须把"最顶层应用窗口"单独纳入候选；
 * 2. getLayer() 对同包的对话框与宿主页面返回同一个值（实测都是 21000），
 *    排序只能依赖 getWindows() 的返回顺序。
 */
public final class ModalGuard {

    private static final String TAG = "VSG-Modal";

    /** 视为"关闭类"按钮的文案（小写比较）。 */
    private static final String[] DISMISS_LABELS = {
            "确定", "确认", "好的", "好", "知道了", "我知道了", "关闭", "我已知晓", "继续",
            "ok", "okay", "got it", "close", "confirm", "dismiss", "yes"
    };

    public static final class Modal {
        /** 无障碍未连接时为 false，表示"无法判断"，而不是"没有弹窗"。 */
        public final boolean known;
        public final boolean detected;
        public final String title;
        public final AccessibilityNodeInfo dismissNode;

        Modal(boolean known, boolean detected, String title, AccessibilityNodeInfo dismissNode) {
            this.known = known;
            this.detected = detected;
            this.title = title;
            this.dismissNode = dismissNode;
        }

        static Modal unknown() {
            return new Modal(false, false, "", null);
        }

        static Modal none() {
            return new Modal(true, false, "", null);
        }
    }

    private ModalGuard() {
    }

    public static Modal check(Context ctx) {
        List<AccessibilityWindowInfo> windows = A11y.windows();
        if (windows.isEmpty()) {
            return Modal.unknown();
        }

        // getWindows() 按 z 序返回：下标越小越靠上。窗口的 getLayer() 对同为
        // TYPE_APPLICATION 的对话框和宿主页面会返回同一个值（实测都是 21000），
        // 所以顺序只能看列表下标，不能看 layer。
        int focusedIndex = -1;
        int topAppIndex = -1;
        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo w = windows.get(i);
            if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            if (focusedIndex < 0 && w.isFocused()) {
                focusedIndex = i;
            }
            if (topAppIndex < 0) {
                topAppIndex = i;
            }
        }

        int screenArea = screenArea(ctx);

        for (int i = 0; i < windows.size(); i++) {
            AccessibilityWindowInfo w = windows.get(i);
            int type = w.getType();
            if (type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) {
                continue;
            }
            if (type != AccessibilityWindowInfo.TYPE_APPLICATION
                    && type != AccessibilityWindowInfo.TYPE_SYSTEM) {
                continue;
            }

            AccessibilityNodeInfo root = rootOf(w);
            if (root == null) {
                continue;
            }
            // 自己的悬浮窗、状态栏、导航栏都排除，只留真正压在目标 App 上的窗口
            String pkg = pkgOf(root);
            if (pkg.isEmpty() || pkg.equals(ctx.getPackageName())
                    || pkg.equals("com.android.systemui")) {
                continue;
            }

            // 只看"压在焦点窗口之上"或"最顶层的应用窗口"。
            // 对话框通常会自己抢走焦点，此时它就是最顶层应用窗口，必须纳入判定，
            // 否则最典型的"扫码时弹出商品不存在"场景会漏判。
            boolean aboveFocus = focusedIndex >= 0 && i < focusedIndex;
            boolean topMostApp = i == topAppIndex;
            if (!aboveFocus && !topMostApp) {
                continue;
            }

            // 对话框的形态特征：不是全屏，或者同包还有另一个应用窗口（对话框盖在页面上）。
            // 两条都不满足时说明这就是普通全屏页面，直接跳过，避免把页面上的"确定"按钮
            // 误判成弹窗。
            boolean shrunk = isShrunk(w, screenArea);
            boolean sibling = hasSiblingAppWindow(windows, i, pkg);
            if (!shrunk && !sibling) {
                continue;
            }

            AccessibilityNodeInfo dismiss = findDismiss(root, 0);
            if (dismiss != null) {
                Log.i(TAG, "modal detected: pkg=" + pkg + " idx=" + i
                        + " top=" + topMostApp + " shrunk=" + shrunk + " sibling=" + sibling
                        + " title=" + titleOf(root));
                return new Modal(true, true, titleOf(root), dismiss);
            }
        }
        return Modal.none();
    }

    private static int screenArea(Context ctx) {
        try {
            DisplayMetrics dm = ctx.getResources().getDisplayMetrics();
            return dm.widthPixels * dm.heightPixels;
        } catch (Throwable t) {
            return 0;
        }
    }

    /** 窗口面积明显小于屏幕（阈值的取法见类注释：留足状态栏/导航栏差异）。 */
    private static boolean isShrunk(AccessibilityWindowInfo w, int screenArea) {
        if (screenArea <= 0) {
            return false;
        }
        try {
            Rect r = new Rect();
            w.getBoundsInScreen(r);
            long area = (long) r.width() * r.height();
            return area > 0 && area < screenArea * 0.85d;
        } catch (Throwable t) {
            return false;
        }
    }

    /** 同包是否还有另一个应用窗口——对话框盖在自己页面上时的典型特征。 */
    private static boolean hasSiblingAppWindow(List<AccessibilityWindowInfo> windows,
                                               int index, String pkg) {
        for (int i = 0; i < windows.size(); i++) {
            if (i == index) {
                continue;
            }
            AccessibilityWindowInfo other = windows.get(i);
            if (other.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                continue;
            }
            AccessibilityNodeInfo otherRoot = rootOf(other);
            if (otherRoot != null && pkg.equals(pkgOf(otherRoot))) {
                return true;
            }
        }
        return false;
    }

    public static boolean dismiss(AccessibilityNodeInfo node) {
        return A11y.click(node);
    }

    private static AccessibilityNodeInfo rootOf(AccessibilityWindowInfo w) {
        try {
            return w.getRoot();
        } catch (Throwable t) {
            return null;
        }
    }

    private static String pkgOf(AccessibilityNodeInfo node) {
        try {
            CharSequence p = node.getPackageName();
            return p == null ? "" : p.toString();
        } catch (Throwable t) {
            return "";
        }
    }

    /** 在窗口里找第一个"关闭类"按钮。 */
    private static AccessibilityNodeInfo findDismiss(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 12) {
            return null;
        }
        try {
            if (isDismissButton(node)) {
                return node;
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) {
                    continue;
                }
                AccessibilityNodeInfo found = findDismiss(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static boolean isDismissButton(AccessibilityNodeInfo node) {
        try {
            CharSequence cls = node.getClassName();
            boolean looksLikeButton = cls != null && cls.toString().contains("Button");
            if (!looksLikeButton && !node.isClickable()) {
                return false;
            }
            CharSequence text = node.getText();
            if (text == null) {
                text = node.getContentDescription();
            }
            if (text == null) {
                return false;
            }
            String s = text.toString().trim().toLowerCase();
            if (s.isEmpty()) {
                return false;
            }
            for (String label : DISMISS_LABELS) {
                if (s.equals(label) || s.contains(label)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 取窗口里第一段非空文本作为标题，用于提示"检测到哪个弹窗"。 */
    private static String titleOf(AccessibilityNodeInfo root) {
        String s = firstText(root, 0);
        return s.length() > 40 ? s.substring(0, 40) + "…" : s;
    }

    private static String firstText(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 8) {
            return "";
        }
        try {
            CharSequence t = node.getText();
            if (t != null && t.toString().trim().length() > 1) {
                return t.toString().trim();
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                String s = firstText(node.getChild(i), depth + 1);
                if (!s.isEmpty()) {
                    return s;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }
}
