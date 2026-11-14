package com.virtualscanner.gun;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.view.accessibility.AccessibilityManager;

import java.util.Collections;
import java.util.List;

/**
 * 通道 A：无障碍注入。
 *
 * 适用对象是"有标准输入框"的普通 App —— 直接对焦点节点写文本，比 shell 注入快得多，
 * 而且不需要 Shizuku。对没有 EditText 的应用（如思迅自助收银）本通道必然失败，
 * 由 {@link InjectRouter} 自动降级到 Shizuku。
 *
 * 密码框一律拒绝（FR-IN-13），不记录、不复制、不提示内容。
 */
public class A11y extends AccessibilityService {

    /** 注入结果码，供路由层决定是否降级。 */
    public static final int OK = 0;
    /** 无障碍服务没连上 */
    public static final int NO_SERVICE = 1;
    /** 当前界面找不到可编辑的焦点节点 */
    public static final int NO_TARGET = 2;
    /** 焦点是密码框，按策略拒绝 */
    public static final int PASSWORD = 3;
    /** 节点存在但写不进去 */
    public static final int SET_FAILED = 4;
    /** 需要回车，但系统版本不支持无障碍发回车键 */
    public static final int ENTER_UNSUPPORTED = 5;
    /** 文本已写入，但回车没发出去（不能再降级，否则内容会重复） */
    public static final int OK_NO_ENTER = 6;

    private static volatile A11y instance;
    private static volatile String foregroundPackage = "";

    public static A11y get() {
        return instance;
    }

    public static boolean isConnected() {
        return instance != null;
    }

    /**
     * 是否已在系统设置里启用。
     * 用 AccessibilityManager 查比解析 Settings.Secure 更可靠，且能区分"启用但未连接"。
     */
    public static boolean isEnabled(Context ctx) {
        try {
            AccessibilityManager am = (AccessibilityManager)
                    ctx.getSystemService(Context.ACCESSIBILITY_SERVICE);
            if (am == null || !am.isEnabled()) {
                return false;
            }
            List<AccessibilityServiceInfo> enabled =
                    am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK);
            for (AccessibilityServiceInfo info : enabled) {
                if (info.getResolveInfo() == null) {
                    continue;
                }
                ServiceInfo si = info.getResolveInfo().serviceInfo;
                if (si != null && ctx.getPackageName().equals(si.packageName)
                        && si.name != null && si.name.endsWith("A11y")) {
                    return true;
                }
            }
        } catch (Throwable ignored) {
        }
        return false;
    }

    /** 单个无障碍服务的详情页。这个 action 没有公开常量，只能写字符串（API 26 起 AOSP 设置认它）。 */
    private static final String ACTION_A11Y_DETAILS = "android.settings.ACCESSIBILITY_DETAILS_SETTINGS";

    /**
     * 跳到系统设置去开无障碍。
     *
     * <p>优先直接落到本服务自己的详情页：系统的无障碍列表按 App 分组，"开哪个"本来是个
     * 需要用户自己找的问题，能免一次找就免一次。部分定制 ROM（MIUI / HyperOS）可能不认
     * 这个 action，所以失败时退回列表页——退回去以后由界面上的提示文案指明条目名字。
     */
    public static void openSettings(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                ComponentName me = new ComponentName(ctx, A11y.class);
                ctx.startActivity(new Intent(ACTION_A11Y_DETAILS)
                        .setData(Uri.fromParts("package", me.flattenToString(), null))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
                return;
            } catch (Throwable ignored) {
                // 落到列表页
            }
        }
        try {
            ctx.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        } catch (Throwable ignored) {
        }
    }

    public static String foregroundPackage() {
        return foregroundPackage;
    }

    /**
     * 当前真正持有焦点的窗口包名。
     *
     * <p>{@link #foregroundPackage()} 靠窗口事件更新，无障碍刚被重新绑定、还没收到任何
     * 事件时是空串。图库识别要记"回来时该等到谁"，空串会让判定失效，所以这里直接问一眼
     * 活跃窗口兜底。
     */
    public static String activePackage() {
        A11y s = instance;
        if (s == null) {
            return foregroundPackage;
        }
        try {
            AccessibilityNodeInfo root = s.getRootInActiveWindow();
            CharSequence pkg = root == null ? null : root.getPackageName();
            if (pkg != null && pkg.length() > 0) {
                return pkg.toString();
            }
        } catch (Throwable ignored) {
        }
        return foregroundPackage;
    }

    public static List<AccessibilityWindowInfo> windows() {
        A11y s = instance;
        if (s == null) {
            return Collections.emptyList();
        }
        try {
            List<AccessibilityWindowInfo> list = s.getWindows();
            return list == null ? Collections.<AccessibilityWindowInfo>emptyList() : list;
        } catch (Throwable t) {
            return Collections.emptyList();
        }
    }

    // ---------------- 目标定位 ----------------

    /** 找到的输入目标。 */
    public static final class Target {
        public AccessibilityNodeInfo node;
        public boolean password;
        public boolean multiline;
        public String pkg = "";
    }

    public static Target findTarget() {
        A11y s = instance;
        if (s == null) {
            return null;
        }

        // 1) 活跃窗口。绝大多数情况在这里就能命中。
        Target t = targetIn(rootOrNull(s));
        if (t != null) {
            return t;
        }

        // 2) 软键盘展开时，部分 ROM（实测 HyperOS + 搜狗/微信输入法）会把"活跃窗口"
        //    判给输入法自己，getRootInActiveWindow() 里只剩键盘按键，找不到输入框。
        //    此时逐个应用窗口再找一遍，避免误判成"没有输入框"而降级到 shell。
        //    先看带输入焦点的窗口，避免分屏时认错应用。
        for (int pass = 0; pass < 2; pass++) {
            for (AccessibilityWindowInfo w : windows()) {
                if (w == null || w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) {
                    continue;
                }
                boolean focused = false;
                try {
                    focused = w.isFocused();
                } catch (Throwable ignored) {
                }
                if (pass == 0 && !focused) {
                    continue;
                }
                if (pass == 1 && focused) {
                    continue;
                }
                AccessibilityNodeInfo root = null;
                try {
                    root = w.getRoot();
                } catch (Throwable ignored) {
                }
                t = targetIn(root);
                if (t != null) {
                    return t;
                }
            }
        }
        return null;
    }

    private static AccessibilityNodeInfo rootOrNull(A11y s) {
        try {
            return s.getRootInActiveWindow();
        } catch (Throwable t) {
            return null;
        }
    }

    private static Target targetIn(AccessibilityNodeInfo root) {
        if (root == null) {
            return null;
        }
        AccessibilityNodeInfo focus = null;
        try {
            focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT);
        } catch (Throwable ignored) {
        }

        // findFocus 拿不到输入框时（WebView 的焦点常挂在容器上），先在树里找"有焦点"的
        // 可编辑节点。不能直接取"第一个可编辑节点"：浏览器地址栏在层级里排在 WebView 前面，
        // 用户明明点的是网页里的输入框，文字却会被写进地址栏并触发跳转。
        AccessibilityNodeInfo node = isEditable(focus) ? focus : findEditableInTree(root, true);
        if (node == null) {
            node = findEditableInTree(root, false);
        }
        // 地址栏与网页表单可能同时上报"有焦点"（WebView 的虚拟节点失焦后不会清掉这个标志，
        // 展开悬浮面板时窗口焦点落到地址栏尤其明显）。网页里有焦点输入框时以它为准。
        if (node != null && !insideWebView(node)) {
            AccessibilityNodeInfo web = findFocusedEditableInWebView(root, 0);
            if (web != null) {
                node = web;
            }
        }
        if (node == null || isOwnConfigField(node)) {
            return null;
        }

        Target t = new Target();
        t.node = node;
        try {
            t.password = node.isPassword();
            t.multiline = (node.getInputType() & 0x00020000) != 0; // TYPE_TEXT_FLAG_MULTI_LINE
            CharSequence p = node.getPackageName();
            t.pkg = p == null ? "" : p.toString();
        } catch (Throwable ignored) {
        }
        return t;
    }

    /**
     * 本应用自己的「自检内容」输入框不能当注入目标。
     *
     * <p>它的内容会被 TextWatcher 存进配置，而自检又拿这个值去套前后缀：
     * 一旦注入写进去，就变成"扫一次码 → 扫到的内容成了自检内容 → 下次自检再套一层前后缀"，
     * 字符串越滚越长（实测滚到过 `PDA[]PDA[]6901…-END-END6901…-END`）。
     * 与其事后猜"这次改动是不是注入造成的"，不如一开始就不往这个框里写。
     */
    private static boolean isOwnConfigField(AccessibilityNodeInfo node) {
        A11y s = instance;
        if (s == null) {
            return false;
        }
        try {
            CharSequence pkg = node.getPackageName();
            if (pkg == null || !pkg.toString().equals(s.getPackageName())) {
                return false;
            }
            String id = node.getViewIdResourceName();
            return id != null && id.endsWith(":id/etTestText");
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean isEditable(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            if (node.isEditable()) {
                return true;
            }
            CharSequence cls = node.getClassName();
            if (cls == null) {
                return false;
            }
            String name = cls.toString();
            return name.endsWith("EditText") || name.endsWith("AutoCompleteTextView")
                    || name.endsWith("SearchView");
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在树里找可编辑节点。
     *
     * <p>{@code requireFocused=true} 只认"自己报告有输入焦点"的节点（WebView 里的表单会这样
     * 上报）；为 false 时退化成"第一个可见可编辑节点"，供完全没有焦点上报的界面使用。
     */
    private static AccessibilityNodeInfo findEditableInTree(
            AccessibilityNodeInfo node, boolean requireFocused) {
        return findEditableInTree(node, 0, requireFocused);
    }

    /** 节点是否位于 WebView 内部（虚拟节点本身不带这个信息，只能向上看容器类名）。 */
    private static boolean insideWebView(AccessibilityNodeInfo node) {
        AccessibilityNodeInfo p = node;
        for (int i = 0; i < 10 && p != null; i++) {
            try {
                CharSequence cls = p.getClassName();
                if (cls != null && cls.toString().endsWith("WebView")) {
                    return true;
                }
                p = p.getParent();
            } catch (Throwable t) {
                return false;
            }
        }
        return false;
    }

    /** 在 WebView 子树里找有输入焦点的可编辑节点；找不到返回 null。 */
    private static AccessibilityNodeInfo findFocusedEditableInWebView(
            AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > 14) {
            return null;
        }
        try {
            CharSequence cls = node.getClassName();
            if (cls != null && cls.toString().endsWith("WebView")) {
                AccessibilityNodeInfo found = findEditableInTree(node, 0, true);
                if (found != null) {
                    return found;
                }
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo found = findFocusedEditableInWebView(node.getChild(i), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static AccessibilityNodeInfo findEditableInTree(
            AccessibilityNodeInfo node, int depth, boolean requireFocused) {
        if (node == null || depth > 14) {
            return null;
        }
        try {
            if (isEditable(node) && node.isVisibleToUser()
                    && (!requireFocused || node.isFocused())) {
                return node;
            }
            int count = node.getChildCount();
            for (int i = 0; i < count; i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) {
                    continue;
                }
                AccessibilityNodeInfo found = findEditableInTree(child, depth + 1, requireFocused);
                if (found != null) {
                    return found;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    // ---------------- 注入 ----------------

    public static int inject(String text, boolean enter) {
        return inject(text, enter, false);
    }

    /**
     * @param append 追加而不是替换。整页扫描/批量采集时用：一次几十条，替换的话前一条就没了。
     *               实时扫码保持替换——收银的商品码框是"填一条、回车提交、清空等下一条"。
     */
    public static int inject(String text, boolean enter, boolean append) {
        Target t = findTarget();
        if (t == null) {
            return instance == null ? NO_SERVICE : NO_TARGET;
        }
        if (t.password) {
            return PASSWORD;
        }

        // 回车语义分流（真机结论：小米备忘录正文对无障碍上报 multiline=false，
        // 且 ACTION_IME_ENTER 返回 true 却不产生换行——只发 IME 回车会"成功但没效果"）。
        // 追加/多行场景把换行符直接拼进内容最稳：多行与富文本编辑器正常换行，
        // 单行控件会把它过滤掉；单行字段（收银商品码框）的提交仍靠 IME 回车。
        if (enter && (t.multiline || append)) {
            text = text + "\n";
        }

        String payload = text;
        boolean needImeEnter = false;
        CharSequence existing = null;
        // -1 = 不显式设置光标；>=0 表示写完后把光标放回这里
        int caret = -1;
        // 替换模式：光标放在 payload 末尾，但要等回车处理完再算（老系统会补 \n）
        boolean caretAtEnd = false;
        // 追加模式实际写入的片段（粘贴用，只含新增内容）
        String pasteFragment = payload;
        // 本次读取到的光标原始位置，仅用于日志排查
        int selStart = -1;
        int selEnd = -1;

        if (append || t.multiline) {
            // ACTION_SET_TEXT 是"整段替换"，不拼接就会把上一条冲掉——整页扫描几十个序列号，
            // 最后只剩最后一个。"多行"字段自动拼（记事本、备忘录），批量录入则一律拼。
            // 拼接与光标计算是纯字符串逻辑，抽在 {@link A11yAppend}，tools/sim 可离线回放。
            try {
                existing = t.node.getText();
            } catch (Throwable ignored) {
            }
            try {
                selStart = t.node.getTextSelectionStart();
                selEnd = t.node.getTextSelectionEnd();
            } catch (Throwable ignored) {
            }
            String[] r = A11yAppend.build(
                    existing == null ? "" : existing.toString(), selStart, selEnd, text);
            payload = r[0];
            pasteFragment = r[1];
            caret = Integer.parseInt(r[2]);
        } else {
            // 替换模式：整段写完后光标也放回末尾，别停在开头
            caretAtEnd = true;
        }
        Log.i("VSG-A11y", "inject pkg=" + t.pkg + " multiline=" + t.multiline
                + " existingLen=" + (existing == null ? -1 : existing.length())
                + " sel=" + selStart + ".." + selEnd
                + " payloadLen=" + payload.length() + " caret=" + caret);

        if (enter) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // 多行字段已拼入换行符，IME 回车只在单行字段上发（负责"提交"），
                // 避免既拼 \n 又回车的编辑器出现双倍空行
                needImeEnter = !t.multiline;
            } else if (!t.multiline && !append) {
                // 老系统的单行替换模式没有可用的回车手段，如实报错，
                // 避免"写了一半又降级"造成内容重复
                return ENTER_UNSUPPORTED;
            }
        }
        if (caretAtEnd) {
            caret = payload.length();
        }

        boolean wrote;
        if (append || t.multiline) {
            // 追加模式优先走"粘贴"：编辑器自己的粘贴管线会把光标自然地停在插入内容之后。
            // 实测小米备忘录对 SET_TEXT 的处理是"整段替换后光标回开头"，紧跟着补发的
            // ACTION_SET_SELECTION 也会被它自己的文本变更回调改回去——只有粘贴这条路
            // 光标不会乱跑。粘贴不被支持的控件（部分 WebView 表单）再退回 SET_TEXT。
            wrote = paste(t.node, pasteFragment, caret)
                    || setText(t.node, payload, caret);
        } else {
            wrote = setText(t.node, payload, caret);
        }
        if (!wrote) {
            return SET_FAILED;
        }
        if (needImeEnter && !pressEnter(t.node)) {
            // 文本已经写进去了，此时绝不能返回"失败"让路由去走 Shizuku，
            // 否则同一段内容会被发两遍。
            return OK_NO_ENTER;
        }
        return OK;
    }

    private static boolean setText(AccessibilityNodeInfo node, String text, int caret) {
        try {
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            Bundle args = new Bundle();
            args.putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text);
            if (node.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) {
                // SET_TEXT 是"整段替换"，不少编辑器会把光标重置到开头。
                // 把光标放回刚写入内容的末尾——这是打字的自然位置，App 不去纠，
                // 用户就得每次扫码后自己把光标拖回末尾。
                if (caret >= 0) {
                    restoreCaret(node, caret);
                }
                return true;
            }
        } catch (Throwable ignored) {
        }
        // 少数控件不认 ACTION_SET_TEXT，退回"写剪贴板 + 粘贴"
        return paste(node, text, caret);
    }

    /** 把光标放到 caret 位置（尽力而为：不支持设置选区的控件忽略即可，内容已写进去）。 */
    private static void restoreCaret(AccessibilityNodeInfo node, int caret) {
        try {
            Bundle sel = new Bundle();
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, caret);
            sel.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, caret);
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, sel);
        } catch (Throwable ignored) {
        }
    }

    private static boolean paste(AccessibilityNodeInfo node, String text, int caret) {
        A11y s = instance;
        if (s == null) {
            return false;
        }
        try {
            // 粘贴落在光标处，先确保焦点在目标输入框上
            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
            ClipboardManager cm = (ClipboardManager)
                    s.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) {
                return false;
            }
            cm.setPrimaryClip(ClipData.newPlainText("vsg", text));
            boolean ok = node.performAction(AccessibilityNodeInfo.ACTION_PASTE);
            // 无障碍粘贴不一定同步编辑器的 IME 光标：真机（小米备忘录）实测，
            // 粘贴后紧跟着发的 ACTION_IME_ENTER 会落在粘贴前的旧光标位置，
            // 换行插进内容中间。粘贴成功后把光标补到刚写入内容的末尾。
            if (ok && caret >= 0) {
                restoreCaret(node, caret);
            }
            return ok;
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean pressEnter(AccessibilityNodeInfo node) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return false;
        }
        try {
            return node.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.getId());
        } catch (Throwable t) {
            return false;
        }
    }

    /** 供 ModalGuard 点击弹窗按钮。 */
    public static boolean click(AccessibilityNodeInfo node) {
        if (node == null) {
            return false;
        }
        try {
            AccessibilityNodeInfo clickable = node;
            AccessibilityNodeInfo p = node.getParent();
            int guard = 0;
            while (!clickable.isClickable() && p != null && guard++ < 4) {
                clickable = p;
                p = p.getParent();
            }
            return clickable.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        } catch (Throwable t) {
            return false;
        }
    }

    // ---------------- 生命周期 ----------------

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        super.onDestroy();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }
        CharSequence pkg = event.getPackageName();
        if (pkg == null || event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            return;
        }
        // 自己也算一个窗口：悬浮面板展开/收起同样会发 WINDOW_STATE_CHANGED，把前台记成
        // 自己。图库流程据此判断"回来时该等谁"，记错就会等错人（等不到，白等 3 秒）
        if (getPackageName().contentEquals(pkg)) {
            return;
        }
        foregroundPackage = pkg.toString();
    }

    @Override
    public void onInterrupt() {
    }
}
