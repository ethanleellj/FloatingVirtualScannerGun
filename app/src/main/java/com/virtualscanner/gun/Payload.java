package com.virtualscanner.gun;

/**
 * 一次注入要发送的完整内容：前缀 + 条码 + 后缀，外加结尾是否补回车。
 *
 * 前后缀按"成对"语义处理：两者都支持转义序列（\n \r \t \\ \xNN），
 * 因此 `[` + 内容 + `]` 、`PDA-` + 内容 + `\r\n` 这类键盘楔习惯都能直接配出来。
 * 解出来的控制字符不在本类里拆分成按键——那是各通道自己的事：
 * 剪贴板原样给，无障碍写文本，Shizuku 拆成 input text 与 input keyevent 交替执行。
 */
public final class Payload {

    public final String code;
    public final String text;
    public final boolean enter;

    private Payload(String code, String text, boolean enter) {
        this.code = code;
        this.text = text;
        this.enter = enter;
    }

    public static Payload of(Profile p, String code) {
        String safeCode = code == null ? "" : code;
        String text = unescape(p.prefix) + safeCode + unescape(p.suffix);
        return new Payload(safeCode, text, p.enter);
    }

    /** 手动发送/自检用：不套前后缀，直接发这段文本。 */
    public static Payload raw(String text, boolean enter) {
        return new Payload(text, text, enter);
    }

    /** 转义解码：\n \r \t \0 \\ \xNN，其余原样保留。 */
    public static String unescape(String s) {
        if (s == null || s.isEmpty()) {
            return "";
        }
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c != '\\' || i + 1 >= s.length()) {
                b.append(c);
                continue;
            }
            char n = s.charAt(i + 1);
            switch (n) {
                case 'n':
                    b.append('\n');
                    i++;
                    break;
                case 'r':
                    b.append('\r');
                    i++;
                    break;
                case 't':
                    b.append('\t');
                    i++;
                    break;
                case '0':
                    b.append('\0');
                    i++;
                    break;
                case '\\':
                    b.append('\\');
                    i++;
                    break;
                case 'x':
                    if (i + 3 < s.length()) {
                        try {
                            b.append((char) Integer.parseInt(s.substring(i + 2, i + 4), 16));
                            i += 3;
                        } catch (NumberFormatException e) {
                            b.append(c);
                        }
                    } else {
                        b.append(c);
                    }
                    break;
                default:
                    // 未识别的转义保持原样，避免用户输入被悄悄吞掉
                    b.append(c);
                    break;
            }
        }
        return b.toString();
    }

    /** 把控制字符转成可见形式，用于界面预览与历史列表。 */
    public static String visible(String s) {
        if (s == null) {
            return "";
        }
        return s.replace("\r", "\\r").replace("\n", "\\n").replace("\t", "\\t");
    }

    /** 预览：不带前后缀的发送内容，供主界面"最终发送"提示用。 */
    public String preview() {
        return visible(text) + (enter ? " ⏎" : "");
    }

    /** 控制字符转可见形式的完整发送内容。 */
    public String visibleText() {
        return visible(text);
    }
}
