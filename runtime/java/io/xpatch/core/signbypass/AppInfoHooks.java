package io.xpatch.core.signbypass;

import android.content.pm.ApplicationInfo;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;

public final class AppInfoHooks {

    private static final String TAG = "XpAppInfo";

    private static boolean installed;
    private static int hookedCount;
    private static String lastError = "尚未安装";
    private static volatile String originalFactory;
    private static volatile boolean originalCaptured;

    private AppInfoHooks() {
    }

    private static String originalFactory() {
        if (!originalCaptured) {
            String value = AppEnv.baseApp == null ? null : AppEnv.baseApp.appComponentFactory;
            if (value != null && value.length() == 0) {
                value = null;
            }
            originalFactory = value;
            originalCaptured = true;
            XpHook.log0(TAG, "原始工厂记录=" + (value == null ? "无（宿主原本没有工厂）" : value));
        }
        return originalFactory;
    }

    public static synchronized boolean install() {
        if (installed) {
            return true;
        }
        originalFactory();
        int count = 0;
        count += hookParcelConstructor();
        count += hookPackageManager("android.app.ApplicationPackageManager");
        count += hookContextGetApplicationInfo("android.app.ContextImpl");
        count += hookContextGetApplicationInfo("android.content.ContextWrapper");
        hookedCount = count;
        if (count == 0) {
            installed = false;
            lastError = "ApplicationInfo 工厂还原 hook 全部失败";
            XpHook.log0(TAG, "工厂还原未启用：" + lastError);
            return false;
        }
        installed = true;
        lastError = null;
        XpHook.log0(TAG, "工厂还原已启用：宿主读 getApplicationInfo 看不到补丁工厂（hook 数=" + count + "）");
        return true;
    }

    private static int hookParcelConstructor() {
        Class<?> cls = loadClass("android.content.pm.ApplicationInfo");
        if (cls == null) {
            return 0;
        }
        try {
            java.lang.reflect.Constructor<?> ctor = cls.getDeclaredConstructor(
                    android.os.Parcel.class);
            if (XpHook.doHook(ctor, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    param.proceed();
                    if (param.this0 instanceof ApplicationInfo) {
                        restore((ApplicationInfo) param.this0);
                    }
                }
            }) != null) {
                return 1;
            }
            XpHook.log0(TAG, "ApplicationInfo(Parcel) hook 失败");
        } catch (Throwable th) {
            XpHook.log0(TAG, "ApplicationInfo(Parcel) hook 异常：" + Log.getStackTraceString(th));
        }
        return 0;
    }

    private static int hookPackageManager(String className) {
        Class<?> cls = loadClass(className);
        if (cls == null) {
            return 0;
        }
        int count = 0;
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod("getApplicationInfo",
                    String.class, Integer.TYPE);
            if (XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    param.proceed();
                    Object result = param.getResult();
                    if (result instanceof ApplicationInfo) {
                        restore((ApplicationInfo) result);
                    }
                }
            }) != null) {
                count++;
            } else {
                XpHook.log0(TAG, className + ".getApplicationInfo(String,int) hook 失败");
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, className + ".getApplicationInfo hook 异常：" + Log.getStackTraceString(th));
        }
        return count;
    }

    private static int hookContextGetApplicationInfo(String className) {
        Class<?> cls = loadClass(className);
        if (cls == null) {
            return 0;
        }
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod("getApplicationInfo");
            if (XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    param.proceed();
                    Object result = param.getResult();
                    if (result instanceof ApplicationInfo) {
                        restore((ApplicationInfo) result);
                    }
                }
            }) != null) {
                return 1;
            }
            XpHook.log0(TAG, className + ".getApplicationInfo hook 失败");
        } catch (Throwable th) {
            XpHook.log0(TAG, className + ".getApplicationInfo hook 异常：" + Log.getStackTraceString(th));
        }
        return 0;
    }

    private static void restore(ApplicationInfo info) {
        if (info == null) {
            return;
        }
        String pkg = AppEnv.packageName;
        if (pkg == null || info.packageName == null || !pkg.equals(info.packageName)) {
            return;
        }
        String factory = originalFactory();
        info.appComponentFactory = factory;
    }

    private static Class<?> loadClass(String name) {
        try {
            return Class.forName(name);
        } catch (Throwable th) {
            return null;
        }
    }

    public static synchronized boolean isInstalled() {
        return installed;
    }

    public static synchronized int hookedCount() {
        return hookedCount;
    }

    public static synchronized String lastError() {
        return lastError;
    }
}
