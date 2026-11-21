package com.virtualscanner.gun;

import java.util.Locale;

/**
 * 比较 GitHub tag 与本地 versionName 的数字段，纯逻辑、可离线回放。
 *
 * <p>"v0.2.1"、"0.2"、"0.2-demo" 视为同一版本；末尾的非数字后缀不参与比较。
 */
public final class Versions {

    private Versions() {
    }

    public static int compare(String remote, String local) {
        int[] a = numbers(remote);
        int[] b = numbers(local);
        int len = Math.max(a.length, b.length);
        for (int i = 0; i < len; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return x < y ? -1 : 1;
            }
        }
        return 0;
    }

    private static int[] numbers(String v) {
        if (v == null) {
            return new int[0];
        }
        String s = v.trim().toLowerCase(Locale.US);
        if (s.startsWith("v")) {
            s = s.substring(1);
        }
        StringBuilder digits = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isDigit(c) || c == '.') {
                digits.append(c);
            } else {
                break;
            }
        }
        String[] parts = digits.toString().split("\\.");
        int[] out = new int[parts.length];
        for (int i = 0; i < parts.length; i++) {
            try {
                out[i] = parts[i].isEmpty() ? 0 : Integer.parseInt(parts[i]);
            } catch (NumberFormatException ignored) {
                out[i] = 0;
            }
        }
        return out;
    }
}
