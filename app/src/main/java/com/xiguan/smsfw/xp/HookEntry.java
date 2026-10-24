package com.xiguan.smsfw.xp;

import com.xiguan.smsfw.XLog;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

/**
 * Xposed 入口，由 assets/xposed_init 指定。
 * <p>
 * 只处理电话进程（com.android.phone），其它进程直接返回，
 * 保证模块不会给无关应用带来任何开销或风险。
 */
public class HookEntry implements IXposedHookLoadPackage {

    /** 短信由电话进程处理；少数 ROM 是 com.android.phone 的变体 */
    private static final String[] TARGET_PACKAGES = {
            "com.android.phone",
            "com.android.services.telephony",
    };

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) {
        if (lpparam == null || lpparam.packageName == null) {
            return;
        }
        boolean target = false;
        for (String pkg : TARGET_PACKAGES) {
            if (pkg.equals(lpparam.packageName)) {
                target = true;
                break;
            }
        }
        if (!target) {
            return;
        }

        try {
            SmsHook.install(lpparam.classLoader);
        } catch (Throwable t) {
            XLog.e("安装短信 Hook 失败（" + lpparam.packageName + "）", t);
        }
    }
}
