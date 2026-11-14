package com.virtualscanner.gun;

import android.content.Context;
import android.text.TextUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * 配置档 + 常用指令的打包格式（一个 JSON 文件）。
 *
 * <p>现场批量铺设备时，一台机器调好（前后缀、通道、弹窗策略、常用指令），
 * 导出成文件丢到其他机器导入即可，不必逐台重设。
 *
 * <p>导入规则（可反复导入，结果一致）：
 * <ul>
 *   <li>同 id 的配置档 → 覆盖（内容以文件为准）</li>
 *   <li>新 id → 追加</li>
 *   <li>常用指令 → 按名字覆盖/追加</li>
 *   <li>文件里记录了"当前配置档"，导入后若该档存在则顺手切过去</li>
 * </ul>
 */
public final class ProfileBundle {

    /** 文件里的身份标记：导错文件时能明确拒绝，而不是把别的 JSON 硬塞进来 */
    public static final String APP_ID = "virtual-scanner-gun";
    public static final String TYPE = "vsg-profiles";
    public static final int VERSION = 1;

    public static final class Result {
        public int profilesAdded;
        public int profilesUpdated;
        public int snippets;
        /** 非空表示失败，内容给用户看 */
        public String error;

        public boolean ok() {
            return error == null;
        }
    }

    private ProfileBundle() {
    }

    /** 生成导出用的 JSON 文本。 */
    public static String export(Context ctx) {
        JSONObject root = new JSONObject();
        try {
            root.put("app", APP_ID);
            root.put("type", TYPE);
            root.put("version", VERSION);
            root.put("exportedAt", new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssZ", Locale.US)
                    .format(new Date()));

            JSONArray arr = new JSONArray();
            List<Profile> all = Profiles.all(ctx);
            for (Profile p : all) {
                arr.put(p.toJson());
            }
            root.put("profiles", arr);
            root.put("current", Profiles.current(ctx).id);

            JSONArray sn = new JSONArray();
            for (Snippets.Snippet s : Snippets.all(ctx)) {
                JSONObject o = new JSONObject();
                o.put("name", s.name);
                o.put("text", s.text);
                sn.put(o);
            }
            root.put("snippets", sn);
            return root.toString(2);
        } catch (JSONException e) {
            // 全是基本类型，正常不会发生
            return "{}";
        }
    }

    /** 建议的文件名：带日期，便于同一台机器导出多次时区分。 */
    public static String suggestedName() {
        return "vsg-profiles-"
                + new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date())
                + ".json";
    }

    /** 解析并写入。任何字段缺失都按"用默认值"处理，不因为一个字段就整份失败。 */
    public static Result importFrom(Context ctx, String json) {
        Result result = new Result();
        if (json == null || TextUtils.isEmpty(json.trim())) {
            result.error = ctx.getString(R.string.bundle_err_empty);
            return result;
        }
        JSONObject root;
        try {
            root = new JSONObject(json);
        } catch (JSONException e) {
            result.error = ctx.getString(R.string.bundle_err_not_json);
            return result;
        }
        JSONArray profiles = root.optJSONArray("profiles");
        String type = root.optString("type", "");
        if (profiles == null) {
            result.error = ctx.getString(TextUtils.equals(type, TYPE)
                    ? R.string.bundle_err_no_profiles : R.string.bundle_err_wrong_file);
            return result;
        }

        for (int i = 0; i < profiles.length(); i++) {
            JSONObject o = profiles.optJSONObject(i);
            if (o == null) {
                continue;
            }
            Profile incoming = Profile.fromJson(o);
            if (TextUtils.isEmpty(incoming.id)) {
                incoming.id = Profile.newId();
            }
            String name = incoming.name == null ? "" : incoming.name.trim();
            if (name.isEmpty() && TextUtils.isEmpty(incoming.prefix)
                    && TextUtils.isEmpty(incoming.suffix)) {
                // 三样全空的档导进来只是垃圾，跳过
                continue;
            }
            boolean exists = false;
            for (Profile p : Profiles.all(ctx)) {
                if (p.id.equals(incoming.id)) {
                    exists = true;
                    break;
                }
            }
            Profiles.save(ctx, incoming);
            if (exists) {
                result.profilesUpdated++;
            } else {
                result.profilesAdded++;
            }
        }

        JSONArray snippets = root.optJSONArray("snippets");
        if (snippets != null) {
            for (int i = 0; i < snippets.length(); i++) {
                JSONObject o = snippets.optJSONObject(i);
                if (o == null) {
                    continue;
                }
                String name = o.optString("name", "").trim();
                String text = o.optString("text", "");
                if (name.isEmpty() || text.isEmpty()) {
                    continue;
                }
                Snippets.save(ctx, name, text);
                result.snippets++;
            }
        }

        // 批量铺设备时希望"导入完直接就是调好的那档"
        String current = root.optString("current", "");
        if (!TextUtils.isEmpty(current)) {
            for (Profile p : Profiles.all(ctx)) {
                if (p.id.equals(current)) {
                    Profiles.setCurrent(ctx, current);
                    break;
                }
            }
        }
        return result;
    }
}
