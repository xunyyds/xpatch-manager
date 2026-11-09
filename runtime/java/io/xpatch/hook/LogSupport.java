package io.xpatch.hook;

import android.app.Application;
import android.content.Context;
import android.content.ContextWrapper;
import android.os.Environment;
import android.os.Process;
import android.text.TextUtils;
import android.util.Log;
import java.io.File;
import java.io.FileOutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;






















public class LogSupport {
    private static final String TAG = "XPatchLog";
    private static final long MAX_LOG_FILE_SIZE = 16777216;

    
    private static final int MAX_ROTATE_INDEX = 512;

    private static final SimpleDateFormat format =
        new SimpleDateFormat("'['yyyy-MM-dd'T'HH:mm:ss.SSS']'");

    private static volatile boolean reportedFailure = false;

    
    private static final Map<String, File> activeFiles = new HashMap<String, File>();

    
    private static volatile String cachedProcessName;

    public static synchronized void writeLine(String str) {
        writeLine(null, str);
    }

    public static synchronized void writeLine(String str, String str2) {
        if (str2 == null) {
            return;
        }
        byte[] bytes;
        try {
            bytes = (format.format(Long.valueOf(System.currentTimeMillis()))
                    + " " + processTag() + " " + str2 + "\n").getBytes("UTF-8");
        } catch (Throwable th) {
            reportFailure("日志编码失败", th);
            return;
        }
        if (writeToDisk(str, bytes)) {
            return;
        }
        reportFailure("模块日志无可用目录：" + str, null);
    }

    


    private static String processTag() {
        String name = AppEnv.processName;
        if (TextUtils.isEmpty(name)) {
            name = cachedProcessName;
        }
        if (TextUtils.isEmpty(name)) {
            try {
                name = Application.getProcessName();
            } catch (Throwable ignored) {
            }
            if (TextUtils.isEmpty(name)) {
                try {
                    java.io.BufferedReader reader =
                            new java.io.BufferedReader(new java.io.FileReader("/proc/self/cmdline"));
                    try {
                        name = reader.readLine().trim();
                    } finally {
                        reader.close();
                    }
                } catch (Throwable ignored) {
                }
            }
            cachedProcessName = name;
        }
        int pid = Process.myPid();
        return TextUtils.isEmpty(name) ? "[" + pid + "]" : "[" + pid + ":" + name + "]";
    }

    private static boolean writeToDisk(String module, byte[] bytes) {
        String key = XpHook.normalizeLogModulePackage(module);
        File cached = activeFiles.get(key);
        if (cached != null && append(cached, bytes)) {
            return true;
        }
        if (cached != null) {
            activeFiles.remove(key);
        }
        List<File> candidates = candidateFiles(key, bytes.length);
        for (int i = 0; i < candidates.size(); i++) {
            File candidate = candidates.get(i);
            if (append(candidate, bytes)) {
                activeFiles.put(key, candidate);
                return true;
            }
        }
        return false;
    }

    private static boolean append(File file, byte[] bytes) {
        try {
            FileOutputStream fileOutputStream = new FileOutputStream(file, true);
            try {
                fileOutputStream.write(bytes);
                fileOutputStream.flush();
            } finally {
                fileOutputStream.close();
            }
            return true;
        } catch (Throwable th) {
            return false;
        }
    }

    private static void reportFailure(String message, Throwable th) {
        if (reportedFailure) return;
        reportedFailure = true;
        Log.e(TAG, "日志无法落盘：" + message, th);
    }

    


    private static List<File> candidateFiles(String dirName, int size) {
        List<File> files = new ArrayList<File>(2);
        String pkg = AppEnv.packageName;
        if (!TextUtils.isEmpty(pkg)) {
            try {
                File external = Environment.getExternalStorageDirectory();
                if (external != null) {
                    File base = new File(external, "Android/data/" + pkg + "/.logs");
                    File file = pickFile(new File(base, dirName), size);
                    if (file != null) {
                        files.add(file);
                    }
                }
            } catch (Throwable ignored) {
                
            }
        }

        try {
            Context ctx = appContext();
            if (ctx != null) {
                File base = new File(ctx.getFilesDir(), "xpatch-logs");
                File file = pickFile(new File(base, dirName), size);
                if (file != null) {
                    files.add(file);
                }
            }
        } catch (Throwable ignored) {
            
        }
        return files;
    }

    private static File pickFile(File dir, int size) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            if (!dir.isDirectory()) {
                return null;
            }
            for (int i = 0; i < MAX_ROTATE_INDEX; i++) {
                File candidate = new File(dir, i == 0 ? "logs.log" : "logs_" + i + ".log");
                if (!candidate.exists() || candidate.length() + ((long) size) <= MAX_LOG_FILE_SIZE) {
                    return candidate;
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    
    private static Context appContext() {
        Context cached = AppEnv.appContext;
        if (cached != null) {
            return cached;
        }
        try {
            Class<?> at = Class.forName("android.app.ActivityThread");
            Object thread = at.getMethod("currentActivityThread").invoke(null);
            if (thread == null) return null;
            Class<?> type = thread.getClass();
            while (type != null) {
                try {
                    java.lang.reflect.Field f = type.getDeclaredField("mInitialApplication");
                    f.setAccessible(true);
                    Object app = f.get(thread);
                    if (app instanceof Context) {
                        return new ContextWrapper((Context) app);
                    }
                    break;
                } catch (NoSuchFieldException e) {
                    type = type.getSuperclass();
                }
            }
        } catch (Throwable ignored) {
        }
        return null;
    }
}
