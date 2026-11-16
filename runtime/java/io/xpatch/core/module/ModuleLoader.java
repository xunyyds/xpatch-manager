package io.xpatch.core.module;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Build;
import android.os.Process;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Log;
import dalvik.system.DexFile;
import dalvik.system.InMemoryDexClassLoader;
import dalvik.system.PathClassLoader;
import io.xpatch.core.XpLayout;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHelper;
import io.xpatch.hook.XpHook;
import io.xpatch.utils.DataUtils;
import java.io.File;
import java.io.FileOutputStream;
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
    private static final String[] WRAPPER_LOGICAL = {"dex89", "dex100", "dex101", "dex102"};
    private static final Map<String, byte[]> WRAPPER_DEX_CACHE = new ArrayMap();

    private static String wrapperEntry(String wrapper) {
        for (int i = 0; i < WRAPPER_NAMES.length; i++) {
            if (WRAPPER_NAMES[i].equals(wrapper)) {
                return XpLayout.get(WRAPPER_LOGICAL[i]);
            }
        }
        return WRAPPER_DEX_DIR + wrapper + ".dex";
    }

    private static ZipEntry findEntry(ZipFile zipFile, String name) {
        ZipEntry entry = zipFile.getEntry(name);
        if (entry != null) {
            return entry;
        }
        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        while (entries.hasMoreElements()) {
            ZipEntry candidate = entries.nextElement();
            if (name.equals(candidate.getName())) {
                return candidate;
            }
        }
        return null;
    }

    public static void preloadWrapperDexes() {
        synchronized (WRAPPER_DEX_CACHE) {
            ZipFile zipFile = null;
            try {
                String apk = AppEnv.selfApk;
                zipFile = new ZipFile(apk);
                for (String str : WRAPPER_NAMES) {
                    if (WRAPPER_DEX_CACHE.containsKey(str)) {
                        continue;
                    }
                    try {
                        String entryName = wrapperEntry(str);
                        ZipEntry entry = findEntry(zipFile, entryName);
                        if (entry == null) {
                            throw new IllegalStateException("missing payload entry: " + entryName);
                        }
                        InputStream inputStream = zipFile.getInputStream(entry);
                        try {
                            WRAPPER_DEX_CACHE.put(str, DataUtils.readAllBytes(inputStream));
                        } finally {
                            inputStream.close();
                        }
                        XpHook.log0(TAG, "已预载 wrapper dex " + str);
                    } catch (Throwable th) {
                        XpHook.log0(TAG, "预载 wrapper dex 失败：" + str
                                + "（selfApk=" + AppEnv.selfApk + "，size=" + zipFile.size()
                                + "，baseDir="
                                + (AppEnv.baseApp == null ? "null" : AppEnv.baseApp.sourceDir) + "）\n"
                                + Log.getStackTraceString(th));
                    }
                }
            } catch (Throwable th) {
                XpHook.log0(TAG, "预载 wrapper dex 失败：无法打开 " + AppEnv.selfApk + "\n" + Log.getStackTraceString(th));
            } finally {
                if (zipFile != null) {
                    try {
                        zipFile.close();
                    } catch (Throwable ignored) {
                    }
                }
            }
        }
    }

    public static boolean loadModule(RemoteModule remoteModule) {
        if (remoteModule == null || TextUtils.isEmpty(remoteModule.apkPath)) {
            return false;
        }
        return loadModule(remoteModule.apkPath, remoteModule.packageName, remoteModule.useApiLevel);
    }

    public static boolean loadModule(String str) {
        return loadModule(str, null, 0);
    }

    public static boolean loadModule(String str, String str2, int i) {
        int iResolveApiLevel;
        String str3;
        if (i > 0) {
            iResolveApiLevel = i;
        } else {
            try {
                iResolveApiLevel = resolveApiLevel(str, str2);
            } catch (Throwable th) {
                XpHook.log0(TAG, "加载模块失败：" + str + "\n" + Log.getStackTraceString(th));
                return false;
            }
        }
        WrapperLoadPlan wrapperLoadPlanCreateWrapperLoadPlan = createWrapperLoadPlan(iResolveApiLevel);
        String[] plannedWrappers = wrapperLoadPlanCreateWrapperLoadPlan.wrapperNames;
        StringBuilder sb = new StringBuilder("正在加载模块：");
        sb.append(str);
        if (TextUtils.isEmpty(str2)) {
            str3 = "";
        } else {
            str3 = "，包名=" + str2;
        }
        sb.append(str3);
        sb.append("，API 级别=");
        sb.append(iResolveApiLevel);
        sb.append("，wrapper=");
        sb.append(Arrays.toString(plannedWrappers));
        XpHook.log0(TAG, sb.toString());
        try {
            ClassLoader classLoaderCreateModuleClassLoader = createModuleClassLoader(str, plannedWrappers);
            XpHelper.setClassLoaderTrusted(classLoaderCreateModuleClassLoader);
            Method declaredMethod = classLoaderCreateModuleClassLoader.loadClass(WRAPPER_ENTRY_CLASS).getDeclaredMethod(WRAPPER_ENTRY_METHOD, String.class, ClassLoader.class);
            declaredMethod.setAccessible(true);
            declaredMethod.invoke(null, str, classLoaderCreateModuleClassLoader);
            StringBuilder sb2 = new StringBuilder("加载模块成功：路径=");
            sb2.append(str);
            sb2.append("，API 级别=");
            sb2.append(iResolveApiLevel);
            sb2.append("，wrapper=");
            sb2.append(Arrays.toString(plannedWrappers));
            sb2.append(i > 0 ? "，来源=请求" : "，来源=自动");
            XpHook.log0(TAG, sb2.toString());
            return true;
        } catch (Throwable th) {
            XpHook.log0(TAG, "加载模块失败：" + str + "\n" + Log.getStackTraceString(th));
            return false;
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
            byte[] selfEntryBytes = readSelfEntryBytes(wrapperEntry(str));
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
            throw new IllegalStateException("找不到 DexFile(ByteBuffer[], ClassLoader, ...) 构造方法");
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
        ByteBuffer[] wrapperBuffers = new ByteBuffer[wrapperNames.length];
        for (int w = 0; w < wrapperNames.length; w++) {
            wrapperBuffers[w] = ByteBuffer.wrap(getWrapperDexBytes(wrapperNames[w]));
        }
        String nativeLibDir = resolveModuleNativeLibDir(str);
        return new PathClassLoader(str, nativeLibDir,
                new InMemoryDexClassLoader(wrapperBuffers, nativeLibDir, ModuleLoader.class.getClassLoader()));
    }

    private static int countModuleDex(ZipFile zipFile) {
        int dexCount = 0;
        int i = 1;
        while (true) {
            ZipEntry entry = zipFile.getEntry(i == 1 ? "classes.dex" : "classes" + i + ".dex");
            if (entry == null) {
                break;
            }
            dexCount++;
            i++;
        }
        return dexCount;
    }

    private static String resolveModuleNativeLibDir(String apkPath) {
        ZipFile zipFile = null;
        try {
            zipFile = new ZipFile(apkPath);
            if (countModuleDex(zipFile) == 0) {
                throw new IllegalStateException("模块 APK 中没有 dex：" + apkPath);
            }
            java.util.Set<String> present = new java.util.HashSet<String>();
            java.util.Set<String> compressed = new java.util.HashSet<String>();
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith("lib/") || name.length() <= 4) {
                    continue;
                }
                int split = name.indexOf('/', 4);
                if (split < 0) {
                    continue;
                }
                String abi = name.substring(4, split);
                if (abi.length() == 0) {
                    continue;
                }
                if (split == name.length() - 1) {
                    present.add(abi);
                    continue;
                }
                String fileName = name.substring(split + 1);
                if (fileName.indexOf('/') >= 0 || !fileName.endsWith(".so")) {
                    continue;
                }
                present.add(abi);
                if (entry.getMethod() != ZipEntry.STORED) {
                    compressed.add(abi);
                }
            }
            if (present.isEmpty()) {
                XpHook.log0(TAG, "模块 " + apkPath + " 没有 lib/<abi>/ 目录或 lib/<abi>/*.so 条目：不提供 native 库路径");
                return null;
            }
            boolean is64 = Process.is64Bit();
            String abi = pickAbi(present, is64, true);
            if (abi != null) {
                return moduleNativeDir(zipFile, apkPath, abi, compressed, is64);
            }
            abi = pickAbi(present, is64, false);
            if (abi != null) {
                XpHook.log0(TAG, "模块没有匹配进程位宽（" + (is64 ? "64" : "32")
                        + "）的 lib/<abi>/，回退 " + abi + "：" + apkPath);
                return moduleNativeDir(zipFile, apkPath, abi, compressed, is64);
            }
            XpHook.log0(TAG, "模块 APK 的 lib 目录与设备 ABI 都不匹配（" + present
                    + " / 设备=" + java.util.Arrays.toString(Build.SUPPORTED_ABIS)
                    + "）：只丢 native 能力");
            return null;
        } catch (Exception e) {
            XpHook.log0(TAG, "探测模块 native 库目录失败：" + apkPath + " -> " + e);
            return null;
        } finally {
            if (zipFile != null) {
                try {
                    zipFile.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String moduleNativeDir(ZipFile zipFile, String apkPath, String abi,
            java.util.Set<String> compressed, boolean is64) {
        String installed = installedNativeDir(zipFile, apkPath, abi);
        if (installed != null) {
            XpHook.log0(TAG, "模块 native 库目录（模块自身已安装目录）：" + installed);
            return installed;
        }
        if (compressed.contains(abi)) {
            String dir = extractModuleNativeLibs(zipFile, apkPath, abi);
            if (dir != null) {
                XpHook.log0(TAG, "模块 native 库目录（压缩条目已解压）：" + dir);
                return dir;
            }
            XpHook.log0(TAG, "模块 " + abi + " 的 native 库为压缩存储且解压失败，仍回退 zip 路径：" + apkPath);
        }
        XpHook.log0(TAG, "模块 native 库目录：" + apkPath + "!/lib/" + abi
                + "/（进程位宽=" + (is64 ? "64" : "32") + "）");
        return apkPath + "!/lib/" + abi + "/";
    }

    private static String installedNativeDir(ZipFile zipFile, String apkPath, String abi) {
        if (abi == null || abi.length() == 0) {
            return null;
        }
        String directory = installedNativeDirName(abi);
        File root = null;
        File apk = new File(apkPath);
        File parent = apk.getParentFile();
        if (parent != null && directory != null) {
            File sibling = new File(new File(parent, "lib"), directory);
            if (sibling.isDirectory()) {
                root = sibling;
            }
        }
        if (root == null) {
            root = installedNativeDirByPackage(apkPath, directory);
        }
        if (root == null) {
            return null;
        }
        String prefix = "lib/" + abi + "/";
        java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
        int matched = 0;
        while (entries.hasMoreElements()) {
            String name = entries.nextElement().getName();
            if (!name.startsWith(prefix)) {
                continue;
            }
            String fileName = name.substring(prefix.length());
            if (fileName.length() == 0 || fileName.indexOf('/') >= 0 || !fileName.endsWith(".so")) {
                continue;
            }
            File library = new File(root, fileName);
            if (!library.isFile() || !library.canRead()) {
                return null;
            }
            matched++;
        }
        return matched > 0 ? root.getAbsolutePath() : null;
    }

    private static String installedNativeDirName(String abi) {
        if ("arm64-v8a".equals(abi)) {
            return "arm64";
        }
        if ("armeabi-v7a".equals(abi) || "armeabi".equals(abi)) {
            return "arm";
        }
        if ("x86_64".equals(abi) || "x86".equals(abi) || "riscv64".equals(abi) || "mips64".equals(abi)) {
            return abi;
        }
        return null;
    }

    private static File installedNativeDirByPackage(String apkPath, String directory) {
        try {
            Context context = AppEnv.appContext;
            if (context == null) {
                return null;
            }
            PackageInfo archive = context.getPackageManager().getPackageArchiveInfo(apkPath, 0);
            if (archive == null || archive.packageName == null || archive.packageName.length() == 0) {
                return null;
            }
            ApplicationInfo info = context.getPackageManager()
                    .getApplicationInfo(archive.packageName, 0);
            if (info == null || info.nativeLibraryDir == null) {
                return null;
            }
            File root = new File(info.nativeLibraryDir);
            if (!root.isDirectory() || (directory != null && !directory.equals(root.getName()))) {
                return null;
            }
            return root;
        } catch (Throwable th) {
            return null;
        }
    }

    private static String pickAbi(java.util.Set<String> present, boolean is64, boolean sameWidth) {
        String[] abis = sameWidth
                ? (is64 ? Build.SUPPORTED_64_BIT_ABIS : Build.SUPPORTED_32_BIT_ABIS)
                : (is64 ? Build.SUPPORTED_32_BIT_ABIS : Build.SUPPORTED_64_BIT_ABIS);
        for (int i = 0; abis != null && i < abis.length; i++) {
            if (present.contains(abis[i])) {
                return abis[i];
            }
        }
        return null;
    }

    private static String extractModuleNativeLibs(ZipFile zipFile, String apkPath, String abi) {
        try {
            Context context = AppEnv.appContext;
            if (context == null) {
                return null;
            }
            File source = new File(apkPath);
            String tag = Integer.toHexString(apkPath.hashCode()) + "_"
                    + Long.toHexString(source.length()) + "_"
                    + Long.toHexString(source.lastModified());
            File abiDir = new File(new File(new File(context.getCacheDir(), "xp_module_libs"), tag), abi);
            String prefix = "lib/" + abi + "/";
            java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
            int extracted = 0;
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                String name = entry.getName();
                if (!name.startsWith(prefix)) {
                    continue;
                }
                String fileName = name.substring(prefix.length());
                if (fileName.length() == 0 || fileName.indexOf('/') >= 0 || !fileName.endsWith(".so")) {
                    continue;
                }
                if (fileName.startsWith("!")) {
                    fileName = fileName.substring(1);
                    if (fileName.length() == 0) {
                        continue;
                    }
                }
                File target = new File(abiDir, fileName);
                File parent = target.getParentFile();
                if (parent != null) {
                    parent.mkdirs();
                }
                InputStream input = zipFile.getInputStream(entry);
                try {
                    FileOutputStream output = new FileOutputStream(target);
                    try {
                        output.write(DataUtils.readAllBytes(input));
                    } finally {
                        output.close();
                    }
                } finally {
                    input.close();
                }
                extracted++;
            }
            if (extracted == 0) {
                return null;
            }
            return abiDir.getAbsolutePath();
        } catch (Throwable th) {
            XpHook.log0(TAG, "解压模块 native 库失败：" + apkPath + "（" + abi + "）-> " + th);
            return null;
        }
    }

    private static byte[] readSelfEntryBytes(String str) throws Exception {
        String apk = AppEnv.selfApk;
        ZipFile zipFile = new ZipFile(apk);
        try {
            ZipEntry entry = zipFile.getEntry(str);
            if (entry == null) {
                throw new IllegalStateException("缺少 payload 条目：" + str);
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
            XpHook.log0(TAG, "检查模块配置失败：" + str + "\n" + Log.getStackTraceString(e));
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
            LinkedHashSet<Integer> levels = new LinkedHashSet<Integer>();
            int legacy = this.legacyApiLevel;
            if (legacy > 0) {
                if (legacy != 89 && legacy != 100 && legacy != 101 && legacy != 102) {
                    legacy = 89;
                }
                levels.add(Integer.valueOf(legacy));
            } else if (this.legacyModule) {
                levels.add(89);
            }
            if (this.modernModule || this.modernMinApiLevel >= 100 || this.modernTargetApiLevel >= 100) {
                int minimum = this.modernMinApiLevel >= 100 ? this.modernMinApiLevel : 100;
                int target = this.modernTargetApiLevel >= minimum ? this.modernTargetApiLevel : minimum;
                if (target > 102) {
                    target = 102;
                }
                if (minimum > 102) {
                    levels.add(Integer.valueOf(minimum));
                } else {
                    for (int level = minimum; level <= target; level++) {
                        levels.add(Integer.valueOf(level));
                    }
                }
            }
            this.apiLevels.clear();
            this.apiLevels.addAll(levels);
        }
    }

    private static final class WrapperLoadPlan {
        private final String[] wrapperNames;

        private WrapperLoadPlan(String[] strArr) {
            this.wrapperNames = strArr;
        }
    }
}
