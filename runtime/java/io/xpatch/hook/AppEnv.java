package io.xpatch.hook;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import io.xpatch.core.module.RemoteModule;
import java.util.List;

public class AppEnv {
    public static final String SIG_MODE_OFF = "off";
    public static final String SIG_MODE_JAVA = "java";
    public static final String SIG_MODE_JAVA_FILE = "java_file";
    public static final String SIG_MODE_DEEP = "deep";

    public static ClassLoader appClassLoader = null;
    public static Context appContext = null;
    public static ApplicationInfo baseApp = null;
    public static String buildNonce = null;
    public static int core_ver = 1;
    public static boolean dynamicMode = false;
    public static boolean fileProvider = false;
    /** 在 native io 重定向启用前从包装 APK 预读的 file provider dex。 */
    public static byte[] fileProviderDex;
    public static ClassLoader hook_core_cl = null;
    /** hook_core.dex 的 CoreEntry.InitHookCore 已成功 System.load 过 hook_core.so（含 x86_64 资产）。 */
    public static boolean nativeLoadedByHookCore = false;
    public static List<RemoteModule> modules = null;
    public static String o_apk_path = null;
    public static String packageName = null;
    public static String processName = null;
    public static long read_version_code = -1;
    public static String selfApk;
    /** 首次进入时的包装 APK 路径；动态模式切换后 selfApk 可能被框架改成原包，运行层资产要用这个。 */
    public static String wrapperApk;
    public static int sign_bypass_type;
    public static Signature[] signatures;
    public static String source_apk_path;

    public static String sig_mode = SIG_MODE_JAVA;
    public static boolean sig_mode_explicit = false;
    public static String signing_details = null;

    /**
     * 管理器包名：打包时由 ApkPatcher 写入 xp/config.json 的 manager_package，缺省 io.xpatch。
     * 模块崩溃广播用它定位接收方，避免内核侧写死包名（渠道后缀/改名后广播会静默消失）。
     */
    public static String managerPackage = "io.xpatch";


    public static boolean isInMainProc() {
        return processName.equals(packageName);
    }
}
