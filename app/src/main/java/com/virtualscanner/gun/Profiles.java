package com.virtualscanner.gun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 配置档仓库。全部落在 SharedPreferences 的一段 JSON 里，量小、无需建表。
 *
 * 首次初始化时会把旧版本散落的 prefix/suffix/enter/dedup 键迁移成"默认"配置档，
 * 老用户升级后设置不丢。
 */
public final class Profiles {

    private static final String FILE = "vsg_prefs";
    private static final String KEY_LIST = "profiles_json";
    private static final String KEY_CURRENT = "current_profile";

    private Profiles() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** 读取全部配置档；损坏或为空时回落到一个内置默认档。 */
    public static List<Profile> all(Context ctx) {
        List<Profile> result = new ArrayList<>();
        String raw = sp(ctx).getString(KEY_LIST, null);
        if (raw != null) {
            try {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.optJSONObject(i);
                    if (o != null) {
                        result.add(Profile.fromJson(o));
                    }
                }
            } catch (JSONException ignored) {
            }
        }
        if (result.isEmpty()) {
            result.add(createInitial(ctx));
            persist(ctx, result);
        }
        return result;
    }

    /**
     * 第一次运行（或数据被清空）时构造默认档：
     * 若存在旧版单档配置，则原样继承，避免升级后设置丢失。
     */
    private static Profile createInitial(Context ctx) {
        Profile p = Profile.createDefault();
        SharedPreferences s = sp(ctx);
        p.prefix = s.getString(Prefs.KEY_PREFIX, "");
        p.suffix = s.getString(Prefs.KEY_SUFFIX, "");
        p.enter = s.getBoolean(Prefs.KEY_ENTER, true);
        p.dedupMs = s.getInt(Prefs.KEY_DEDUP, 1500);
        return p;
    }

    public static Profile current(Context ctx) {
        ensureInit(ctx);
        String id = sp(ctx).getString(KEY_CURRENT, "");
        List<Profile> list = all(ctx);
        for (Profile p : list) {
            if (p.id.equals(id)) {
                return p;
            }
        }
        return list.get(0);
    }

    public static void setCurrent(Context ctx, String id) {
        sp(ctx).edit().putString(KEY_CURRENT, id).apply();
    }

    /** 按 id 覆盖保存；id 不存在则追加。 */
    public static void save(Context ctx, Profile profile) {
        ensureInit(ctx);
        List<Profile> list = all(ctx);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(profile.id)) {
                // 保持顺序不变，只替换内容
                list.set(i, profile.copy());
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(profile.copy());
        }
        persist(ctx, list);
    }

    /** 删除配置档。最后一档不允许删除，否则会没有任何可用配置。 */
    public static boolean delete(Context ctx, String id) {
        List<Profile> list = all(ctx);
        if (list.size() <= 1) {
            return false;
        }
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).id.equals(id)) {
                list.remove(i);
                persist(ctx, list);
                if (id.equals(sp(ctx).getString(KEY_CURRENT, ""))) {
                    setCurrent(ctx, list.get(0).id);
                }
                return true;
            }
        }
        return false;
    }

    public static Profile duplicate(Context ctx, Profile src) {
        Profile copy = src.copy();
        copy.id = Profile.newId();
        copy.name = displayName(ctx, src) + " 2";
        save(ctx, copy);
        return copy;
    }

    /** 空名字统一显示为"默认"，不落库，跟随语言变化。 */
    public static String displayName(Context ctx, Profile p) {
        return p.name == null || p.name.trim().isEmpty()
                ? ctx.getString(R.string.profile_default) : p.name;
    }

    public static String channelName(Context ctx, int channel) {
        switch (channel) {
            case Profile.CHANNEL_A11Y:
                return ctx.getString(R.string.channel_a11y);
            case Profile.CHANNEL_SHIZUKU:
                return ctx.getString(R.string.channel_shizuku);
            case Profile.CHANNEL_CLIPBOARD:
                return ctx.getString(R.string.channel_clipboard);
            case Profile.CHANNEL_NET:
                return ctx.getString(R.string.channel_net);
            case Profile.CHANNEL_HID:
                return ctx.getString(R.string.channel_hid);
            default:
                return ctx.getString(R.string.channel_auto);
        }
    }

    public static String modalPolicyName(Context ctx, int policy) {
        switch (policy) {
            case Profile.MODAL_AUTO_CLOSE:
                return ctx.getString(R.string.modal_auto_close);
            case Profile.MODAL_WAIT:
                return ctx.getString(R.string.modal_wait);
            default:
                return ctx.getString(R.string.modal_block);
        }
    }

    private static void persist(Context ctx, List<Profile> list) {
        JSONArray arr = new JSONArray();
        for (Profile p : list) {
            arr.put(p.toJson());
        }
        sp(ctx).edit().putString(KEY_LIST, arr.toString()).apply();
    }

    /** 确保存储里有数据；主界面与服务启动时都会调用。 */
    public static void ensureInit(Context ctx) {
        if (sp(ctx).getString(KEY_LIST, null) == null) {
            persist(ctx, all(ctx));
        }
    }
}
