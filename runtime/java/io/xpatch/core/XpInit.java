package io.xpatch.core;

import android.app.Application;
import android.app.LoadedApk;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.os.Process;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import io.xpatch.core.module.ModuleLoader;
import io.xpatch.core.module.RemoteModule;
import io.xpatch.core.module.RemoteModuleProvider;
import io.xpatch.core.remote.RemoteAccessBridge;
import io.xpatch.core.signbypass.AppInfoHooks;
import io.xpatch.core.signbypass.CheckSignaturesBypass;
import io.xpatch.core.signbypass.DeferredSignHooks;
import io.xpatch.core.signbypass.FileLayerHooks;
import io.xpatch.core.signbypass.PMSignBypass;
import io.xpatch.core.signbypass.SignBypass;
import io.xpatch.core.signbypass.SignatureFacade;
import io.xpatch.core.signbypass.SigningDetailsData;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHelper;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.AppUtils;
import io.xpatch.utils.DataUtils;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.hidden.HiddenApiBypass;

public class XpInit {
    private static final String TAG = "XpInit";

    private static String configPath() {
        return XpLayout.configEntry();
    }

    private static String hookCorePath() {
        return XpLayout.get("hook_dex");
    }

    private static String originPath() {
        return XpLayout.get("source");
    }

    private static String oOriginPath() {
        return XpLayout.get("o_source");
    }

    private static final String ACTION_MODULE_CRASH = "io.xpatch.action.MODULE_CRASH";
    private static final String PERMISSION_MODULE_CRASH = "io.xpatch.permission.MODULE_CRASH";
    private static final String DEFAULT_MANAGER_PACKAGE = "io.xpatch";

    private static final String MODULE_PENDING_PREFIX = "xpatch-module-pending-";
    private static final String MODULE_COUNTS_FILE = "xpatch-module-crash-counts.txt";
    private static final int MODULE_CRASH_THRESHOLD = 3;
    private static final long MODULE_PENDING_MAX_AGE_MILLIS = 24L * 60L * 60L * 1000L;

    static Object activityThread;
    static LoadedApk loadedApk;
    static ApplicationInfo selfApplicationInfo;

    private static boolean signBypassInstalled = false;
    private static volatile boolean started = false;
    private static volatile boolean succeeded = false;
    private static boolean deferredSignHooksScheduled = false;
    private static final List<Runnable> startupCallbacks = new ArrayList<Runnable>();
    private static boolean fallbackStartupHookInstalled = false;
    private static final List<File> startupPendingFiles = new ArrayList<File>();
    private static final Set<String> startupLoadedPackages = new HashSet<String>();
    private static final Runnable STARTUP_COMMIT = new Runnable() {
        @Override
        public void run() {
            commitStartup();
        }
    };

    public static synchronized void startInit() {
        if (started) {
            XpHook.log0(TAG, "重复初始化已忽略（进程=" + AppEnv.processName + "）");
            return;
        }
        started = true;
        List<String> failed = new ArrayList<String>();
        runStep("removeProfileFiles", failed, new Step() {
            @Override
            public void run() throws Throwable {
                AppUtils.removeProfileFiles(AppEnv.packageName);
            }
        });
        runStep("remoteOATFiles", failed, new Step() {
            @Override
            public void run() throws Throwable {
                AppUtils.remoteOATFiles(AppEnv.packageName);
            }
        });
        runStep("prepareCacheDir", failed, new Step() {
            @Override
            public void run() throws Throwable {
                new File(AppEnv.baseApp.dataDir + "/cache/android_id/").mkdirs();
            }
        });
        runStep("performXpConfig", failed, new Step() {
            @Override
            public void run() throws Throwable {
                performXpConfig();
            }
        });
        runStep("initHookCore", failed, new Step() {
            @Override
            public void run() throws Throwable {
                if (initHookCore()) {
                    NativeLoader.markLoadedByHookCore();
                } else {
                    XpHook.log0(TAG, "hook_core.dex 未能初始化：Java 层过签与文件层仍可用，native 特性降级");
                }
            }
        });
        runStep("preloadWrapperAssets", failed, new Step() {
            @Override
            public void run() throws Throwable {
                preloadWrapperAssets();
            }
        });
        runStep("installSignBypass", failed, new Step() {
            @Override
            public void run() throws Throwable {
                installSignBypass();
            }
        });
        runStep("switchClassLoader", failed, new Step() {
            @Override
            public void run() throws Throwable {
                if (!AppEnv.dynamicMode) {
                    return;
                }
                getApplicationInfo();
                initClassLoaderInject();
                XpHook.log0(TAG, "切换应用成功");
            }
        });
        runStep("injectFileProviderDex", failed, new Step() {
            @Override
            public void run() throws Throwable {
                if (!AppEnv.fileProvider) {
                    return;
                }
                injectFileProviderDexFile();
            }
        });
        runStep("createAppContext", failed, new Step() {
            @Override
            public void run() throws Throwable {
                if (AppEnv.appContext != null) {
                    return;
                }
                createAppContext();
            }
        });
        runStep("remoteAccessBridge", failed, new Step() {
            @Override
            public void run() throws Throwable {
                if (AppEnv.appContext == null) {
                    return;
                }
                RemoteAccessBridge.initialize(AppEnv.appContext);
            }
        });
        runStep("preloadHostFactory", failed, new Step() {
            @Override
            public void run() throws Throwable {
                String factory = AppEnv.baseApp.appComponentFactory;
                if (factory == null || factory.length() == 0) {
                    return;
                }
                AppEnv.appClassLoader.loadClass(factory);
            }
        });
        runStep("preloadWrapperDexes", failed, new Step() {
            @Override
            public void run() throws Throwable {
                ModuleLoader.preloadWrapperDexes();
            }
        });
        runStep("initModules", failed, new Step() {
            @Override
            public void run() throws Throwable {
                initModules();
            }
        });
        if (failed.isEmpty()) {
            succeeded = true;
            XpHook.log0(TAG, "初始化完成：全部步骤成功（进程=" + AppEnv.processName
                    + "，签名模式=" + AppEnv.sig_mode + "）");
            return;
        }
        XpHook.log0(TAG, "init failed steps=" + failed);
    }

    public static boolean initSucceeded() {
        return succeeded;
    }

    private interface Step {
        void run() throws Throwable;
    }

    private static void runStep(String name, List<String> failed, Step step) {
        try {
            step.run();
        } catch (Throwable th) {
            failed.add(name + "(" + th.getClass().getSimpleName() + ": " + th.getMessage() + ")");
            XpHook.log0(TAG, "步骤 " + name + " 失败：" + Log.getStackTraceString(th));
        }
    }

    private static void installSignBypass() {
        String mode = AppEnv.sig_mode;
        if (AppEnv.SIG_MODE_OFF.equals(mode)) {
            XpHook.log0(TAG, "签名伪装已关闭（sig_mode=off）：跳过 native / 签名 / 文件层安装");
            return;
        }
        if (!AppEnv.sig_mode_explicit && AppEnv.sign_bypass_type <= 0) {
            XpHook.log0(TAG, "签名伪装已禁用（配置 bypass_sign_type=" + AppEnv.sign_bypass_type
                    + "，且没有 sig_mode 键）");
            return;
        }
        if (signBypassInstalled) {
            return;
        }

        boolean nativeLoaded = false;
        try {
            nativeLoaded = NativeLoader.load(obtainBootstrapContext());
        } catch (Throwable th) {
            XpHook.log0(TAG, "hook_core 加载异常：" + Log.getStackTraceString(th));
        }
        if (nativeLoaded) {
            String loadedPath = NativeLoader.loadedPath();
            XpHook.log0(TAG, "hook_core 已加载：" + (loadedPath == null ? "未知路径" : loadedPath));
        } else {
            XpHook.log0(TAG, "hook_core 未加载：native 层（seccomp / 文件层重定向）将降级");
        }

        boolean fileLayerWanted = wantsFileLayer(mode);
        String sourcePath = AppEnv.source_apk_path;
        boolean hasSourceApk = sourcePath != null && sourcePath.length() > 0;

        Signature[] signs = SignatureFacade.originals();
        boolean installed = false;
        try {
            boolean primitive = SignatureFacade.install();
            boolean strict = AppEnv.sign_bypass_type == 2
                    || AppEnv.SIG_MODE_DEEP.equals(mode);
            PMSignBypass.setHasSigningCertificateEnabled(strict);
            boolean early = false;
            try {
                early = PMSignBypass.installEarly();
            } catch (Throwable th) {
                XpHook.log0(TAG, "hasSigningCertificate 早期安装失败：" + Log.getStackTraceString(th));
            }
            installProviderStageHooks();
            try {
                if (!AppInfoHooks.install()) {
                    XpHook.log0(TAG, "工厂还原未启用：" + AppInfoHooks.lastError()
                            + "（宿主 pm.getApplicationInfo 会看到补丁工厂名）");
                }
            } catch (Throwable th) {
                XpHook.log0(TAG, "工厂还原安装异常：" + Log.getStackTraceString(th));
            }
            XpHook.log0(TAG, "签名严格校验开关=" + strict + "，bypass=" + AppEnv.sign_bypass_type
                    + "，hasSigningCertificate=" + (early ? "已安装" : "未安装"));
            try {
                PMSignBypass.enableSignBypassByHook();
            } catch (Throwable th) {
                XpHook.log0(TAG, "PackageInfo 层签名伪装安装失败：" + Log.getStackTraceString(th));
            }
            boolean wantsNativeRedirect = fileLayerWanted || AppEnv.sign_bypass_type == 2;
            if (wantsNativeRedirect) {
                boolean redirectEnabled = false;
                try {
                    redirectEnabled = SignBypass.enable();
                } catch (Throwable th) {
                    XpHook.log0(TAG, "native io 重定向不可用：" + Log.getStackTraceString(th));
                }
                if (redirectEnabled) {
                    XpHook.log0(TAG, "native io 重定向已启用：bypass=" + AppEnv.sign_bypass_type
                            + "，目标=" + (hasSourceApk ? sourcePath : "无原包"));
                } else {
                    XpHook.log0(TAG, "native io 重定向未启用（降级）：bypass=" + AppEnv.sign_bypass_type
                            + "，库=" + (nativeLoaded ? "已加载" : "未加载")
                            + "，原包=" + (hasSourceApk ? "有" : "无"));
                }
            }
            if (fileLayerWanted) {
                try {
                    boolean fileLayer = FileLayerHooks.install();
                    XpHook.log0(TAG, fileLayer
                            ? "文件层路径重定向已启用：" + FileLayerHooks.installedTarget()
                                    + "（hook 数=" + FileLayerHooks.hookedCount() + "）"
                            : "文件层未启用：" + FileLayerHooks.lastError());
                } catch (Throwable th) {
                    XpHook.log0(TAG, "文件层路径重定向失败：" + Log.getStackTraceString(th));
                }
            }
            scheduleDeferredSignHooks();
            probeSignatureBypass();
            installed = true;
            XpHook.log0(TAG, "签名伪装已安装：模式=" + mode
                    + "，原语层=" + (primitive ? "开启" : "关闭")
                    + "，native=" + (nativeLoaded ? "已加载" : "未加载")
                    + "，证书数=" + (signs == null ? 0 : signs.length)
                    + "，原始证书=" + sha256Prefix(signs)
                    + "，详情[" + describeSigningDetails() + "]");
        } catch (Throwable th) {
            XpHook.log0(TAG, "签名伪装失败：" + Log.getStackTraceString(th)
                    + "\n  => 应用将看到重签证书，可能触发自校验闪退");
        } finally {
            signBypassInstalled = installed;
        }
    }

    private static void probeSignatureBypass() {
        if (!Log.isLoggable("XpSigProbe", Log.DEBUG)) {
            return;
        }
        try {
            PackageInfo info = AppEnv.appContext.getPackageManager()
                    .getPackageInfo(AppEnv.packageName, PackageManager.GET_SIGNATURES);
            Signature[] seen = info == null ? null : info.signatures;
            Signature[] original = SignatureFacade.originals();
            String seenHash = sha256Prefix(seen);
            String originalHash = sha256Prefix(original);
            Log.d("XpSigProbe", "package=" + AppEnv.packageName
                    + " seen=" + seenHash + " original=" + originalHash
                    + " match=" + (seenHash != null && seenHash.equals(originalHash)));
        } catch (Throwable th) {
            Log.d("XpSigProbe", "probe failed", th);
        }
    }

    private static boolean wantsFileLayer(String mode) {
        return AppEnv.SIG_MODE_JAVA_FILE.equals(mode)
                || AppEnv.SIG_MODE_DEEP.equals(mode)
                || AppEnv.sign_bypass_type == 1
                || AppEnv.sign_bypass_type == 4;
    }

    private static void installProviderStageHooks() {
        try {
            PMSignBypass.installDeferredHooks();
        } catch (Throwable th) {
            XpHook.log0(TAG, "getPackageArchiveInfo 重定向安装异常：" + Log.getStackTraceString(th));
        }
        try {
            if (!CheckSignaturesBypass.installNow()) {
                XpHook.log0(TAG, "checkSignatures 伪装未就绪：provider 阶段的自校验可能仍看到真实现");
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "checkSignatures 伪装安装异常：" + Log.getStackTraceString(th));
        }
    }

    private static void scheduleDeferredSignHooks() {
        boolean scheduled = false;
        try {
            scheduled = DeferredSignHooks.schedule();
        } catch (Throwable th) {
            XpHook.log0(TAG, "延后签名 hook 排队失败：" + Log.getStackTraceString(th));
        }
        deferredSignHooksScheduled = scheduled;
        if (!scheduled) {
            XpHook.log0(TAG, "延后签名 hook 未排队：启动完成回调改由兜底钩子执行（Application.onCreate 返回之后）");
        }
    }

    private static Context obtainBootstrapContext() {
        Context ctx = AppEnv.appContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            createAppContext();
        } catch (Throwable th) {
            XpHook.log0(TAG, "提前创建 app context 失败：" + Log.getStackTraceString(th));
        }
        ctx = AppEnv.appContext;
        if (ctx != null) {
            return ctx;
        }
        try {
            Object thread = HiddenApiBypass.getDeclaredMethod(
                    Class.forName("android.app.ActivityThread"), "currentActivityThread",
                    new Class[0]).invoke(null, new Object[0]);
            if (thread != null) {
                Field field = findObjectField(thread.getClass(), "mInitialApplication");
                if (field != null) {
                    field.setAccessible(true);
                    Object app = field.get(thread);
                    if (app instanceof Context) {
                        return (Context) app;
                    }
                }
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "反射取 Application 失败：" + Log.getStackTraceString(th));
        }
        return null;
    }

    private static String describeSigningDetails() {
        SigningDetailsData data = SigningDetailsData.get();
        return data == null ? "无（未解析到 signing_details / sign）" : data.describe();
    }

    private static String sha256Prefix(Signature[] signs) {
        if (signs == null || signs.length == 0) {
            return "none";
        }
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256")
                    .digest(signs[0].toByteArray());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (int i = 0; i < 4; i++) {
                int value = digest[i] & 0xFF;
                if (value < 0x10) {
                    sb.append('0');
                }
                sb.append(Integer.toHexString(value));
            }
            return sb.toString();
        } catch (Throwable th) {
            return "unknown";
        }
    }

    private static void preloadWrapperAssets() {
        try {
            io.xpatch.core.module.ModuleLoader.preloadWrapperDexes();
        } catch (Throwable th) {
            XpHook.log0(TAG, "预载 wrapper dex 失败（提前）：" + Log.getStackTraceString(th));
        }
        if (!AppEnv.fileProvider) {
            return;
        }
        try {
            String fileProviderEntry = XpLayout.get("file_provider");
            ZipFile zipFile = new ZipFile(AppEnv.selfApk);
            ZipEntry entry = zipFile.getEntry(fileProviderEntry);
            if (entry == null) {
                java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry candidate = entries.nextElement();
                    if (fileProviderEntry.equals(candidate.getName())) {
                        entry = candidate;
                        break;
                    }
                }
            }
            if (entry != null && entry.getSize() > 0) {
                AppEnv.fileProviderDex = DataUtils.readAllBytes(zipFile.getInputStream(entry));
                XpHook.log0(TAG, "已预读 file provider dex：" + AppEnv.fileProviderDex.length + " B");
            }
            zipFile.close();
        } catch (Throwable th) {
            XpHook.log0(TAG, "预读 file provider dex 失败：" + Log.getStackTraceString(th));
        }
    }

    private static void injectFileProviderDexFile() throws Exception {
        Constructor<?> constructor;
        if (AppEnv.fileProvider) {
            byte[] allBytes = AppEnv.fileProviderDex;
            String fileProviderEntry = XpLayout.get("file_provider");
            if (allBytes == null) {
                ZipFile zipFile = new ZipFile(AppEnv.selfApk);
                ZipEntry entry = zipFile.getEntry(fileProviderEntry);
                if (entry == null) {
                    java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry candidate = entries.nextElement();
                        if (fileProviderEntry.equals(candidate.getName())) {
                            entry = candidate;
                            break;
                        }
                    }
                }
                if (entry != null && entry.getSize() > 0) {
                    allBytes = DataUtils.readAllBytes(zipFile.getInputStream(entry));
                }
                zipFile.close();
            }
            if (allBytes == null || allBytes.length == 0) {
                XpHook.log0(TAG, fileProviderEntry + " 缺失或为空，跳过 file provider dex 注入");
                return;
            }
            ByteBuffer[] byteBufferArr = {ByteBuffer.wrap(allBytes)};
            Constructor<?>[] declaredConstructors = DexFile.class.getDeclaredConstructors();
            int length = declaredConstructors.length;
            int i = 0;
            while (true) {
                if (i >= length) {
                    constructor = null;
                    break;
                }
                constructor = declaredConstructors[i];
                if (constructor.getParameterCount() == 3 && constructor.getParameterTypes()[0] == ByteBuffer[].class) {
                    break;
                } else {
                    i++;
                }
            }
            constructor.setAccessible(true);
            DexFile dexFile = (DexFile) constructor.newInstance(byteBufferArr, AppEnv.appClassLoader, null);
            Constructor<?> constructor2 = Class.forName("dalvik.system.DexPathList$Element").getConstructor(DexFile.class);
            constructor2.setAccessible(true);
            Object objNewInstance = constructor2.newInstance(dexFile);
            Field fieldFindObjectField = findObjectField(BaseDexClassLoader.class, "pathList");
            fieldFindObjectField.setAccessible(true);
            Object obj = fieldFindObjectField.get(AppEnv.appClassLoader);
            Field fieldFindObjectField2 = findObjectField(Class.forName("dalvik.system.DexPathList"), "dexElements");
            fieldFindObjectField2.setAccessible(true);
            Object obj2 = fieldFindObjectField2.get(obj);
            Object objNewInstance2 = Array.newInstance(Class.forName("dalvik.system.DexPathList$Element"), Array.getLength(obj2) + 1);
            for (int i2 = 0; i2 < Array.getLength(obj2); i2++) {
                Array.set(objNewInstance2, i2, Array.get(obj2, i2));
            }
            Array.set(objNewInstance2, Array.getLength(obj2), objNewInstance);
            fieldFindObjectField2.set(obj, objNewInstance2);
        }
    }

    private static void initClassLoaderInject() throws Exception {
        if (selfApplicationInfo == null) {
            getApplicationInfo();
        }
        String redirect = AppEnv.source_apk_path;
        if (redirect == null || redirect.length() == 0) {
            XpHook.log0(TAG, "动态模式跳过 classloader 切换：没有原包路径");
            return;
        }
        Field boundApplicationField = findObjectField(
                Class.forName("android.app.ActivityThread"), "mBoundApplication");
        boundApplicationField.setAccessible(true);
        Object boundApplication = boundApplicationField.get(activityThread);
        Field packageInfoField = findObjectField(
                Class.forName("android.app.ActivityThread$ActivityClientRecord"), "packageInfo");
        packageInfoField.setAccessible(true);
        ArrayMap activities = (ArrayMap) getObjectField(activityThread, "mActivities");
        Map packages = (Map) getObjectField(activityThread, "mPackages");
        final String previousSourceDir = selfApplicationInfo.sourceDir;
        final String previousPublicSourceDir = selfApplicationInfo.publicSourceDir;
        final Object previousPackage = packages.get(selfApplicationInfo.packageName);
        final Context previousAppContext = AppEnv.appContext;
        final ClassLoader previousAppClassLoader = AppEnv.appClassLoader;
        final ClassLoader previousDynamicLoader = AppEnv.dynamicLoader;
        final LoadedApk previousLoadedApk = loadedApk;
        LoadedApk loadedApk2;
        Context newAppContext;
        ClassLoader newAppClassLoader;
        try {
            selfApplicationInfo.sourceDir = redirect;
            selfApplicationInfo.publicSourceDir = redirect;
            packages.remove(selfApplicationInfo.packageName);
            loadedApk2 = (LoadedApk) HiddenApiBypass.invoke(Class.forName("android.app.ActivityThread"),
                    activityThread, "getPackageInfoNoCheck", selfApplicationInfo,
                    getObjectField(boundApplication, "compatInfo"));
            if (loadedApk2 == null) {
                throw new IllegalStateException("getPackageInfoNoCheck 返回 null（原包=" + redirect + "）");
            }
            newAppClassLoader = (ClassLoader) getObjectField(loadedApk2, "mClassLoader");
            if (newAppClassLoader == null) {
                newAppClassLoader = forceClassLoader(loadedApk2);
            }
            if (newAppClassLoader == null) {
                throw new IllegalStateException("新 LoadedApk.mClassLoader 为 null（原包=" + redirect + "）");
            }
            verifyLoaderCanLoadHost(newAppClassLoader, redirect);
            newAppContext = (Context) HiddenApiBypass.invoke(Class.forName("android.app.ContextImpl"),
                    null, "createAppContext", activityThread, loadedApk2);
            if (newAppContext == null) {
                throw new IllegalStateException("ContextImpl.createAppContext 返回 null（原包=" + redirect + "）");
            }
            XpHook.log0(TAG, "从以下路径加载：" + selfApplicationInfo.sourceDir);
        } catch (Throwable th) {
            selfApplicationInfo.sourceDir = previousSourceDir;
            selfApplicationInfo.publicSourceDir = previousPublicSourceDir;
            if (previousPackage != null) {
                packages.put(selfApplicationInfo.packageName, previousPackage);
            } else {
                packages.remove(selfApplicationInfo.packageName);
            }
            throw th instanceof Exception ? (Exception) th : new RuntimeException(th);
        }
        final LoadedApk targetLoadedApk = loadedApk2;
        boolean switched = false;
        try {
            activities.forEach(new BiConsumer() {
                @Override
                public final void accept(Object obj2, Object obj3) {
                    XpInit.lambda$initClassLoaderInject$0(packageInfoField, targetLoadedApk, obj2, obj3);
                }
            });
            if (loadedApk != null) {
                switchField(loadedApk2, loadedApk, "mBaseClassLoader");
                switchField(loadedApk2, loadedApk, "mClassLoader");
                switchField(loadedApk2, loadedApk, "mDefaultClassLoader");
            }
            setFieldValue(boundApplication, "info", loadedApk2);
            AppEnv.appContext = newAppContext;
            AppEnv.appClassLoader = newAppClassLoader;
            AppEnv.dynamicLoader = newAppClassLoader;
            loadedApk = loadedApk2;
            switched = true;
        } finally {
            selfApplicationInfo.sourceDir = previousSourceDir;
            selfApplicationInfo.publicSourceDir = previousPublicSourceDir;
            if (!switched) {
                AppEnv.appContext = previousAppContext;
                AppEnv.appClassLoader = previousAppClassLoader;
                AppEnv.dynamicLoader = previousDynamicLoader;
                loadedApk = previousLoadedApk;
                if (previousPackage != null) {
                    packages.put(selfApplicationInfo.packageName, previousPackage);
                } else {
                    packages.remove(selfApplicationInfo.packageName);
                }
                try {
                    setFieldValue(boundApplication, "info", previousLoadedApk);
                } catch (Throwable th) {
                    XpHook.log0(TAG, "回滚 mBoundApplication.info 失败：" + Log.getStackTraceString(th));
                }
            }
        }
    }

    static void lambda$initClassLoaderInject$0(Field field, LoadedApk loadedApk2, Object obj, Object obj2) {
        if (obj2 != null) {
            try {
                field.set(obj2, loadedApk2);
            } catch (IllegalAccessException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void createAppContext() {
        if (AppEnv.appContext != null) {
            return;
        }
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object objInvoke = HiddenApiBypass.getDeclaredMethod(cls, "currentActivityThread", new Class[0]).invoke(null, new Object[0]);
            Field fieldFindObjectField = findObjectField(cls, "mBoundApplication");
            fieldFindObjectField.setAccessible(true);
            Object obj = fieldFindObjectField.get(objInvoke);
            Field fieldFindObjectField2 = findObjectField(obj.getClass(), "info");
            fieldFindObjectField2.setAccessible(true);
            Object obj2 = fieldFindObjectField2.get(obj);
            Method declaredMethod = HiddenApiBypass.getDeclaredMethod(Class.forName("android.app.ContextImpl"), "createAppContext", cls, LoadedApk.class);
            declaredMethod.setAccessible(true);
            AppEnv.appContext = (Context) declaredMethod.invoke(null, objInvoke, obj2);
        } catch (Exception e) {
            throw new ExceptionInInitializerError("XPatch 初始化失败!!!\n" + e);
        }
    }

    private static void setFieldValue(Object obj, String str, Object obj2) {
        try {
            Field fieldFindObjectField = findObjectField(obj.getClass(), str);
            fieldFindObjectField.setAccessible(true);
            fieldFindObjectField.set(obj, obj2);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void verifyLoaderCanLoadHost(ClassLoader loader, String redirect) {
        String className = null;
        try {
            className = AppEnv.baseApp == null ? null : AppEnv.baseApp.className;
        } catch (Throwable ignored) {
        }
        if (className == null || className.length() == 0) {
            XpHook.log0(TAG, "宿主未声明 Application 类，跳过切换前的加载校验");
            return;
        }
        try {
            Class.forName(className, false, loader);
            XpHook.log0(TAG, "切换前校验通过：新 classloader 能加载宿主 Application " + className);
        } catch (Throwable th) {
            throw new IllegalStateException("新 classloader 加载不了宿主 Application " + className
                    + "（原包=" + redirect + "，可能不是完整原始包）", th);
        }
    }

    private static ClassLoader forceClassLoader(LoadedApk loadedApk) {
        try {
            Object loader = HiddenApiBypass.invoke(LoadedApk.class, loadedApk, "getClassLoader", new Class[0]);
            if (loader instanceof ClassLoader) {
                return (ClassLoader) loader;
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "LoadedApk.getClassLoader 调用失败：" + Log.getStackTraceString(th));
        }
        try {
            java.lang.reflect.Method method = LoadedApk.class.getMethod("getClassLoader", new Class[0]);
            Object loader = method.invoke(loadedApk, new Object[0]);
            if (loader instanceof ClassLoader) {
                return (ClassLoader) loader;
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "反射 getClassLoader 失败：" + Log.getStackTraceString(th));
        }
        return null;
    }

    private static void switchField(Object obj, Object obj2, String str) {
        try {
            Field fieldFindObjectField = findObjectField(obj.getClass(), str);
            fieldFindObjectField.setAccessible(true);
            fieldFindObjectField.set(obj2, fieldFindObjectField.get(obj));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static void getApplicationInfo() {
        try {
            activityThread = HiddenApiBypass.getDeclaredMethod(Class.forName("android.app.ActivityThread"), "currentActivityThread", new Class[0]).invoke(null, new Object[0]);
            Field fieldFindObjectField = findObjectField(Class.forName("android.app.ActivityThread"), "mBoundApplication");
            fieldFindObjectField.setAccessible(true);
            Object obj = fieldFindObjectField.get(activityThread);
            Field fieldFindObjectField2 = findObjectField(obj.getClass(), "info");
            fieldFindObjectField2.setAccessible(true);
            loadedApk = (LoadedApk) fieldFindObjectField2.get(obj);
            Field fieldFindObjectField3 = findObjectField(obj.getClass(), "appInfo");
            fieldFindObjectField3.setAccessible(true);
            selfApplicationInfo = (ApplicationInfo) fieldFindObjectField3.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Object getObjectField(Object obj, String str) {
        try {
            Field fieldFindObjectField = findObjectField(obj.getClass(), str);
            fieldFindObjectField.setAccessible(true);
            return fieldFindObjectField.get(obj);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static Field findObjectField(Class<?> cls, String str) {
        List<String> tried = new ArrayList<String>();
        Class<?> current = cls;
        while (current != null) {
            tried.add(current.getName() + "#" + str);
            try {
                for (Field field : HiddenApiBypass.getInstanceFields(current)) {
                    if (field.getName().equals(str)) {
                        return field;
                    }
                }
            } catch (Throwable th) {
                tried.add(current.getName() + " 枚举失败：" + th);
            }
            current = current.getSuperclass();
        }
        throw new IllegalStateException("找不到字段 " + str + "（尝试过：" + tried + "）");
    }

    private static void performXpConfig() {
        XpLayout.ensureInstalled();
        String configEntry = configPath();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(AppEnv.selfApk);
        } catch (Throwable th) {
            XpHook.log0(TAG, "无法打开安装包读取配置：" + AppEnv.selfApk + "（" + configEntry + "）\n"
                    + Log.getStackTraceString(th));
            return;
        }
        try {
            ZipEntry configZipEntry = zipFile.getEntry(configEntry);
            if (configZipEntry == null) {
                XpHook.log0(TAG, "产物缺配置条目 " + configEntry
                        + "：签名伪装与模块加载都会降级");
            } else {
                try {
                    applyConfig(configEntry, readEntryBytes(zipFile, configZipEntry));
                } catch (Throwable th) {
                    XpHook.log0(TAG, "无法读取配置：" + configEntry + "\n" + Log.getStackTraceString(th));
                }
            }
            releaseEmbeddedApk(zipFile, originPath(), true);
            releaseEmbeddedApk(zipFile, oOriginPath(), false);
        } finally {
            try {
                zipFile.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static byte[] readEntryBytes(ZipFile zipFile, ZipEntry entry) throws IOException {
        InputStream inputStream = zipFile.getInputStream(entry);
        try {
            return DataUtils.readAllBytes(inputStream);
        } finally {
            try {
                inputStream.close();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void applyConfig(String configEntry, byte[] raw) throws Exception {
        JSONObject jSONObject = new JSONObject(new String(raw));
        String appComponentName = jSONObject.has("app_component_name")
                ? jSONObject.optString("app_component_name", "") : "";
        int bypassType = SignBypass.normalizeType(jSONObject.optInt("bypass_sign_type"));
        int coreVer = jSONObject.optInt("ver");
        boolean dynamicMode = jSONObject.optBoolean("dynamic_mode");
        long realVersionCode = jSONObject.optLong("real_version_code", -1L);
        boolean fileProvider = jSONObject.optBoolean("file_provider");
        String buildNonce = jSONObject.optString("build_nonce", "");
        String managerPackage = normalizeManagerPackage(
                jSONObject.optString("manager_package", ""), AppEnv.managerPackage);
        String rawMode = jSONObject.optString("sig_mode", "");
        boolean sigModeExplicit = rawMode != null && rawMode.trim().length() > 0;
        String sigMode = sigModeExplicit
                ? normalizeSigMode(rawMode)
                : (bypassType > 0 ? AppEnv.SIG_MODE_JAVA : AppEnv.SIG_MODE_JAVA);
        String signingDetails = jSONObject.optString("signing_details", null);
        Signature[] legacySignatures = null;
        if (!AppEnv.SIG_MODE_OFF.equals(sigMode)) {
            legacySignatures = parseLegacySignatures(jSONObject);
        }

        AppEnv.baseApp.appComponentFactory = appComponentName;
        AppEnv.sign_bypass_type = bypassType;
        AppEnv.core_ver = coreVer;
        AppEnv.dynamicMode = dynamicMode;
        AppEnv.read_version_code = realVersionCode;
        AppEnv.fileProvider = fileProvider;
        AppEnv.buildNonce = buildNonce;
        AppEnv.managerPackage = managerPackage;
        AppEnv.sig_mode_explicit = sigModeExplicit;
        AppEnv.sig_mode = sigMode;
        AppEnv.signing_details = signingDetails;
        AppEnv.signatures = legacySignatures;
        if (jSONObject.has("ver") && AppEnv.core_ver != CoreEntry.RUNTIME_VERSION) {
            XpHook.log0(TAG, "运行时版本不一致：" + configEntry + " ver=" + AppEnv.core_ver
                    + "，内核 RUNTIME_VERSION=" + CoreEntry.RUNTIME_VERSION
                    + "（建议用当前版本管理器重新打包）");
        }
        SigningDetailsData.init(AppEnv.signing_details, AppEnv.signatures);
    }

    private static void releaseEmbeddedApk(ZipFile zipFile, String entryName, boolean isSource) {
        if (entryName == null || entryName.length() == 0) {
            return;
        }
        ZipEntry entry = zipFile.getEntry(entryName);
        if (entry == null) {
            XpHook.log0(TAG, entryName + " 不存在，跳过原包释放");
            return;
        }
        File cacheDir = new File(AppEnv.baseApp.dataDir + "/cache/android_id/");
        String finalName = entry.getCrc() + ".apk";
        File target = new File(cacheDir, finalName);
        if (target.isFile() && target.length() == entry.getSize()) {
            if (isUsableApk(target)) {
                markEmbeddedApk(entryName, target, isSource);
                return;
            }
            XpHook.log0(TAG, "缓存原包不可用（缺清单或 classes.dex），重新释放：" + target);
            deleteQuietly(target);
        }
        File unique = new File(cacheDir, entry.getCrc() + "-" + Process.myPid()
                + "-" + System.currentTimeMillis() + ".apk");
        boolean written = false;
        try {
            cacheDir.mkdirs();
            InputStream inputStream = null;
            FileOutputStream outputStream = null;
            try {
                inputStream = zipFile.getInputStream(entry);
                outputStream = new FileOutputStream(unique);
                DataUtils.copyStream(inputStream, outputStream);
                outputStream.flush();
            } finally {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Throwable ignored) {
                    }
                }
                if (outputStream != null) {
                    try {
                        outputStream.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
            if (unique.length() != entry.getSize()) {
                XpHook.log0(TAG, "原包释放不完整（" + unique.length() + " / " + entry.getSize()
                        + "）：放弃该原包，过签改用纯 Java 路径");
                return;
            }
            if (!isUsableApk(unique)) {
                XpHook.log0(TAG, "原包释放后校验失败（缺清单或 classes.dex）：放弃该原包，过签改用纯 Java 路径");
                return;
            }
            if (!unique.renameTo(target)) {
                deleteQuietly(target);
                if (!unique.renameTo(target)) {
                    XpHook.log0(TAG, "原包释放落位失败：放弃该原包，过签改用纯 Java 路径（" + target + "）");
                    return;
                }
            }
            written = true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "原包释放失败：" + entryName + "\n" + Log.getStackTraceString(th));
            return;
        } finally {
            if (!written) {
                deleteQuietly(unique);
            }
        }
        if (!isUsableApk(target)) {
            XpHook.log0(TAG, "落位后的原包校验失败，已删除（" + target + "）：过签改用纯 Java 路径");
            deleteQuietly(target);
            return;
        }
        markEmbeddedApk(entryName, target, isSource);
    }

    private static boolean isUsableApk(File file) {
        if (file == null || !file.isFile() || file.length() <= 0L) {
            return false;
        }
        ZipFile zip = null;
        try {
            zip = new ZipFile(file);
            if (zip.getEntry("AndroidManifest.xml") == null) {
                return false;
            }
            return zip.getEntry("classes.dex") != null;
        } catch (Throwable th) {
            return false;
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void markEmbeddedApk(String entryName, File target, boolean isSource) {
        if (isSource) {
            AppEnv.source_apk_path = target.getAbsolutePath();
        } else {
            AppEnv.o_apk_path = target.getAbsolutePath();
        }
        XpHook.log0(TAG, (isSource ? "原包" : "o_app") + "已就绪：" + target.getAbsolutePath()
                + "（条目 " + entryName + "）");
        if (target.setWritable(false)) {
            return;
        }
        XpHook.log0(TAG, "原包只读化失败（后续仍可读）：" + target);
    }

    private static String normalizeManagerPackage(String raw, String fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if (value.length() == 0 || value.indexOf(' ') >= 0 || value.indexOf('/') >= 0) {
            return fallback;
        }
        return value;
    }

    private static String normalizeSigMode(String raw) {
        String mode = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.US);
        if (AppEnv.SIG_MODE_OFF.equals(mode) || AppEnv.SIG_MODE_JAVA.equals(mode)
                || AppEnv.SIG_MODE_JAVA_FILE.equals(mode) || AppEnv.SIG_MODE_DEEP.equals(mode)) {
            return mode;
        }
        XpHook.log0(TAG, "未知的签名模式 sig_mode=" + raw + "，按 java 处理");
        return AppEnv.SIG_MODE_JAVA;
    }

    private static Signature[] parseLegacySignatures(JSONObject config) {
        try {
            JSONArray array = new JSONArray(config.optString("sign", "[]"));
            Signature[] signatures = new Signature[array.length()];
            for (int i = 0; i < array.length(); i++) {
                signatures[i] = new Signature(DataUtils.hexToByteArray(array.getString(i)));
            }
            return signatures;
        } catch (Throwable th) {
            XpHook.log0(TAG, "解析 legacy sign（原证书）失败：" + Log.getStackTraceString(th));
            return null;
        }
    }

    private static boolean initHookCore() {
        String hookCoreEntry = hookCorePath();
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(AppEnv.selfApk);
            ZipEntry entry = zipFile.getEntry(hookCoreEntry);
            if (entry == null) {
                XpHook.log0(TAG, hookCoreEntry + " 不存在：产物缺 hook_core.dex，native 特性全部降级");
                return false;
            }
            byte[] allBytes = DataUtils.readAllBytes(zipFile.getInputStream(entry));
            if (allBytes == null || allBytes.length == 0) {
                XpHook.log0(TAG, hookCoreEntry + " 为空：native 特性全部降级");
                return false;
            }
            AppEnv.hook_core_cl = SimpleClassLoader.loadDex(
                    new ByteBuffer[]{ByteBuffer.wrap(allBytes)}, XpInit.class.getClassLoader());
            if (AppEnv.hook_core_cl == null) {
                XpHook.log0(TAG, hookCoreEntry + " 载入 ClassLoader 失败：native 特性全部降级");
                return false;
            }
            AppEnv.hook_core_cl.loadClass("io.xpatch.core.entry.CoreEntry")
                    .getMethod("InitHookCore", new Class[0]).invoke(null, new Object[0]);
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "hook_core 初始化失败：" + hookCoreEntry + "\n" + Log.getStackTraceString(th));
            AppEnv.hook_core_cl = null;
            return false;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void refreshScopeModulePaths() {
        if (AppEnv.appContext == null) {
            return;
        }
        try {
            new RemoteModuleProvider(AppEnv.appContext).refreshScopeModulePaths();
        } catch (Throwable th) {
            XpHook.log0(TAG, "刷新模块作用域失败：" + Log.getStackTraceString(th));
        }
    }

    private static void initModules() throws Exception {
        AppEnv.modules = new RemoteModuleProvider(AppEnv.appContext).getModules();
        handleModuleStartupCrashes(AppEnv.modules);
        boolean changedPath = false;
        int loadedCount = 0;
        for (RemoteModule remoteModule : AppEnv.modules) {
            if (remoteModule == null || remoteModule.packageName == null) {
                continue;
            }
            if (remoteModule.apkPath == null || remoteModule.apkPath.length() == 0
                    || !new File(remoteModule.apkPath).exists()) {
                changedPath = true;
                continue;
            }
            File pending = modulePendingFile(remoteModule.packageName);
            writePendingModule(pending, remoteModule.packageName,
                    moduleSignature(remoteModule.apkPath));
            boolean loaded;
            try {
                loaded = ModuleLoader.loadModule(remoteModule);
            } catch (Throwable th) {
                loaded = false;
                XpHook.log0(TAG, "加载模块异常：" + remoteModule.packageName + "\n" + Log.getStackTraceString(th));
            }
            if (loaded) {
                loadedCount++;
                synchronized (startupPendingFiles) {
                    startupPendingFiles.add(pending);
                }
                synchronized (startupLoadedPackages) {
                    startupLoadedPackages.add(remoteModule.packageName);
                }
                registerStartupCallback(STARTUP_COMMIT);
            } else {
                deleteQuietly(pending);
                XpHook.log0(TAG, "模块 " + remoteModule.packageName + " 加载失败，本次不生效");
            }
        }
        if (changedPath) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public final void run() {
                    Toast.makeText(AppEnv.appContext, "部分模块apk路径已变更,请保持XPatch后台以更新模块apk路径", 1).show();
                }
            });
        }
        XpHook.log0(TAG, "模块初始化完成：生效 " + loadedCount
                + " 个，候选 " + AppEnv.modules.size() + " 个：" + AppEnv.modules);
    }

    private static void commitStartup() {
        List<File> pendings;
        synchronized (startupPendingFiles) {
            pendings = new ArrayList<File>(startupPendingFiles);
            startupPendingFiles.clear();
        }
        Set<String> packages;
        synchronized (startupLoadedPackages) {
            packages = new HashSet<String>(startupLoadedPackages);
            startupLoadedPackages.clear();
        }
        for (int i = 0; i < pendings.size(); i++) {
            deleteQuietly(pendings.get(i));
        }
        resetModuleCrashCounts(packages);
    }

    private static void registerStartupCallback(Runnable callback) {
        if (callback == null) {
            return;
        }
        synchronized (startupCallbacks) {
            if (!startupCallbacks.contains(callback)) {
                startupCallbacks.add(callback);
            }
        }
        try {
            DeferredSignHooks.onStartupComplete(callback);
        } catch (Throwable th) {
            XpHook.log0(TAG, "注册启动完成回调失败：" + Log.getStackTraceString(th));
        }
        if (!deferredSignHooksScheduled) {
            installFallbackStartupHook();
        }
    }

    private static void installFallbackStartupHook() {
        synchronized (startupCallbacks) {
            if (fallbackStartupHookInstalled) {
                return;
            }
        }
        boolean installed = false;
        try {
            Class<?> activityThreadClass = Class.forName("android.app.ActivityThread");
            Class<?> bindDataClass = Class.forName("android.app.ActivityThread$AppBindData");
            Method target = activityThreadClass.getDeclaredMethod("handleBindApplication", bindDataClass);
            installed = XpHook.doHook(target, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    param.proceed();
                    runStartupCallbacks();
                }
            }) != null;
            if (!installed) {
                XpHook.log0(TAG, "启动完成兜底钩子（handleBindApplication 尾部）安装失败，改投主线程 Handler.post");
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "启动完成兜底钩子（handleBindApplication）不可用：" + Log.getStackTraceString(th));
        }
        if (!installed) {
            installed = postStartupCallbacksToMainLooper();
        }
        if (installed) {
            synchronized (startupCallbacks) {
                fallbackStartupHookInstalled = true;
            }
            XpHook.log0(TAG, "启动完成兜底钩子已安装：pending 将在启动完成后删除");
            return;
        }
        XpHook.log0(TAG, "启动完成兜底钩子安装失败：pending 可能被下次启动误判为崩溃");
    }

    private static boolean postStartupCallbacksToMainLooper() {
        try {
            Handler handler = new Handler(Looper.getMainLooper());
            return handler.post(new Runnable() {
                @Override
                public void run() {
                    runStartupCallbacks();
                }
            });
        } catch (Throwable th) {
            XpHook.log0(TAG, "主线程 Handler.post 兜底失败：" + Log.getStackTraceString(th));
            return false;
        }
    }

    private static void runStartupCallbacks() {
        List<Runnable> pending;
        synchronized (startupCallbacks) {
            pending = new ArrayList<Runnable>(startupCallbacks);
            startupCallbacks.clear();
        }
        for (int i = 0; i < pending.size(); i++) {
            try {
                pending.get(i).run();
            } catch (Throwable ignored) {
            }
        }
    }

    private static void notifyModuleCrash(String modulePkg) {
        Context ctx = AppEnv.appContext;
        if (ctx == null) {
            XpHook.log0(TAG, "模块崩溃广播未发送：app context 为空（module=" + modulePkg + "）");
            return;
        }
        String manager = normalizeManagerPackage(AppEnv.managerPackage, DEFAULT_MANAGER_PACKAGE);
        Intent intent = new Intent(ACTION_MODULE_CRASH);
        intent.setPackage(manager);
        intent.putExtra("module", modulePkg);
        intent.putExtra("app", AppEnv.packageName);
        try {
            if (ctx.getPackageManager().queryBroadcastReceivers(intent, 0).isEmpty()) {
                XpHook.log0(TAG, "模块崩溃广播没有可见接收方（管理器 " + manager
                        + " 未安装/已停用，或被包可见性限制）：module=" + modulePkg
                        + "，app=" + AppEnv.packageName);
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "探测模块崩溃广播接收方失败：" + Log.getStackTraceString(th));
        }
        try {
            ctx.sendBroadcast(intent, PERMISSION_MODULE_CRASH);
        } catch (Throwable th) {
            XpHook.log0(TAG, "模块崩溃广播发送失败（" + intent + "）：" + Log.getStackTraceString(th));
        }
    }

    private static File moduleStateDir() {
        try {
            return AppEnv.appContext == null ? null : AppEnv.appContext.getFilesDir();
        } catch (Throwable th) {
            return null;
        }
    }

    private static File modulePendingFile(String pkg) {
        File dir = moduleStateDir();
        if (dir == null) {
            return null;
        }
        return new File(dir, MODULE_PENDING_PREFIX + stableHash(processNameForHash())
                + "-" + Process.myPid() + "-" + stableHash(pkg));
    }

    private static String processNameForHash() {
        if (AppEnv.processName != null) {
            return AppEnv.processName;
        }
        if (AppEnv.packageName != null) {
            return AppEnv.packageName;
        }
        return "unknown";
    }

    private static String stableHash(String str) {
        if (str == null) {
            return "0";
        }
        int hash = 0;
        for (int i = 0; i < str.length(); i++) {
            hash = hash * 31 + str.charAt(i);
        }
        return Integer.toHexString(hash);
    }

    private static void writePendingModule(File file, String pkg, String signature) {
        if (file == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write((pkg + "\n" + (signature == null ? "" : signature) + "\n").getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "写入 pending 失败（本次崩溃将无法归因）：" + file + "\n"
                    + Log.getStackTraceString(th));
        }
    }

    private static String moduleSignature(String apkPath) {
        if (apkPath == null || apkPath.length() == 0) {
            return null;
        }
        try {
            File file = new File(apkPath);
            if (!file.isFile()) {
                return null;
            }
            return file.length() + ":" + file.lastModified();
        } catch (Throwable th) {
            return null;
        }
    }

    private static void deleteQuietly(File file) {
        if (file == null) {
            return;
        }
        try {
            file.delete();
        } catch (Throwable ignored) {
        }
    }

    private static void handleModuleStartupCrashes(List<RemoteModule> modules) {
        File dir = moduleStateDir();
        if (dir == null) {
            return;
        }
        Map<String, CrashCount> counts;
        CountLock lockHandle = acquireCountLock();
        try {
            counts = readModuleCounts();
            Map<String, String> currentSignatures = currentModuleSignatures(modules);
            Set<String> active = new HashSet<String>(currentSignatures.keySet());
            java.util.Iterator<Map.Entry<String, CrashCount>> iterator = counts.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<String, CrashCount> entry = iterator.next();
                if (!active.contains(entry.getKey())) {
                    iterator.remove();
                }
            }
            Set<String> crashed = new HashSet<String>();
            collectStartupCrashes(dir, counts, currentSignatures, crashed);
            writeModuleCounts(counts);
            java.util.Iterator<RemoteModule> moduleIterator = modules.iterator();
            while (moduleIterator.hasNext()) {
                RemoteModule candidate = moduleIterator.next();
                if (candidate != null && crashed.contains(candidate.packageName)) {
                    moduleIterator.remove();
                    XpHook.log0(TAG, "模块 " + candidate.packageName + " 已达崩溃阈值，从作用域移出，本次不加载");
                }
            }
        } finally {
            if (lockHandle != null) {
                lockHandle.close();
            }
        }
    }

    private static void collectStartupCrashes(File dir, Map<String, CrashCount> counts,
                                              Map<String, String> currentSignatures, Set<String> blocked) {
        String processPrefix = MODULE_PENDING_PREFIX + stableHash(processNameForHash()) + "-";
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null) {
                    continue;
                }
                if (!file.getName().startsWith(processPrefix)) {
                    continue;
                }
                long modified = file.lastModified();
                String[] entry = readPendingModule(file);
                deleteQuietly(file);
                if (entry == null || entry[0].length() == 0) {
                    continue;
                }
                long age = System.currentTimeMillis() - modified;
                if (modified > 0 && age > MODULE_PENDING_MAX_AGE_MILLIS) {
                    XpHook.log0(TAG, "pending 已超龄（" + (age / 1000L) + " s），视为非崩溃：" + entry[0]);
                    continue;
                }
                String pkg = entry[0];
                if (!currentSignatures.containsKey(pkg)) {
                    continue;
                }
                String current = currentSignatures.get(pkg);
                if (entry[1].length() > 0 && current != null && !entry[1].equals(current)) {
                    counts.remove(pkg);
                    XpHook.log0(TAG, "模块 " + pkg + " 上次启动崩溃后 apk 已变化，计数清零");
                    continue;
                }
                CrashCount count = counts.get(pkg);
                if (count == null) {
                    count = new CrashCount();
                    counts.put(pkg, count);
                }
                count.count = count.count + 1;
                count.timestamp = System.currentTimeMillis();
                if (count.count >= MODULE_CRASH_THRESHOLD) {
                    count.count = MODULE_CRASH_THRESHOLD;
                    XpHook.log0(TAG, "模块 " + pkg + " 已连续 " + MODULE_CRASH_THRESHOLD
                            + " 次导致启动崩溃：移除作用域并通知用户");
                    blocked.add(pkg);
                    notifyModuleCrash(pkg);
                } else {
                    XpHook.log0(TAG, "模块 " + pkg + " 上次启动崩溃（" + count.count + "/"
                            + MODULE_CRASH_THRESHOLD + "），本次继续重试");
                }
            }
        }
    }

    private static Map<String, String> currentModuleSignatures(List<RemoteModule> modules) {
        Map<String, String> signatures = new HashMap<String, String>();
        if (modules == null) {
            return signatures;
        }
        for (int i = 0; i < modules.size(); i++) {
            RemoteModule module = modules.get(i);
            if (module == null || module.packageName == null) {
                continue;
            }
            String signature = moduleSignature(module.apkPath);
            if (signature != null) {
                signatures.put(module.packageName, signature);
            }
        }
        return signatures;
    }

    private static String[] readPendingModule(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String pkg = reader.readLine();
            String signature = reader.readLine();
            if (pkg == null) {
                return null;
            }
            return new String[]{pkg.trim(), signature == null ? "" : signature.trim()};
        } catch (Throwable th) {
            return null;
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }


    private static final class CrashCount {
        int count;
        long timestamp;
    }

    private static final class CountLock {
        java.io.RandomAccessFile file;
        java.nio.channels.FileLock lock;

        void close() {
            if (lock != null) {
                try {
                    lock.release();
                } catch (Throwable ignored) {
                }
                lock = null;
            }
            if (file != null) {
                try {
                    file.close();
                } catch (Throwable ignored) {
                }
                file = null;
            }
        }
    }

    private static CountLock acquireCountLock() {
        File dir = moduleStateDir();
        if (dir == null) {
            return null;
        }
        CountLock handle = new CountLock();
        try {
            handle.file = new java.io.RandomAccessFile(new File(dir, MODULE_COUNTS_FILE + ".lock"), "rw");
            handle.lock = handle.file.getChannel().lock();
            return handle;
        } catch (Throwable th) {
            XpHook.log0(TAG, "模块崩溃计数跨进程加锁失败（降级为无锁读写）：" + Log.getStackTraceString(th));
            handle.close();
            return null;
        }
    }

    private static Map<String, CrashCount> readModuleCounts() {
        Map<String, CrashCount> counts = new HashMap<String, CrashCount>();
        File dir = moduleStateDir();
        if (dir == null) {
            return counts;
        }
        File file = new File(dir, MODULE_COUNTS_FILE);
        if (!file.isFile()) {
            return counts;
        }
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line;
            while ((line = reader.readLine()) != null) {
                int eq = line.indexOf('=');
                if (eq <= 0) {
                    continue;
                }
                String pkg = line.substring(0, eq).trim();
                if (pkg.length() == 0) {
                    continue;
                }
                CrashCount entry = parseCrashCount(line.substring(eq + 1));
                if (entry != null) {
                    counts.put(pkg, entry);
                }
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "读取模块崩溃计数失败：" + Log.getStackTraceString(th));
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
        return counts;
    }

    private static CrashCount parseCrashCount(String raw) {
        String value = raw == null ? "" : raw.trim();
        String[] parts = value.split("@", -1);
        if (parts.length == 0) {
            return null;
        }
        try {
            CrashCount entry = new CrashCount();
            entry.count = Integer.parseInt(parts[0].trim());
            entry.timestamp = parts.length > 1 && parts[1].trim().length() > 0
                    ? Long.parseLong(parts[1].trim()) : 0L;
            return entry;
        } catch (Throwable th) {
            return null;
        }
    }

    private static void writeModuleCounts(Map<String, CrashCount> counts) {
        File dir = moduleStateDir();
        if (dir == null) {
            return;
        }
        File target = new File(dir, MODULE_COUNTS_FILE);
        File tmp = new File(dir, MODULE_COUNTS_FILE + "-" + Process.myPid() + ".tmp");
        try {
            StringBuilder sb = new StringBuilder();
            for (Map.Entry<String, CrashCount> entry : counts.entrySet()) {
                sb.append(entry.getKey()).append('=').append(entry.getValue().count);
                sb.append('@').append(entry.getValue().timestamp);
                sb.append('\n');
            }
            FileOutputStream out = new FileOutputStream(tmp, false);
            try {
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
            if (!tmp.renameTo(target)) {
                deleteQuietly(target);
                if (!tmp.renameTo(target)) {
                    deleteQuietly(tmp);
                }
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "写入模块崩溃计数失败：" + Log.getStackTraceString(th));
            deleteQuietly(tmp);
        }
    }

    private static void resetModuleCrashCounts(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return;
        }
        CountLock lockHandle = acquireCountLock();
        try {
            Map<String, CrashCount> counts = readModuleCounts();
            boolean changed = false;
            for (String pkg : packages) {
                if (counts.remove(pkg) != null) {
                    changed = true;
                }
            }
            if (changed) {
                writeModuleCounts(counts);
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "清除模块崩溃计数失败：" + Log.getStackTraceString(th));
        } finally {
            if (lockHandle != null) {
                lockHandle.close();
            }
        }
    }

}
