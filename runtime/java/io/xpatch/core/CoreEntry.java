package io.xpatch.core;

import android.content.pm.ApplicationInfo;
import android.os.Process;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.utils.AppUtils;

public class CoreEntry {
    public static void loadAppInPack(ClassLoader classLoader, ApplicationInfo applicationInfo) {
        AppEnv.processName = AppUtils.getProcessName();
        AppEnv.appClassLoader = classLoader;
        AppEnv.baseApp = applicationInfo;
        AppEnv.packageName = applicationInfo.packageName;
        AppEnv.selfApk = applicationInfo.sourceDir;
        Log.d("XPatch", "XPatch Load InPack: pkg=" + AppEnv.packageName + ",proc=" + AppEnv.processName);
        if (Process.myUid() % 100000 == 0) {
            Log.d("XPatch", "do not init isolated process");
        } else {
            XpInit.startInit();
        }
    }
}
