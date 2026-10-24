package com.xiguan.smsfw;

import android.util.Log;

/**
 * 日志：同时写 logcat 和 Xposed/LSPosed 日志。
 * XposedBridge 只在被注入的进程里存在，普通 App 进程调用会抛 NoClassDefFoundError，
 * 因此全部包在 try/catch 里。
 */
public final class XLog {

    private static final boolean XPOSED;

    static {
        boolean ok;
        try {
            Class.forName("de.robv.android.xposed.XposedBridge", false, XLog.class.getClassLoader());
            ok = true;
        } catch (Throwable t) {
            ok = false;
        }
        XPOSED = ok;
    }

    private XLog() {
    }

    public static void i(String msg) {
        Log.i(Const.TAG, msg);
        xposed(Log.INFO, msg);
    }

    public static void w(String msg) {
        Log.w(Const.TAG, msg);
        xposed(Log.WARN, msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(Const.TAG, msg, t);
        xposed(Log.ERROR, msg + "\n" + Log.getStackTraceString(t));
    }

    private static void xposed(int priority, String msg) {
        if (!XPOSED) {
            return;
        }
        try {
            de.robv.android.xposed.XposedBridge.log("[SmsFw] " + msg);
        } catch (Throwable ignored) {
            // ignore
        }
    }
}
