package com.virtualscanner.gun;

/**
 * 追加模式的纯字符串逻辑：把新内容插到光标处，并算出写完后光标应停的位置。
 *
 * <p>与 Android 类解耦（不 import 任何 android.*），tools/sim 可离线回放。
 * A11y 注入时拿现有文本和光标位置调 {@link #build}，再走 ACTION_SET_TEXT +
 * ACTION_SET_SELECTION 落到真实控件上。
 *
 * <p>规则：
 * <ul>
 *   <li>光标拿不到（-1 / 越界）按"末尾"处理——与整页扫描的顺次录入语义一致</li>
 *   <li>新内容自带结尾换行时（自动回车已把 \n 拼进内容）<b>前面不再补 \n</b>：
 *       补了就成了"回车 → 内容 → 回车"，比真扫描枪多一个前导空行</li>
 *   <li>只有内容本身没有结尾换行（自动回车关掉）时才补前导 \n，避免两个码粘成一串</li>
 *   <li>光标停在刚写入内容的末尾：下次扫码接着往后追加，用户也能直接继续打字</li>
 * </ul>
 */
public final class A11yAppend {

    private A11yAppend() {
    }

    /**
     * @param existing 输入框现有文本（null 按空串处理）
     * @param selStart 扫码前的选区起点（-1 表示拿不到）
     * @param selEnd   扫码前的选区终点（-1 表示拿不到）
     * @param text     要插入的新内容
     * @return 长度 3：[0]=写整段用的 payload，[1]=粘贴回退用的片段，[2]=光标位置（字符串形式）
     */
    public static String[] build(String existing, int selStart, int selEnd, String text) {
        String b = existing == null ? "" : existing;
        int len = b.length();
        // 拿不到光标就按"末尾"处理
        int start = (selStart < 0 || selStart > len) ? len : selStart;
        int end = (selEnd < start || selEnd > len) ? start : selEnd;
        String before = b.substring(0, start);
        String after = b.substring(end);
        // 新内容自带结尾换行（自动回车）→ 不补前导换行，否则就是"回车 → 内容 → 回车"；
        // 只有自动回车关掉（内容不带 \n）时才补，避免两个码粘成一串。
        // 上一段本来就以换行结尾时也不补。
        String sep = (!before.isEmpty() && !before.endsWith("\n")
                && !text.endsWith("\n")) ? "\n" : "";
        String payload = before + sep + text + after;
        String fragment = sep + text;
        int caret = (before + sep + text).length();
        return new String[]{payload, fragment, String.valueOf(caret)};
    }
}
