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

/**
 * 内核日志写入。
 *
 * 历史上这里只往 /sdcard/Android/data/<包名>/.logs/ 写，写失败就把异常
 * printStackTrace 吞掉。Android 11+ 的分区存储收紧、以及"应用自身
 * 还没拿到 ApplicationInfo / 外部存储不可挂载"等场景下，这一条路径会整体失效，
 * 表现为用户看到的现象：**应用无限闪退，但磁盘上一条框架日志都没有**。
 *
 * 现在的策略是多级降级，任何一级**真正写成功**才返回：
 *
 *   1. 外部私有目录 Android/data/<pkg>/.logs/<module>/   —— 插件与用户最容易取到
 *   2. 应用私有目录 filesDir/xpatch-logs/<module>/       —— 外部存储不可用时仍可用
 *   3. 仅 logcat                                            —— 最后兜底
 *
 * 注意：判断“可写”必须靠一次真实写入，而不是“目录存在”。旧实现只看目录能否
 * 创建，一旦第 1 级目录存在但写入被 SELinux 拒绝，日志就直接丢了，不会降级。
 *
 * 行格式：`[yyyy-MM-dd'T'HH:mm:ss.SSS] [pid:进程名] 消息`。
 * 进程名是排查多进程问题的唯一线索（同一个宿主会起主进程与若干子进程，
 * 它们共用同一份 logs.log，没有进程名就无法判断某行属于谁）。
 */
public class LogSupport {
    private static final String TAG = "XPatchLog";
    private static final long MAX_LOG_FILE_SIZE = 16777216;

    /** 单文件轮转序号上限，避免异常情况下 for 循环长时间空转。 */
    private static final int MAX_ROTATE_INDEX = 512;

    private static final SimpleDateFormat format =
        new SimpleDateFormat("'['yyyy-MM-dd'T'HH:mm:ss.SSS']'");

    private static volatile boolean reportedFailure = false;

    /** module -> 当前可写的日志文件；写失败时失效并重新走降级链。 */
    private static final Map<String, File> activeFiles = new HashMap<String, File>();

    /** 进程名只解析一次；AppEnv.processName 可用时优先用它。 */
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
            reportFailure("encode failed", th);
            return;
        }
        if (writeToDisk(str, bytes)) {
            return;
        }
        reportFailure("no writable log dir for module=" + str, null);
    }

    /**
     * 每行前缀 `[pid:进程名]`；进程名取不到时退化成 `[pid]`，任何情况下都不抛异常。
     */
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
        Log.e(TAG, "log persistence unavailable: " + message, th);
    }

    /**
     * 按顺序给出所有候选文件（第 1 级、第 2 级）。是否可写由调用方实际写入验证。
     */
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
                // 继续降级
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
            // 继续降级
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

    /** 通过反射读取 ActivityThread 的当前 Application，避免依赖初始化顺序。 */
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
