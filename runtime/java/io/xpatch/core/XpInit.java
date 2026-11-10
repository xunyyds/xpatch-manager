package io.xpatch.core;

import android.app.Application;
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
    private static final String CONFIG_PATH = "xp/config.json";
    private static final String HOOK_CORE_PATH = "xp/hook_core.dex";
    private static final String ORIGIN_PATH = "xp/source.apk";
    private static final String O_ORIGIN_PATH = "xp/o_app.apk";
    private static final String TAG = "XpInit";

    /** 模块崩溃广播：action、接收方需要的权限（W5 在管理器清单里声明同名 signature 权限）。 */
    private static final String ACTION_MODULE_CRASH = "io.xpatch.action.MODULE_CRASH";
    private static final String PERMISSION_MODULE_CRASH = "io.xpatch.permission.MODULE_CRASH";
    private static final String DEFAULT_MANAGER_PACKAGE = "io.xpatch";

    /** 熔断状态：本进程写的 pending 残留 + 跨进程共享的计数文件。 */
    private static final String MODULE_PENDING_PREFIX = "xpatch-module-pending-";
    private static final String MODULE_COUNTS_FILE = "xpatch-module-crash-counts.txt";
    private static final int MODULE_CRASH_THRESHOLD = 3;
    /** 弱证据时间窗：两次计数间隔超过 24h 视为不相干，旧计数清零（P2-13）。 */
    private static final long MODULE_CRASH_WINDOW_MS = 24L * 60L * 60L * 1000L;

    static Object activityThread;
    static LoadedApk loadedApk;
    static ApplicationInfo selfApplicationInfo;

    /** installSignBypass() 的幂等守卫：重复调用会重复装 hook（历史 P3 隐患）。 */
    private static boolean signBypassInstalled = false;
    /**
     * 进程级初始化只允许一次：entryHook 同时保留了工厂注入与 provider 备用入口，
     * 两条路径都可能触发 CoreEntry.loadAppInPack；二次进入会二次 initHookCore()
     * （同一进程再 System.load 一份 hook_core.so）并在模拟器上直接 SIGSEGV。
     * 这里做进程级幂等，后续重复调用只记一条日志。
     */
    private static volatile boolean started = false;
    /**
     * {@code DeferredSignHooks.schedule()} 的返回值（是否真的排上队）：决定“启动完成”回调走哪条路径。
     * 只由 schedule() 的返回值赋值；为 false 时必须装本类的兜底启动钩子（proceed 之后跑回调）。
     */
    private static boolean deferredSignHooksScheduled = false;
    /** 启动完成后要执行的回调（删除 pending）；只执行一次，单个回调异常不影响其它回调。 */
    private static final List<Runnable> startupCallbacks = new ArrayList<Runnable>();
    /** 兜底启动钩子是否已安装：sig_mode=off 等场景 DeferredSignHooks 不排队，必须自己挂。 */
    private static boolean fallbackStartupHookInstalled = false;
    /** 本进程本次“加载成功”的模块：只有活到启动完成才清它们的崩溃计数。 */
    private static final Set<String> startupLoadedPackages = new HashSet<String>();
    /** 本进程本次写入的 pending 文件：启动完成时删除；进程崩溃则留作下次启动的证据。 */
    private static final List<File> startupPendingFiles = new ArrayList<File>();
    /** 启动完成收尾（单例，注册幂等；列表被消费一次后重复执行是空操作）。 */
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
        try {
            AppUtils.removeProfileFiles(AppEnv.packageName);
            AppUtils.remoteOATFiles(AppEnv.packageName);
            new File(AppEnv.baseApp.dataDir + "/cache/android_id/").mkdirs();
            performXpConfig();
            initHookCore();
            // hook_core.dex 的 CoreEntry.InitHookCore 已经在上面 System.load 过 hook_core.so；
            // 记录这个事实，NativeLoader.load() 据此避免重复加载（跨 classloader 二次 load 会抛错）。
            AppEnv.nativeLoadedByHookCore = true;
            preDeopt();
            // 必须在 native io 重定向启用前读包装 APK：重定向会把 selfApk 的 open
            // 重定向到 xp/source.apk，之后再读 xp/xp89.dex 只会看到原包的 3 个条目。
            preloadWrapperAssets();
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
            }
            // createAppContext() 幂等：installSignBypass() 可能已提前建过（NativeLoader 需要 codeCacheDir），
            // 动态模式切换失败时这里兜底，保证后续模块加载一定有 context（P2-14）
            if (AppEnv.appContext == null) {
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
            // sig_mode=off：不加载 native、不装签名/文件层 hook；
            // performXpConfig 也会跳过原包释放与 legacy 证书解析
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
        signBypassInstalled = true;

        // 顺序不变量：native 库必须先于 SignBypass.enable()（LevelOne 的 io 重定向要用它），
        // 原包路径必须先于 native 重定向；任何一步失败都只降级，默认 java/3 路径必须能启动。
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

        // 原包路径只认 performXpConfig 释放出来的 cache/android_id/<crc>.apk；
        // 这里不再现场重建“假原包”（P1-1：拼出来的包没有有效签名）
        boolean fileLayerWanted = wantsFileLayer(mode);
        String sourcePath = AppEnv.source_apk_path;
        boolean hasSourceApk = sourcePath != null && sourcePath.length() > 0;

        Signature[] signs = SignatureFacade.originals();
        try {
            boolean primitive = SignatureFacade.install();
            // strict 不再由 bypass 单独决定：极致档是 deep + bypass=3，必须同时打开
            // hasSigningCertificate（严格校验）与 Mix native 重定向。
            boolean strict = AppEnv.sign_bypass_type == 2
                    || AppEnv.SIG_MODE_DEEP.equals(mode);
            PMSignBypass.setHasSigningCertificateEnabled(strict);
            // hasSigningCertificate 立即安装：不再走“sleep 1500ms 的延后路径”（Application.onCreate 里的自校验早于它）
            boolean early = false;
            try {
                early = PMSignBypass.installEarly();
            } catch (Throwable th) {
                XpHook.log0(TAG, "hasSigningCertificate 早期安装失败：" + Log.getStackTraceString(th));
            }
            // provider 阶段同步段（A0b）：ContentProvider.onCreate 早于 callApplicationOnCreate，
            // 只在 DeferredSignHooks.installAll 里装会让 provider 的自校验看到真实现。
            // 两者都是 getMethod + doHook（不构造 ApplicationPackageManager 实例），且带幂等守卫：
            // DeferredSignHooks.installAll() 再调一次无副作用。
            // 早期 AppEnv.appContext 可能为空：installDeferredHooks 只装 ApplicationPackageManager，
            // OEM 包装类留给后续 installAll 补（见 review/90-.../dev/I1a-startup.md 的 W2 移交）。
            installProviderStageHooks();
            XpHook.log0(TAG, "签名严格校验开关=" + strict + "，bypass=" + AppEnv.sign_bypass_type
                    + "，hasSigningCertificate=" + (early ? "已安装" : "未安装"));
            try {
                PMSignBypass.enableSignBypassByHook();
            } catch (Throwable th) {
                XpHook.log0(TAG, "PackageInfo 层签名伪装安装失败：" + Log.getStackTraceString(th));
            }
            // native io 重定向只在“该档确实要文件层/原生层”时启用：seccomp(bypass=1)、
            // file/strict(bypass=2，dobby)、deep/3(极致档，Mix)；
            // java_file/deep(需要字节级原包)；bypass=3 的 basic 档不碰 native。
            boolean wantsNativeRedirect = fileLayerWanted || AppEnv.sign_bypass_type == 2;
            if (wantsNativeRedirect) {
                boolean redirectEnabled = false;
                try {
                    redirectEnabled = SignBypass.enable();
                } catch (Throwable th) {
                    XpHook.log0(TAG, "native io 重定向不可用：" + Log.getStackTraceString(th));
                }
                // 只有真实启用才打“已启用”（旧代码无条件打，日志与实现相反，P1-2/P3）
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
                // 文件层只需要路径 hook。这里不再 getApplicationInfo()+initClassLoaderInject()：
                // 那会在 app context 之前重复建 context、把 LoadedApk 切两次（P2-14）
                try {
                    boolean fileLayer = FileLayerHooks.install();
                    // 只有真装上了才打“已启用”；失败原因由 FileLayerHooks.lastError() 给出
                    XpHook.log0(TAG, fileLayer
                            ? "文件层路径重定向已启用：" + FileLayerHooks.installedTarget()
                                    + "（hook 数=" + FileLayerHooks.hookedCount() + "）"
                            : "文件层未启用：" + FileLayerHooks.lastError());
                } catch (Throwable th) {
                    XpHook.log0(TAG, "文件层路径重定向失败：" + Log.getStackTraceString(th));
                }
            }
            scheduleDeferredSignHooks();
            XpHook.log0(TAG, "签名伪装已安装：模式=" + mode
                    + "，原语层=" + (primitive ? "开启" : "关闭")
                    + "，native=" + (nativeLoaded ? "已加载" : "未加载")
                    + "，证书数=" + (signs == null ? 0 : signs.length)
                    + "，原始证书=" + sha256Prefix(signs)
                    + "，详情[" + describeSigningDetails() + "]");
        } catch (Throwable th) {
            XpHook.log0(TAG, "签名伪装失败：" + Log.getStackTraceString(th)
                    + "\n  => 应用将看到重签证书，可能触发自校验闪退");
        }
    }

    /** 需要文件层（路径重定向）的档位：java_file / deep / seccomp(bypass=1)。 */
    private static boolean wantsFileLayer(String mode) {
        return AppEnv.SIG_MODE_JAVA_FILE.equals(mode)
                || AppEnv.SIG_MODE_DEEP.equals(mode)
                || AppEnv.sign_bypass_type == 1;
    }

    /**
     * 同步安装"provider 阶段就要可用"的两条 hook（集成待办 A0b / W2 移交 2）。
     *
     * <p>时序不变量：{@code ContentProvider.onCreate} 早于 {@code Instrumentation.callApplicationOnCreate}，
     * 所以 checkSignatures / getPackageArchiveInfo 必须在本函数的同步段就装上，不能等
     * {@code DeferredSignHooks.installAll()}（那是 Application.onCreate 之前才跑）。
     *
     * <p>幂等：{@code CheckSignaturesBypass} 有 installed + MAX_ATTEMPTS 守卫，
     * {@code PMSignBypass.installDeferredHooks} 有 archiveHooksInstalled 守卫，
     * 因此 {@code DeferredSignHooks.installAll()} 稍后再调一次只多一行日志、没有副作用。
     *
     * <p>早期 {@code AppEnv.appContext} 可能为空：此时 installDeferredHooks 只装
     * {@code ApplicationPackageManager}（OEM 包装子类要等 context 才知道类名），由 installAll 补齐。
     */
    private static void installProviderStageHooks() {
        // 与 DeferredSignHooks.installAll() 的顺序一致：先 getPackageArchiveInfo，再 checkSignatures
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

    /**
     * 排队延后签名 hook，并把"是否真的排上队"记进 {@link #deferredSignHooksScheduled}。
     *
     * <p>不变量：这个标志只由 {@code DeferredSignHooks.schedule()} 的返回值决定。
     * 返回 false（doHook 失败）时 DeferredSignHooks 内部只做 installAll、不置"启动完成"，
     * 所以本类必须自己挂兜底启动钩子（{@code registerStartupCallback} 里按此标志判断）。
     */
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

    /**
     * 取一个可用于 NativeLoader（codeCacheDir）等早期动作的 Context。
     * installSignBypass() 早于框架创建 Application，此时 AppEnv.appContext 可能为空：
     * 先用幂等的 createAppContext() 自建，失败再退回反射拿 mInitialApplication。
     */
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

    /**
     * 在 native io 重定向之前读取包装 APK 里的运行层资产：redirect 会把这些路径的
     * open 重定向到 xp/source.apk，之后再读 xp/xp89.dex / xp/file_provider 就会拿到原包内容。
     */
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
            ZipFile zipFile = new ZipFile(AppEnv.selfApk);
            ZipEntry entry = zipFile.getEntry("xp/file_provider");
            if (entry == null) {
                java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                while (entries.hasMoreElements()) {
                    ZipEntry candidate = entries.nextElement();
                    if ("xp/file_provider".equals(candidate.getName())) {
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

    private static void preDeopt() {        for (Method method : Instrumentation.class.getDeclaredMethods()) {
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
            byte[] allBytes = AppEnv.fileProviderDex;
            if (allBytes == null) {
                // 兜底：没有提前预读（极老产物/非动态路径）时再读一次包装 APK；
                // 此时若 native 重定向已启用，可能会读到原包，注入会失败但会被调用方降级。
                ZipFile zipFile = new ZipFile(AppEnv.selfApk);
                ZipEntry entry = zipFile.getEntry("xp/file_provider");
                if (entry == null) {
                    java.util.Enumeration<? extends ZipEntry> entries = zipFile.entries();
                    while (entries.hasMoreElements()) {
                        ZipEntry candidate = entries.nextElement();
                        if ("xp/file_provider".equals(candidate.getName())) {
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
                XpHook.log0(TAG, "xp/file_provider 缺失或为空，跳过 file provider dex 注入");
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
        Field fieldFindObjectField = findObjectField(Class.forName("android.app.ActivityThread"), "mBoundApplication");
        fieldFindObjectField.setAccessible(true);
        Object obj = fieldFindObjectField.get(activityThread);
        // 这两份状态是框架真正在用的（mBoundApplication.appInfo / mPackages），
        // 任何一步抛异常都必须还原，否则应用会在补丁包路径下启动（P2-14）
        final String previousSourceDir = selfApplicationInfo.sourceDir;
        final String previousPublicSourceDir = selfApplicationInfo.publicSourceDir;
        final Map packages = (Map) getObjectField(activityThread, "mPackages");
        final Object previousPackage = packages.get(selfApplicationInfo.packageName);
        boolean switched = false;
        try {
            selfApplicationInfo.sourceDir = redirect;
            selfApplicationInfo.publicSourceDir = redirect;
            packages.remove(selfApplicationInfo.packageName);
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
            AppEnv.appClassLoader = (ClassLoader) getObjectField(loadedApk2, "mClassLoader");
            switched = true;
        } finally {
            // sourceDir/publicSourceDir 永远还原（框架后续还要用 mBoundApplication.appInfo 的原始路径）；
            // mPackages 只在切换没完成时还原：成功时新的 LoadedApk 必须留在缓存里给后续 Activity 用
            selfApplicationInfo.sourceDir = previousSourceDir;
            selfApplicationInfo.publicSourceDir = previousPublicSourceDir;
            if (!switched) {
                if (previousPackage != null) {
                    packages.put(selfApplicationInfo.packageName, previousPackage);
                } else {
                    packages.remove(selfApplicationInfo.packageName);
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
            // 幂等：installSignBypass() 为 NativeLoader 提前建过一次时就不要再建第二份（P2-14）
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
                AppEnv.managerPackage = normalizeManagerPackage(
                        jSONObject.optString("manager_package", ""), AppEnv.managerPackage);
                String rawMode = jSONObject.optString("sig_mode", "");
                AppEnv.sig_mode_explicit = rawMode != null && rawMode.trim().length() > 0;
                AppEnv.sig_mode = AppEnv.sig_mode_explicit
                        ? normalizeSigMode(rawMode) : AppEnv.SIG_MODE_JAVA;
                AppEnv.signing_details = jSONObject.optString("signing_details", null);
                // 版本只告警不阻断：内核可能比打包器新，功能降级比启动失败可接受
                if (jSONObject.has("ver") && AppEnv.core_ver != CoreEntry.RUNTIME_VERSION) {
                    XpHook.log0(TAG, "运行时版本不一致：xp/config.json ver=" + AppEnv.core_ver
                            + "，内核 RUNTIME_VERSION=" + CoreEntry.RUNTIME_VERSION
                            + "（建议用当前版本管理器重新打包）");
                }
                // sig_mode=off 时不解析 legacy 证书：不装任何签名 hook，解析结果也没人用
                if (!AppEnv.SIG_MODE_OFF.equals(AppEnv.sig_mode)) {
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
        // sig_mode=off 不释放原包（否则每个应用白占一份 APK 体积，且 source_apk_path 非空
        // 会让“文件层可用”的判断失真）；dynamic_mode 自己要原包路径，单独保留
        boolean signBypassEnabled = !AppEnv.SIG_MODE_OFF.equals(AppEnv.sig_mode);
        if ((signBypassEnabled && AppEnv.sign_bypass_type > 0) || AppEnv.dynamicMode) {
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

    /** 管理器包名只接受形如 a.b.c 的值，空串/脏值回落到打包时的缺省值。 */
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

    // ------------------------------------------------------------------
    // 模块熔断：pending（本次加载证据）+ counts（跨进程计数，带 24h 时间窗）
    // ------------------------------------------------------------------

    private static void initModules() throws Exception {
        AppEnv.modules = new RemoteModuleProvider(AppEnv.appContext).getModules();
        // 先处理“上次启动留下的证据”：同时得到本进程本次要跳过的模块
        Set<String> disabled = loadModuleCrashCounts(AppEnv.modules);
        boolean changedPath = false;
        int loadedCount = 0;
        int skippedCount = 0;
        for (RemoteModule remoteModule : AppEnv.modules) {
            if (remoteModule == null || remoteModule.packageName == null) {
                continue;
            }
            if (disabled.contains(remoteModule.packageName)) {
                skippedCount++;
                XpHook.log0(TAG, "本次启动跳过模块 " + remoteModule.packageName
                        + "：已判定它连续导致启动崩溃（熔断已持久化，恢复需在管理器里确认）");
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
            // pending 不在 loadModule 返回时删：模块运行期崩溃（Application/Activity 阶段）
            // 才是最常见形态，必须活到“启动完成”才算本次加载安全（P1-3）
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
                // 模块没装上（入口抛异常）：删掉它的 pending，别把“装不上”误记成“崩溃”
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
        XpHook.log0(TAG, "模块初始化完成：生效 " + loadedCount + " 个，熔断跳过 " + skippedCount
                + " 个，候选 " + AppEnv.modules.size() + " 个：" + AppEnv.modules);
    }

    /**
     * 启动完成收尾：删除本进程的 pending + 清“加载成功且活到启动完成”的模块计数。
     *
     * <p>不变量：计数不能在 loadModule 返回时清（旧实现的做法），否则上一次启动留下的崩溃
     * 计数会在本次启动的加载成功后立刻被抹掉，阈值永远到不了（P1-3 / P2-11）。
     */
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

    /**
     * 注册“启动完成”回调（删除 pending / 清模块崩溃计数）。
     *
     * <p>时序不变量（P1 回归修复后）：回调只在 <b>Application.onCreate 正常返回之后</b>执行。
     * <ul>
     *   <li>主路径：{@code DeferredSignHooks} 的 {@code callApplicationOnCreate} 钩子做
     *       {@code installAll(); proceed(); markStartupComplete();}——proceed 抛异常时不会置位，
     *       pending 就留到下次启动计一次崩溃。</li>
     *   <li>兜底路径：{@link #deferredSignHooksScheduled} 为 false（{@code sig_mode=off}、
     *       跟 W2 的 schedule() 同样处于“未排队”，包括 doHook 返回 null / 抛异常的失败降级）
     *       时，本类自己挂 {@link #installFallbackStartupHook()}，同样在 proceed 之后执行回调。</li>
     * </ul>
     */
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

    /**
     * 兜底启动钩子：{@code DeferredSignHooks.schedule()} 没排上队时由本类自己等 Application.onCreate。
     *
     * <p>顺序不可交换：必须 {@code param.proceed()}（真正的 Application.onCreate）在前、
     * {@code runStartupCallbacks()}（→ {@link #STARTUP_COMMIT} 删 pending + 清计数）在后；
     * proceed 抛异常时回调不执行，pending 留到下次启动计崩溃。
     */
    private static void installFallbackStartupHook() {
        synchronized (startupCallbacks) {
            if (fallbackStartupHookInstalled) {
                return;
            }
            fallbackStartupHookInstalled = true;
        }
        try {
            Method target = Instrumentation.class.getMethod("callApplicationOnCreate", Application.class);
            boolean hooked = XpHook.doHook(target, 0, new XpHook.XpCallback() {
                @Override
                public void onHook(XpHook.XpParam param) throws Throwable {
                    // 先让框架跑完 Application.onCreate，再认为“启动完成”
                    param.proceed();
                    runStartupCallbacks();
                }
            }) != null;
            if (hooked) {
                XpHook.log0(TAG, "启动完成兜底钩子已安装：pending 将在 Application.onCreate 返回之后删除");
            } else {
                XpHook.log0(TAG, "启动完成兜底钩子安装失败：pending 可能被下次启动误判为崩溃");
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "启动完成兜底钩子安装失败：" + Log.getStackTraceString(th));
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
        // 探测接收方：管理器未安装/被 force-stop 时 AMS 会静默丢弃，内核侧只能自己记一条日志。
        // 注意 Android 11+ 的包可见性可能让探测得到“假阴性”，所以结果只用于日志，不阻断发送。
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

    /**
     * pending 文件名 = 前缀 + 进程名短哈希 + pid + 模块名短哈希。
     * 带进程名短哈希：启动时只清理“同名进程”的残留，兄弟进程正在用的记录不会被误删（P2-12）；
     * 带模块名短哈希：一次加载多个模块时每个模块都有独立证据，不会只记住最后一个。
     */
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

    /**
     * 稳定短哈希：进程名/包名可能含 ':' 与空格，不能直接进文件名；且必须跨启动稳定
     * （同一进程名的不同进程要能互相识别），所以显式写死 31 进制哈希，不依赖平台默认实现。
     */
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

    /**
     * pending 内容两行：包名 + 模块文件签名（length:lastModified）。
     * 签名用于“模块更新后自动恢复”：文件变了就说明上次崩溃的模块已被替换，计数清零（见 loadModuleCrashCounts）。
     */
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

    /** 模块 APK 的文件签名：长度 + 最后修改时间；取不到返回 null（null 不参与“已变化”判定）。 */
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

    /**
     * 统计“上次启动留下的 pending 残留”，返回本次要跳过的（已熔断）模块。
     * - 只认本进程名（短哈希前缀）的文件，兄弟进程的残留由各自进程处理（P2-12）
     * - 模块 APK 已变化（用户换了模块文件）→ 清零该模块计数并解除禁用
     * - 已达阈值的模块写回 THRESHOLD 持久跳过，不再 remove（P2-11：避免“崩 3 次、跳过 1 次”循环）
     * - 计数 24h 内没有新证据就清零（P2-13 弱证据时间窗）
     */
    private static Set<String> loadModuleCrashCounts(List<RemoteModule> modules) {
        Set<String> disabled = new HashSet<String>();
        File dir = moduleStateDir();
        if (dir == null) {
            return disabled;
        }
        Map<String, CrashCount> counts = readModuleCounts();
        Map<String, String> currentSignatures = currentModuleSignatures(modules);
        // 1) 模块文件变化：之前因它造成的崩溃不再可信（用户已经换过模块），清零并解除禁用
        java.util.Iterator<Map.Entry<String, CrashCount>> iterator = counts.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<String, CrashCount> entry = iterator.next();
            CrashCount count = entry.getValue();
            String current = currentSignatures.get(entry.getKey());
            if (count.signature != null && current != null && !count.signature.equals(current)) {
                iterator.remove();
                XpHook.log0(TAG, "模块 " + entry.getKey() + " 的 apk 已变化（更新/替换），清零崩溃计数");
            }
        }
        // 2) 上次启动崩溃证据：只处理本进程名留下的 pending
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
                String[] entry = readPendingModule(file);
                deleteQuietly(file);
                if (entry == null || entry[0].length() == 0) {
                    continue;
                }
                String pkg = entry[0];
                String current = currentSignatures.get(pkg);
                if (entry[1].length() > 0 && current != null && !entry[1].equals(current)) {
                    // 崩溃之后模块被更新过：这次崩溃不再归因于它
                    counts.remove(pkg);
                    XpHook.log0(TAG, "模块 " + pkg + " 上次启动崩溃后 apk 已变化，不计入熔断");
                    continue;
                }
                CrashCount count = counts.get(pkg);
                if (count == null) {
                    count = new CrashCount();
                    counts.put(pkg, count);
                }
                boolean alreadyDisabled = count.count >= MODULE_CRASH_THRESHOLD;
                count.count = count.count + 1;
                count.timestamp = System.currentTimeMillis();
                if (current != null) {
                    count.signature = current;
                }
                if (count.count >= MODULE_CRASH_THRESHOLD) {
                    count.count = MODULE_CRASH_THRESHOLD;
                    if (!alreadyDisabled) {
                        XpHook.log0(TAG, "模块 " + pkg + " 已连续 " + count.count
                                + " 次导致启动崩溃：本进程持久跳过它并通知管理器"
                                + "（移除动作由用户在管理器确认）");
                        notifyModuleCrash(pkg);
                    }
                } else {
                    XpHook.log0(TAG, "模块 " + pkg + " 上次启动崩溃（" + count.count + "/"
                            + MODULE_CRASH_THRESHOLD + "），本次继续重试");
                }
            }
        }
        // 3) 已持久禁用的模块即使本次没有新证据也必须跳过：
        //    “跳过 → 不写 pending → 不再计数”会让禁用状态在下次启动凭空失效
        for (Map.Entry<String, CrashCount> entry : counts.entrySet()) {
            if (entry.getValue().count >= MODULE_CRASH_THRESHOLD) {
                disabled.add(entry.getKey());
            }
        }
        writeModuleCounts(counts);
        return disabled;
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

    /** 读 pending：返回 {包名, 模块文件签名}；读不到返回 null。 */
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

    /** 计数条目：次数 + 最后一次计数的时刻（24h 时间窗）+ 当时模块文件签名（变化即清零）。 */
    private static final class CrashCount {
        int count;
        long timestamp;
        String signature;
    }

    /** 读计数文件（格式 pkg=count@timestamp[@signature]）；超过 24h 的条目直接丢弃（等价清零）。 */
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
        long now = System.currentTimeMillis();
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
                if (entry == null) {
                    continue;
                }
                if (entry.timestamp > 0 && now - entry.timestamp > MODULE_CRASH_WINDOW_MS) {
                    continue;
                }
                counts.put(pkg, entry);
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

    /**
     * 解析一行计数：{@code pkg=count@timestamp[@signature]}。
     * 第三段（模块文件签名）是可选的，兼容旧的 {@code pkg=count} 与 {@code pkg=count@ts}。
     */
    private static CrashCount parseCrashCount(String raw) {
        String value = raw == null ? "" : raw.trim();
        String[] parts = value.split("@", -1);
        if (parts.length == 0) {
            return null;
        }
        try {
            CrashCount entry = new CrashCount();
            entry.count = Integer.parseInt(parts[0].trim());
            // 旧格式 pkg=count 没有时间戳：按“无窗口”保留（迁移兼容）
            entry.timestamp = parts.length > 1 && parts[1].trim().length() > 0
                    ? Long.parseLong(parts[1].trim()) : 0L;
            entry.signature = parts.length > 2 && parts[2].trim().length() > 0
                    ? parts[2].trim() : null;
            return entry;
        } catch (Throwable th) {
            return null;
        }
    }

    /** 计数文件用 tmp + renameTo 原子替换，避免并发进程读到半截文件（P2-12）。 */
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
                if (entry.getValue().signature != null) {
                    sb.append('@').append(entry.getValue().signature);
                }
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
                    XpHook.log0(TAG, "模块崩溃计数写盘失败（rename 未生效）：" + target);
                    deleteQuietly(tmp);
                }
            }
        } catch (Throwable th) {
            XpHook.log0(TAG, "写入模块崩溃计数失败：" + Log.getStackTraceString(th));
            deleteQuietly(tmp);
        }
    }

    /**
     * 清计数：只在“模块加载成功（ModuleLoader.loadModule 返回 true）且进程活到启动完成”时调用
     * （见 commitStartup）。一次读、一次写，避免每模块一次全量重写（P3）。
     */
    private static void resetModuleCrashCounts(Set<String> packages) {
        if (packages == null || packages.isEmpty()) {
            return;
        }
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
        }
    }

}
