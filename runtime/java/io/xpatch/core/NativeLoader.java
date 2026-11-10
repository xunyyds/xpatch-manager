package io.xpatch.core;

import android.content.Context;
import android.os.Build;
import android.os.Process;
import android.util.Log;
import io.xpatch.hook.AppEnv;
import io.xpatch.hook.XpHook;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * hook_core.so 的解包与加载。文件层（Seccomp / dobby / mix）的唯一 native 前置条件：
 * 没有它，{@link NativeCalls} 的 native 方法一律 UnsatisfiedLinkError。
 *
 * <p>不变量：
 * <ol>
 *   <li>一个进程最多成功加载一次：{@code loaded} 置真后所有调用直接返回 true
 *       （对同一个路径第二次 System.load 会抛 "already loaded in another classloader"）。</li>
 *   <li>资产来自 {@code AppEnv.selfApk} 的 zip，不用 {@code Context.getAssets()}：
 *       selfApk 可能只是 split 的 base.apk。</li>
 *   <li>资产按 ABI 择优：先找 {@code xp/hook_core_<首选ABI>.so}（如
 *       {@code hook_core_x86_64.so}、{@code hook_core_arm64-v8a.so}），包里没有才退回通用
 *       {@code xp/hook_core.so}（当前 arm64 构建）。回退是必要的：管理器可能只打包了通用资产。</li>
 *   <li>临时文件只落在本进程私有目录 {@code <codeCacheDir>/xp/}，名字带 ABI 与 pid，
 *       多进程并行初始化时不会互相截断。</li>
 *   <li>只有 System.load 成功才删临时文件：Linux 上 linker 已 mmap 该 inode，unlink 不影响已映射代码；
 *       加载失败则保留现场，需 adb pull 后 readelf 判断 ELF 类型/ABI 是否与设备匹配。</li>
 * </ol>
 */
public final class NativeLoader {

    private static final String TAG = "XpNative";

    /**
     * 通用（arm64）资产路径。与 ApkPatcher 写入的资产路径一致；改这里必须同步 manager 打包逻辑。
     * 首选 ABI 有专属资产时用 {@link #assetEntryFor(String)}，它才是本类的实际入口。
     */
    public static final String ASSET_ENTRY = "xp/hook_core.so";

    /** ABI 专属资产模板：{@code xp/hook_core_<abi>.so}。 */
    private static final String ASSET_ABI_PREFIX = "xp/hook_core_";

    private static final String ASSET_ABI_SUFFIX = ".so";

    /** 失败残留文件的清理阈值（毫秒）。 */
    private static final long STALE_MILLIS = 60 * 1000L;

    private static boolean loaded;
    private static String loadedPath;
    /** 本进程实际加载的 zip 条目名（成功后才非 null），用于诊断"到底是哪个资产生效"。 */
    private static String loadedAsset;

    private static String lastError = "尚未尝试加载";

    private NativeLoader() {
    }

    /**
     * 该 ABI 的专属资产条目名：{@code xp/hook_core_<abi>.so}。
     *
     * <p>例：{@code arm64-v8a → xp/hook_core_arm64-v8a.so}、
     * {@code x86_64 → xp/hook_core_x86_64.so}。arm64 真机若没有专属条目，
     * 由 {@link #extract} 退回 {@link #ASSET_ENTRY}。
     *
     * <p>ABI 为空、为 {@code unknown} 或含 {@code '/'}、{@code '.'} 等非 ABI 字符时直接返回
     * {@link #ASSET_ENTRY}：ABI 名会拼进 zip 路径，不能让外部输入决定解包目标。
     *
     * @param abi 形如 {@code Build.SUPPORTED_ABIS[0]} 的 ABI 名
     * @return 该 ABI 的专属条目名；返回值不保证在包里存在
     */
    public static String assetEntryFor(String abi) {
        if (abi == null) {
            return ASSET_ENTRY;
        }
        String name = abi.trim();
        if (name.length() == 0 || "unknown".equals(name)) {
            return ASSET_ENTRY;
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return ASSET_ENTRY;
            }
        }
        return ASSET_ABI_PREFIX + name + ASSET_ABI_SUFFIX;
    }

    /**
     * 解包并加载 hook_core.so。幂等：成功后重复调用只返回 true。
     *
     * @param context 优先用它的 getCodeCacheDir()；为 null 时退回 AppEnv.appContext，
     *                再退回 {@code <AppEnv.baseApp.dataDir>/code_cache}（installSignBypass 阶段可能还没有 Context）
     * @return 是否已处于"本进程已加载"状态
     */
    public static synchronized boolean load(Context context) {
        if (loaded) {
            return true;
        }
        // hook_core.dex 的 CoreEntry.InitHookCore 在 initHookCore() 阶段已经 System.load 过
        // 同一份库（XpHelper.extractSo 现在按 ABI 选资产）。同一个进程里再 load 一次轻则
        // 重复映射、重则 "already loaded in another classloader"，所以直接采信那个结果。
        if (AppEnv.nativeLoadedByHookCore) {
            loaded = true;
            lastError = null;
            XpHook.log0(TAG, "hook_core.so 已由 hook_core.dex 加载，跳过重复 System.load");
            return true;
        }
        try {
            return loadInternal(context);
        } catch (Throwable th) {
            lastError = "加载异常：" + th;
            XpHook.log0(TAG, "hook_core.so 加载异常：" + Log.getStackTraceString(th));
            return false;
        }
    }

    private static boolean loadInternal(Context context) {
        String apk = AppEnv.selfApk;
        if (apk == null || apk.trim().length() == 0) {
            return fail("AppEnv.selfApk 为空，无法定位安装包");
        }
        File dir = resolveLibDir(context);
        if (dir == null) {
            return fail("没有可写的 codeCacheDir（context 与 baseApp.dataDir 都不可用）");
        }
        String abi = primaryAbi();
        File target = new File(dir, "hook_core-" + abi + "-" + Process.myPid() + ".so");
        // 失败路径不删现场，所以同目录会留 hook_core-<abi>-<pid>.so；启动时清掉 60 s 前的残留，
        // 避免"每个进程每次启动多一个 1.9 MB 文件"（与 SourceReconstructor 那条磁盘泄漏同类）。
        // 成功的加载会在 System.load 后立刻删除，所以超过 60 s 的文件必然是失败/中途死亡留下的。
        sweepStale(dir, target);
        String asset = extract(apk, target, abi);
        if (asset == null) {
            // 半成品没有诊断价值，删掉避免下一轮被误当成"上次已解包"
            deleteQuietly(target, false);
            return false;
        }
        String preferred = assetEntryFor(abi);
        if (!asset.equals(preferred)) {
            // 不是错，但真机上"以为加载了专属资产"的误判都从这里开始，必须留痕
            XpHook.log0(TAG, "包里没有 " + preferred + "，回退通用资产 " + asset
                    + "（设备首选 ABI=" + abi + "）");
        }
        try {
            System.load(target.getAbsolutePath());
        } catch (Throwable th) {
            // 失败保留文件：ABI 不匹配时这是唯一可诊断物（readelf 看 Machine / .ARM.attributes）
            return fail("System.load 失败（资产 " + asset + " → " + target.getAbsolutePath() + "）：" + th
                    + "；设备首选 ABI=" + abi + "，支持的 ABI=" + abiList());
        }
        loaded = true;
        loadedPath = target.getAbsolutePath();
        loadedAsset = asset;
        lastError = null;
        // 已加载后再删除：inode 已映射进进程，删文件不回收代码页
        deleteQuietly(target, true);
        XpHook.log0(TAG, "hook_core.so 已加载（资产 " + asset + "，ABI " + abi
                + "，临时文件已删除）：" + loadedPath);
        return true;
    }

    private static File resolveLibDir(Context context) {
        File base = null;
        Context ctx = context != null ? context : AppEnv.appContext;
        if (ctx != null) {
            try {
                base = ctx.getCodeCacheDir();
            } catch (Throwable th) {
                XpHook.log0(TAG, "读取 codeCacheDir 失败：" + th);
            }
        }
        if (base == null && AppEnv.baseApp != null
                && AppEnv.baseApp.dataDir != null && AppEnv.baseApp.dataDir.length() > 0) {
            // 与 ContextImpl.getCodeCacheDir() 等价（<dataDir>/code_cache）；
            // installSignBypass 早于 createAppContext，此时只能这样取
            base = new File(AppEnv.baseApp.dataDir, "code_cache");
        }
        if (base == null) {
            return null;
        }
        File dir = new File(base, "xp");
        if (!dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            return null;
        }
        return dir;
    }

    /**
     * 把资产解包到 {@code target}：先找 ABI 专属条目（{@link #assetEntryFor(String)}），
     * 包里没有才退回通用 {@link #ASSET_ENTRY}。
     *
     * @return 实际解包用的 zip 条目名；失败返回 {@code null}（原因已写进 {@link #lastError()}）
     */
    private static String extract(String apk, File target, String abi) {
        String preferred = assetEntryFor(abi);
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            String entryName = preferred;
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null && !preferred.equals(ASSET_ENTRY)) {
                // 旧产物/只打通用资产的管理器：arm64 真机靠这条回退才能继续跑
                entryName = ASSET_ENTRY;
                entry = zip.getEntry(entryName);
            }
            if (entry == null) {
                fail("安装包里没有 " + preferred + "，也没有回退资产 " + ASSET_ENTRY
                        + "（旧产物或管理器没打包 native 库）：" + apk);
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            try {
                // 同名残留（同 pid 重试）直接截断覆盖
                FileOutputStream out = new FileOutputStream(target);
                try {
                    byte[] buffer = new byte[64 * 1024];
                    int n;
                    while ((n = in.read(buffer)) > 0) {
                        out.write(buffer, 0, n);
                    }
                    out.flush();
                } finally {
                    out.close();
                }
            } finally {
                in.close();
            }
            if (target.length() <= 0) {
                fail("解包结果为空：" + target + "（资产 " + entryName + "）");
                return null;
            }
            return entryName;
        } catch (Throwable th) {
            fail("解包 " + preferred + "（回退 " + ASSET_ENTRY + "）失败：" + Log.getStackTraceString(th));
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

    private static boolean fail(String reason) {
        lastError = reason;
        XpHook.log0(TAG, "hook_core.so 未加载：" + reason);
        return false;
    }

    /** 清理上一轮失败留下的临时 .so（只删 60 s 前的，避免和兄弟进程正在解包/加载竞争）。 */
    private static void sweepStale(File dir, File current) {
        try {
            File[] files = dir.listFiles();
            if (files == null) {
                return;
            }
            long deadline = System.currentTimeMillis() - STALE_MILLIS;
            String currentPath = current.getAbsolutePath();
            for (int i = 0; i < files.length; i++) {
                File file = files[i];
                if (file == null || !file.isFile()) {
                    continue;
                }
                if (file.getAbsolutePath().equals(currentPath)) {
                    continue;
                }
                if (!file.getName().startsWith("hook_core-")) {
                    continue;
                }
                if (file.lastModified() < deadline) {
                    deleteQuietly(file, false);
                }
            }
        } catch (Throwable ignored) {
        }
    }

    private static void deleteQuietly(File file, boolean logFailure) {
        try {
            if (file == null || !file.exists()) {
                return;
            }
            if (!file.delete() && logFailure) {
                // 删除失败不影响已加载的代码，仅提示
                XpHook.log0(TAG, "临时文件删除失败（不影响已加载代码）：" + file);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String primaryAbi() {
        try {
            if (Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0) {
                return Build.SUPPORTED_ABIS[0];
            }
            if (Build.CPU_ABI != null && Build.CPU_ABI.length() > 0) {
                return Build.CPU_ABI;
            }
        } catch (Throwable ignored) {
        }
        return "unknown";
    }

    private static String abiList() {
        try {
            return Build.SUPPORTED_ABIS == null
                    ? "unknown" : java.util.Arrays.toString(Build.SUPPORTED_ABIS);
        } catch (Throwable th) {
            return "unknown";
        }
    }

    /** 仅用于测试/诊断：已加载的绝对路径（可能为 null，且对应文件通常已被删除）。 */
    public static synchronized String loadedPath() {
        return loadedPath;
    }

    /**
     * 仅用于诊断：本进程实际加载的 zip 条目名（如 {@code xp/hook_core_x86_64.so}），
     * 未加载成功时为 {@code null}。与 {@link #lastError()} 一起判断"是专属资产还是回退资产"。
     */
    public static synchronized String loadedAsset() {
        return loadedAsset;
    }

    /** native 库是否已在本进程加载成功。 */
    public static synchronized boolean isLoaded() {
        return loaded;
    }

    /** 最近一次失败原因（成功后清空），供层级日志说明降级原因。 */
    public static synchronized String lastError() {
        return lastError;
    }
}
