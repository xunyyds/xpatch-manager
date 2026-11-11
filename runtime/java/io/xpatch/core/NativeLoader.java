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

public final class NativeLoader {

    private static final String TAG = "XpNative";

    public static String assetEntry() {
        return XpLayout.get("hook_so");
    }

    private static final long STALE_MILLIS = 60 * 1000L;

    private static boolean loaded;
    private static String loadedPath;
    private static String loadedAsset;

    private static String lastError = "尚未尝试加载";

    private NativeLoader() {
    }

    public static String assetEntryFor(String abi) {
        if (abi == null) {
            return assetEntry();
        }
        String name = abi.trim();
        if (name.length() == 0 || "unknown".equals(name)) {
            return assetEntry();
        }
        for (int i = 0; i < name.length(); i++) {
            char c = name.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '_' || c == '-';
            if (!ok) {
                return assetEntry();
            }
        }
        return XpLayout.get("hook_so_" + name);
    }

    public static synchronized boolean load(Context context) {
        if (loaded) {
            return true;
        }
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
        sweepStale(dir, target);
        String asset = extract(apk, target, abi);
        if (asset == null) {
            deleteQuietly(target, false);
            return false;
        }
        String preferred = assetEntryFor(abi);
        if (!asset.equals(preferred)) {
            XpHook.log0(TAG, "包里没有 " + preferred + "，回退通用资产 " + asset
                    + "（设备首选 ABI=" + abi + "）");
        }
        try {
            System.load(target.getAbsolutePath());
        } catch (Throwable th) {
            return fail("System.load 失败（资产 " + asset + " → " + target.getAbsolutePath() + "）：" + th
                    + "；设备首选 ABI=" + abi + "，支持的 ABI=" + abiList());
        }
        loaded = true;
        loadedPath = target.getAbsolutePath();
        loadedAsset = asset;
        lastError = null;
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

    private static String extract(String apk, File target, String abi) {
        String generic = assetEntry();
        String preferred = assetEntryFor(abi);
        ZipFile zip = null;
        try {
            zip = new ZipFile(apk);
            String entryName = preferred;
            ZipEntry entry = zip.getEntry(entryName);
            if (entry == null && !preferred.equals(generic)) {
                entryName = generic;
                entry = zip.getEntry(entryName);
            }
            if (entry == null) {
                fail("安装包里没有 " + preferred + "，也没有回退资产 " + generic
                        + "（旧产物或管理器没打包 native 库）：" + apk);
                return null;
            }
            InputStream in = zip.getInputStream(entry);
            try {
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
            fail("解包 " + preferred + "（回退 " + generic + "）失败：" + Log.getStackTraceString(th));
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

    public static synchronized String loadedPath() {
        return loadedPath;
    }

    public static synchronized String loadedAsset() {
        return loadedAsset;
    }

    public static synchronized boolean isLoaded() {
        return loaded;
    }

    public static synchronized String lastError() {
        return lastError;
    }
}
