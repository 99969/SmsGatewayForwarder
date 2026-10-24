package com.xiguan.smsfw.xp;

import android.content.BroadcastReceiver;
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
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XSharedPreferences;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Hook 系统短信入口（com.android.phone 进程里的 InboundSmsHandler）。
 * <p>
 * 相比第一版增强了三件事，目的都是「万一不工作，能一眼看出卡在哪」：
 * <ol>
 *   <li>安装阶段就把类名、方法数、命中方法的完整签名打进日志；</li>
 *   <li>加了一个探针：dispatchIntent 前 3 次被调用时无条件记一条 action，
 *       用来区分「hook 没装上」和「hook 装上了但 action 不匹配」；</li>
 *   <li>解析阶段逐段记录 PDU 段数 / format / 每段解析结果，
 *       用来区分「没收到短信」和「收到了但解析失败」。</li>
 * </ol>
 */
public final class SmsHook {

    private static final String HANDLER_CLASS = "com.android.internal.telephony.InboundSmsHandler";
    private static final String METHOD_NAME = "dispatchIntent";

    private static volatile boolean sInstalled = false;
    private static String sLastKey = "";
    private static long sLastAt = 0L;
    private static final AtomicInteger sProbe = new AtomicInteger(0);

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
            XLog.w("找不到类 " + HANDLER_CLASS + "，本 ROM 可能不使用该实现");
            return;
        }

        Method[] methods = handlerClass.getDeclaredMethods();
        XLog.i("命中类 " + HANDLER_CLASS + "，声明方法数=" + methods.length);

        Method primary = null;
        for (Method m : methods) {
            if (METHOD_NAME.equals(m.getName())) {
                Class<?>[] ps = m.getParameterTypes();
                if (ps.length >= 2 && ps[0] == Intent.class) {
                    primary = m;
                    break;
                }
            }
        }

        if (primary != null) {
            hookOne(primary);
            sInstalled = true;
            return;
        }

        // 兜底：方法改名了也不要紧，找「首参 Intent + 含 BroadcastReceiver」的方法
        XLog.w("没有名为 " + METHOD_NAME + " 的方法，改用模糊匹配");
        int count = 0;
        for (Method m : methods) {
            Class<?>[] ps = m.getParameterTypes();
            if (ps.length >= 2 && ps[0] == Intent.class && contains(ps, BroadcastReceiver.class)) {
                hookOne(m);
                count++;
            }
        }
        if (count > 0) {
            sInstalled = true;
            XLog.i("模糊匹配共 hook " + count + " 个方法");
        } else {
            XLog.w("模糊匹配也没找到可用方法，短信转发不可用");
        }
    }

    private static void hookOne(Method target) {
        XposedBridge.hookMethod(target, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) {
                try {
                    if (param.args != null && param.args.length > 0 && param.args[0] instanceof Intent) {
                        onDispatchIntent((Intent) param.args[0]);
                    }
                } catch (Throwable t) {
                    // 绝不能把异常抛回电话进程，否则可能连带系统电话一起崩
                    XLog.e("处理短信失败", t);
                }
            }
        });
        StringBuilder sb = new StringBuilder();
        for (Class<?> p : target.getParameterTypes()) {
            sb.append(p.getSimpleName()).append(", ");
        }
        XLog.i("已 Hook " + target.getDeclaringClass().getSimpleName() + "#" + target.getName()
                + "(" + sb + ")");
    }

    private static boolean contains(Class<?>[] arr, Class<?> type) {
        for (Class<?> c : arr) {
            if (c == type) {
                return true;
            }
        }
        return false;
    }

    private static void onDispatchIntent(Intent intent) {
        String action = intent.getAction();

        // 探针：只记前 3 次，用来确认 hook 真的活了
        if (sProbe.getAndIncrement() < 3) {
            XLog.i("探针 dispatchIntent 被调用, action=" + action);
        }

        if (!Telephony.Sms.Intents.SMS_DELIVER_ACTION.equals(action)) {
            return;
        }

        Bundle extras = intent.getExtras();
        if (extras == null) {
            XLog.w("SMS_DELIVER 但 intent 没有 extras");
            return;
        }
        Object[] pdus = (Object[]) extras.get("pdus");
        if (pdus == null || pdus.length == 0) {
            XLog.w("SMS_DELIVER 但没有 pdus，extras=" + extras.keySet());
            return;
        }
        String format = extras.getString("format");
        XLog.i("收到短信 PDU: 段数=" + pdus.length + " format=" + format);

        String sender = null;
        long timeMillis = 0L;
        StringBuilder content = new StringBuilder();

        for (Object pdu : pdus) {
            if (!(pdu instanceof byte[])) {
                XLog.w("pdu 不是 byte[]，实际类型=" + (pdu == null ? "null" : pdu.getClass().getName()));
                continue;
            }
            SmsMessage sm = parseOne((byte[]) pdu, format);
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

        if (content.length() == 0 && (sender == null || sender.isEmpty())) {
            XLog.w("短信解析结果为空（sender/content 都拿不到），放弃转发");
            return;
        }

        int subId = intent.getIntExtra("subscription", -1);
        SmsInfo info = new SmsInfo(sender, content.toString(), timeMillis, slotIndexOf(subId));

        // 同一条短信可能被不同接收者重复投递，做一次短时间去重
        String key = info.sender + "|" + info.content + "|" + info.timeMillis;
        synchronized (SmsHook.class) {
            long now = System.currentTimeMillis();
            if (key.equals(sLastKey) && (now - sLastAt) < 3000L) {
                XLog.i("重复投递，跳过");
                return;
            }
            sLastKey = key;
            sLastAt = now;
        }

        XLog.i("准备转发 from=" + info.sender + " slot=" + info.slotLabel()
                + " len=" + info.content.length());

        Thread worker = new Thread(() -> forward(info), "sms-fw-forward");
        worker.setDaemon(true);
        worker.start();
    }

    private static SmsMessage parseOne(byte[] pdu, String format) {
        if (format != null) {
            try {
                return SmsMessage.createFromPdu(pdu, format);
            } catch (Throwable t) {
                XLog.w("createFromPdu(pdu," + format + ") 失败: " + t);
            }
        }
        try {
            return SmsMessage.createFromPdu(pdu);
        } catch (Throwable t) {
            XLog.w("createFromPdu(pdu) 失败: " + t);
        }
        return null;
    }

    private static void forward(SmsInfo info) {
        Settings s;
        try {
            XSharedPreferences sp = new XSharedPreferences(Const.PKG, Const.PREFS_NAME);
            sp.reload();
            s = Settings.load(sp);
        } catch (Throwable t) {
            XLog.e("读取模块配置失败（检查 LSPosed 是否已给本模块启用 xposedsharedprefs）", t);
            return;
        }

        if (!s.enable) {
            XLog.i("转发开关已关闭，跳过");
            return;
        }
        if (s.sourceEmpty) {
            XLog.w("读到的配置为空：请确认 App 里点过「保存配置」，并在 LSPosed 里重启过作用域。将按默认值继续发送。");
        }
        if (s.token == null || s.token.trim().isEmpty()) {
            XLog.w("API Token 为空，网关大概率返回 401。请先在 App 里填入 Token。");
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
