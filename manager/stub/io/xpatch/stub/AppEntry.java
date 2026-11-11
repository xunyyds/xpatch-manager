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
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * 引导桩（产物清单里的 appComponentFactory）。
 *
 * <h3>命名约束（动手改之前先读）</h3>
 * 本文件首行的 package 声明、以及本类与同目录 provider 的类名，三者都**不能改**：
 * 打包器的 StubRewriter 是按「包名 + 类名的全限定形式」在 dex 里做**等长字符串替换**
 * 来混淆类名的，名字一改它就命中不了（清单会指向一个不存在的类，打开即 CNFE）。
 * 反过来说，源码里除这一行 package 声明之外，不允许再出现「完整包名形式」的字符串字面量：
 * 字面量会被 d8 原样写进 dex 的字符串池（明文，strings 一扫就能看到），而这正是本功能
 * 要消除的东西；package 声明本身不会进 dex（dex 只记录类描述符，描述符由编译器生成）。
 * <p>
 * 所以下面所有注释都刻意不再拼出那个包名——否则它又会变成一条能被 grep 到的字面量。
 * 运行层入口类名同理：老产物需要一个默认值，那个默认值只能在运行时拼出来（而且不能用
 * StringBuilder，会被 d8 折叠回常量），见 {@link #legacyClass()}。
 * <p>
 * 另外，新增的字段名/方法名都刻意取短：dex 里连字段名、方法名、方法签名都是明文字符串，
 * 而这份 dex 会被原样注入到每一个产物里，几个字节也是净增量（历史上它一共只有 11 KB）。
 * <p>
 * <b>字符串层同样要干净</b>：`classes*.dex` 是产物里最显眼的那个 dex（不是资产，任何工具都会
 * 先看它），所以除了打包器会替换掉的类描述符之外，源码里不能留下任何"能被 grep 到框架名"的
 * 字面量——TAG、回落条目名、日志目录名统统按 §“字符串层”处理（中性标签 / 运行时拼接）。
 * 注意拼接**只能用 String.concat**：StringBuilder 的常量 append 链会被 d8 --release 折回
 * 单个常量字符串塞进 string_ids（实测），等于没拆。
 *
 * <h3>读取路径</h3>
 * 条目名一律「先发现、读不到再回落旧名字」：开了注入路径混淆的产物把 config 与内核 dex
 * 换成了无意义随机名，名字记在 config 的 res_* 键里；没开的老产物一个 res_* 都没有，
 * 于是每个键都回落到旧常量，走的还是与改动之前完全相同的分支。
 */
public class AppEntry extends AppComponentFactory {

    /**
     * logcat 标签。
     *
     * 故意用中性的 "Boot"，不再是框架名：这份 dex 会原样注入到产物里，而 `classes*.dex`
     * 是任何静态工具第一个看的 dex，"grep 得到框架名"正是本功能要消除的东西。
     * 代价（已知且接受）：引导桩早期日志的 logcat tag 从框架名变成 "Boot"；框架自己的日志
     * 走 XpHook.log0 → 文件 + 自己的 tag，不受影响。排查时按 "Boot" 过滤即可。
     */
    private static final String TAG = "Boot";

    /**
     * 布局候选条目的名字正则。
     *
     * 必须与 manager（`InjectLayout.kt`）和 runtime（`XpLayout.java`）里的那份**逐字一致**。
     * 三份实现各自独立扫描同一份产物，任何一处漂移都会变成「打包成功、目标应用却找不到
     * config」这种没有报错的静默故障——因为找不到就回落旧名字，老产物照样跑，问题只在
     * 混淆产物上才暴露。这也是它写得这么死板的原因：没有派生哈希、没有可配置项。
     * <p>
     * 多命中时的取法也必须与那两份一致：**按 zip 条目顺序取第一个通过内容校验的**
     * （不排序，省掉 ArrayList/Collections 两个类引用与一次排序调用）。
     */
    private static final Pattern CAND = Pattern.compile(
            "^assets/[0-9a-f]{8}/[0-9a-z]{10,14}\\.(dat|bin|json)$");

    /** 候选 config 的体积上限：超过它的一律不可能是我们的 JSON，绝不为"形状像"去解压大文件。 */
    private static final int MAX_CFG = 1024 * 1024;

    /**
     * 旧产物的固定条目名。
     *
     * 新产物（开了路径混淆）里 config / 内核 dex 都是随机名，名字从 config 的 res_* 键读；
     * 只有读不到时才会落到这两个常量，所以老产物行为与改动之前逐字节一致。
     * <p>
     * 值本身用 {@link String#concat} 拼，不写成完整字面量：`xp/config.json` 这种字符串会被
     * `grep xp/` 一眼命中（这就是"字符串层没藏干净"）。拆成 "xp" + "/config.json" 之后，
     * 池里两条字符串之间的长度前缀/结尾 0 字节把 "xp/" 隔开，静态扫描不再命中；
     * 运行时拼出来的值与旧常量完全一致，行为零变化。
     */
    private static final String OLD_CFG = "xp".concat("/config.json");
    private static final String OLD_CORE = "xp".concat("/core.dex");

    /** config 里本模块要读的键（读侧必须容忍缺失：缺 = 老产物 = 全用旧名字）。 */
    private static final String K_CORE = "res_core";
    private static final String K_CLASS = "res_runtime_class";
    private static final String K_FACTORY = "res_factory";

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
            ZipFile zip = null;
            try {
                // 一次打开产物：先"发现" config，再按 config 里的布局读内核 dex。
                // 旧实现是按写死的条目名各读各的；这里同一个 ZipFile 顺序读，内容完全一样
                // （老产物读到的仍是 xp/config.json 与 xp/core.dex）。
                zip = new ZipFile(info.sourceDir);
                String cfgEntry = findConfig(zip);
                byte[] raw = cfgEntry == null ? null : readEntry(zip, cfgEntry);
                String cfg = raw == null ? null : new String(raw);
                String core = cfgValue(cfg, K_CORE, OLD_CORE);
                String cls = cfgValue(cfg, K_CLASS, legacyClass());
                // res_factory 是打包器改名后的桩类名，只用于日志/自检：它与本 dex 里
                // AppEntry.class.getName() 应当一致，不一致就说明"只混淆了清单没混淆 dex"
                // （或反之），这类半套产物靠这条日志一眼可见，不必等用户报"打开就闪退"。
                String factory = cfgValue(cfg, K_FACTORY, AppEntry.class.getName());
                Log.i(TAG, "引导布局：cfg=" + cfgEntry + " core=" + core
                        + " class=" + cls + " factory=" + factory);
                byte[] dex = readEntry(zip, core);
                // 先把 zip 放掉再去做内核初始化：后面的 loadAppInPack 可能很重，
                // 没必要替它一直握着文件描述符（旧实现也是读完就关）。
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

    /**
     * 判断配置里的工厂名是否指回引导桩自身（指回自身必须拒绝，否则无限递归）。
     *
     * 只能用自省拿到的名字：dex 会被打包器就地改名成随机类名，写死字面量在两个意义上都错
     * ——它既是本功能要消除的痕迹（字面量进 dex 字符串池），改名之后又会认不出自己，
     * 于是把委派指回自身、无限递归，表现就是打开即闪退。
     */
    private static boolean isSelf(String name) {
        return AppEntry.class.getName().equals(name);
    }

    /**
     * 旧产物（config 里没有 res_runtime_class）的默认运行层入口类名。
     *
     * 这里**绝不能**写成单个字符串字面量：本功能的目标之一就是让引导桩 dex 里不再留框架
     * 包名前缀的痕迹，d8 会把字面量原样写进 string_ids（明文可扫）。
     * <p>
     * 也不能写成 StringBuilder 的常量 append 链——javac 倒是不会折叠跨方法调用的拼接，
     * 但 **d8 的 --release 优化会**（实测：它会把整条 append 链折成一个常量字符串塞回
     * string_ids，等于白做）。所以这里走 {@link String#concat} 这个库调用：d8 不折叠库调用。
     * 顺带把框架名拆成两段（"…xp" + "atch…"），连 "xpatch" 都不会在字符串池里连续出现，
     * 拼接后的完整类名同理不可能连续出现——长度前缀字节把它们隔开了。
     */
    private static String legacyClass() {
        return "io.xp".concat("atch.core.CoreEntry");
    }

    /**
     * 发现 config 条目名。
     *
     * 发现机制刻意不引入新痕迹（不写固定文件名、不用 extra field、不写 meta-data）：
     * 扫条目名 → 正则匹配 → 解压出来验内容，唯一判据就是"名字形状 + 内容里有 res_cfg"。
     * 多个命中时取**zip 条目顺序里的第一个**，不排序——省掉两个集合类引用，
     * 而且 manager / runtime 只要也按"顺序第一个"挑，三份实现就得到同一个结果。
     * <p>
     * "发现/未发现"的证据统一由 inject() 那行「引导布局：cfg=…」给出（cfg 为 null = 未发现，
     * cfg=xp/config.json = 回落老产物），所以这里刻意不再多打一条日志——这份 dex 会被注入到
     * 每一个产物里，一行日志的字符串也是净体积。
     *
     * @return 命中的条目名；没有候选时回落到 {@link #OLD_CFG}（条目存在才用），
     *         两者都没有则返回 null——老产物没有 config 是合法状态，这里不报错。
     */
    private static String findConfig(ZipFile zip) {
        try {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements()) {
                ZipEntry entry = entries.nextElement();
                if (entry == null || entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName();
                // 先做一次前缀预筛：本方法在"config 里没有原厂工厂名"时会被每次组件实例化反复调用，
                // 而 Pattern.matcher() 是每次分配一个 Matcher 的；startsWith 是纯字符比较，
                // 能把 95% 以上的条目挡在正则之前（正则仍然是判定权威，这里只是省开销）。
                if (name != null && name.startsWith("assets/")
                        && CAND.matcher(name).matches() && isConfig(zip, name)) {
                    return name;
                }
            }
            // 没有候选：回落到旧名字（老产物）。存在才用。
            return zip.getEntry(OLD_CFG) == null ? null : OLD_CFG;
        } catch (Throwable t) {
            // 扫描出错（zip 损坏、OOM…）就当没发现：注入本身还有旧名字兜底，绝不能因此中断启动。
            Log.w(TAG, "扫描产物条目失败：" + t);
            return null;
        }
    }

    /**
     * 候选条目是否就是我们的 config：≤1 MB、trim 后以 '{' 开头、且含 "res_cfg"。
     *
     * 先按**声明尺寸**卡 1 MB 上限再解压（zip 中央目录里的 uncompressed size 就是这个值），
     * 这样"名字形状像但其实是几十 MB 的资源"根本不会进内存——引导阶段内存很敏感。
     * 读取复用 {@link #readEntry(ZipFile, String)}：同样是"解压整条目"，没必要再写一遍。
     */
    private static boolean isConfig(ZipFile zip, String name) {
        try {
            ZipEntry entry = zip.getEntry(name);
            // 负数（> 2 GB 被截断）与 0 都视为不可信，直接放弃。
            int size = entry == null ? -1 : (int) entry.getSize();
            if (size <= 0 || size > MAX_CFG) {
                return false;
            }
            byte[] raw = readEntry(zip, name);
            if (raw == null) {
                return false;
            }
            // 平台默认字符集在 Android 上就是 UTF-8（与旧代码 new String(raw) 一致）；
            // 要对比的键名是纯 ASCII，即使遇到畸形字节也只会变成替换字符，不影响判定。
            String text = new String(raw).trim();
            return text.length() > 0 && text.charAt(0) == '{' && text.indexOf("\"res_cfg\"") >= 0;
        } catch (Throwable t) {
            // 单个候选读失败不影响别的候选，更不该让启动挂掉。
            return false;
        }
    }

    /**
     * 从 config JSON 里读一个字符串键；缺失 / 空值一律返回回落值。
     *
     * 读侧必须容忍缺失：没有 res_* 的 config 就是老产物，它必须继续按旧常量工作。
     * 这里不套 try/catch 是有意的：{@link #jsonStringValue} 只做下标运算与判负返回，
     * 自身不会抛（越界都在内部判掉了）；真出意外也有 inject() 的 catch(Throwable) 兜底落盘。
     */
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
            // 关闭失败无所谓，读出来的字节已经拿到了。
        }
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
                    // 目录名与管理器 RuntimeLogStore.PRIVATE_LOG_DIR 必须一致（它按
                    // /data/data/<pkg>/<PRIVATE_LOG_DIR> 去找），所以这里**只拆字面量不改路径**：
                    // "xpa" + "tch-logs" 拼出来还是同一个目录，只是 dex 里不再有可 grep 的整词。
                    File fallback = new File(dataDir, "xpa".concat("tch-logs"));
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

    /**
     * 读"原厂工厂类名"。
     *
     * 与注入路径共用同一套 config 发现逻辑：混淆产物里 config 是随机名，写死
     * {@link #OLD_CFG} 会读不到——那样在已经注入成功的应用里，"原厂工厂委派"会静默退化成
     * super，Tencent 系应用就是打开即闪退（见 instantiateApplication 的注释）。
     */
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

    /** 在一个已经打开的产物上读条目（内容与旧实现逐字节相同，只是不重复开 ZipFile）。 */
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
