package io.xpatch.core.signbypass;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.File;

public final class FileLayerHooks {

    private static final String TAG = "XpFileLayer";

    private static final String CONTEXT_IMPL = "android.app.ContextImpl";
    private static final String CONTEXT_WRAPPER = "android.content.ContextWrapper";

    private static boolean installed;
    private static String targetPath;
    private static String selfApkPath;
    private static int hookedCount;
    private static String lastError = "尚未安装";

    private FileLayerHooks() {
    }

    public static synchronized boolean install() {
        if (installed) {
            XpHook.log0(TAG, "文件层已安装，跳过重复安装：" + targetPath);
            return true;
        }
        String raw = AppEnv.source_apk_path;
        if (raw == null || raw.trim().length() == 0) {
            lastError = "没有字节级原包（AppEnv.source_apk_path 为空，打包时未内嵌 xp/source.apk）";
            XpHook.log0(TAG, "文件层未启用：" + lastError);
            return false;
        }
        String canonical = canonicalPath(raw);
        if (canonical == null) {
            lastError = "原包路径无法规范化：" + raw;
            XpHook.log0(TAG, "文件层未启用：" + lastError);
            return false;
        }
        if (!new File(canonical).isFile()) {
            lastError = "原包文件不存在：" + canonical;
            XpHook.log0(TAG, "文件层未启用：" + lastError);
            return false;
        }
        targetPath = canonical;
        selfApkPath = canonicalPath(AppEnv.selfApk);
        AppEnv.source_apk_path = canonical;

        int count = 0;
        count += installOn(CONTEXT_IMPL, true);
        count += installOn(CONTEXT_WRAPPER, false);
        hookedCount = count;
        if (count == 0) {
            installed = false;
            targetPath = null;
            lastError = "Context hook 全部失败（doHook 返回 null）";
            XpHook.log0(TAG, "文件层未启用：" + lastError);
            return false;
        }
        installed = true;
        lastError = null;
        XpHook.log0(TAG, "文件层已启用：codePath/resourcePath/applicationInfo -> " + targetPath
                + "（hook 数=" + count + "）");
        return true;
    }

    private static int installOn(String className, boolean withApplicationInfo) {
        Class<?> cls = loadClass(className);
        if (cls == null) {
            XpHook.log0(TAG, className + " 不存在，跳过该 Context 的 hook");
            return 0;
        }
        int count = 0;
        if (hookPath(cls, "getPackageCodePath")) {
            count++;
        }
        if (hookPath(cls, "getPackageResourcePath")) {
            count++;
        }
        if (withApplicationInfo && hookApplicationInfo(cls)) {
            count++;
        }
        return count;
    }

    private static boolean hookPath(Class<?> cls, String name) {
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod(name);
            XpHook.XpUnhook token = XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    String target = targetPath;
                    if (target == null) {
                        param.proceed();
                        return;
                    }
                    param.proceed();
                    Object result = param.getResult();
                    if (!(result instanceof String)) {
                        return;
                    }
                    String original = (String) result;
                    if (!isSelfContext(param.this0) && !samePath(original, selfApkPath)) {
                        return;
                    }
                    param.setResult(target);
                }
            });
            if (token == null) {
                XpHook.log0(TAG, name + " hook 失败：" + cls.getName());
                return false;
            }
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, name + " hook 异常（" + cls.getName() + "）：" + Log.getStackTraceString(th));
            return false;
        }
    }

    private static boolean hookApplicationInfo(Class<?> cls) {
        try {
            java.lang.reflect.Method method = cls.getDeclaredMethod("getApplicationInfo");
            XpHook.XpUnhook token = XpHook.doHook(method, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    String target = targetPath;
                    if (target == null) {
                        param.proceed();
                        return;
                    }
                    param.proceed();
                    Object result = param.getResult();
                    if (!(result instanceof ApplicationInfo)) {
                        return;
                    }
                    ApplicationInfo info = (ApplicationInfo) result;
                    if (info.packageName == null || !info.packageName.equals(AppEnv.packageName)) {
                        return;
                    }
                    String factory = AppEnv.baseApp == null ? null : AppEnv.baseApp.appComponentFactory;
                    if (target.equals(info.sourceDir) && target.equals(info.publicSourceDir)
                            && equal(factory, info.appComponentFactory)) {
                        return;
                    }
                    ApplicationInfo copy = new ApplicationInfo(info);
                    copy.sourceDir = target;
                    copy.publicSourceDir = target;
                    copy.appComponentFactory = factory;
                    param.setResult(copy);
                }
            });
            if (token == null) {
                XpHook.log0(TAG, "getApplicationInfo hook 失败：" + cls.getName());
                return false;
            }
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "getApplicationInfo hook 异常（" + cls.getName() + "）："
                    + Log.getStackTraceString(th));
            return false;
        }
    }

    private static boolean isSelfContext(Object this0) {
        String pkg = AppEnv.packageName;
        if (pkg == null || !(this0 instanceof Context)) {
            return false;
        }
        try {
            return pkg.equals(((Context) this0).getPackageName());
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean samePath(String path, String canonical) {
        if (path == null || canonical == null) {
            return false;
        }
        if (path.equals(canonical)) {
            return true;
        }
        try {
            String normalized = canonicalPath(path);
            return normalized != null && normalized.equals(canonical);
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    static String canonicalPath(String path) {
        if (path == null || path.trim().length() == 0) {
            return null;
        }
        File file = new File(path.trim());
        try {
            return file.getCanonicalPath();
        } catch (Throwable th) {
            try {
                return file.getAbsolutePath();
            } catch (Throwable ignored) {
                return null;
            }
        }
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

    public static synchronized String installedTarget() {
        return targetPath;
    }

    public static synchronized int hookedCount() {
        return hookedCount;
    }

    public static synchronized String lastError() {
        return lastError;
    }
}
