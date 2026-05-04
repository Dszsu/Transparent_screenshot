package com.dszsu.tss;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public class JsonConfigManager {
    private static final String PREFS_NAME = "module_config";
    private static final String KEY_JSON = "config_json";
    private static final String VERSION = "v2.0";

    private final SharedPreferences prefs;
    private JSONObject root;

    public JsonConfigManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        load();
    }

    /**
     * 从 SharedPreferences 加载 JSON，若不存在则初始化
     */
    private void load() {
        String jsonStr = prefs.getString(KEY_JSON, "{}");
        try {
            root = new JSONObject(jsonStr);
        } catch (JSONException e) {
            root = new JSONObject();
        }
        // 确保版本
        try {
            root.put("version", VERSION);
            if (!root.has("packages")) {
                root.put("packages", new JSONObject());
            }
        } catch (JSONException ignored) {}
    }

    /**
     * 获取指定包名的配置对象（可能为 null 若不存在）
     */
    private JSONObject getPackageConfig(String packageName) {
        try {
            JSONObject packages = root.getJSONObject("packages");
            if (packages.has(packageName)) {
                return packages.getJSONObject(packageName);
            }
        } catch (JSONException ignored) {}
        return null;
    }

    /**
     * 获取指定包名的已启用功能列表
     */
    public List<String> getEnabledFeatures(String packageName) {
        List<String> result = new ArrayList<>();
        JSONObject pkg = getPackageConfig(packageName);
        if (pkg != null) {
            try {
                if (pkg.has("enabled") && !pkg.isNull("enabled")) {
                    JSONArray arr = pkg.getJSONArray("enabled");
                    for (int i = 0; i < arr.length(); i++) {
                        result.add(arr.getString(i));
                    }
                }
            } catch (JSONException ignored) {}
        }
        return result;
    }

    /**
     * 获取指定包名的窗口标题，没设置则返回空字符串
     */
    public String getWindowTitle(String packageName) {
        JSONObject pkg = getPackageConfig(packageName);
        if (pkg != null) {
            try {
                if (pkg.has("window_title") && !pkg.isNull("window_title")) {
                    return pkg.getString("window_title");
                }
            } catch (JSONException ignored) {}
        }
        return "";
    }

    /**
     * 设置某个功能的开启/关闭状态
     * @param feature 功能标识
     * @param enabled 是否启用
     */
    public void setFeatureEnabled(String packageName, String feature, boolean enabled) {
        try {
            JSONObject packages = root.getJSONObject("packages");
            JSONObject pkg = packages.optJSONObject(packageName);
            if (pkg == null) {
                pkg = new JSONObject();
                packages.put(packageName, pkg);
            }

            JSONArray enabledList = pkg.optJSONArray("enabled");
            if (enabledList == null) {
                enabledList = new JSONArray();
                pkg.put("enabled", enabledList);
            }

            if (enabled) {
                // 添加（避免重复）
                boolean exists = false;
                for (int i = 0; i < enabledList.length(); i++) {
                    if (enabledList.getString(i).equals(feature)) {
                        exists = true;
                        break;
                    }
                }
                if (!exists) {
                    enabledList.put(feature);
                }
            } else {
                // 移除该项
                JSONArray newList = new JSONArray();
                for (int i = 0; i < enabledList.length(); i++) {
                    String f = enabledList.getString(i);
                    if (!f.equals(feature)) {
                        newList.put(f);
                    }
                }
                pkg.put("enabled", newList);
            }

            // 清理空对象：若 enabled 数组为空且无 window_title，删除该包名条目
            boolean hasEnabled = pkg.optJSONArray("enabled") != null && Objects.requireNonNull(pkg.optJSONArray("enabled")).length() > 0;
            boolean hasTitle = pkg.has("window_title") && !pkg.isNull("window_title") && !pkg.optString("window_title").isEmpty();
            if (!hasEnabled && !hasTitle) {
                packages.remove(packageName);
            }

            save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * 设置窗口标题（空字符串表示删除）
     */
    public void setWindowTitle(String packageName, String title) {
        try {
            JSONObject packages = root.getJSONObject("packages");
            JSONObject pkg = packages.optJSONObject(packageName);
            if (pkg == null) {
                if (title.isEmpty()) return; // 无数据，不创建对象
                pkg = new JSONObject();
                packages.put(packageName, pkg);
            }

            if (title.isEmpty()) {
                pkg.remove("window_title");
            } else {
                pkg.put("window_title", title);
            }

            // 清理空对象
            boolean hasEnabled = pkg.optJSONArray("enabled") != null && Objects.requireNonNull(pkg.optJSONArray("enabled")).length() > 0;
            boolean hasTitle = pkg.has("window_title") && !pkg.isNull("window_title") && !pkg.optString("window_title").isEmpty();
            if (!hasEnabled && !hasTitle) {
                packages.remove(packageName);
            }

            save();
        } catch (JSONException e) {
            e.printStackTrace();
        }
    }

    /**
     * 将修改后的 JSON 保存回 SharedPreferences
     */
    private void save() {
        prefs.edit().putString(KEY_JSON, root.toString()).apply();
    }
}