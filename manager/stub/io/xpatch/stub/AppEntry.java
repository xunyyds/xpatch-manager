package io.xpatch.stub;

import android.app.AppComponentFactory;
import android.app.Application;
import android.app.Activity;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.ContentProvider;
import android.content.pm.ApplicationInfo;
import android.os.Build;
import android.os.Environment;
import android.util.Log;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Enumeration;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AppEntry extends AppComponentFactory {

    private static final String TAG = "Boot";

    private static final int MAX_CFG = 64 * 1024;

    private static final int MAX_PROBES = 2048;

    private static final java.util.HashSet<String> CFG_EXTS = new java.util.HashSet<String>(
            java.util.Arrays.asList("json", "dat", "bin", "txt", "lst"));

    private static final String OLD_CFG = "xp".concat("/config.json");
    private static final String OLD_CORE = "xp".concat("/core.dex");

    private static final String K_CORE = "res_core";
    private static final String K_CLASS = "res_runtime_class";
    private static final String K_FACTORY = "res_factory";

    private static volatile boolean injected;
    private static volatile boolean injecting;
    private static AppComponentFactory delegate;
    private static ApplicationInfo selfInfo;

    @Override
    public ClassLoader instantiateClassLoader(ClassLoader cl, ApplicationInfo info) {
        inject(cl, info);
        return cl;
    }

    public static synchronized boolean ensureInjected(ClassLoader cl, ApplicationInfo info) {
        return inject(cl, info);
    }

    private static synchronized boolean inject(ClassLoader cl, ApplicationInfo info) {
        if (injected) {
            return true;
        }
        if (injecting) {
            Log.i(TAG, "注入进行中，跳过重入调用");
            return true;
        }
        injecting = true;
        try {
            if (info == null) {
                info = resolveApplicationInfo();
            }
            if (info == null) {
                Log.e(TAG, "缺少 ApplicationInfo，无法注入");
                fatal("注入运行时：无法解析 ApplicationInfo", new IllegalStateException("no ApplicationInfo"));
                return false;
            }
            selfInfo = info;
            ZipFile zip = null;
            try {
                zip = new ZipFile(info.sourceDir);
                String cfgEntry = findConfig(zip);
                byte[] raw = cfgEntry == null ? null : readEntry(zip, cfgEntry);
                String cfg = raw == null ? null : new String(raw);
                String core = cfgValue(cfg, K_CORE, OLD_CORE);
                String cls = cfgValue(cfg, K_CLASS, legacyClass());
                String factory = cfgValue(cfg, K_FACTORY, AppEntry.class.getName());
                Log.i(TAG, "引导布局：cfg=" + cfgEntry + " core=" + core
                        + " class=" + cls + " factory=" + factory);
                byte[] dex = readEntry(zip, core);
                closeZip(zip);
                zip = null;
                if (dex == null || dex.length == 0) {
                    Log.e(TAG, "运行时 dex 缺失");
                    fatal("运行时 dex 缺失：" + core, new IllegalStateException("empty entry"));
                    return false;
                }
                ClassLoader runtime = new dalvik.system.InMemoryDexClassLoader(
                        new ByteBuffer[]{ByteBuffer.wrap(dex)}, null, ClassLoader.getSystemClassLoader());
                Class<?> entry = Class.forName(cls, true, runtime);
                Method method = entry.getMethod("loadAppInPack", ClassLoader.class, ApplicationInfo.class);
                method.invoke(null, cl, info);
                injected = true;
                Log.i(TAG, "运行时已注入：" + info.packageName);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "运行时注入失败", t);
                fatal("注入运行时到 " + info.packageName, t);
                return false;
            } finally {
                closeZip(zip);
            }
        } finally {
            injecting = false;
        }
    }

    private static AppComponentFactory delegate(ClassLoader cl, ApplicationInfo info) {
        AppComponentFactory factory = delegate;
        if (factory != null) {
            return factory;
        }
        ApplicationInfo appInfo = info != null ? info : selfInfo;
        if (appInfo == null) {
            return null;
        }
        String name = null;
        try {
            name = readFactoryName(appInfo.sourceDir);
        } catch (Throwable t) {
            Log.w(TAG, "读取原厂工厂名失败：" + t);
        }
        if (name == null || name.length() == 0 || isSelf(name)) {
            if (name != null && name.length() > 0) {
                Log.w(TAG, "忽略指回自身的工厂：" + name);
            }
            return null;
        }
        try {
            Class<?> cls = Class.forName(name, true, cl);
            Object instance = cls.newInstance();
            if (instance instanceof AppComponentFactory) {
                factory = (AppComponentFactory) instance;
                delegate = factory;
            }
        } catch (Throwable t) {
            Log.w(TAG, "原厂工厂不可用：" + name + " -> " + t);
        }
        return factory;
    }

    private static boolean isSelf(String name) {
        return AppEntry.class.getName().equals(name);
    }

    private static String legacyClass() {
        return "io.xp".concat("atch.core.CoreEntry");
    }

    private static String findConfig(ZipFile zip) {
        try {
            java.util.ArrayList<ZipEntry> items = new java.util.ArrayList<ZipEntry>();
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry != null && !entry.isDirectory()) {
                    items.add(entry);
                }
            }
            int budget = MAX_PROBES;
            for (int index = items.size() - 1; index >= 0; index--) {
                ZipEntry entry = items.get(index);
                String name = entry.getName();
                if (!mayBeConfig(name, entry.getSize())) {
                    continue;
                }
                if (budget-- <= 0) {
                    break;
                }
                if (isConfig(zip, name)) {
                    return name;
                }
            }
            return zip.getEntry(OLD_CFG) == null ? null : OLD_CFG;
        } catch (Throwable t) {
            Log.w(TAG, "扫描产物条目失败：" + t);
            return null;
        }
    }

    private static boolean mayBeConfig(String name, long size) {
        if (name == null || name.length() == 0 || size <= 0 || size > MAX_CFG) {
            return false;
        }
        int dot = name.lastIndexOf('.');
        int slash = name.lastIndexOf('/');
        if (dot <= slash || dot == name.length() - 1) {
            return true;
        }
        String ext = name.substring(dot + 1).toLowerCase(Locale.US);
        return CFG_EXTS.contains(ext);
    }

    private static boolean isConfig(ZipFile zip, String name) {
        try {
            ZipEntry entry = zip.getEntry(name);
            int size = entry == null ? -1 : (int) entry.getSize();
            if (size <= 0 || size > MAX_CFG) {
                return false;
            }
            byte[] raw = readEntry(zip, name);
            if (raw == null) {
                return false;
            }
            String text = new String(raw).trim();
            return text.length() > 0 && text.charAt(0) == '{' && text.indexOf("\"res_cfg\"") >= 0;
        } catch (Throwable t) {
            return false;
        }
    }

    private static String cfgValue(String cfg, String key, String fallback) {
        if (cfg == null || cfg.length() == 0) {
            return fallback;
        }
        String value = jsonStringValue(cfg, key);
        return value != null && value.length() > 0 ? value : fallback;
    }

    private static void closeZip(ZipFile zip) {
        if (zip == null) {
            return;
        }
        try {
            zip.close();
        } catch (Throwable ignored) {
        }
    }

    private static void fatal(String stage, Throwable t) {
        Log.e(TAG, "引导失败：" + stage, t);
        try {
            ApplicationInfo info = selfInfo;
            if (info == null) {
                info = resolveApplicationInfo();
            }
            String pkg = info != null ? info.packageName : null;
            if (pkg == null || pkg.length() == 0) {
                pkg = guessPackageName();
            }
            if (pkg == null || pkg.length() == 0) {
                return;
            }
            String text = "[" + new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS", Locale.US)
                .format(new Date())
                + "] 引导桩 -> 引导失败于 " + stage
                + "\n包名: " + pkg
                + "\n进程: " + readProcessName()
                + "\nsourceDir: " + (info != null ? info.sourceDir : "?")
                + "\nAndroid: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")"
                + "\n设备: " + Build.MANUFACTURER + " " + Build.MODEL
                + "\n" + Log.getStackTraceString(t) + "\n";

            File dir = new File(Environment.getExternalStorageDirectory(),
                "Android/data/" + pkg + "/.logs/_framework");
            if (!ensureDir(dir) || !appendFile(new File(dir, "bootstrap.log"), text)) {
                String dataDir = info != null ? info.dataDir : null;
                if (dataDir != null) {
                    File fallback = new File(dataDir, "xpa".concat("tch-logs"));
                    if (ensureDir(fallback)) {
                        appendFile(new File(fallback, "bootstrap.log"), text);
                    }
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static ApplicationInfo resolveApplicationInfo() {
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Method current = at.getMethod("currentActivityThread");
            Object thread = current.invoke(null);
            if (thread == null) {
                return null;
            }
            Object bound = readField(thread, "mBoundApplication");
            if (bound == null) {
                return null;
            }
            Object appInfo = readField(bound, "appInfo");
            return appInfo instanceof ApplicationInfo ? (ApplicationInfo) appInfo : null;
        } catch (Throwable t) {
            return null;
        }
    }

    private static Object readField(Object target, String name) {
        Class<?> type = target.getClass();
        while (type != null) {
            try {
                java.lang.reflect.Field field = type.getDeclaredField(name);
                field.setAccessible(true);
                return field.get(target);
            } catch (NoSuchFieldException e) {
                type = type.getSuperclass();
            } catch (Throwable t) {
                return null;
            }
        }
        return null;
    }

    private static String guessPackageName() {
        String proc = readProcessName();
        if (proc == null || proc.length() == 0 || "?".equals(proc)) {
            return null;
        }
        int colon = proc.indexOf(':');
        return colon > 0 ? proc.substring(0, colon) : proc;
    }

    private static boolean ensureDir(File dir) {
        try {
            return dir.isDirectory() || dir.mkdirs();
        } catch (Throwable t) {
            return false;
        }
    }

    private static boolean appendFile(File file, String text) {
        FileOutputStream out = null;
        try {
            out = new FileOutputStream(file, true);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            return true;
        } catch (Throwable t) {
            return false;
        } finally {
            if (out != null) {
                try {
                    out.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String readProcessName() {
        BufferedReader reader = null;
        try {
            reader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            String line = reader.readLine();
            return line == null ? "?" : line.trim();
        } catch (Throwable t) {
            return "?";
        } finally {
            if (reader != null) {
                try {
                    reader.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    @Override
    public Application instantiateApplication(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            try {
                return factory.instantiateApplication(cl, className);
            } catch (Throwable t) {
                fatal("委派 instantiateApplication(" + className + ")", t);
                throw t;
            }
        }
        try {
            return super.instantiateApplication(cl, className);
        } catch (Throwable t) {
            fatal("super.instantiateApplication(" + className + ")", t);
            throw t;
        }
    }

    @Override
    public Activity instantiateActivity(ClassLoader cl, String className, android.content.Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateActivity(cl, className, intent);
        }
        return super.instantiateActivity(cl, className, intent);
    }

    @Override
    public Service instantiateService(ClassLoader cl, String className, android.content.Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateService(cl, className, intent);
        }
        return super.instantiateService(cl, className, intent);
    }

    @Override
    public BroadcastReceiver instantiateReceiver(ClassLoader cl, String className, android.content.Intent intent)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateReceiver(cl, className, intent);
        }
        return super.instantiateReceiver(cl, className, intent);
    }

    @Override
    public ContentProvider instantiateProvider(ClassLoader cl, String className)
            throws InstantiationException, IllegalAccessException, ClassNotFoundException {
        AppComponentFactory factory = delegate(cl, null);
        if (factory != null) {
            return factory.instantiateProvider(cl, className);
        }
        return super.instantiateProvider(cl, className);
    }

    private static final String[] FACTORY_KEYS = {
        "app_component_name",
        "factory",
    };

    private static String readFactoryName(String apkPath) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
            String cfgEntry = findConfig(zip);
            byte[] raw = cfgEntry == null ? null : readEntry(zip, cfgEntry);
            if (raw == null) {
                return null;
            }
            String text = new String(raw);
            for (String key : FACTORY_KEYS) {
                String value = jsonStringValue(text, key);
                if (value != null && value.length() > 0) {
                    return value;
                }
            }
            return null;
        } catch (Throwable t) {
            Log.w(TAG, "读取原厂工厂名失败：" + t);
            return null;
        } finally {
            closeZip(zip);
        }
    }

    private static String jsonStringValue(String text, String key) {
        int at = text.indexOf('"' + key + '"');
        if (at < 0) {
            return null;
        }
        int colon = text.indexOf(':', at + key.length() + 2);
        if (colon < 0) {
            return null;
        }
        int start = text.indexOf('"', colon + 1);
        if (start < 0) {
            return null;
        }
        int end = text.indexOf('"', start + 1);
        if (end < 0) {
            return null;
        }
        return text.substring(start + 1, end);
    }

    private static byte[] readEntry(ZipFile zip, String entryName) {
        try {
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null) {
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            ByteArrayOutputStream out = new ByteArrayOutputStream(Math.max(8192, (int) entry.getSize()));
            byte[] buffer = new byte[65536];
            int read;
            while ((read = in.read(buffer)) > 0) {
                out.write(buffer, 0, read);
            }
            in.close();
            return out.toByteArray();
        } catch (Throwable t) {
            Log.e(TAG, "read " + entryName + " failed", t);
            return null;
        }
    }
}


