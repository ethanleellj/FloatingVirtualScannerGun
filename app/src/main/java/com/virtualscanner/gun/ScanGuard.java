package com.virtualscanner.gun;

/**
 * 扫码结果确认器：把"相机连续帧"整理成"一次呈现、一次输入"。
 *
 * <p>为什么必须有这一层：相机是连续流，一个对焦不准的 EAN-13 会被 ML Kit 解成
 * "合法但错误"的值——EAN-13 的校验位有 1/10 的概率碰巧通过，所以校验位救不了
 * 这种误读。真机实测：一张静止条码在 7 秒内被解出 6 个不同的值
 * （{@code 6901236347102} / {@code 6990186347102} / {@code 3490126039102} …）。
 * 如果每帧都注入，收银台就会扫错商品。
 *
 * <p>更麻烦的是：只靠时间窗口挡不住这种误读。标签一直停在取景框里，ML Kit 就会
 * 持续吐出互不相同的错值，任何一个"1 秒没见过的值"看起来都像新商品。所以本类
 * 的核心不是去重，而是<b>把一次呈现框成一个事务</b>：
 *
 * <ol>
 *   <li><b>连续一致</b>：同一个值必须<b>连续</b>命中 {@link #CONFIRM_VOTES} 帧才放行。
 *       早先用的是"1 秒窗口内累计 2 次"，实测漏过一个错值：真机上正确码与错码
 *       {@code 7904869343434} 交替出现，累计够 2 次就放行了，而这个错值的 EAN-13
 *       校验位是合法的，校验也拦不住。改成连续一致后，错值必须连成一片才可能通过；</li>
 *   <li><b>呈现锁</b>：注入成功后整体上锁，此后任何识别结果都不采信——不管内容
 *       一样还是不一样，直到条码真的离开画面（连续 {@link #GONE_MS} 没有解出任何
 *       条码）才解锁。这是掐掉"停在那儿一直刷"的那一刀；</li>
 *   <li><b>换码保护</b>：解锁后若立刻冒出一个内容不同的值，仍按同一张标签的另一次
 *       误读处理（人换商品没那么快）；同一个值要重新录入，标签必须真的离开画面
 *       至少 {@link #MIN_AWAY_MS}，否则视为同一次呈现的抖动。</li>
 * </ol>
 *
 * <p>纯逻辑、无 Android 依赖，方便用 javac 回放真机日志验证。
 */
public class ScanGuard {

    /** 同一个值需要连续命中几帧才注入。3 帧在 10 fps 下约 200~300 ms。 */
    public static final int CONFIRM_VOTES = 3;
    /** 连续命中的有效期：两帧间隔超过这个时间，视为中断。 */
    public static final long VOTE_WINDOW_MS = 1000L;
    /** 连续这么久没解出任何条码，认为条码已离开画面，解除呈现锁。 */
    public static final long GONE_MS = 700L;
    /**
     * 同一个码要重新录入，标签必须<b>真的离开画面</b>至少这么久。
     *
     * <p>这个阈值和 {@link #GONE_MS} 不是一回事。真机实测：手持包装时对焦抖动几帧，
     * 静默时长就是 703 / 708 / 723 / 729 ms —— 每次都刚过 700 ms 门槛，于是同一次
     * 呈现被切成 4 段，同一个条码录了 4 次。所以"是否同一次呈现"要看离开时长，
     * 而不是看"上次注入到现在"过了多久。
     */
    public static final long MIN_AWAY_MS = 1200L;
    /** 解锁之后、注入之后，这段时间内不接受"内容不同"的新码。 */
    public static final long SWAP_GUARD_MS = 1200L;

    /** 同一个码重复扫描的最小间隔，可被配置档的"去重时间"覆盖。 */
    private long sameCodeWindowMs = 1500L;

    /**
     * 是否忽略连续重复的条码。false 时同码离开画面即可再次注入，
     * 但仍要真的离开画面（{@link #GONE_MS}）才会解锁，同一呈现不会刷屏。
     */
    private boolean ignoreDup = true;

    /** 当前这一串连续相同的识别结果。 */
    private String runValue;
    private int runCount;
    private long runAt;

    /**
     * 进入当前这段"有内容"的时间之前，画面空了多久。
     *
     * <p>必须挂在守卫上而不是挂在 run 上：静默结束后常会先冒出一两帧杂值，
     * 再稳定成真码，run 会因此重建。若把离开时长存在 run 上，重建时就被清零，
     * 手抖保护直接失效。
     */
    private long silentMs;

    /** 本次呈现是否已经消耗掉。 */
    private boolean locked;
    private String lastEmitCode;
    private long lastEmitAt;
    private boolean hasEmitted;
    private long noBarcodeAt = -1L;

    private int rejected;

    /** 状态事件回调，仅用于日志/排障；为 null 时完全不产生开销。 */
    public interface Listener {
        void onEvent(String event, String detail);
    }

    private Listener listener;

    public void setListener(Listener l) {
        this.listener = l;
    }

    private void fire(String event, String detail) {
        Listener l = listener;
        if (l != null) {
            l.onEvent(event, detail);
        }
    }

    public void setSameCodeWindowMs(long ms) {
        this.sameCodeWindowMs = Math.max(0L, ms);
    }

    /** 关掉后，同一个码离开画面即可再次注入（连续录相同 S/N 等场景）。 */
    public void setIgnoreDup(boolean ignore) {
        this.ignoreDup = ignore;
    }

    /** 累计被丢弃的可疑结果数量（用于日志与状态栏提示）。 */
    public int rejected() {
        return rejected;
    }

    /**
     * 送一帧的识别结果进来。
     *
     * @param value 本帧解出的条码内容；没解出时传 null 或空串
     * @param now   单调时钟（{@code SystemClock.elapsedRealtime()}）
     * @return 需要注入的码；不需要注入时返回 null
     */
    public String onFrame(String value, long now) {
        final boolean blank = value == null || value.trim().isEmpty();
        if (locked && noBarcodeAt >= 0L && now - noBarcodeAt >= GONE_MS) {
            // 静默够久：本次呈现结束，允许扫下一件
            locked = false;
            fire("UNLOCK", "gone " + (now - noBarcodeAt) + "ms");
        }

        if (blank) {
            // 这一帧什么都没解出来，一致性链断掉
            breakRun();
            if (noBarcodeAt < 0L) {
                noBarcodeAt = now;
            }
            return null;
        }
        if (noBarcodeAt >= 0L) {
            // 静默结束后的第一帧：把这段空档的长度记下来，供本次呈现的
            // 重复判定使用。之后同一段呈现里的杂值/换值都不再改动它。
            silentMs = now - noBarcodeAt;
            noBarcodeAt = -1L;
        }

        if (locked) {
            // 换一件商品的第二种走法：画面里始终有可解码的内容（两张标签贴在一张纸上、
            // 或镜头从 A 平滑移到 B 中间没有一帧落空），靠"连续 700ms 解不出码"解锁
            // 会永远等不到。此时若解出「不同内容」且距上次注入已超过换码保护期，
            // 就认定是下一件商品：解锁并让它重新走三帧确认。
            // 错值不会被这次解锁放大——它照样要连成 3 帧，而同码复扫被下面的
            // silentMs(=0) 挡住。
            if (!value.equals(lastEmitCode) && now - lastEmitAt >= SWAP_GUARD_MS) {
                locked = false;
                silentMs = 0L;
                fire("UNLOCK_SWAP", value + " after " + (now - lastEmitAt) + "ms");
            } else {
                // 还在同一次呈现里，任何结果都不采信（包括错值）
                return null;
            }
        }

        if (value.equals(runValue) && now - runAt <= VOTE_WINDOW_MS) {
            runCount++;
        } else {
            // 换了个值（或间隔太久）：前面那串没连成就作废
            if (runValue != null && runCount < CONFIRM_VOTES) {
                rejected++;
                fire("REJECT", "run broke after " + runCount + "x " + runValue);
            }
            runValue = value;
            runCount = 1;
        }
        runAt = now;

        if (runCount < CONFIRM_VOTES) {
            return null;
        }
        // 同一个码回来了，但它只离开了 awayNeed 以内的这么点时间——
        // 手一抖 / 对焦一跑就会这样，同一张标签，不该再录一次。
        // 关掉"忽略重复"后这里不再拦截，同码离开画面即可再录。
        final long awayNeed = Math.max(MIN_AWAY_MS, sameCodeWindowMs);
        if (ignoreDup && hasEmitted && value.equals(lastEmitCode) && silentMs < awayNeed) {
            fire("REJECT_DUP", value + " awayOnly=" + silentMs + "ms need=" + awayNeed);
            breakRun();
            return null;
        }
        // 刚注入完就冒出一个"不一样"的码：人不可能这么快换商品
        if (hasEmitted && !value.equals(lastEmitCode)
                && now - lastEmitAt < SWAP_GUARD_MS) {
            rejected++;
            fire("REJECT_SWAP", value + " after " + (now - lastEmitAt) + "ms of " + lastEmitCode);
            breakRun();
            return null;
        }

        breakRun();
        locked = true;
        lastEmitCode = value;
        lastEmitAt = now;
        hasEmitted = true;
        fire("LOCK", value + " away=" + silentMs + "ms rejectedSoFar=" + rejected);
        return value;
    }

    private void breakRun() {
        runValue = null;
        runCount = 0;
    }
}
