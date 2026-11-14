import com.virtualscanner.gun.ScanGuard;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * ScanGuard 离线回放：用确定的帧时间线量"连扫两件商品"这类时序边界。
 *
 * <p>相机 10 fps、一帧 100 ms 是实测设置（ImageAnalysis 的默认节流）。
 * 场景里的 null 表示"这一帧没解出条码"。
 *
 * <p>编译运行：
 * <pre>
 * javac -d tools/sim/out app/src/main/java/com/virtualscanner/gun/ScanGuard.java tools/sim/GuardSim.java
 * java -cp tools/sim/out GuardSim
 * </pre>
 */
public final class GuardSim {

    private static final long FRAME = 100L;

    /** 一帧的结果：内容或 null（没解出）。 */
    private static final class Step {
        final String value;
        final int times;
        final boolean blank;

        private Step(String value, int times) {
            this.value = value;
            this.times = times;
            this.blank = value == null;
        }
    }

    private static Step code(String v, int times) {
        return new Step(v, times);
    }

    private static Step blank(int times) {
        return new Step(null, times);
    }

    private static final class Outcome {
        final List<String> emits = new ArrayList<>();
        final List<Long> emitAt = new ArrayList<>();
        final List<String> events = new ArrayList<>();
        int rejected;

        String summary() {
            if (emits.isEmpty()) {
                return "0 次注入";
            }
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < emits.size(); i++) {
                if (i > 0) {
                    sb.append(" -> ");
                }
                sb.append("'").append(emits.get(i)).append("'@")
                        .append(emitAt.get(i)).append("ms");
            }
            return emits.size() + " 次注入: " + sb;
        }
    }

    private static Outcome run(Step... steps) {
        return runWith(ScanGuardDefaults.NONE, steps);
    }

    private static Outcome runWith(ScanGuardDefaults d, Step... steps) {
        ScanGuard guard = new ScanGuard();
        Outcome out = new Outcome();
        guard.setListener((event, detail) -> out.events.add(event + " " + detail));
        if (d == ScanGuardDefaults.IGNORE_DUP_OFF) {
            guard.setIgnoreDup(false);
        }
        long t = 0L;
        for (Step s : steps) {
            for (int i = 0; i < s.times; i++) {
                String got = guard.onFrame(s.value, t);
                if (got != null) {
                    out.emits.add(got);
                    out.emitAt.add(t);
                }
                t += FRAME;
            }
        }
        out.rejected = guard.rejected();
        return out;
    }

    private enum ScanGuardDefaults {
        NONE, IGNORE_DUP_OFF
    }

    private static final String A = "6901236347102";
    private static final String B = "6901028075831";
    private static final String X = "7904869343434";

    public static void main(String[] args) {
        Map<String, Outcome> results = new LinkedHashMap<>();

        // 1. 扫完 A 不加停顿，直接换 B（两张标签贴在一张纸上时就是这样）
        results.put("A 扫完直接换 B（无空档）",
                run(code(A, 4), code(B, 15)));

        // 2. 中间空 700ms：刚够解锁
        results.put("A -> 空 700ms -> B",
                run(code(A, 4), blank(7), code(B, 12)));

        // 3. 中间空 1.5s：正常换商品的手速
        results.put("A -> 空 1.5s -> B",
                run(code(A, 4), blank(15), code(B, 12)));

        // 4. 静止条码 40s：锁是否真的兜住
        results.put("同一张标签静止 40s", run(code(A, 400)));

        // 5. 手抖：离开 600ms 又回到同一个码（低于 MIN_AWAY_MS）
        results.put("同码离开 600ms 又回来",
                run(code(A, 4), blank(6), code(A, 10)));

        // 6. 同码离开 1.3s 再回来（收银员重复扫同一件）
        results.put("同码离开 1.3s 再回来",
                run(code(A, 4), blank(13), code(A, 10)));

        // 7. 真码与错值逐帧交替：确认链被打断，应当一次都不注入
        results.put("真码/错值逐帧交替 2s",
                run(code(A, 1), code(X, 1), code(A, 1), code(X, 1), code(A, 1),
                        code(X, 1), code(A, 1), code(X, 1), code(A, 1), code(X, 1),
                        code(A, 1), code(X, 1), code(A, 1), code(X, 1), code(A, 1),
                        code(X, 1), code(A, 1), code(X, 1), code(A, 1), code(X, 1)));

        // 8. 错值连成 3 帧（已知限制的量化：这种误读会被当成真码）
        results.put("错值连 3 帧后回真码",
                run(code(A, 2), code(X, 3), code(A, 10)));

        // 9. 换 B 之后再换 C，验证锁不会永久卡死
        results.put("A -> 空 1.5s -> B -> 空 1.5s -> C",
                run(code(A, 4), blank(15), code(B, 12), blank(15), code("4006381333931", 12)));

        // 10. 忽略重复开（默认）：同码离开 1.3s 再回来，只录一次
        results.put("默认(忽略重复)：同码离开 1.3s 再回来",
                run(code(A, 4), blank(13), code(A, 10)));

        // 11. 忽略重复关：同码离开 1.3s 再回来，应录两次
        results.put("忽略重复关：同码离开 1.3s 再回来",
                runWith(ScanGuardDefaults.IGNORE_DUP_OFF,
                        code(A, 4), blank(13), code(A, 10)));

        int emitSum = 0;
        for (Map.Entry<String, Outcome> e : results.entrySet()) {
            Outcome o = e.getValue();
            emitSum += o.emits.size();
            System.out.println("── " + e.getKey());
            System.out.println("   结果: " + o.summary() + "   (累计丢弃 " + o.rejected + ")");
            if (!o.events.isEmpty()) {
                System.out.println("   事件: " + String.join(" | ", o.events));
            }
        }
        System.out.println();
        System.out.println("合计注入 " + emitSum + " 次");
    }
}
