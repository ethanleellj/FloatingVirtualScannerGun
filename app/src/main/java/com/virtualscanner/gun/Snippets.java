package com.virtualscanner.gun;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 常用指令：一段命名文本，一键发送。
 *
 * 典型场景是思迅的配置串
 * []setting[]selfpos[]192.168.10.100[]20100[]0001[]11[]
 * ——以前每次都要现找那张二维码扫一遍，现在存成一条指令即可。
 */
public final class Snippets {

    public static final class Snippet {
        public String name = "";
        public String text = "";

        public Snippet(String name, String text) {
            this.name = name;
            this.text = text;
        }
    }

    private static final String FILE = "vsg_prefs";
    private static final String KEY = "snippets_json";

    private Snippets() {
    }

    private static SharedPreferences sp(Context ctx) {
        return ctx.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public static List<Snippet> all(Context ctx) {
        List<Snippet> list = new ArrayList<>();
        String raw = sp(ctx).getString(KEY, null);
        if (raw == null) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(raw);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.optJSONObject(i);
                if (o != null) {
                    list.add(new Snippet(o.optString("name", ""), o.optString("text", "")));
                }
            }
        } catch (JSONException ignored) {
        }
        return list;
    }

    /** 追加一条；同名（忽略大小写）视为覆盖。 */
    public static void save(Context ctx, String name, String text) {
        List<Snippet> list = all(ctx);
        boolean replaced = false;
        for (int i = 0; i < list.size(); i++) {
            if (list.get(i).name.equalsIgnoreCase(name)) {
                list.set(i, new Snippet(name, text));
                replaced = true;
                break;
            }
        }
        if (!replaced) {
            list.add(new Snippet(name, text));
        }
        persist(ctx, list);
    }

    public static void delete(Context ctx, int index) {
        List<Snippet> list = all(ctx);
        if (index >= 0 && index < list.size()) {
            list.remove(index);
            persist(ctx, list);
        }
    }

    private static void persist(Context ctx, List<Snippet> list) {
        JSONArray arr = new JSONArray();
        for (Snippet s : list) {
            JSONObject o = new JSONObject();
            try {
                o.put("name", s.name);
                o.put("text", s.text);
            } catch (JSONException ignored) {
            }
            arr.put(o);
        }
        sp(ctx).edit().putString(KEY, arr.toString()).apply();
    }
}
