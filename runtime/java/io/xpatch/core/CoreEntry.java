package io.xpatch.core;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.utils.AppUtils;

public class CoreEntry {
    public static final int RUNTIME_VERSION = 42;

    public static void loadAppInPack(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        AppEnv.processName = AppUtils.getProcessName();
        AppEnv.appClassLoader = classLoader;
        AppEnv.baseApp = applicationInfo;
        AppEnv.packageName = applicationInfo.packageName;
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

    public static ClassLoader dynamicClassLoader() {
        if (!AppEnv.dynamicMode) {
            return null;
        }
        String source = AppEnv.source_apk_path;
        if (source == null || source.length() == 0) {
            return null;
        }
        ClassLoader loader = AppEnv.dynamicLoader;
        if (loader != null) {
            return loader;
        }
        synchronized (CoreEntry.class) {
            loader = AppEnv.dynamicLoader;
            if (loader == null) {
                loader = new dalvik.system.PathClassLoader(source, ClassLoader.getSystemClassLoader());
                AppEnv.dynamicLoader = loader;
            }
        }
        return loader;
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
