package com.virtualscanner.gun;

import java.util.ArrayList;
import java.util.List;

/**
 * 一帧里出现多个条码时，决定采信哪一个。
 *
 * <p>默认是 <b>只取第一个</b>（{@link #POLICY_FIRST}）：按识别结果返回的先后顺序取
 * 第一个非空值。这是业务上最简单的口径——收银台一次只对准一件商品，画面里若同时
 * 出现货架标签之类的干扰码，以先识别到的为准。
 *
 * <p>需要更稳的场合可在设置里换策略：只取第二个 / 第三个 / 第四个、只取最后一个、取
 * 面积最大的（离镜头最近的那个）、取最靠画面中心的，或者干脆拒绝采信（多码时不扫，
 * 等用户把画面收干净）。序号类策略在画面里没有第 N 个码时按"本帧没识别到"处理，不拿
 * 别的码顶替；"只取最后一个"总能取到值（只要画面里至少有一个可解码的码）。
 *
 * <p>注意 {@link #POLICY_FIRST} 的口径依赖识别器的返回顺序。"取面积最大"和"取中心"
 * 是为"返回顺序不稳定"准备的替代口径：同一组码逐帧换位置时，前者的三帧一致永远
 * 凑不齐（见 tools/sim/PickSim.java 的对照）。
 *
 * <p>另有"整页扫描"（{@link #POLICY_ALL}）：一页纸上印着一整列序列号时，逐件对准镜头
 * 太慢，改成拍一张照片、由 {@link #pickAll} 把全页的码按阅读顺序一次列出来。
 *
 * <p>纯逻辑，不依赖 Android，便于离线回放验证。
 */
public final class BarcodePick {

    /** 默认：取返回列表里第一个非空值。 */
    public static final int POLICY_FIRST = 0;
    /** 取包围盒面积最大的（离镜头最近、占画面最多）。 */
    public static final int POLICY_LARGEST = 1;
    /** 取离画面中心最近的。 */
    public static final int POLICY_CENTER = 2;
    /** 有多个不同内容时整帧不采信。 */
    public static final int POLICY_SKIP = 3;
    /**
     * 取返回列表里第 2 / 3 / 4 个非空值。
     *
     * <p>取值刻意排在 4..6，不插在中间：这几个策略存在 SharedPreferences 里，
     * 调整顺序会把老用户的设置解释成另一个策略。
     */
    public static final int POLICY_SECOND = 4;
    public static final int POLICY_THIRD = 5;
    public static final int POLICY_FOURTH = 6;
    /**
     * 取返回列表里最后一个非空值。
     *
     * <p>取值排在末尾，理由同上：已存盘的整数值不能重排。语义上它与"只取第一个"对称，
     * 用来应对"关心的那张总是被排到最后"的场合（例如干扰码在前）。
     */
    public static final int POLICY_LAST = 7;
    /**
     * 整页扫描：不做取舍，把一张照片里的所有不同内容都录进去。
     *
     * <p>它是一枚"模式开关"，不是"取哪一个"的口径：选中后实时帧不再逐帧注入（一页纸上
     * 几十个码，逐帧录会乱序、漏号），改为让用户拍一张清晰照片，由 {@link #pickAll} 一次
     * 列出全部内容，再按阅读顺序逐条录入。手举着扫整页必然晃动，糊掉的码会识别错，这是
     * 相机预览分辨率（1280×720）和手持抖动共同决定的，拍照镜像则是全分辨率。
     *
     * <p>取值 8 同样只追加不改写。
     */
    public static final int POLICY_ALL = 8;

    /** 整页扫描一次最多录入多少条：防止把整面货架拍进来时一路录到失控。 */
    public static final int MAX_ALL = 100;

    /** 一条识别结果的必要信息。坐标可为 0（等价于"没有包围盒"）。 */
    public static final class Candidate {
        public final String value;
        public final int left;
        public final int top;
        public final int right;
        public final int bottom;

        public Candidate(String value, int left, int top, int right, int bottom) {
            this.value = value;
            this.left = left;
            this.top = top;
            this.right = right;
            this.bottom = bottom;
        }

        public Candidate(String value) {
            this(value, 0, 0, 0, 0);
        }

        boolean hasBox() {
            return right > left && bottom > top;
        }

        long area() {
            return hasBox() ? (long) (right - left) * (bottom - top) : 0L;
        }

        /** 到画面中心的距离平方；没有包围盒时给一个"很远"的值。 */
        long centerDistance2(int frameW, int frameH) {
            if (!hasBox()) {
                return Long.MAX_VALUE / 4;
            }
            long cx = (left + right) / 2L;
            long cy = (top + bottom) / 2L;
            long dx = cx - frameW / 2L;
            long dy = cy - frameH / 2L;
            return dx * dx + dy * dy;
        }
    }

    private BarcodePick() {
    }

    public static String pick(List<Candidate> candidates, int frameW, int frameH, int policy) {
        if (candidates == null || candidates.isEmpty()) {
            return null;
        }
        if (policy == POLICY_SKIP && distinctValues(candidates) > 1) {
            return null;
        }
        final int nth = nthIndex(policy);
        if (nth > 0) {
            int seen = 0;
            for (Candidate c : candidates) {
                if (c == null || c.value == null || c.value.trim().isEmpty()) {
                    continue;
                }
                if (++seen == nth) {
                    return c.value;
                }
            }
            // 这张画面里没有第 N 个码：不拿别的码顶替，按"本帧没识别到"处理
            return null;
        }
        if (policy == POLICY_LAST) {
            String last = null;
            for (Candidate c : candidates) {
                if (c == null || c.value == null || c.value.trim().isEmpty()) {
                    continue;
                }
                last = c.value;
            }
            return last;
        }
        Candidate chosen = null;
        for (Candidate c : candidates) {
            if (c == null || c.value == null || c.value.trim().isEmpty()) {
                continue;
            }
            if (chosen == null) {
                chosen = c;
                continue;
            }
            if (policy == POLICY_LARGEST) {
                if (c.area() > chosen.area()) {
                    chosen = c;
                }
            } else if (policy == POLICY_CENTER) {
                if (c.centerDistance2(frameW, frameH) < chosen.centerDistance2(frameW, frameH)) {
                    chosen = c;
                }
            }
        }
        return chosen == null ? null : chosen.value;
    }

    /** 序号类策略对应的"第 N 个"；面积/中心/跳帧/整页类返回 0。 */
    private static int nthIndex(int policy) {
        switch (policy) {
            case POLICY_FIRST:
                return 1;
            case POLICY_SECOND:
                return 2;
            case POLICY_THIRD:
                return 3;
            case POLICY_FOURTH:
                return 4;
            default:
                return 0;
        }
    }

    /**
     * 整页扫描：把一张照片里的所有<b>不同</b>内容按阅读顺序列出来，最多 {@code max} 条。
     *
     * <p>顺序是"先上后下、同一行先左后右"，和人抄一页序列号的习惯一致——录进记事本
     * 之后不用再手工排序。行内合并用了容差（默认半个行高），因为同一行的条码上下沿
     * 很难完全齐平，严格按上沿排序会把一行拆成两三行、顺序跳来跳去。
     *
     * <p>相同内容只留第一条：一页纸很少真的印两遍同一个序列号，而识别器对同一个物理
     * 码偶尔会给出两个重叠的框（尤其是印刷质量差的 Code128），不去重就会录两遍。
     * 没有包围盒的结果（极少见）排在有坐标的后面，保持识别器给的先后顺序。
     */
    public static List<String> pickAll(List<Candidate> candidates, int max) {
        List<String> out = new ArrayList<>();
        if (candidates == null || candidates.isEmpty()) {
            return out;
        }
        List<Candidate> boxed = new ArrayList<>();
        List<Candidate> unboxed = new ArrayList<>();
        for (Candidate c : candidates) {
            if (c == null || c.value == null || c.value.trim().isEmpty()) {
                continue;
            }
            if (c.hasBox()) {
                boxed.add(c);
            } else {
                unboxed.add(c);
            }
        }
        sortReadingOrder(boxed);
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        for (Candidate c : boxed) {
            collect(c.value, seen, out, max);
        }
        for (Candidate c : unboxed) {
            collect(c.value, seen, out, max);
        }
        return out;
    }

    private static void collect(String value, java.util.Set<String> seen,
                                List<String> out, int max) {
        if (max > 0 && out.size() >= max) {
            return;
        }
        if (seen.add(value)) {
            out.add(value);
        }
    }

    /** 原地按阅读顺序排序（只处理有包围盒的候选）。 */
    static void sortReadingOrder(List<Candidate> list) {
        if (list.size() < 2) {
            return;
        }
        list.sort((a, b) -> Integer.compare(centerY(a), centerY(b)));
        long sumHeight = 0;
        for (Candidate c : list) {
            sumHeight += c.bottom - c.top;
        }
        // 行内合并容差：行高的一半。太小会把一行拆散，太大又会把紧邻的两行并成一行
        int tolerance = (int) Math.max(12L, sumHeight / list.size() / 2L);
        int start = 0;
        while (start < list.size()) {
            int anchor = centerY(list.get(start));
            int end = start + 1;
            while (end < list.size() && centerY(list.get(end)) - anchor <= tolerance) {
                end++;
            }
            list.subList(start, end).sort((a, b) -> Integer.compare(a.left, b.left));
            start = end;
        }
    }

    private static int centerY(Candidate c) {
        return c.hasBox() ? (c.top + c.bottom) / 2 : Integer.MAX_VALUE / 2;
    }

    /** 有效值去重后的个数，用于判断这一帧是否"有歧义"。 */
    public static int distinctValues(List<Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return 0;
        }
        java.util.LinkedHashSet<String> set = new java.util.LinkedHashSet<>();
        for (Candidate c : candidates) {
            if (c != null && c.value != null && !c.value.trim().isEmpty()) {
                set.add(c.value);
            }
        }
        return set.size();
    }
}
