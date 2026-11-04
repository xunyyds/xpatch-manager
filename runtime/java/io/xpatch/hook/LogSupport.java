package io.xpatch.hook;

import android.os.Environment;
import android.text.TextUtils;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;

public class LogSupport {
    private static final long MAX_LOG_FILE_SIZE = 16777216;
    private static final SimpleDateFormat format = new SimpleDateFormat("'['yyyy-MM-dd'T'HH:mm:ss.SSS']'");

    public static synchronized void writeLine(String str) {
        writeLine(null, str);
    }

    public static synchronized void writeLine(String str, String str2) {
        try {
            String str3 = format.format(Long.valueOf(System.currentTimeMillis())) + " " + str2 + "\n";
            File logFile = getLogFile(str, str3.getBytes().length);
            if (logFile != null) {
                FileOutputStream fileOutputStream = new FileOutputStream(logFile, true);
                fileOutputStream.write(str3.getBytes());
                fileOutputStream.close();
            }
        } catch (Throwable th) {
            th.printStackTrace();
        }
    }

    private static File getLogFile(String str, int i) {
        File externalStorageDirectory;
        if (TextUtils.isEmpty(AppEnv.packageName) || (externalStorageDirectory = Environment.getExternalStorageDirectory()) == null) {
            return null;
        }
        File file = new File(externalStorageDirectory, "Android/data/" + AppEnv.packageName + "/.logs/" + XpHook.normalizeLogModulePackage(str));
        if (!file.exists() && !file.mkdirs()) {
            return null;
        }
        for (int i2 = 0; i2 < Integer.MAX_VALUE; i2++) {
            File file2 = new File(file, i2 == 0 ? "logs.log" : "logs_" + i2 + ".log");
            if (!file2.exists() || file2.length() + ((long) i) <= MAX_LOG_FILE_SIZE) {
                return file2;
            }
        }
        return null;
    }
}
