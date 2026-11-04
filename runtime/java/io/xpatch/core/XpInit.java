package io.xpatch.core;

import android.app.Instrumentation;
import android.app.LoadedApk;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.Signature;
import android.os.Handler;
import android.os.Looper;
import android.util.ArrayMap;
import android.util.Log;
import android.widget.Toast;
import dalvik.system.BaseDexClassLoader;
import dalvik.system.DexFile;
import io.xpatch.core.module.ModuleLoader;
import io.xpatch.core.module.RemoteModule;
import io.xpatch.core.module.RemoteModuleProvider;
import io.xpatch.core.remote.RemoteAccessBridge;
import io.xpatch.core.signbypass.SignBypass;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHelper;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.AppUtils;
import io.xpatch.utils.DataUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.Map;
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
            if (AppEnv.dynamicMode && AppEnv.packageName.equals(AppEnv.processName)) {
                try {
                    getApplicationInfo();
                    initClassLoaderInject();
                    injectFileProviderDexFile();
                    XpHook.log0(TAG, "switch app success ");
                } catch (Throwable th) {
                    XpHook.log0(TAG, "switch app failed " + Log.getStackTraceString(th));
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
            doLoop();
        } catch (Throwable th2) {
            XpHook.log0(TAG, "create app failed " + Log.getStackTraceString(th2));
        }
    }

    private static void preDeopt() {
        for (Method method : Instrumentation.class.getDeclaredMethods()) {
            if (method.getName().equals("newApplication")) {
                XpHook.deopt0(method);
                XpHook.log0(TAG, "deopt " + method);
            }
        }
        try {
            for (Method method2 : LoadedApk.class.getDeclaredMethods()) {
                if (method2.getName().contains("makeApplication")) {
                    XpHook.deopt0(method2);
                    XpHook.log0(TAG, "deopt " + method2);
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
        XpHook.log0(TAG, "load from " + selfApplicationInfo.sourceDir);
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

    private static void loadXpLib() throws Exception {
        String strExtractSo = XpHelper.extractSo("core.so");
        System.load(strExtractSo);
        new File(strExtractSo).delete();
        XpHelper.setClassLoaderTrusted(XpInit.class.getClassLoader());
        XpHelper.setClassLoaderTrusted(AppEnv.hook_core_cl);
    }

    private static void loadNativeBypass() throws Exception {
        SignBypass.enable();
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
                if (AppEnv.sign_bypass_type > 0) {
                    JSONArray jSONArray = new JSONArray(jSONObject.getString("sign"));
                    AppEnv.signatures = new Signature[jSONArray.length()];
                    for (int i = 0; i < jSONArray.length(); i++) {
                        AppEnv.signatures[i] = new Signature(DataUtils.hexToByteArray(jSONArray.getString(i)));
                    }
                }
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
            XpHook.log0(TAG, "can't read config file: xp/config.json");
        }
        if (AppEnv.sign_bypass_type > 0 || AppEnv.dynamicMode) {
            ZipEntry entry = zipFile.getEntry(ORIGIN_PATH);
            if (entry == null) {
                XpHook.log0(TAG, "xp/source.apk absent, skip source extraction");
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
                XpHook.log0(TAG, "xp/o_app.apk absent, skip");
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
            XpHook.log0(TAG, "refresh scope module paths failed " + Log.getStackTraceString(th));
        }
    }

    private static void doLoop() {
        new Thread(new Runnable() {
            @Override
            public final void run() {
                XpInit.lambda$doLoop$1();
            }
        }).start();
    }

    static void lambda$doLoop$1() {
        while (true) {
            try {
                Thread.sleep(1000L);
                if (System.currentTimeMillis() >= 999) {
                    return;
                } else {
                    XpHook.log0(TAG, "loop");
                }
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }

    private static void initModules() throws Exception {
        AppEnv.modules = new RemoteModuleProvider(AppEnv.appContext).getModules();
        boolean z = false;
        for (RemoteModule remoteModule : AppEnv.modules) {
            if (new File(remoteModule.apkPath).exists()) {
                ModuleLoader.loadModule(remoteModule);
            } else {
                z = true;
            }
        }
        if (z) {
            new Handler(Looper.getMainLooper()).post(new Runnable() {
                @Override
                public final void run() {
                    Toast.makeText(AppEnv.appContext, "部分模块apk路径已变更,请保持XPatch后台以更新模块apk路径", 1).show();
                }
            });
        }
        XpHook.log0(TAG, "init modules done," + AppEnv.modules);
    }
}
