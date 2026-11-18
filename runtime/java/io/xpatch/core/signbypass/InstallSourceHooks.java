package io.xpatch.core.signbypass;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.InstallSourceInfo;
import android.content.pm.PackageManager;
import android.content.pm.SigningInfo;
import android.util.Log;
import io.xpatch.core.reflect.XClass;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

public final class InstallSourceHooks {

    private static final String TAG = "XpInstallSource";

    private static final String[] EXCLUDED = {
            "com.android.shell",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.vending.installer",
            "android",
    };

    private static final String[] KNOWN_STORES = {
            "com.bbk.appstore",
            "com.heytap.market",
            "com.oppo.market",
            "com.huawei.appmarket",
            "com.hihonor.appmarket",
            "com.xiaomi.market",
            "com.sec.android.app.samsungapps",
            "com.tencent.android.qqdownloader",
            "com.qihoo.appstore",
            "com.meizu.mstore",
            "com.android.vending",
    };

    private static volatile String storePackage;
    private static volatile boolean installed;
    private static volatile boolean logged;

    private InstallSourceHooks() {
    }

    public static synchronized boolean install() {
        if (installed) {
            return true;
        }
        String store = resolveStorePackage();
        if (store == null || store.length() == 0) {
            log("跳过安装来源伪装：没有识别到本机应用商店");
            return false;
        }
        storePackage = store;
        boolean hooked = false;
        hooked |= hookInstallerPackageName();
        hooked |= hookInstallSourceInfo();
        installed = hooked;
        log(hooked
                ? "安装来源伪装已启用：商店=" + store + "（getInstallerPackageName=" + hooked + "）"
                : "安装来源伪装未安装：两个入口都没挂上");
        return hooked;
    }

    public static String storePackage() {
        return storePackage;
    }

    private static String resolveStorePackage() {
        String detected = detectFromInstalledApps();
        if (detected != null) {
            return detected;
        }
        return detectFromCandidates();
    }

    private static Context resolveContext() {
        Context cached = AppEnv.appContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) {
                return null;
            }
            Class<?> type = thread.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field field = type.getDeclaredField("mInitialApplication");
                    field.setAccessible(true);
                    Object app = field.get(thread);
                    if (app instanceof Context) {
                        return (Context) app;
                    }
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static String detectFromInstalledApps() {
        Context context = resolveContext();
        if (context == null) {
            return null;
        }
        try {
            PackageManager manager = context.getPackageManager();
            if (manager == null) {
                return null;
            }
            Map<String, Integer> counts = new HashMap<String, Integer>();
            for (ApplicationInfo info : manager.getInstalledApplications(0)) {
                if (info == null || info.packageName == null) {
                    continue;
                }
                String installer = manager.getInstallerPackageName(info.packageName);
                if (installer == null || installer.length() == 0 || isExcluded(installer)) {
                    continue;
                }
                Integer current = counts.get(installer);
                counts.put(installer, current == null ? 1 : current + 1);
            }
            String best = null;
            int bestCount = 0;
            for (Map.Entry<String, Integer> entry : counts.entrySet()) {
                if (entry.getValue() > bestCount) {
                    best = entry.getKey();
                    bestCount = entry.getValue();
                }
            }
            return best;
        } catch (Throwable th) {
            log("枚举已安装应用的来源失败：" + th);
            return null;
        }
    }

    private static String detectFromCandidates() {
        Context context = resolveContext();
        if (context == null) {
            return null;
        }
        PackageManager manager = context.getPackageManager();
        if (manager == null) {
            return null;
        }
        for (String candidate : KNOWN_STORES) {
            try {
                manager.getPackageInfo(candidate, 0);
                return candidate;
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static boolean isExcluded(String installer) {
        for (String excluded : EXCLUDED) {
            if (excluded.equals(installer)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isSelf(Object value) {
        return value instanceof String && AppEnv.packageName != null
                && AppEnv.packageName.equals(value);
    }

    private static boolean hookInstallerPackageName() {
        Class<?> runtime = runtimeManagerClass();
        if (runtime == null) {
            return false;
        }
        try {
            Method method = runtime.getMethod("getInstallerPackageName", new Class[]{String.class});
            if (XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    if (isSelf(param.args != null && param.args.length > 0 ? param.args[0] : null)) {
                        logHit("getInstallerPackageName");
                        param.setResult(storePackage);
                        return;
                    }
                    param.proceed();
                }
            }) == null) {
                return false;
            }
            return true;
        } catch (Throwable th) {
            log("hook getInstallerPackageName 失败：" + Log.getStackTraceString(th));
            return false;
        }
    }

    private static boolean hookInstallSourceInfo() {
        boolean hooked = false;
        Class<?> runtime = XClass.loadEx("android.app.ApplicationPackageManager");
        if (runtime != null) {
            hooked |= hookInstallSourceOn(runtime);
        }
        hooked |= hookInstallSourceOn(PackageManager.class);
        Context context = resolveContext();
        if (context != null) {
            try {
                PackageManager live = context.getPackageManager();
                if (live != null && live.getClass() != PackageManager.class) {
                    hooked |= hookInstallSourceOn(live.getClass());
                }
            } catch (Throwable ignored) {
            }
        }
        return hooked;
    }

    private static boolean hookInstallSourceOn(Class<?> cls) {
        try {
            Method method = cls.getMethod("getInstallSourceInfo", new Class[]{String.class});
            if (XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    if (!isSelf(param.args != null && param.args.length > 0 ? param.args[0] : null)) {
                        param.proceed();
                        return;
                    }
                    String store = storePackage;
                    if (store == null) {
                        param.proceed();
                        return;
                    }
                    param.proceed();
                    Object result = param.getResult();
                    if (result instanceof InstallSourceInfo) {
                        if (patchInPlace((InstallSourceInfo) result, store)) {
                            logHit("getInstallSourceInfo(就地改写)");
                            return;
                        }
                    }
                    InstallSourceInfo forged = buildInfo();
                    if (forged != null) {
                        logHit("getInstallSourceInfo(替换对象)");
                        param.setResult(forged);
                    }
                }
            }) != null) {
                log("getInstallSourceInfo hook ok: " + cls.getName());
                return true;
            }
        } catch (Throwable th) {
            log("getInstallSourceInfo 挂载失败 " + cls.getName() + "：" + th);
        }
        return false;
    }

    private static boolean patchInPlace(InstallSourceInfo info, String store) {
        boolean changed = false;
        changed |= setField(info, "mInstallingPackageName", store);
        changed |= setField(info, "mInitiatingPackageName", store);
        changed |= setField(info, "mOriginatingPackageName", null);
        return changed;
    }

    private static boolean setField(Object target, String name, Object value) {
        try {
            java.lang.reflect.Field field = InstallSourceInfo.class.getDeclaredField(name);
            field.setAccessible(true);
            field.set(target, value);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static InstallSourceInfo buildInfo() {
        String store = storePackage;
        if (store == null) {
            return null;
        }
        for (Class<?>[] signature : new Class[][]{
                {String.class, String.class, String.class, SigningInfo.class, Integer.TYPE},
                {String.class, String.class, String.class, SigningInfo.class},
        }) {
            try {
                Constructor<?> constructor = InstallSourceInfo.class.getConstructor(signature);
                Object instance = signature.length == 5
                        ? constructor.newInstance(store, store, store, null, 0)
                        : constructor.newInstance(store, store, store, null);
                if (instance instanceof InstallSourceInfo) {
                    return (InstallSourceInfo) instance;
                }
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static Class<?> runtimeManagerClass() {
        Class<?> runtime = XClass.loadEx("android.app.ApplicationPackageManager");
        return runtime != null ? runtime : PackageManager.class;
    }

    private static void logHit(String api) {
        if (logged) {
            return;
        }
        logged = true;
        log(api + " 首次命中：本包返回商店 " + storePackage);
    }

    private static void log(String message) {
        XpHook.log0(TAG, message);
    }
}
