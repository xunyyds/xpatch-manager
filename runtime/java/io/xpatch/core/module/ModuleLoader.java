package io.xpatch.core.module;

import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import dalvik.system.DexFile;
import dalvik.system.InMemoryDexClassLoader;
import dalvik.system.PathClassLoader;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHelper;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class ModuleLoader {
    private static final String TAG = "ModuleLoader";
    private static final String WRAPPER_DEX_DIR = "xp/";
    private static final String WRAPPER_ENTRY_CLASS = "io.xpatch.wrapper.WrapperBase";
    private static final String WRAPPER_ENTRY_METHOD = "loadModule";
    private static final String[] WRAPPER_NAMES = {"xp89", "xp100", "xp101", "xp102"};
    private static final Map<String, byte[]> WRAPPER_DEX_CACHE = new ArrayMap();

    public static void preloadWrapperDexes() {
        synchronized (WRAPPER_DEX_CACHE) {
            for (String str : WRAPPER_NAMES) {
                Map<String, byte[]> map = WRAPPER_DEX_CACHE;
                if (!map.containsKey(str)) {
                    try {
                        map.put(str, readSelfEntryBytes(WRAPPER_DEX_DIR + str + ".dex"));
                        StringBuilder sb = new StringBuilder();
                        sb.append("preloaded wrapper dex ");
                        sb.append(str);
                        XpHook.log0(TAG, sb.toString());
                    } catch (Throwable th) {
                        XpHook.log0(TAG, "preload wrapper dex failed " + str + "\n" + Log.getStackTraceString(th));
                    }
                }
            }
        }
    }

    public static void loadModule(RemoteModule remoteModule) {
        if (remoteModule == null || TextUtils.isEmpty(remoteModule.apkPath)) {
            return;
        }
        loadModule(remoteModule.apkPath, remoteModule.packageName, remoteModule.useApiLevel);
    }

    public static void loadModule(String str) {
        loadModule(str, null, 0);
    }

    public static void loadModule(String str, String str2, int i) {
        int iResolveApiLevel;
        String str3;
        if (i > 0) {
            iResolveApiLevel = i;
        } else {
            try {
                iResolveApiLevel = resolveApiLevel(str, str2);
            } catch (Throwable th) {
                XpHook.log0(TAG, "load module failed " + str + "\n" + Log.getStackTraceString(th));
                return;
            }
        }
        WrapperLoadPlan wrapperLoadPlanCreateWrapperLoadPlan = createWrapperLoadPlan(iResolveApiLevel);
        String[] plannedWrappers = wrapperLoadPlanCreateWrapperLoadPlan.wrapperNames;
        StringBuilder sb = new StringBuilder("Loading module from ");
        sb.append(str);
        if (TextUtils.isEmpty(str2)) {
            str3 = "";
        } else {
            str3 = ", package=" + str2;
        }
        sb.append(str3);
        sb.append(", apiLevel=");
        sb.append(iResolveApiLevel);
        sb.append(", wrappers=");
        sb.append(Arrays.toString(plannedWrappers));
        XpHook.log0(TAG, sb.toString());
        try {
            ClassLoader classLoaderCreateModuleClassLoader = createModuleClassLoader(str, plannedWrappers);
            XpHelper.setClassLoaderTrusted(classLoaderCreateModuleClassLoader);
            Method declaredMethod = classLoaderCreateModuleClassLoader.loadClass(WRAPPER_ENTRY_CLASS).getDeclaredMethod(WRAPPER_ENTRY_METHOD, String.class, ClassLoader.class);
            declaredMethod.setAccessible(true);
            declaredMethod.invoke(null, str, classLoaderCreateModuleClassLoader);
            StringBuilder sb2 = new StringBuilder("load module success path=");
            sb2.append(str);
            sb2.append(", apiLevel=");
            sb2.append(iResolveApiLevel);
            sb2.append(", wrappers=");
            sb2.append(Arrays.toString(plannedWrappers));
            sb2.append(i > 0 ? ", source=request" : ", source=auto");
            XpHook.log0(TAG, sb2.toString());
        } catch (Throwable th) {
            XpHook.log0(TAG, "load module failed " + str + "\n" + Log.getStackTraceString(th));
        }
    }

    private static void injectWrapperDexes(ClassLoader classLoader, String[] strArr) throws Exception {
        for (String str : strArr) {
            injectWrapperDex(classLoader, str);
        }
    }

    private static void injectWrapperDex(ClassLoader classLoader, String str) throws Exception {
    }

    private static byte[] getWrapperDexBytes(String str) throws Exception {
        Map<String, byte[]> map = WRAPPER_DEX_CACHE;
        synchronized (map) {
            byte[] bArr = map.get(str);
            if (bArr != null) {
                return bArr;
            }
            byte[] selfEntryBytes = readSelfEntryBytes(WRAPPER_DEX_DIR + str + ".dex");
            map.put(str, selfEntryBytes);
            return selfEntryBytes;
        }
    }

    private static DexFile newInMemoryDexFile(byte[] bArr, ClassLoader classLoader) throws Exception {
        Constructor<?> constructor;
        Constructor<?>[] declaredConstructors = DexFile.class.getDeclaredConstructors();
        int length = declaredConstructors.length;
        int i = 0;
        while (true) {
            if (i >= length) {
                constructor = null;
                break;
            }
            constructor = declaredConstructors[i];
            Class<?>[] parameterTypes = constructor.getParameterTypes();
            if (parameterTypes.length == 3 && parameterTypes[0] == ByteBuffer[].class && parameterTypes[1] == ClassLoader.class) {
                break;
            }
            i++;
        }
        if (constructor == null) {
            throw new IllegalStateException("DexFile(ByteBuffer[], ClassLoader, ...) constructor not found");
        }
        constructor.setAccessible(true);
        return (DexFile) constructor.newInstance(new ByteBuffer[]{ByteBuffer.wrap(bArr)}, classLoader, null);
    }

    private static Field findField(Class<?> cls, String str) throws NoSuchFieldException {
        while (cls != null) {
            try {
                Field declaredField = cls.getDeclaredField(str);
                declaredField.setAccessible(true);
                return declaredField;
            } catch (NoSuchFieldException unused) {
                cls = cls.getSuperclass();
            }
        }
        throw new NoSuchFieldException(str);
    }

    private static ClassLoader createModuleClassLoader(String str, String[] wrapperNames) throws Exception {
        ArrayList arrayList = new ArrayList();
        ZipFile zipFile = new ZipFile(str);
        int i = 1;
        while (true) {
            try {
                ZipEntry entry = zipFile.getEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex");
                if (entry == null) {
                    break;
                }
                InputStream inputStream = zipFile.getInputStream(entry);
                try {
                    arrayList.add(DataUtils.readAllBytes(inputStream));
                    if (inputStream != null) {
                        inputStream.close();
                    }
                    i++;
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
            } catch (Throwable th3) {
                try {
                    zipFile.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
                throw th3;
            }
        }
        zipFile.close();
        if (arrayList.isEmpty()) {
            throw new IllegalStateException("no dex found in module apk: " + str);
        }
        ByteBuffer[] byteBufferArr = new ByteBuffer[arrayList.size()];
        for (int i2 = 0; i2 < arrayList.size(); i2++) {
            byteBufferArr[i2] = ByteBuffer.wrap((byte[]) arrayList.get(i2));
        }
        String str2 = str + "!/lib/arm64-v8a/";
        ByteBuffer[] wrapperBuffers = new ByteBuffer[wrapperNames.length];
        for (int w = 0; w < wrapperNames.length; w++) {
            wrapperBuffers[w] = ByteBuffer.wrap(getWrapperDexBytes(wrapperNames[w]));
        }
        return new PathClassLoader(str, str2, new InMemoryDexClassLoader(wrapperBuffers, str2, ModuleLoader.class.getClassLoader()));
    }

    private static byte[] readSelfEntryBytes(String str) throws Exception {
        ZipFile zipFile = new ZipFile(AppEnv.selfApk);
        try {
            ZipEntry entry = zipFile.getEntry(str);
            if (entry == null) {
                throw new IllegalStateException("missing payload entry: " + str);
            }
            InputStream inputStream = zipFile.getInputStream(entry);
            try {
                byte[] allBytes = DataUtils.readAllBytes(inputStream);
                if (inputStream != null) {
                    inputStream.close();
                }
                zipFile.close();
                return allBytes;
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
        } catch (Throwable th3) {
            try {
                zipFile.close();
            } catch (Throwable th4) {
                th3.addSuppressed(th4);
            }
            throw th3;
        }
    }

    private static int resolveApiLevel(String str, String str2) {
        ModuleApiInfo moduleApiInfoInspectModuleApiInfo = inspectModuleApiInfo(str, str2);
        if (moduleApiInfoInspectModuleApiInfo.apiLevels.isEmpty()) {
            return moduleApiInfoInspectModuleApiInfo.modernModule ? 100 : 89;
        }
        return ((Integer) moduleApiInfoInspectModuleApiInfo.apiLevels.get(moduleApiInfoInspectModuleApiInfo.apiLevels.size() - 1)).intValue();
    }

    private static WrapperLoadPlan createWrapperLoadPlan(int i) {
        // 四个 wrapper dex 都声明同名类 io.xpatch.wrapper.WrapperBase，
        // 同一 ClassLoader 内只能存在一个，因此必须按选择精确注入单个 wrapper。
        if (i < 100) {
            return new WrapperLoadPlan(new String[]{"xp89"});
        }
        if (i == 101) {
            return new WrapperLoadPlan(new String[]{"xp101"});
        }
        if (i > 101) {
            return new WrapperLoadPlan(new String[]{"xp102"});
        }
        return new WrapperLoadPlan(new String[]{"xp100"});
    }

    private static ModuleApiInfo inspectModuleApiInfo(String str, String str2) {
        ModuleApiInfo moduleApiInfo = new ModuleApiInfo();
        try {
            ZipFile zipFile = new ZipFile(str);
            try {
                ZipEntry entry = zipFile.getEntry("META-INF/xposed/module.prop");
                boolean z = true;
                if (entry != null) {
                    moduleApiInfo.modernModule = true;
                    InputStream inputStream = zipFile.getInputStream(entry);
                    try {
                        Properties properties = new Properties();
                        properties.load(inputStream);
                        moduleApiInfo.modernMinApiLevel = parseInt(properties.getProperty("minApiVersion"));
                        moduleApiInfo.modernTargetApiLevel = parseInt(properties.getProperty("targetApiVersion"));
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
                }
                if (zipFile.getEntry("assets/xposed_init") == null) {
                    z = false;
                }
                moduleApiInfo.legacyModule = z;
                zipFile.close();
            } catch (Throwable th3) {
                try {
                    zipFile.close();
                } catch (Throwable th4) {
                    th3.addSuppressed(th4);
                }
                throw th3;
            }
        } catch (Exception e) {
            XpHook.log0(TAG, "inspect module config failed " + str + "\n" + Log.getStackTraceString(e));
        }
        int installedLegacyApiLevel = readInstalledLegacyApiLevel(str2);
        if (installedLegacyApiLevel <= 0) {
            installedLegacyApiLevel = readArchiveLegacyApiLevel(str);
        }
        moduleApiInfo.legacyApiLevel = installedLegacyApiLevel;
        moduleApiInfo.buildAvailableApiLevels();
        return moduleApiInfo;
    }

    private static int readInstalledLegacyApiLevel(String str) {
        if (!TextUtils.isEmpty(str) && AppEnv.appContext != null) {
            try {
                PackageInfo packageInfo = AppEnv.appContext.getPackageManager().getPackageInfo(str, 128);
                if (packageInfo != null && packageInfo.applicationInfo != null) {
                    return readLegacyApiLevel(packageInfo.applicationInfo);
                }
            } catch (Exception unused) {
            }
        }
        return 0;
    }

    private static int readArchiveLegacyApiLevel(String str) {
        if (AppEnv.appContext == null) {
            return 0;
        }
        try {
            PackageInfo packageArchiveInfo = AppEnv.appContext.getPackageManager().getPackageArchiveInfo(str, 128);
            if (packageArchiveInfo != null && packageArchiveInfo.applicationInfo != null) {
                ApplicationInfo applicationInfo = packageArchiveInfo.applicationInfo;
                applicationInfo.sourceDir = str;
                applicationInfo.publicSourceDir = str;
                return readLegacyApiLevel(applicationInfo);
            }
        } catch (Exception unused) {
        }
        return 0;
    }

    private static int readLegacyApiLevel(ApplicationInfo applicationInfo) {
        if (applicationInfo.metaData == null) {
            return 0;
        }
        return applicationInfo.metaData.getInt("xposedminversion");
    }

    private static int parseInt(String str) {
        if (TextUtils.isEmpty(str)) {
            return 0;
        }
        try {
            return Integer.parseInt(str.trim());
        } catch (NumberFormatException unused) {
            return 0;
        }
    }

    private static final class ModuleApiInfo {
        private final List<Integer> apiLevels;
        private int legacyApiLevel;
        private boolean legacyModule;
        private int modernMinApiLevel;
        private boolean modernModule;
        private int modernTargetApiLevel;

        private ModuleApiInfo() {
            this.apiLevels = new ArrayList();
        }

        public void buildAvailableApiLevels() {
            int i;
            LinkedHashSet linkedHashSet = new LinkedHashSet();
            int i2 = this.legacyApiLevel;
            if (i2 > 0) {
                if (i2 != 89 && i2 != 100 && i2 != 101 && i2 != 102) {
                    i2 = 89;
                }
                linkedHashSet.add(Integer.valueOf(i2));
            } else if (this.legacyModule) {
                linkedHashSet.add(89);
            }
            int i3 = this.modernMinApiLevel;
            int i4 = this.modernTargetApiLevel;
            int i5 = 100;
            if (i3 >= 100) {
                i5 = 102;
                if (i4 > 102) {
                    i4 = 102;
                }
                if (i4 < 102) {
                    i4 = 100;
                }
                if (i3 < 102) {
                    for (i = 100; i <= i4; i++) {
                        linkedHashSet.add(Integer.valueOf(i));
                    }
                } else if (i3 >= i5) {
                    linkedHashSet.add(Integer.valueOf(i3));
                } else if (this.modernModule) {
                    linkedHashSet.add(Integer.valueOf(i5));
                }
            } else if (i4 >= 100) {
                i3 = 100;
                i5 = 102;
                if (i4 > 102) {
                    i4 = 102;
                }
                if (i4 < 102) {
                    i4 = 100;
                }
                if (i3 < 102) {
                    for (i = 100; i <= i4; i++) {
                        if (i != 89 || i == 100 || i == 101 || i == 102) {
                            linkedHashSet.add(Integer.valueOf(i));
                        }
                    }
                } else if (i3 >= i5) {
                    linkedHashSet.add(Integer.valueOf(i3));
                } else if (this.modernModule) {
                    linkedHashSet.add(Integer.valueOf(i5));
                }
            } else if (i3 >= i5) {
                linkedHashSet.add(Integer.valueOf(i3));
            } else if (this.modernModule) {
                linkedHashSet.add(Integer.valueOf(i5));
            }
            this.apiLevels.clear();
            this.apiLevels.addAll(linkedHashSet);
        }
    }

    private static final class WrapperLoadPlan {
        private final String[] wrapperNames;

        private WrapperLoadPlan(String[] strArr) {
            this.wrapperNames = strArr;
        }
    }
}
