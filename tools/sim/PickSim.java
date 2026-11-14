import com.virtualscanner.gun.BarcodePick;
import com.virtualscanner.gun.ScanGuard;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * 一帧多个条码时的采信规则回放。
 *
 * <p>默认策略是"只取第一个"，所以这里既验证四个策略各自的口径，也把
 * "识别器返回顺序逐帧抖动"这个真实情况对照出来：默认口径下三帧一致会凑不齐，
 * 换成"面积最大"才能稳定。
 */
public final class PickSim {

    private static int pass;
    private static int fail;

    private static BarcodePick.Candidate c(String v, int l, int t, int r, int b) {
        return new BarcodePick.Candidate(v, l, t, r, b);
    }

    private static void check(String name, String expect, String actual) {
        boolean ok = expect == null ? actual == null : expect.equals(actual);
        System.out.println((ok ? "  PASS  " : "  FAIL  ") + name
                + "  -> " + actual + (ok ? "" : "  (期望 " + expect + ")"));
        if (ok) {
            pass++;
        } else {
            fail++;
        }
    }

    public static void main(String[] args) {
        String A = "6901236347102";   // 商品码，近，框大
        String L = "6901028075831";   // 货架标签，远，框小

        System.out.println("── 默认：只取第一个（POLICY_FIRST）");
        check("单码", A, BarcodePick.pick(Collections.singletonList(c(A, 100, 100, 500, 300)),
                1000, 800, BarcodePick.POLICY_FIRST));
        check("两码：取列表第一个，不看面积", L,
                BarcodePick.pick(Arrays.asList(c(L, 10, 10, 90, 60), c(A, 100, 100, 500, 300)),
                        1000, 800, BarcodePick.POLICY_FIRST));
        check("两码：顺序翻转后跟着变（口径就是返回顺序）", A,
                BarcodePick.pick(Arrays.asList(c(A, 100, 100, 500, 300), c(L, 10, 10, 90, 60)),
                        1000, 800, BarcodePick.POLICY_FIRST));
        check("跳过空值再取第一个", A,
                BarcodePick.pick(Arrays.asList(new BarcodePick.Candidate("  "),
                        new BarcodePick.Candidate(null), c(A, 1, 1, 50, 50)),
                        1000, 800, BarcodePick.POLICY_FIRST));

        System.out.println("── 序号策略：只取第 N 个");
        List<BarcodePick.Candidate> four = Arrays.asList(
                c(A, 100, 100, 500, 300), c(L, 10, 10, 90, 60),
                c("4006381333931", 600, 500, 700, 560), c("1234567890128", 800, 700, 900, 760));
        check("第一个", A, BarcodePick.pick(four, 1000, 900, BarcodePick.POLICY_FIRST));
        check("第二个", L, BarcodePick.pick(four, 1000, 900, BarcodePick.POLICY_SECOND));
        check("第三个", "4006381333931",
                BarcodePick.pick(four, 1000, 900, BarcodePick.POLICY_THIRD));
        check("第四个", "1234567890128",
                BarcodePick.pick(four, 1000, 900, BarcodePick.POLICY_FOURTH));
        check("只有两个码时要第三个 -> 本帧无码", null,
                BarcodePick.pick(Arrays.asList(c(A, 100, 100, 500, 300), c(L, 10, 10, 90, 60)),
                        1000, 800, BarcodePick.POLICY_THIRD));
        check("空值不占序号", L,
                BarcodePick.pick(Arrays.asList(new BarcodePick.Candidate(""),
                        c(A, 100, 100, 500, 300), c(L, 10, 10, 90, 60)),
                        1000, 800, BarcodePick.POLICY_SECOND));

        System.out.println("── 末位策略：只取最后一个");
        check("四个码取末位", "1234567890128",
                BarcodePick.pick(four, 1000, 900, BarcodePick.POLICY_LAST));
        check("两码取末位", L,
                BarcodePick.pick(Arrays.asList(c(A, 100, 100, 500, 300), c(L, 10, 10, 90, 60)),
                        1000, 800, BarcodePick.POLICY_LAST));
        check("单码时末位就是它", A,
                BarcodePick.pick(Collections.singletonList(c(A, 100, 100, 500, 300)),
                        1000, 800, BarcodePick.POLICY_LAST));
        check("末尾是空值则往前取", A,
                BarcodePick.pick(Arrays.asList(c(A, 100, 100, 500, 300), new BarcodePick.Candidate(" "),
                        new BarcodePick.Candidate(null)), 1000, 800, BarcodePick.POLICY_LAST));
        check("末位策略空列表", null,
                BarcodePick.pick(Collections.emptyList(), 1000, 800, BarcodePick.POLICY_LAST));

        System.out.println("── 其余策略");
        check("面积最大", A,
                BarcodePick.pick(Arrays.asList(c(L, 10, 10, 90, 60), c(A, 100, 100, 500, 300)),
                        1000, 800, BarcodePick.POLICY_LARGEST));
        check("最靠中心", A,
                BarcodePick.pick(Arrays.asList(c(L, 0, 0, 200, 200), c(A, 400, 300, 600, 500)),
                        1000, 800, BarcodePick.POLICY_CENTER));
        check("多码时跳过该帧", null,
                BarcodePick.pick(Arrays.asList(c(L, 10, 10, 90, 60), c(A, 100, 100, 500, 300)),
                        1000, 800, BarcodePick.POLICY_SKIP));
        check("跳帧策略下只有单码仍照扫", A,
                BarcodePick.pick(Collections.singletonList(c(A, 100, 100, 500, 300)),
                        1000, 800, BarcodePick.POLICY_SKIP));
        check("同一个码出现两次不算歧义", A,
                BarcodePick.pick(Arrays.asList(c(A, 10, 10, 90, 60), c(A, 100, 100, 500, 300)),
                        1000, 800, BarcodePick.POLICY_SKIP));
        check("空列表", null, BarcodePick.pick(Collections.emptyList(), 1000, 800,
                BarcodePick.POLICY_FIRST));

        System.out.println("── 多码同框连续 10 帧（返回顺序逐帧抖动）");
        List<String> first = emitAll(BarcodePick.POLICY_FIRST);
        List<String> largest = emitAll(BarcodePick.POLICY_LARGEST);
        check("默认口径：三帧一致凑不齐，一次都没注入", "[]", first.toString());
        check("面积最大：稳定取到商品码并注入一次", "[6901236347102]", largest.toString());

        System.out.println("── 整页扫描：一张照片里的全部内容（POLICY_ALL）");
        // 模拟一页纸：三行、每行两列，识别器返回顺序是乱的（真实情况就是乱的）
        List<BarcodePick.Candidate> page = Arrays.asList(
                c("S3", 700, 420, 900, 470),    // 第二行、右列
                c("S1", 100, 100, 300, 150),    // 第一行、左列
                c("S4", 100, 520, 300, 570),    // 第三行、左列
                c("S2", 400, 105, 600, 152),    // 第一行、中列（比 S1 低 5px，仍属同一行）
                c("S1", 100, 100, 300, 150));   // 同一个物理码被重复框出
        check("三行四码：先上后下、同一行先左后右", "[S1, S2, S3, S4]",
                BarcodePick.pickAll(page, 0).toString());
        check("重复的框只录一次", "4",
                String.valueOf(BarcodePick.pickAll(page, 0).size()));
        check("上限 2 条", "[S1, S2]", BarcodePick.pickAll(page, 2).toString());
        check("上限为 0 表示不限", "[S1, S2, S3, S4]", BarcodePick.pickAll(page, 0).toString());
        check("空列表", "[]", BarcodePick.pickAll(Collections.emptyList(), 0).toString());
        check("全是空值", "[]", BarcodePick.pickAll(Arrays.asList(
                new BarcodePick.Candidate(""), new BarcodePick.Candidate(null)), 0).toString());
        check("没有包围盒的结果排在最后", "[S1, X]", BarcodePick.pickAll(Arrays.asList(
                new BarcodePick.Candidate("X"), c("S1", 100, 100, 300, 150)), 0).toString());
        check("竖排一列（序列号常见排法）", "[A, B, C]", BarcodePick.pickAll(Arrays.asList(
                c("C", 100, 800, 400, 850), c("A", 100, 100, 400, 150),
                c("B", 100, 450, 400, 500)), 0).toString());

        System.out.println();
        System.out.println("合计 " + pass + " 通过 / " + fail + " 失败");
        if (fail > 0) {
            System.exit(1);
        }
    }

    /** 两码同框、ML Kit 返回顺序逐帧翻转时，某策略在 10 帧里注入了什么。 */
    private static List<String> emitAll(int policy) {
        String A = "6901236347102";
        String L = "6901028075831";
        ScanGuard guard = new ScanGuard();
        List<String> emitted = new ArrayList<>();
        long t = 0L;
        for (int frame = 0; frame < 10; frame++) {
            List<BarcodePick.Candidate> scene = frame % 2 == 0
                    ? Arrays.asList(c(L, 10, 10, 90, 60), c(A, 100, 100, 500, 300))
                    : Arrays.asList(c(A, 100, 100, 500, 300), c(L, 10, 10, 90, 60));
            String v = guard.onFrame(BarcodePick.pick(scene, 1000, 800, policy), t);
            if (v != null) {
                emitted.add(v);
            }
            t += 100L;
        }
        return emitted;
    }
}
