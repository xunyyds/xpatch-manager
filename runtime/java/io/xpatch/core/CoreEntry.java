package io.xpatch.core;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.utils.AppUtils;

public class CoreEntry {
    /** 运行时内核版本：与打包端 ApkPatcher.RUNTIME_VERSION 对齐（W7 的版本门禁按此校验）。 */
    public static final int RUNTIME_VERSION = 42;

    public static void loadAppInPack(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        AppEnv.processName = AppUtils.getProcessName();
        AppEnv.appClassLoader = classLoader;
        AppEnv.baseApp = applicationInfo;
        AppEnv.packageName = applicationInfo.packageName;
        // 首次进入时记录包装 APK；动态模式切换 LoadedApk 后 selfApk 可能被改成原包，
        // xp/xp89.dex 等运行层资产必须继续从包装 APK 读。
        if (AppEnv.wrapperApk == null || AppEnv.wrapperApk.length() == 0) {
            AppEnv.wrapperApk = applicationInfo.sourceDir;
        }
        if (AppEnv.selfApk == null || AppEnv.selfApk.length() == 0) {
            AppEnv.selfApk = applicationInfo.sourceDir;
        }
        Log.d("XPatch", "XPatch 载入：包=" + AppEnv.packageName + "，进程=" + AppEnv.processName);
        if (isIsolatedProcess()) {
            Log.d("XPatch", "跳过 isolated 进程：进程=" + AppEnv.processName
                    + "，uid=" + Process.myUid());
        } else {
            XpInit.startInit();
        }
    }

    private static boolean isIsolatedProcess() {
        try {
            if (Process.isIsolated()) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        try {
            int uid = Process.myUid();
            if (uid >= ISOLATED_UID_LOW && uid <= ISOLATED_UID_HIGH) {
                return true;
            }
        } catch (Throwable ignored) {
        }
        String name = AppEnv.processName;
        if (name != null) {
            if (name.contains(":sandboxed_process")
                    || name.contains(":sandboxed_privilege_process")
                    || name.contains(":sandboxed_compute")
                    || name.contains(":webview_sandboxed")) {
                return true;
            }
        }
        return false;
    }

    private static final int ISOLATED_UID_LOW = 90000;
    private static final int ISOLATED_UID_HIGH = 99999;
}
