package com.xiguan.smsfw;

import android.util.Log;

/**
 * 日志：同时写 logcat 和 Xposed/LSPosed 日志。
 * <p>
 * 这里故意不用 Class.forName 做能力探测 —— 探测失败会导致 XposedBridge.log 永远不被调用，
 * 于是「模块到底有没有被注入」就完全看不出来了。直接 try/catch 调用，
 * 普通 App 进程里会抛 NoClassDefFoundError，被吞掉即可。
 */
public final class XLog {

    private XLog() {
    }

    public static void i(String msg) {
        Log.i(Const.TAG, msg);
        toXposed("I", msg);
    }

    public static void w(String msg) {
        Log.w(Const.TAG, msg);
        toXposed("W", msg);
    }

    public static void e(String msg, Throwable t) {
        Log.e(Const.TAG, msg, t);
        toXposed("E", msg + "\n" + Log.getStackTraceString(t));
    }

    private static void toXposed(String level, String msg) {
        try {
            de.robv.android.xposed.XposedBridge.log("[SmsFw][" + level + "] " + msg);
        } catch (Throwable ignored) {
            // 普通 App 进程里没有 XposedBridge，忽略
        }
    }
}
