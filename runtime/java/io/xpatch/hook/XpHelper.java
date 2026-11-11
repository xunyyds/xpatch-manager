package io.xpatch.hook;

import io.xpatch.core.XpLayout;
import io.xpatch.utils.DataUtils;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class XpHelper {
    public static String extractSo(String str) throws Exception {
        String apk = AppEnv.selfApk;
        ZipFile zipFile = new ZipFile(apk);
        try {
            // ABI 分资产：管理器会同时打包 hook_core.so（arm64 通用）与 hook_core_<abi>.so
            // （如 x86_64）。设备优先取自己 ABI 的那份，否则 x86_64 模拟器会 System.load
            // 一个 AArch64 ELF 而直接 UnsatisfiedLinkError。
            ZipEntry entry = null;
            if ("hook_core.so".equals(str)) {
                String abi = primaryAbi();
                if (abi.length() > 0) {
                    // 混淆产物里条目名是随机的：res_hook_so_x86_64 之类由 XpLayout 解析，
                    // 包里没有该 ABI 的键时 get() 回落旧名，下面的 entry == null 分支照常兜底。
                    entry = zipFile.getEntry(XpLayout.get("hook_so_" + abi));
                }
            }
            if (entry == null) {
                entry = zipFile.getEntry(entryName(str));
            }
            if (entry == null) {
                return "";
            }
            InputStream inputStream = zipFile.getInputStream(entry);
            String str2 = AppEnv.baseApp.dataDir + "/cache/android_id/" + System.currentTimeMillis();
            FileOutputStream fileOutputStream = new FileOutputStream(str2);
            DataUtils.copyStream(inputStream, fileOutputStream);
            inputStream.close();
            fileOutputStream.close();
            return str2;
        } finally {
            zipFile.close();
        }
    }

    /**
     * 裸条目名 → 包装 APK 里的真实条目名。
     *
     * <p>本类唯一的调用方是 hook_core.dex（{@link #extractSo(String)}），它传的是
     * {@code "hook_core.so"}；开了路径混淆的产物把这个条目换成了随机名，名字记在 config 的
     * {@code res_hook_so} 里，所以要过 {@link XpLayout}。
     *
     * <p>其它名字（历史的 {@code "core.so"}、将来新增的名字）保持历史行为按 {@code xp/} 直拼：
     * 布局表里没有它们的键，也就没有"正确名字"可言，直拼至少与今天逐字一致。
     */
    private static String entryName(String name) {
        if ("hook_core.so".equals(name)) {
            return XpLayout.get("hook_so");
        }
        return "xp/" + name;
    }

    /** 设备首选 ABI；过滤掉路径分隔符等异常值，避免拼出目录穿越的资产名。 */
    private static String primaryAbi() {
        try {
            if (android.os.Build.SUPPORTED_ABIS != null && android.os.Build.SUPPORTED_ABIS.length > 0) {
                String abi = android.os.Build.SUPPORTED_ABIS[0];
                if (abi != null && abi.length() > 0 && abi.indexOf('/') < 0
                        && abi.indexOf(' ') < 0 && abi.indexOf('.') < 0) {
                    return abi;
                }
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    public static void setClassLoaderTrusted(ClassLoader classLoader) {
    }
}
