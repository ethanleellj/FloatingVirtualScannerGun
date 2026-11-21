import com.virtualscanner.gun.Versions;

/** 更新检查的版本比较回放：不需要 Android 环境。 */
public class UpdateSim {

    private static int checks;

    public static void main(String[] args) {
        // 新版本
        expect(1, "v0.2", "0.1-demo");
        expect(1, "0.1.1", "0.1-demo");
        expect(1, "v1.0.0", "0.9.9");
        // 相同版本（tag 的 v 前缀、-demo 后缀都不影响）
        expect(0, "0.1", "0.1-demo");
        expect(0, "v0.1-demo", "0.1-demo");
        expect(0, "V0.1.0", "0.1");
        // 旧版本 / 空 tag（404 或无 tag 时不能误报更新）
        expect(-1, "v0.0.9", "0.1-demo");
        expect(-1, "", "0.1-demo");
        expect(-1, "release-candidate", "0.1-demo");
        System.out.println("UpdateSim: " + checks + "/" + checks + " passed");
    }

    private static void expect(int want, String remote, String local) {
        checks++;
        int got = Versions.compare(remote, local);
        if (got != want) {
            throw new AssertionError(remote + " vs " + local
                    + ": expected " + want + ", got " + got);
        }
    }
}
