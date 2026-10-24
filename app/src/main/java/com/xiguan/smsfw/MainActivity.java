package com.xiguan.smsfw;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 设置界面。
 * <p>
 * 全部用系统原生控件 + 代码构建，不用 AppCompat / Material / Dagger / ButterKnife /
 * GreenDAO / Cyanea。旧版界面一打开就闪退，很大程度是被这些 2019 年的库（尤其是
 * 自己 new 一个 Resources 来换肤的 Cyanea）拖垮的；这里把它们全部去掉，
 * 界面本身几乎不可能崩。
 */
public class MainActivity extends Activity {

    private Switch swEnable;
    private Switch swFilter;
    private Switch swBlock;
    private EditText etGateway;
    private EditText etToken;
    private EditText etTitle;
    private EditText etBody;
    private EditText etDevice;
    private EditText etKeywords;
    private TextView tvResult;

    private SharedPreferences sp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.app_name);
        sp = getSharedPreferences(Const.PREFS_NAME, Context.MODE_PRIVATE);

        try {
            buildUi();
            ensureDefaults();
            load();
        } catch (Throwable t) {
            // 兜底：界面构建出任何问题都只提示，不闪退
            XLog.e("界面初始化失败", t);
            Toast.makeText(this, "界面初始化失败: " + t, Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            save(false);
        } catch (Throwable t) {
            XLog.e("保存配置失败", t);
        }
    }

    /* ==================== UI ==================== */

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(16);
        root.setPadding(pad, pad, pad, pad);
        scroll.addView(root, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        setContentView(scroll);

        root.addView(title("短信转发 · cmcc-message-gateway"));

        swEnable = new Switch(this);
        swEnable.setText("启用短信转发");
        root.addView(swEnable, matchWrap());

        root.addView(label("网关地址"));
        etGateway = field(Const.DEF_GATEWAY, false);
        etGateway.setInputType(InputType.TYPE_TEXT_VARIATION_URI);
        root.addView(etGateway, matchWrap());

        root.addView(label("API Token（必填，走 ?token= 鉴权）"));
        etToken = field("在此填入网关的 API Token", false);
        root.addView(etToken, matchWrap());

        root.addView(label("标题模板"));
        etTitle = field(Const.DEF_TITLE_TPL, false);
        root.addView(etTitle, matchWrap());

        root.addView(label("内容模板"));
        etBody = field(Const.DEF_BODY_TPL, true);
        root.addView(etBody, matchWrap());

        root.addView(label("设备名（可选，模板里用 {device} 引用）"));
        etDevice = field("", false);
        root.addView(etDevice, matchWrap());

        swFilter = new Switch(this);
        swFilter.setText("启用关键词过滤");
        root.addView(swFilter, matchWrap());

        root.addView(label("关键词（多个用逗号或换行分隔）"));
        etKeywords = field("", true);
        root.addView(etKeywords, matchWrap());

        swBlock = new Switch(this);
        swBlock.setText("命中关键词时【不转发】");
        root.addView(swBlock, matchWrap());

        Button btnSave = new Button(this);
        btnSave.setText("保存配置");
        btnSave.setOnClickListener(v -> {
            if (save(true)) {
                toast("已保存");
            }
        });
        root.addView(btnSave, matchWrap());

        Button btnTest = new Button(this);
        btnTest.setText("发送测试消息");
        btnTest.setOnClickListener(v -> doTest());
        root.addView(btnTest, matchWrap());

        Button btnHealth = new Button(this);
        btnHealth.setText("检查网关连通性（/health）");
        btnHealth.setOnClickListener(v -> doHealth());
        root.addView(btnHealth, matchWrap());

        Button btnLsposed = new Button(this);
        btnLsposed.setText("打开 LSPosed 管理器（看日志 / 改作用域）");
        btnLsposed.setOnClickListener(v -> openLsposed());
        root.addView(btnLsposed, matchWrap());

        tvResult = new TextView(this);
        tvResult.setTextSize(13f);
        tvResult.setTextColor(Color.parseColor("#333333"));
        tvResult.setPadding(0, dp(12), 0, dp(8));
        tvResult.setText("尚未测试");
        root.addView(tvResult, matchWrap());

        root.addView(label("模板可用变量"));
        TextView vars = new TextView(this);
        vars.setTextSize(13f);
        vars.setText("{sender} 号码\n{content} 短信内容\n{time} 接收时间\n{slot} 卡槽（卡1/卡2）\n{device} 上面填的设备名");
        root.addView(vars, matchWrap());

        root.addView(label("使用步骤"));
        TextView steps = new TextView(this);
        steps.setTextSize(13f);
        steps.setText("1. 在 LSPosed 里启用本模块（包名 com.xiguan.smsfw）\n"
                + "2. 作用域必须勾选「电话 / 电话服务」(com.android.phone) —— 新装的模块作用域是空的，要重新勾\n"
                + "3. 重启手机（或强制停止电话进程）\n"
                + "4. 回到本页点「发送测试消息」验证\n"
                + "5. 之后收到的短信会自动推送到网关\n\n"
                + "排查：LSPosed 管理器 → 日志，搜 SmsFw。\n"
                + "· 搜不到任何 SmsFw → 模块没被注入，作用域没勾或没重启\n"
                + "· 有「命中类 InboundSmsHandler」但没有「收到短信 PDU」→ hook 没命中短信路径\n"
                + "· 有「收到短信 PDU」但没有「准备转发」→ 短信解析失败\n"
                + "· 有「准备转发」但没结果 → 网络/Token 问题");
        root.addView(steps, matchWrap());
    }

    private TextView title(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(17f);
        tv.setPadding(0, 0, 0, dp(8));
        return tv;
    }

    private TextView label(String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(12f);
        tv.setTextColor(Color.parseColor("#888888"));
        tv.setPadding(0, dp(10), 0, dp(2));
        return tv;
    }

    private EditText field(String hint, boolean multiLine) {
        EditText et = new EditText(this);
        et.setHint(hint);
        et.setTextSize(14f);
        if (multiLine) {
            et.setSingleLine(false);
            et.setMinLines(2);
            et.setGravity(android.view.Gravity.TOP | android.view.Gravity.START);
        } else {
            et.setSingleLine(true);
        }
        return et;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
    }

    private int dp(int v) {
        return Math.round(getResources().getDisplayMetrics().density * v);
    }

    /* ==================== 数据 ==================== */

    /** 第一次安装时把默认值写进去，保证装完即可用 */
    private void ensureDefaults() {
        if (sp.getBoolean(Const.K_INITED, false)) {
            return;
        }
        SharedPreferences.Editor e = sp.edit();
        e.putBoolean(Const.K_ENABLE, true);
        e.putString(Const.K_GATEWAY, Const.DEF_GATEWAY);
        e.putString(Const.K_TOKEN, Const.DEF_TOKEN);
        e.putString(Const.K_TITLE_TPL, Const.DEF_TITLE_TPL);
        e.putString(Const.K_BODY_TPL, Const.DEF_BODY_TPL);
        e.putString(Const.K_DEVICE, "");
        e.putBoolean(Const.K_FILTER_ENABLE, false);
        e.putString(Const.K_FILTER_KEYWORDS, "");
        e.putBoolean(Const.K_FILTER_BLOCK, true);
        e.putInt(Const.K_TIMEOUT, 10000);
        e.putBoolean(Const.K_INITED, true);
        e.apply();
    }

    private void load() {
        Settings s = Settings.load(sp);
        swEnable.setChecked(s.enable);
        etGateway.setText(s.gateway);
        etToken.setText(s.token);
        etTitle.setText(s.titleTpl);
        etBody.setText(s.bodyTpl);
        etDevice.setText(s.device);
        swFilter.setChecked(s.filterEnable);
        etKeywords.setText(s.filterKeywords);
        swBlock.setChecked(s.filterBlock);

        if (s.token == null || s.token.trim().isEmpty()) {
            tvResult.setText("提示：请先填入网关的 API Token，再点「发送测试消息」。");
        }
    }

    private boolean save(boolean toastIt) {
        try {
            SharedPreferences.Editor e = sp.edit();
            e.putBoolean(Const.K_ENABLE, swEnable.isChecked());
            e.putString(Const.K_GATEWAY, etGateway.getText().toString().trim());
            e.putString(Const.K_TOKEN, etToken.getText().toString().trim());
            e.putString(Const.K_TITLE_TPL, etTitle.getText().toString());
            e.putString(Const.K_BODY_TPL, etBody.getText().toString());
            e.putString(Const.K_DEVICE, etDevice.getText().toString().trim());
            e.putBoolean(Const.K_FILTER_ENABLE, swFilter.isChecked());
            e.putString(Const.K_FILTER_KEYWORDS, etKeywords.getText().toString());
            e.putBoolean(Const.K_FILTER_BLOCK, swBlock.isChecked());
            e.putInt(Const.K_TIMEOUT, 10000);
            e.putBoolean(Const.K_INITED, true);
            e.apply();
            return true;
        } catch (Throwable t) {
            XLog.e("保存失败", t);
            if (toastIt) {
                toast("保存失败: " + t);
            }
            return false;
        }
    }

    /* ==================== 测试 ==================== */

    private void doTest() {
        save(false);
        tvResult.setText("发送中…");
        final Settings s = Settings.load(sp);
        final SmsInfo info = new SmsInfo("10086", "这是一条来自「短信转发网关」的测试消息",
                System.currentTimeMillis(), 0);
        final String title = info.render(s.titleTpl, s.device);
        final String body = info.render(s.bodyTpl, s.device);

        new Thread(() -> {
            final Gateway.Result r = Gateway.push(s, title, body);
            runOnUiThread(() -> tvResult.setText(
                    "标题: " + title + "\n\n" + r.summary()));
        }, "sms-fw-test").start();
    }

    private void doHealth() {
        save(false);
        tvResult.setText("检查中…");
        final Settings s = Settings.load(sp);
        new Thread(() -> {
            final Gateway.Result r = Gateway.health(s);
            runOnUiThread(() -> tvResult.setText("/health → " + r.summary()));
        }, "sms-fw-health").start();
    }

    private void openLsposed() {
        try {
            Intent i = getPackageManager().getLaunchIntentForPackage("org.lsposed.manager");
            if (i == null) {
                toast("未找到 LSPosed 管理器");
                return;
            }
            startActivity(i);
        } catch (Throwable t) {
            toast("打开失败: " + t);
        }
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }
}
