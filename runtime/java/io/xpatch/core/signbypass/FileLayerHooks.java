package io.xpatch.core.signbypass;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.File;

/**
 * 文件层：把本进程看到的"自己的 APK 路径"改成字节级原包
 * （{@code xp/source.apk} 释放出的 {@code <crc>.apk}，见 AppEnv.source_apk_path）。
 *
 * <p>不变量：
 * <ol>
 *   <li>只有 {@code AppEnv.source_apk_path} 非空且文件真实存在时才安装 hook；拿不到原包就完全不 hook，
 *       所有 getter 保持真实安装路径（宁可不过文件级自校验，也不能让 codePath 指向不存在的文件）。</li>
 *   <li>只改写"本包"的返回值：this0 的包名等于 AppEnv.packageName，或原返回值就是 AppEnv.selfApk；
 *       {@code createPackageContext()} 出来的其它包 Context 必须原样透传。</li>
 *   <li>{@code getApplicationInfo()} 额外要求 {@code AppEnv.packageName.equals(result.packageName)}，
 *       且已经替换过的对象不再复制（ContextWrapper → ContextImpl 会走两次 hook，避免每次调用分配两个副本）。</li>
 *   <li>install() 幂等：成功装上后 installed=true，重复调用直接返回 true——对同一个 member 再 doHook
 *       只会追加第二个 DispatchParam，回调会跑两遍。</li>
 *   <li>所有路径用 {@link File#getCanonicalPath()} 规范化，与 native 层 openat 的整串比较（g_self == path）
 *       保持同一写法。</li>
 *   <li>不覆盖 stat/access：native 侧只 hook 了 open/openat/fopen
 *       （{@code io_redirect::install}），Java 侧也只改 Context 的 3 个 getter，保持最小暴露面；
 *       走 stat/access 的自校验仍然读到补丁包，属已知缺口（见 review/parts/04-filelayer.md "native 精确匹配"）。</li>
 * </ol>
 */
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

    /**
     * 安装文件层 hook。
     *
     * @return 是否已处于"文件层已安装"状态；false 时 {@link #lastError()} 给出原因
     *         （无原包 / 原包文件不存在 / 所有 hook 都失败）
     */
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
        // 回写规范化后的路径：保证 native 层（LevelOne/Two/Mix 走 getRedirectTarget）与 Java 层用同一串
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
                    // 只动本包：别的包（createPackageContext）必须拿到自己的 sourceDir
                    if (info.packageName == null || !info.packageName.equals(AppEnv.packageName)) {
                        return;
                    }
                    String factory = AppEnv.baseApp == null ? null : AppEnv.baseApp.appComponentFactory;
                    if (target.equals(info.sourceDir) && target.equals(info.publicSourceDir)
                            && equal(factory, info.appComponentFactory)) {
                        // 已被内层（ContextImpl）hook 替换过，不再复制第二份
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
            // 只在快路径不相等时才做一次规范化（getPackageCodePath 是热路径）
            String normalized = canonicalPath(path);
            return normalized != null && normalized.equals(canonical);
        } catch (Throwable th) {
            return false;
        }
    }

    private static boolean equal(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * 包内可见：把路径规范化为绝对规范形式（解析符号链接、去掉 ../），失败时退回绝对路径。
     *
     * @return null 表示入参为空
     */
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

    /** 文件层是否已安装（诊断用）。 */
    public static synchronized boolean isInstalled() {
        return installed;
    }

    /** 已启用的重定向目标（规范化后的原包路径，未启用时为 null）。 */
    public static synchronized String installedTarget() {
        return targetPath;
    }

    /** 成功装上的 hook 数（0-5：ContextImpl 3 个 + ContextWrapper 2 个）。 */
    public static synchronized int hookedCount() {
        return hookedCount;
    }

    /** 最近一次未启用的原因；成功时为 null。 */
    public static synchronized String lastError() {
        return lastError;
    }
}
