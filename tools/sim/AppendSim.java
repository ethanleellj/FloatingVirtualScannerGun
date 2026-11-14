import com.virtualscanner.gun.A11yAppend;

/**
 * A11yAppend 离线回放：验证"追加到光标处"的拼接与光标计算。
 *
 * <p>背景：ACTION_SET_TEXT 整段替换后，不少编辑器（含小米备忘录）把光标重置到开头，
 * 用户每次扫码后都得把光标拖到末尾。修复 = 在光标处插入 + 写完后用
 * ACTION_SET_SELECTION 把光标放回刚写入内容的末尾。这里回放的是前半段纯逻辑。
 *
 * <p>另一个约定：自动回车开启时，A11y 已把 {@code \n} 拼在内容末尾，此时
 * {@link A11yAppend} <b>不再补前导换行</b>——否则真机上看到的是
 * "回车 → 内容 → 回车"，比扫描枪多一个前导空行。
 *
 * <p>编译运行：
 * <pre>
 * javac -d tools/sim/out app/src/main/java/com/virtualscanner/gun/A11yAppend.java tools/sim/AppendSim.java
 * java -cp tools/sim/out AppendSim
 * </pre>
 */
public final class AppendSim {

    private static int checks;

    private static int failures;

    public static void main(String[] args) {
        // 1. 空输入框 + 拿不到光标：直接写，光标到末尾
        check("empty field, enter on",
                A11yAppend.build("", -1, -1, "6901\n"),
                "6901\n", "6901\n", 5);

        // 2. 光标在末尾，上一段以 \n 结尾（正常连续扫码）→ 只带结尾换行
        check("append after previous newline",
                A11yAppend.build("TEST123\n", 8, 8, "6901\n"),
                "TEST123\n6901\n", "6901\n", 13);

        // 3. 现有文本没有结尾换行（例如用户自己打的字）→ 不补前导换行，
        //    内容紧跟光标，回车只留在末尾
        check("existing text without trailing newline",
                A11yAppend.build("TEST123", 7, 7, "6901\n"),
                "TEST1236901\n", "6901\n", 12);

        // 4. 光标在行中间 → 插到光标处（不再自动另起一行）
        check("caret in middle",
                A11yAppend.build("ABCDEF", 3, 3, "X\n"),
                "ABCX\nDEF", "X\n", 5);

        // 5. 有选中区域 → 选区被新内容替换（与普通打字行为一致）
        check("selection replaced",
                A11yAppend.build("ABCDEF", 2, 5, "X\n"),
                "ABX\nF", "X\n", 4);

        // 6. 自动回车关掉（内容不带 \n）→ 补前导 \n，避免两个码粘成一串
        check("enter off keeps separator",
                A11yAppend.build("TEST123", 7, 7, "6901"),
                "TEST123\n6901", "\n6901", 12);

        // 7. 自动回车关掉 + 空输入框 → 什么都不补
        check("enter off, empty field",
                A11yAppend.build("", -1, -1, "6901"),
                "6901", "6901", 4);

        // 8. 光标越界（节点文本与选区不同步的脏数据）→ 按末尾处理
        check("selection out of range",
                A11yAppend.build("TEST123", 99, 99, "6901\n"),
                "TEST1236901\n", "6901\n", 12);

        // 9. 光标倒挂（end < start，真实控件不会报，防御性处理）→ 按末尾处理
        check("selection inverted",
                A11yAppend.build("TEST123", 5, 2, "6901\n"),
                "TEST16901\n23", "6901\n", 10);

        if (failures == 0) {
            System.out.println("AppendSim: all " + checks + " scenarios passed");
        } else {
            System.out.println("AppendSim: " + failures + " FAILURES");
            System.exit(1);
        }
    }

    private static void check(String name, String[] r,
                              String wantPayload, String wantFragment, int wantCaret) {
        checks++;
        boolean ok = wantPayload.equals(r[0])
                && wantFragment.equals(r[1])
                && wantCaret == Integer.parseInt(r[2]);
        if (ok) {
            System.out.println("PASS  " + name);
        } else {
            failures++;
            System.out.println("FAIL  " + name
                    + "  payload=[" + r[0] + "] want=[" + wantPayload + "]"
                    + "  fragment=[" + r[1] + "] want=[" + wantFragment + "]"
                    + "  caret=" + r[2] + " want=" + wantCaret);
        }
    }
}
