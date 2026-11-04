package io.xpatch.utils;

import android.system.Os;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;

public class AppUtils {
    public static String getProcessName() {
        try {
            BufferedReader bufferedReader = new BufferedReader(new FileReader("/proc/self/cmdline"));
            try {
                String strTrim = bufferedReader.readLine().trim();
                bufferedReader.close();
                return strTrim;
            } catch (Throwable th) {
                try {
                    bufferedReader.close();
                } catch (Throwable th2) {
                    th.addSuppressed(th2);
                }
                throw th;
            }
        } catch (IOException e) {
            e.printStackTrace();
            return null;
        }
    }

    public static void removeProfileFiles(String str) {
        FileUtils.delete(new File("/data/misc/profiles/cur/" + (Os.getuid() / 100000) + "/" + str + "/primary.prof"));
        new File("/data/misc/profiles/cur/" + (Os.getuid() / 100000) + "/" + str + "/primary.prof").mkdirs();
    }

    public static void remoteOATFiles(String str) {
        FileUtils.delete(new File("/data/user/" + (Os.getuid() / 100000) + "/" + str + "/cache/oat_primary"));
    }
}
