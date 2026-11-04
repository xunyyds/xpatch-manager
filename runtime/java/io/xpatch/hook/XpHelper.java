package io.xpatch.hook;

import io.xpatch.utils.DataUtils;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

public class XpHelper {
    public static String extractSo(String str) throws Exception {
        ZipFile zipFile = new ZipFile(AppEnv.selfApk);
        ZipEntry entry = zipFile.getEntry("xp/" + str);
        if (entry != null) {
            InputStream inputStream = zipFile.getInputStream(entry);
            String str2 = AppEnv.baseApp.dataDir + "/cache/android_id/" + System.currentTimeMillis();
            FileOutputStream fileOutputStream = new FileOutputStream(str2);
            DataUtils.copyStream(inputStream, fileOutputStream);
            inputStream.close();
            fileOutputStream.close();
            zipFile.close();
            return str2;
        }
        zipFile.close();
        return "";
    }

    public static void setClassLoaderTrusted(ClassLoader classLoader) {
    }
}
