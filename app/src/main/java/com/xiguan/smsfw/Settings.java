package com.xiguan.smsfw;

import android.content.SharedPreferences;

import java.util.Collections;
import java.util.Map;

/**
 * 配置值对象。
 * <p>
 * 设计要点：<b>所有读取都做类型校验 + 异常兜底</b>。
 * 旧版模块崩溃的根因之一就是 {@code (String) value} 强转 —— 只要 shared_prefs 里
 * 存在一个非 String 的值（历史版本写过 Integer / Set / Float），读取时立刻
 * ClassCastException，界面一打开就闪退。所以这里任何一个字段坏掉都只会退回默认值，
 * 不会影响整个页面。
 */
public final class Settings {

    public boolean enable = true;
    public String gateway = Const.DEF_GATEWAY;
    public String token = Const.DEF_TOKEN;
    public String titleTpl = Const.DEF_TITLE_TPL;
    public String bodyTpl = Const.DEF_BODY_TPL;
    public String device = "";
    public int timeoutMs = 10000;

    public boolean filterEnable = false;
    public String filterKeywords = "";
    /** true = 命中关键词就不转发；false = 只有命中关键词才转发 */
    public boolean filterBlock = true;

    private Settings() {
    }

    public static Settings defaults() {
        return new Settings();
    }

    public static Settings load(SharedPreferences sp) {
        Settings s = new Settings();
        if (sp == null) {
            return s;
        }
        Map<String, ?> all;
        try {
            all = sp.getAll();
        } catch (Throwable t) {
            XLog.w("读取配置失败，使用默认值: " + t);
            return s;
        }
        if (all == null) {
            return s;
        }

        s.enable = bool(all, Const.K_ENABLE, s.enable);
        s.gateway = str(all, Const.K_GATEWAY, s.gateway);
        s.token = str(all, Const.K_TOKEN, s.token);
        s.titleTpl = str(all, Const.K_TITLE_TPL, s.titleTpl);
        s.bodyTpl = str(all, Const.K_BODY_TPL, s.bodyTpl);
        s.device = str(all, Const.K_DEVICE, s.device);
        s.timeoutMs = clamp(num(all, Const.K_TIMEOUT, s.timeoutMs), 3000, 60000);

        s.filterEnable = bool(all, Const.K_FILTER_ENABLE, s.filterEnable);
        s.filterKeywords = str(all, Const.K_FILTER_KEYWORDS, s.filterKeywords);
        s.filterBlock = bool(all, Const.K_FILTER_BLOCK, s.filterBlock);

        if (s.gateway.trim().isEmpty()) {
            s.gateway = Const.DEF_GATEWAY;
        }
        return s;
    }

    /** 判断这条短信是否应该被过滤掉（不转发） */
    public boolean shouldSkip(String content) {
        if (!filterEnable) {
            return false;
        }
        if (filterKeywords == null || filterKeywords.trim().isEmpty()) {
            return false;
        }
        boolean hit = false;
        for (String raw : filterKeywords.split("[,，;；\\n]")) {
            String kw = raw.trim();
            if (kw.isEmpty()) {
                continue;
            }
            if (content != null && content.contains(kw)) {
                hit = true;
                break;
            }
        }
        return filterBlock ? hit : !hit;
    }

    /* ---------------- 类型安全取值 ---------------- */

    private static String str(Map<String, ?> all, String key, String def) {
        Object v = all.get(key);
        if (v instanceof String) {
            return (String) v;
        }
        if (v instanceof Number || v instanceof Boolean) {
            return String.valueOf(v);
        }
        return def;
    }

    private static boolean bool(Map<String, ?> all, String key, boolean def) {
        Object v = all.get(key);
        if (v instanceof Boolean) {
            return (Boolean) v;
        }
        if (v instanceof String) {
            return "true".equalsIgnoreCase((String) v);
        }
        if (v instanceof Number) {
            return ((Number) v).intValue() != 0;
        }
        return def;
    }

    private static int num(Map<String, ?> all, String key, int def) {
        Object v = all.get(key);
        if (v instanceof Number) {
            return ((Number) v).intValue();
        }
        if (v instanceof String) {
            try {
                return Integer.parseInt(((String) v).trim());
            } catch (Throwable ignored) {
                return def;
            }
        }
        return def;
    }

    private static int clamp(int v, int min, int max) {
        return v < min ? min : (v > max ? max : v);
    }

    static Map<String, ?> emptyMap() {
        return Collections.emptyMap();
    }
}
