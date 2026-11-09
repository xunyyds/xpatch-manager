package io.xpatch.core.signbypass;

import android.content.pm.ApplicationInfo;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;

public final class FileLayerHooks {

    private FileLayerHooks() {
    }

    public static void install() {
        install("android.app.ContextImpl");
        install("android.content.ContextWrapper");
    }

    private static void install(String className) {
        Class<?> cls = XClassLoad.load(className);
        if (cls == null) {
            return;
        }
        hookPath(cls, "getPackageCodePath");
        hookPath(cls, "getPackageResourcePath");
        hookApplicationInfo(cls);
    }

    private static void hookPath(Class<?> cls, String name) {
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod(name);
            XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    String path = AppEnv.source_apk_path;
                    if (path == null || path.length() == 0) {
                        param.proceed();
                    } else {
                        param.setResult(path);
                    }
                }
            });
        } catch (Throwable th) {
            XpHook.log0("XpFileLayer", name + " hook 失败：" + Log.getStackTraceString(th));
        }
    }

    private static void hookApplicationInfo(Class<?> cls) {
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod("getApplicationInfo");
            XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    String path = AppEnv.source_apk_path;
                    if (path == null || path.length() == 0) {
                        param.proceed();
                        return;
                    }
                    param.proceed();
                    Object result = param.getResult();
                    if (result instanceof ApplicationInfo) {
                        ApplicationInfo copy = new ApplicationInfo((ApplicationInfo) result);
                        copy.sourceDir = path;
                        copy.publicSourceDir = path;
                        copy.appComponentFactory = AppEnv.baseApp == null ? null : AppEnv.baseApp.appComponentFactory;
                        param.setResult(copy);
                    }
                }
            });
        } catch (Throwable th) {
            XpHook.log0("XpFileLayer", "getApplicationInfo hook 失败：" + Log.getStackTraceString(th));
        }
    }

    private static final class XClassLoad {
        static Class<?> load(String name) {
            try {
                return Class.forName(name);
            } catch (Throwable th) {
                return null;
            }
        }
    }
}
