package com.xiguan.smsfw;

/**
 * 全局常量。
 */
public final class Const {

    private Const() {
    }

    public static final String PKG = "com.xiguan.smsfw";

    /** SharedPreferences 文件名必须等于 包名 + "_preferences"，
     *  LSPosed 的 xposedsharedprefs 机制只认这个默认文件名。 */
    public static final String PREFS_NAME = PKG + "_preferences";

    /** cmcc-message-gateway 默认地址（Cloudflare Workers） */
    public static final String DEF_GATEWAY = "https://cmcc-message-gateway.hello-web.workers.dev";

    /** 默认 API Token：故意留空，Token 属于密钥，不写进代码/仓库，装好后在界面里填一次 */
    public static final String DEF_TOKEN = "";

    public static final String DEF_TITLE_TPL = "短信 · {sender}";
    public static final String DEF_BODY_TPL = "{content}\n时间: {time}\n卡槽: {slot}";

    /* ---------- 配置项 key ---------- */
    public static final String K_ENABLE = "fw_enable";
    public static final String K_GATEWAY = "fw_gateway";
    public static final String K_TOKEN = "fw_token";
    public static final String K_TITLE_TPL = "fw_title_tpl";
    public static final String K_BODY_TPL = "fw_body_tpl";
    public static final String K_DEVICE = "fw_device";
    public static final String K_TIMEOUT = "fw_timeout";
    public static final String K_FILTER_ENABLE = "fw_filter_enable";
    public static final String K_FILTER_KEYWORDS = "fw_filter_keywords";
    public static final String K_FILTER_BLOCK = "fw_filter_block";
    public static final String K_INITED = "fw_inited";

    /* ---------- 日志 tag ---------- */
    public static final String TAG = "SmsFwGateway";
}
