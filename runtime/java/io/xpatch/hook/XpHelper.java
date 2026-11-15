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
            ZipEntry entry = null;
            if ("hook_core.so".equals(str)) {
                String abi = primaryAbi();
                if (abi.length() > 0) {
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

    private static String entryName(String name) {
        if ("hook_core.so".equals(name)) {
            return XpLayout.get("hook_so");
        }
        return "xp/" + name;
    }

    private static String primaryAbi() {
        try {
            boolean is64 = android.os.Process.is64Bit();
            String[] candidates = is64
                    ? android.os.Build.SUPPORTED_64_BIT_ABIS : android.os.Build.SUPPORTED_32_BIT_ABIS;
            String abi = firstUsable(candidates);
            if (abi != null) {
                return abi;
            }
            String[] fallback = is64
                    ? android.os.Build.SUPPORTED_32_BIT_ABIS : android.os.Build.SUPPORTED_64_BIT_ABIS;
            abi = firstUsable(fallback);
            if (abi != null) {
                return abi;
            }
        } catch (Throwable ignored) {
        }
        return "";
    }

    private static String firstUsable(String[] abis) {
        if (abis == null) {
            return null;
        }
        for (int i = 0; i < abis.length; i++) {
            String abi = abis[i];
            if (abi != null && abi.length() > 0 && abi.indexOf('/') < 0
                    && abi.indexOf(' ') < 0 && abi.indexOf('.') < 0) {
                return abi;
            }
        }
        return null;
    }

    public static void setClassLoaderTrusted(ClassLoader classLoader) {
    }
}
