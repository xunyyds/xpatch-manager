package io.xpatch.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import io.xpatch.core.module.RemoteModule;
import java.util.List;

public class AppEnv {
    public static ClassLoader appClassLoader = null;
    public static Context appContext = null;
    public static ApplicationInfo baseApp = null;
    public static String buildNonce = null;
    public static int core_ver = 1;
    public static boolean dynamicMode = false;
    public static boolean fileProvider = false;
    public static ClassLoader hook_core_cl = null;
    public static List<RemoteModule> modules = null;
    public static String o_apk_path = null;
    public static String packageName = null;
    public static String processName = null;
    public static long read_version_code = -1;
    public static String selfApk;
    public static int sign_bypass_type;
    public static Signature[] signatures;
    public static String source_apk_path;

    public static boolean isInMainProc() {
        return processName.equals(packageName);
    }
}
