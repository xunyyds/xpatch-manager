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
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class AppEntry extends AppComponentFactory {

    private static final String TAG = "XPatch";
    private static final String RUNTIME_ENTRY = "xp/core.dex";
    private static final String CONFIG_ENTRY = "xp/config.json";
    private static final String RUNTIME_CLASS = "io.xpatch.core.CoreEntry";

    /**
     * 只表示"已经注入成功"。注入失败时保持 false，允许后续入口（provider 的 onCreate、
     * 另一个 ClassLoader 的 factory 回调）重试；旧实现先置位再注入，一次失败就永久静默。
     */
    private static volatile boolean injected;
    /**
     * 注入进行中标记（与 injected 分开）：XpProvider.onCreate 可能在
     * CoreEntry.loadAppInPack → startInit 的同一次调用栈里重入 inject()；
     * synchronized 对同线程可重入，只靠 injected 会创建第二个 runtime dex
     * classloader，导致 XpInit/静态状态各跑一遍，模拟器上直接 SIGSEGV。
     */
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

    /**
     * 注入内核运行时 dex。
     *
     * @return true 表示 CoreEntry.loadAppInPack 已经执行完成；调用方（XpProvider）据此决定
     *         onCreate 的返回值，用户侧才能从"provider 创建失败"看到降级，而不是假装正常。
     */
    private static synchronized boolean inject(ClassLoader cl, ApplicationInfo info) {
        if (injected) {
            return true;
        }
        if (injecting) {
            // 同线程重入（provider.onCreate 在 startInit 调用栈里再次触发）：
            // 复用正在进行的注入，绝不能再建一个 runtime dex classloader。
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
            try {
                byte[] dex = readEntry(info.sourceDir, RUNTIME_ENTRY);
                if (dex == null || dex.length == 0) {
                    Log.e(TAG, "运行时 dex 缺失");
                    fatal("运行时 dex 缺失：" + RUNTIME_ENTRY, new IllegalStateException("empty entry"));
                    return false;
                }
                ClassLoader runtime = new dalvik.system.InMemoryDexClassLoader(
                        new ByteBuffer[]{ByteBuffer.wrap(dex)}, null, ClassLoader.getSystemClassLoader());
                Class<?> entry = Class.forName(RUNTIME_CLASS, true, runtime);
                Method method = entry.getMethod("loadAppInPack", ClassLoader.class, ApplicationInfo.class);
                method.invoke(null, cl, info);
                injected = true;
                Log.i(TAG, "运行时已注入：" + info.packageName);
                return true;
            } catch (Throwable t) {
                Log.e(TAG, "运行时注入失败", t);
                fatal("注入运行时到 " + info.packageName, t);
                return false;
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
            name = readFactoryName(appInfo.sourceDir, CONFIG_ENTRY);
        } catch (Throwable t) {
            Log.w(TAG, "读取原厂工厂名失败：" + t);
        }
        if (name == null || name.length() == 0 || isSelf(name)) {
            // 配置缺失或指回自身时不要委派：指回自身会造成无限递归；配置缺失时落到 super
            // 会因为"应用 Application 类不在该 ClassLoader 中"而抛 ClassNotFoundException，
            // 两者都是打开即闪退。
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

    /** 判断配置里的工厂名是否指回引导桩自身（指回自身必须拒绝，否则无限递归）。 */
    private static boolean isSelf(String name) {
        return AppEntry.class.getName().equals(name)
            || "io.xpatch.stub.AppEntry".equals(name);
    }

    /**
     * 引导失败留证。
     *
     * 不变量：这里绝不允许依赖内核里的任何类，也绝不允许抛异常——此刻内核可能根本没加载起来，
     * 而"闪退后还能问话"全靠这个文件。所以只用最朴素的 FileOutputStream 独立落盘，写入全程吞异常。
     * 行首必须是 [yyyy-MM-dd'T'HH:mm:ss.SSS]，时间戳紧跟 ']'、中间不能有空格，
     * 否则管理器 RuntimeLogStore.headerStamp 会判成"不是一条日志的开头"，整段（含堆栈）被丢弃。
     */
    private static void fatal(String stage, Throwable t) {
        Log.e(TAG, "引导失败：" + stage, t);
        try {
            ApplicationInfo info = selfInfo;
            // 引导阶段可能还没拿到 ApplicationInfo（instantiateClassLoader 之前就崩），
            // 这时从当前进程的 ActivityThread 兜底解析。
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
                    File fallback = new File(dataDir, "xpatch-logs");
                    if (ensureDir(fallback)) {
                        appendFile(new File(fallback, "bootstrap.log"), text);
                    }
                }
            }
        } catch (Throwable ignored) {
            // 日志本身绝不允许成为新的崩溃源。
        }
    }

    /** 从 ActivityThread 读取 ApplicationInfo（隐藏 API，失败即返回 null）。 */
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

    /** 从 /proc/self/cmdline 推断包名，作为最后的兜底。 */
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
            // 原厂工厂没拿到时，系统默认实现要求 Application 类就在传入的 ClassLoader 里；
            // Tencent 系应用（QFixApplicationImplProxy）恰好不满足，于是进程在 makeApplication
            // 阶段直接崩掉，表现就是"打开无限闪退且没有任何日志"——这条分支正是 fatal() 存在的理由。
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

    /**
     * xp/config.json 里"原厂工厂类名"的两个历史键。历史版本打包器写的是 app_component_name，
     * 后来的版本写 factory；旧版引导桩只认其中一个，会静默读不到原厂工厂。
     * 这里按顺序全部尝试，是兼容性约束，不要"清理"成单键。
     */
    private static final String[] FACTORY_KEYS = {
        "app_component_name",
        "factory",
    };

    private static String readFactoryName(String apkPath, String entryName) {
        byte[] raw = readEntry(apkPath, entryName);
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

    private static byte[] readEntry(String apkPath, String entryName) {
        ZipFile zip = null;
        try {
            zip = new ZipFile(apkPath);
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
        } finally {
            if (zip != null) {
                try {
                    zip.close();
                } catch (Throwable ignored) {
                }
            }
        }
    }
}