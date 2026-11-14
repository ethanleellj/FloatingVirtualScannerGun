package com.virtualscanner.gun;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.UUID;

/**
 * 一套完整的按键楔配置。
 *
 * 对应"配置档"概念：换一个目标 App 就换一套前后缀/通道/弹窗策略，
 * 不必每次手动改。
 */
public final class Profile {

    /** 注入通道 */
    public static final int CHANNEL_AUTO = 0;
    public static final int CHANNEL_A11Y = 1;
    public static final int CHANNEL_SHIZUKU = 2;
    public static final int CHANNEL_CLIPBOARD = 3;
    /** V2：把内容 TCP 发到 PC（手机当 PC 扫描枪）。值只追加，已存盘的历史值不能变 */
    public static final int CHANNEL_NET = 4;
    /** V2：手机当 PC 的蓝牙键盘（HID Device）。值只追加 */
    public static final int CHANNEL_HID = 5;

    /** 检测到模态弹窗时的处理策略 */
    public static final int MODAL_BLOCK = 0;
    public static final int MODAL_AUTO_CLOSE = 1;
    public static final int MODAL_WAIT = 2;

    public String id = "";
    public String name = "";

    /** 前缀，支持 \n \r \t \\ \xNN 转义 */
    public String prefix = "";
    /** 后缀文本，转义规则同前缀 */
    public String suffix = "";

    /** 是否在结尾补一个回车（与后缀可组合） */
    public boolean enter = true;

    /** 重复码过滤窗口 */
    public int dedupMs = 1500;

    /**
     * 写入目标输入框的方式：true = 追加到已有内容之后，false = 整段替换。
     * 记事本 / Excel 连续录入要追加；收银商品码框要替换（填一条、回车、清空）。
     */
    public boolean appendMode = true;

    /**
     * 是否忽略连续重复的条码：true = 与上一次相同的码直接跳过不注入。
     * 与 {@link #dedupMs} 不同，这是一个显式的布尔开关，而不是时间窗口。
     */
    public boolean ignoreDup = true;

    public int channel = CHANNEL_AUTO;
    public int modalPolicy = MODAL_BLOCK;

    public static Profile createDefault() {
        Profile p = new Profile();
        p.id = newId();
        p.name = "";
        return p;
    }

    public static String newId() {
        return UUID.randomUUID().toString().substring(0, 8);
    }

    public Profile copy() {
        Profile p = new Profile();
        p.id = id;
        p.name = name;
        p.prefix = prefix;
        p.suffix = suffix;
        p.enter = enter;
        p.dedupMs = dedupMs;
        p.appendMode = appendMode;
        p.ignoreDup = ignoreDup;
        p.channel = channel;
        p.modalPolicy = modalPolicy;
        return p;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id);
            o.put("name", name);
            o.put("prefix", prefix);
            o.put("suffix", suffix);
            o.put("enter", enter);
            o.put("dedup", dedupMs);
            o.put("append", appendMode);
            o.put("ignoreDup", ignoreDup);
            o.put("channel", channel);
            o.put("modal", modalPolicy);
        } catch (JSONException ignored) {
            // 全部是基本类型，不会走到这里
        }
        return o;
    }

    public static Profile fromJson(JSONObject o) {
        Profile p = new Profile();
        p.id = o.optString("id", newId());
        p.name = o.optString("name", "");
        p.prefix = o.optString("prefix", "");
        p.suffix = o.optString("suffix", "");
        p.enter = o.optBoolean("enter", true);
        p.dedupMs = o.optInt("dedup", 1500);
        p.appendMode = o.optBoolean("append", true);
        p.ignoreDup = o.optBoolean("ignoreDup", true);
        p.channel = o.optInt("channel", CHANNEL_AUTO);
        p.modalPolicy = o.optInt("modal", MODAL_BLOCK);
        return p;
    }
}
