package io.xpatch.core;

import android.app.Instrumentation;
import android.app.LoadedApk;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
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
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.BufferedReader;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.json.JSONArray;
import org.json.JSONObject;
import org.lsposed.hidden.HiddenApiBypass;

public class XpInit {
    private static final String CONFIG_PATH = "xp/config.json";
    private static final String HOOK_CORE_PATH = "xp/hook_core.dex";
    private static final String ORIGIN_PATH = "xp/source.apk";
    private static final String O_ORIGIN_PATH = "xp/o_app.apk";
    private static final String TAG = "XpInit";
    static Object activityThread;
    static LoadedApk loadedApk;
    static ApplicationInfo selfApplicationInfo;

    public static void startInit() {
        try {
            AppUtils.removeProfileFiles(AppEnv.packageName);
            AppUtils.remoteOATFiles(AppEnv.packageName);
            new File(AppEnv.baseApp.dataDir + "/cache/android_id/").mkdirs();
            performXpConfig();
            initHookCore();
            preDeopt();
            installSignBypass();
            if (AppEnv.dynamicMode && AppEnv.packageName.equals(AppEnv.processName)) {
                try {
                    getApplicationInfo();
                    initClassLoaderInject();
                    injectFileProviderDexFile();
                    XpHook.log0(TAG, "切换应用成功");
                } catch (Throwable th) {
                    XpHook.log0(TAG, "切换应用失败：" + Log.getStackTraceString(th));
                }
            } else {
                createAppContext();
            }
            if (AppEnv.appContext != null) {
                RemoteAccessBridge.initialize(AppEnv.appContext);
            }
            createFakeInstrumentation();
            if (AppEnv.baseApp.appComponentFactory != null) {
                try {
                    AppEnv.appClassLoader.loadClass(AppEnv.baseApp.appComponentFactory);
                } catch (Exception unused) {
                }
            }
            ModuleLoader.preloadWrapperDexes();
            refreshScopeModulePaths();
            initModules();
        } catch (Throwable th2) {
            XpHook.log0(TAG, "创建应用失败：" + Log.getStackTraceString(th2));
        }
    }

    private static void installSignBypass() {
        String mode = AppEnv.sig_mode;
        if (AppEnv.SIG_MODE_OFF.equals(mode)) {
            XpHook.log0(TAG, "签名伪装已关闭（sig_mode=off）");
            return;
        }
        if (!AppEnv.sig_mode_explicit && AppEnv.sign_bypass_type <= 0) {
            XpHook.log0(TAG, "签名伪装已禁用（配置 bypass_sign_type=" + AppEnv.sign_bypass_type
                    + "，且没有 sig_mode 键）");
            return;
        }
        Signature[] signs = SignatureFacade.originals();
        try {
            boolean primitive = SignatureFacade.install();
            boolean strict = AppEnv.sign_bypass_type == 2;
            PMSignBypass.setHasSigningCertificateEnabled(strict);
            if (strict) {
                try {
                    PMSignBypass.installHasSigningCertificateLater();
                } catch (Throwable th) {
                    XpHook.log0(TAG, "hasSigningCertificate 严格校验排队失败：" + Log.getStackTraceString(th));
                }
            }
            XpHook.log0(TAG, "签名严格校验开关=" + strict + "，bypass=" + AppEnv.sign_bypass_type);
            try {
                PMSignBypass.enableSignBypassByHook();
            } catch (Throwable th) {
                XpHook.log0(TAG, "PackageInfo 层签名伪装安装失败：" + Log.getStackTraceString(th));
            }
            if (AppEnv.sign_bypass_type == 1) {
                try {
                    SignBypass.enable();
                    XpHook.log0(TAG, "Seccomp 文件重定向已启用");
                } catch (Throwable th) {
                    XpHook.log0(TAG, "Seccomp 文件重定向不可用：" + Log.getStackTraceString(th));
                }
            }
            DeferredSignHooks.schedule();
            if ((AppEnv.SIG_MODE_JAVA_FILE.equals(mode) || AppEnv.SIG_MODE_DEEP.equals(mode)
                    || AppEnv.sign_bypass_type == 1)
                    && AppEnv.source_apk_path != null && AppEnv.source_apk_path.length() > 0) {
                try {
                    getApplicationInfo();
                    initClassLoaderInject();
                    FileLayerHooks.install();
                    XpHook.log0(TAG, "文件层路径重定向已启用：" + AppEnv.source_apk_path);
                } catch (Throwable th) {
                    XpHook.log0(TAG, "文件层路径重定向失败：" + Log.getStackTraceString(th));
                }
            }
            XpHook.log0(TAG, "签名伪装已安装：模式=" + mode
                    + "，原语层=" + (primitive ? "开启" : "关闭")
                    + "，证书数=" + (signs == null ? 0 : signs.length)
                    + "，原始证书=" + sha256Prefix(signs)
                    + "，详情[" + describeSigningDetails() + "]");
            if (!AppEnv.SIG_MODE_JAVA.equals(mode)) {
                XpHook.log0(TAG, "签名模式 " + mode + " 的文件层/原生层尚未实现，本轮按 java 层安装");
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "签名伪装失败：" + Log.getStackTraceString(th)
                    + "\n  => 应用将看到重签证书，可能触发自校验闪退");
        }
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

    private static void preDeopt() {
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (method.getName().equals("newApplication")) {
                XpHook.deopt0(method);
                XpHook.log0(TAG, "置为解释执行：" + method);
            }
        }
        try {
            for (Method method2 : LoadedApk.class.getDeclaredMethods()) {
                if (method2.getName().contains("makeApplication")) {
                    XpHook.deopt0(method2);
                    XpHook.log0(TAG, "置为解释执行：" + method2);
                }
            }
        } catch (Exception unused) {
        }
    }

    private static void createFakeInstrumentation() {
        try {
            Class<?> cls = Class.forName("android.app.ActivityThread");
            Object objInvoke = HiddenApiBypass.getDeclaredMethod(cls, "currentActivityThread", new Class[0]).invoke(null, new Object[0]);
            Instrumentation instrumentation = new Instrumentation();
            Class<?> cls2 = instrumentation.getClass();
            Field fieldFindObjectField = findObjectField(cls2, "mThread");
            fieldFindObjectField.setAccessible(true);
            fieldFindObjectField.set(instrumentation, objInvoke);
            Field fieldFindObjectField2 = findObjectField(cls2, "mAppContext");
            fieldFindObjectField2.setAccessible(true);
            fieldFindObjectField2.set(instrumentation, AppEnv.appContext);
            Field declaredField = cls.getDeclaredField("mInstrumentation");
            declaredField.setAccessible(true);
            declaredField.set(objInvoke, instrumentation);
        } catch (Exception e) {
            throw new ExceptionInInitializerError("XPatch 初始化失败!!!\n" + e);
        }
    }

    private static void injectFileProviderDexFile() throws Exception {
        Constructor<?> constructor;
        if (AppEnv.fileProvider) {
            ZipFile zipFile = new ZipFile(AppEnv.selfApk);
            byte[] allBytes = DataUtils.readAllBytes(zipFile.getInputStream(zipFile.getEntry("xp/file_provider")));
            zipFile.close();
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
        Field fieldFindObjectField = findObjectField(Class.forName("android.app.ActivityThread"), "mBoundApplication");
        fieldFindObjectField.setAccessible(true);
        Object obj = fieldFindObjectField.get(activityThread);
        selfApplicationInfo.sourceDir = AppEnv.source_apk_path;
        selfApplicationInfo.publicSourceDir = AppEnv.source_apk_path;
        ((Map) getObjectField(activityThread, "mPackages")).remove(selfApplicationInfo.packageName);
        final LoadedApk loadedApk2 = (LoadedApk) HiddenApiBypass.invoke(Class.forName("android.app.ActivityThread"), activityThread, "getPackageInfoNoCheck", selfApplicationInfo, getObjectField(obj, "compatInfo"));
        XpHook.log0(TAG, "从以下路径加载：" + selfApplicationInfo.sourceDir);
        ArrayMap arrayMap = (ArrayMap) getObjectField(activityThread, "mActivities");
        final Field fieldFindObjectField2 = findObjectField(Class.forName("android.app.ActivityThread$ActivityClientRecord"), "packageInfo");
        fieldFindObjectField2.setAccessible(true);
        arrayMap.forEach(new BiConsumer() {
            @Override
            public final void accept(Object obj2, Object obj3) {
                XpInit.lambda$initClassLoaderInject$0(fieldFindObjectField2, loadedApk2, obj2, obj3);
            }
        });
        AppEnv.appContext = (Context) HiddenApiBypass.invoke(Class.forName("android.app.ContextImpl"), null, "createAppContext", activityThread, loadedApk2);
        switchField(loadedApk2, loadedApk, "mBaseClassLoader");
        switchField(loadedApk2, loadedApk, "mClassLoader");
        switchField(loadedApk2, loadedApk, "mDefaultClassLoader");
        setFieldValue(obj, "info", loadedApk2);
        selfApplicationInfo.sourceDir = AppEnv.selfApk;
        selfApplicationInfo.publicSourceDir = AppEnv.selfApk;
        AppEnv.appClassLoader = (ClassLoader) getObjectField(loadedApk2, "mClassLoader");
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
        for (Field field : HiddenApiBypass.getInstanceFields(cls)) {
            if (field.getName().equals(str)) {
                return field;
            }
        }
        return null;
    }

    @Deprecated
    private static void loadXpLib() throws Exception {
        XpHook.log0(TAG, "loadXpLib 已废弃：Android 17 产物不含 xp/core.so，跳过");
    }

    @Deprecated
    private static void loadNativeBypass() throws Exception {
        XpHook.log0(TAG, "loadNativeBypass 已废弃，请使用 installSignBypass()");
        installSignBypass();
    }

    private static void performXpConfig() throws IOException {
        ZipFile zipFile = new ZipFile(AppEnv.selfApk);
        try {
            InputStream inputStream = zipFile.getInputStream(zipFile.getEntry(CONFIG_PATH));
            try {
                JSONObject jSONObject = new JSONObject(new String(DataUtils.readAllBytes(inputStream)));
                AppEnv.baseApp.appComponentFactory = jSONObject.getString("app_component_name");
                AppEnv.sign_bypass_type = SignBypass.normalizeType(jSONObject.optInt("bypass_sign_type"));
                AppEnv.core_ver = jSONObject.optInt("ver");
                AppEnv.dynamicMode = jSONObject.optBoolean("dynamic_mode");
                AppEnv.read_version_code = jSONObject.optLong("real_version_code", -1L);
                AppEnv.fileProvider = jSONObject.optBoolean("file_provider");
                AppEnv.buildNonce = jSONObject.optString("build_nonce", "");
                String rawMode = jSONObject.optString("sig_mode", "");
                AppEnv.sig_mode_explicit = rawMode != null && rawMode.trim().length() > 0;
                AppEnv.sig_mode = AppEnv.sig_mode_explicit
                        ? normalizeSigMode(rawMode) : AppEnv.SIG_MODE_JAVA;
                AppEnv.signing_details = jSONObject.optString("signing_details", null);
                if (AppEnv.sign_bypass_type > 0 || !AppEnv.SIG_MODE_OFF.equals(AppEnv.sig_mode)) {
                    parseLegacySignatures(jSONObject);
                }
                SigningDetailsData.init(AppEnv.signing_details, AppEnv.signatures);
                if (inputStream != null) {
                    inputStream.close();
                }
            } catch (Throwable th) {
                if (inputStream != null) {
                    try {
                        inputStream.close();
                    } catch (Throwable th2) {
                        th.addSuppressed(th2);
                    }
                }
                throw th;
            }
        } catch (Exception unused) {
            XpHook.log0(TAG, "无法读取配置：xp/config.json");
        }
        if (AppEnv.sign_bypass_type > 0 || AppEnv.dynamicMode) {
            ZipEntry entry = zipFile.getEntry(ORIGIN_PATH);
            if (entry == null) {
                XpHook.log0(TAG, "xp/source.apk 不存在，跳过原包释放");
            } else {
                String str = AppEnv.baseApp.dataDir + "/cache/android_id/" + entry.getCrc() + ".apk";
                File file = new File(str);
                if (!file.getParentFile().exists() || file.length() != entry.getSize()) {
                    file.getParentFile().mkdirs();
                    FileOutputStream fileOutputStream = new FileOutputStream(file);
                    InputStream inputStream2 = zipFile.getInputStream(entry);
                    DataUtils.copyStream(inputStream2, fileOutputStream);
                    inputStream2.close();
                    fileOutputStream.close();
                }
                AppEnv.source_apk_path = str;
                new File(str).setWritable(false);
            }
        }
        try {
            ZipEntry entry2 = zipFile.getEntry(O_ORIGIN_PATH);
            if (entry2 == null) {
                XpHook.log0(TAG, "xp/o_app.apk 不存在，跳过");
            } else {
                String str2 = AppEnv.baseApp.dataDir + "/cache/android_id/" + entry2.getCrc() + ".apk";
                File file2 = new File(str2);
                if (!file2.getParentFile().exists() || file2.length() != entry2.getSize()) {
                    file2.getParentFile().mkdirs();
                    FileOutputStream fileOutputStream2 = new FileOutputStream(file2);
                    InputStream inputStream3 = zipFile.getInputStream(entry2);
                    DataUtils.copyStream(inputStream3, fileOutputStream2);
                    inputStream3.close();
                    fileOutputStream2.close();
                }
                AppEnv.o_apk_path = str2;
                new File(str2).setWritable(false);
            }
        } catch (Exception unused2) {
        }
        zipFile.close();
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

    private static void parseLegacySignatures(JSONObject config) {
        try {
            JSONArray array = new JSONArray(config.optString("sign", "[]"));
            Signature[] signatures = new Signature[array.length()];
            for (int i = 0; i < array.length(); i++) {
                signatures[i] = new Signature(DataUtils.hexToByteArray(array.getString(i)));
            }
            AppEnv.signatures = signatures;
        } catch (Throwable th) {
            XpHook.log0(TAG, "解析 legacy sign（原证书）失败：" + Log.getStackTraceString(th));
        }
    }

    private static void initHookCore() throws Exception {
        ZipFile zipFile = new ZipFile(AppEnv.selfApk);
        byte[] allBytes = DataUtils.readAllBytes(zipFile.getInputStream(zipFile.getEntry(HOOK_CORE_PATH)));
        zipFile.close();
        AppEnv.hook_core_cl = SimpleClassLoader.loadDex(new ByteBuffer[]{ByteBuffer.wrap(allBytes)}, XpInit.class.getClassLoader());
        AppEnv.hook_core_cl.loadClass("io.xpatch.core.entry.CoreEntry").getMethod("InitHookCore", new Class[0]).invoke(null, new Object[0]);
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

    private static final String MODULE_PENDING_PREFIX = "xpatch-module-pending-";
    private static final String MODULE_COUNTS_FILE = "xpatch-module-crash-counts.txt";
    private static final int MODULE_CRASH_THRESHOLD = 3;

    private static void initModules() throws Exception {
        Set<String> skipThisLaunch = loadModuleCrashCounts();
        AppEnv.modules = new RemoteModuleProvider(AppEnv.appContext).getModules();
        boolean changedPath = false;
        for (RemoteModule remoteModule : AppEnv.modules) {
            if (skipThisLaunch.contains(remoteModule.packageName)) {
                XpHook.log0(TAG, "本次启动跳过模块 " + remoteModule.packageName
                        + "：已判定它导致频繁闪退，等待管理器移除作用域");
                continue;
            }
            if (!new File(remoteModule.apkPath).exists()) {
                changedPath = true;
                continue;
            }
            File pending = modulePendingFile();
            writePendingModule(pending, remoteModule.packageName);
            try {
                ModuleLoader.loadModule(remoteModule);
                resetModuleCrashCount(remoteModule.packageName);
            } finally {
                deleteQuietly(pending);
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
        XpHook.log0(TAG, "模块初始化完成：" + AppEnv.modules);
    }

    private static void notifyModuleCrash(String modulePkg) {
        try {
            Context ctx = AppEnv.appContext;
            if (ctx == null) {
                return;
            }
            Intent intent = new Intent("io.xpatch.action.MODULE_CRASH");
            intent.setPackage("io.xpatch");
            intent.putExtra("module", modulePkg);
            intent.putExtra("app", AppEnv.packageName);
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    private static File moduleStateDir() {
        try {
            return AppEnv.appContext == null ? null : AppEnv.appContext.getFilesDir();
        } catch (Throwable th) {
            return null;
        }
    }

    private static File modulePendingFile() {
        File dir = moduleStateDir();
        return dir == null ? null : new File(dir, MODULE_PENDING_PREFIX + Process.myPid());
    }

    private static void writePendingModule(File file, String pkg) {
        if (file == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(file, false);
            try {
                out.write((pkg + "\n").getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
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

    private static Set<String> loadModuleCrashCounts() {
        Set<String> skip = new HashSet<String>();
        java.util.Map<String, Integer> counts = readModuleCounts();
        File dir = moduleStateDir();
        if (dir == null) {
            return skip;
        }
        File[] files = dir.listFiles();
        if (files != null) {
            for (File file : files) {
                if (file == null || !file.getName().startsWith(MODULE_PENDING_PREFIX)) {
                    continue;
                }
                String pkg = readFirstLine(file);
                if (pkg != null && pkg.length() > 0) {
                    Integer old = counts.get(pkg);
                    int count = (old == null ? 0 : old.intValue()) + 1;
                    counts.put(pkg, Integer.valueOf(count));
                    if (count >= MODULE_CRASH_THRESHOLD) {
                        XpHook.log0(TAG, "模块 " + pkg + " 已连续 " + count
                                + " 次导致启动崩溃，正在通知管理器移除作用域");
                        notifyModuleCrash(pkg);
                        skip.add(pkg);
                        counts.remove(pkg);
                    } else {
                        XpHook.log0(TAG, "模块 " + pkg + " 上次启动崩溃（" + count + "/"
                                + MODULE_CRASH_THRESHOLD + "），本次继续重试");
                    }
                }
                deleteQuietly(file);
            }
        }
        writeModuleCounts(counts);
        return skip;
    }

    private static java.util.Map<String, Integer> readModuleCounts() {
        java.util.Map<String, Integer> counts = new java.util.HashMap<String, Integer>();
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
                try {
                    counts.put(line.substring(0, eq), Integer.valueOf(line.substring(eq + 1).trim()));
                } catch (Throwable ignored) {
                }
            }
        } catch (Throwable ignored) {
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

    private static void writeModuleCounts(java.util.Map<String, Integer> counts) {
        File dir = moduleStateDir();
        if (dir == null) {
            return;
        }
        try {
            FileOutputStream out = new FileOutputStream(new File(dir, MODULE_COUNTS_FILE), false);
            try {
                StringBuilder sb = new StringBuilder();
                for (java.util.Map.Entry<String, Integer> entry : counts.entrySet()) {
                    sb.append(entry.getKey()).append('=').append(entry.getValue()).append('\n');
                }
                out.write(sb.toString().getBytes("UTF-8"));
                out.flush();
                out.getFD().sync();
            } finally {
                out.close();
            }
        } catch (Throwable ignored) {
        }
    }

    private static void resetModuleCrashCount(String pkg) {
        try {
            java.util.Map<String, Integer> counts = readModuleCounts();
            if (counts.remove(pkg) != null) {
                writeModuleCounts(counts);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String readFirstLine(File file) {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader(file));
            String line = reader.readLine();
            return line == null ? null : line.trim();
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

}
