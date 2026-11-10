package io.xpatch.hook;

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
            // ABI 分资产：管理器会同时打包 xp/hook_core.so（arm64 通用）与
            // xp/hook_core_<abi>.so（如 x86_64）。设备优先取自己 ABI 的那份，
            // 否则 x86_64 模拟器会 System.load 一个 AArch64 ELF 而直接 UnsatisfiedLinkError。
            ZipEntry entry = null;
            if ("hook_core.so".equals(str)) {
                String abi = primaryAbi();
                if (abi.length() > 0) {
                    entry = zipFile.getEntry("xp/hook_core_" + abi + ".so");
                }
            }
            if (entry == null) {
                entry = zipFile.getEntry("xp/" + str);
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
