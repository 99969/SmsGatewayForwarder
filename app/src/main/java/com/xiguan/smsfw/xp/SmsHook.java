package com.xiguan.smsfw.xp;

import android.content.Intent;
import android.os.Bundle;
import android.provider.Telephony;
import android.telephony.SmsMessage;

import com.xiguan.smsfw.Const;
import com.xiguan.smsfw.Gateway;
import com.xiguan.smsfw.Settings;
import com.xiguan.smsfw.SmsInfo;
import com.xiguan.smsfw.XLog;

import java.lang.reflect.Method;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook 系统短信入口。
 * <p>
 * 与旧版实现的区别：
 * <ul>
 *   <li>不再限定某一个 Android 版本的构造器/方法签名，而是按「方法名 + 首参 Intent」模糊匹配，
 *       跨 Android 7 ~ 16 通用；</li>
 *   <li>不再调用 createPackageContext / 自建 ContentProvider 读配置，
 *       直接用 LSPosed 的 xposedsharedprefs 机制 + XSharedPreferences；</li>
 *   <li>所有异常就地吞掉并记日志，绝不让异常冒泡到电话进程（否则会连带系统电话崩溃）。</li>
 * </ul>
 */
public final class SmsHook {

    private static final String HANDLER_CLASS = "com.android.internal.telephony.InboundSmsHandler";
    private static final String METHOD_NAME = "dispatchIntent";

    private static volatile boolean sInstalled = false;
    private static String sLastKey = "";
    private static long sLastAt = 0L;

    private SmsHook() {
    }

    public static synchronized void install(ClassLoader cl) {
        if (sInstalled) {
            return;
        }
        Class<?> handlerClass;
        try {
            handlerClass = XposedHelpers.findClass(HANDLER_CLASS, cl);
        } catch (Throwable t) {
            XLog.w("未找到 " + HANDLER_CLASS + "（本 ROM 可能不使用该实现）");
            return;
        }

        Method target = null;
        for (Method m : handlerClass.getDeclaredMethods()) {
            if (!METHOD_NAME.equals(m.getName())) {
                continue;
            }
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 2 && ps[0] == Intent.class) {
                target = m;
                break;
            }
        }
        if (target == null) {
            XLog.w("未找到 " + METHOD_NAME + "(Intent, ...)");
            return;
        }

        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                        onDispatchIntent((Intent) param.args[0]);
                    }
                } catch (Throwable t) {
                    // 绝不能把异常抛回电话进程
                    XLog.e("解析短信失败", t);
                }
            }
        });

        sInstalled = true;
        XLog.i("Hook 就绪: " + HANDLER_CLASS + "#" + target.getName()
                + " (" + target.getParameterTypes().length + " args)");
    }

    private static void onDispatchIntent(Intent intent) {
        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(intent.getAction())) {
            return;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) {
            return;
        }
        Object[] pdus = (Object[]) extras.get("pdus");
        if (pdus == null || pdus.length == 0) {
            return;
        }
        String format = extras.getString("format");

        String sender = null;
        long timeMillis = 0L;
        StringBuilder content = new StringBuilder();

        for (Object pdu : pdus) {
            if (!(pdu instanceof byte[])) {
                continue;
            }
            SmsMessage sm;
            try {
                sm = (format != null)
                        ? SmsMessage.createFromPdu((byte[]) pdu, format)
                        : SmsMessage.createFromPdu((byte[]) pdu);
            } catch (Throwable t) {
                continue;
            }
            if (sm == null) {
                continue;
            }
            if (sender == null) {
                sender = sm.getDisplayOriginatingAddress();
                if (sender == null || sender.isEmpty()) {
                    sender = sm.getOriginatingAddress();
                }
                timeMillis = sm.getTimestampMillis();
            }
            String body = sm.getMessageBody();
            if (body != null) {
                content.append(body);
            }
        }

        if (sender == null && content.length() == 0) {
            return;
        }

        int subId = intent.getIntExtra("subscription", -1);
        SmsInfo info = new SmsInfo(sender, content.toString(), timeMillis, slotIndexOf(subId));

        // 同一条短信可能被不同接收者重复投递，做一次短时间去重
        String key = info.sender + "|" + info.content + "|" + info.timeMillis;
        synchronized (SmsHook.class) {
            long now = System.currentTimeMillis();
            if (key.equals(sLastKey) && (now - sLastAt) < 3000L) {
                return;
            }
            sLastKey = key;
            sLastAt = now;
        }

        XLog.i("收到短信 from=" + info.sender + " slot=" + info.slotLabel()
                + " len=" + info.content.length());

        Thread worker = new Thread(() -> forward(info), "sms-fw-forward");
        worker.setDaemon(true);
        worker.start();
    }

    private static void forward(SmsInfo info) {
        Settings s;
        try {
            XSharedPreferences sp = new XSharedPreferences(Const.PKG, Const.PREFS_NAME);
            sp.reload();
            s = Settings.load(sp);
        } catch (Throwable t) {
            XLog.e("读取模块配置失败（检查 LSPosed 里是否已给本模块开启 xposedsharedprefs 支持）", t);
            return;
        }

        if (!s.enable) {
            XLog.i("转发开关已关闭，跳过");
            return;
        }
        if (s.shouldSkip(info.content)) {
            XLog.i("命中关键词过滤规则，跳过");
            return;
        }

        String title = info.render(s.titleTpl, s.device);
        String body = info.render(s.bodyTpl, s.device);
        Gateway.push(s, title, body);
    }

    /** SubscriptionManager.getSlotIndex 在部分版本属于隐藏 API，用反射调用避免编译期依赖 */
    private static int slotIndexOf(int subId) {
        if (subId < 0) {
            return -1;
        }
        try {
            Class<?> cls = Class.forName("android.telephony.SubscriptionManager");
            Method m = cls.getMethod("getSlotIndex", int.class);
            Object r = m.invoke(null, subId);
            if (r instanceof Integer) {
                int slot = (Integer) r;
                return slot >= 0 ? slot : -1;
            }
        } catch (Throwable ignored) {
            // 部分 ROM 不允许查询，忽略即可
        }
        return -1;
    }
}
