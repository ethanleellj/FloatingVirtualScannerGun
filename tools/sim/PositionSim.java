import com.virtualscanner.gun.Position;

/** 悬浮位置恢复的离线回放：不需要 Android 环境。 */
public class PositionSim {

    private static int checks;

    public static void main(String[] args) {
        // 正常范围原样返回（出厂 x=24 / y=360 px）
        expect(24, 24, 1080, 168);
        expect(360, 360, 2400, 500);
        // 上一次的拖动结果在屏幕内：直接沿用
        expect(520, 520, 1080, 168);
        expect(1234, 1234, 2400, 500);
        // 旋转 / 换机后越界：夹回可见范围
        expect(0, -80, 1080, 168);
        expect(912, 1000, 1080, 168);
        expect(300, 1500, 800, 500);
        // 窗口比可用区域还大（极端分屏）：退化为 0
        expect(0, 500, 100, 168);
        expect(0, -1, 100, 168);
        System.out.println("PositionSim: " + checks + "/" + checks + " passed");
    }

    private static void expect(int want, int value, int screen, int window) {
        checks++;
        int got = Position.clamp(value, screen, window);
        if (got != want) {
            throw new AssertionError("clamp(" + value + ", " + screen + ", " + window
                    + "): expected " + want + ", got " + got);
        }
    }
}
