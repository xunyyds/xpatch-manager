package io.xpatch.data

import android.content.Context
import android.os.Build
import io.xpatch.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃现场记录，落在 `filesDir/crash/`。
 *
 * 与 [EventLog] 的 `filesDir/events/` 是两个互不相干的目录，因此各持一把进程级锁
 * （加锁策略保持一致，但不共用同一把锁，避免崩溃线程被日志写入阻塞）。
 *
 * 这里必须加锁的原因：写崩溃现场的是 uncaughtException 线程（可能是任意线程，
 * 甚至可能同时有两个线程在崩溃），而"清理崩溃记录"来自 UI 线程。
 * 不加锁时 `renameTo` + `writeText` 与 `deleteRecursively` 交错，会出现
 * "刚清理完、旧崩溃现场又被写回来"的情况。
 */
object CrashRecorder {

    private const val CRASH_DIR = "crash"
    private const val LAST_FILE = "last_crash.txt"

    /**
     * 崩溃记录不再只留 3 份。
     *
     * 原先是 crash-1..3 轮转，用户连续遇到几次闪退后，最早那份（往往才是
     * 根因现场）就被挤掉了。现在保留全部历史崩溃，只是在导出/列表里
     * 按需截取；单份几十 KB，长期占用可以忽略。
     */
    private const val KEEP_ALL = 0

    /** 进程级锁，覆盖本类所有会动 `filesDir/crash` 的操作。 */
    private val LOCK = Any()

    private fun crashDir(context: Context): File = File(context.filesDir, CRASH_DIR)

    fun install(context: Context) {
        val appContext = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            runCatching { record(appContext, thread, throwable) }
            previous?.uncaughtException(thread, throwable)
        }
    }

    fun lastCrashFile(context: Context): File? = synchronized(LOCK) {
        val file = File(crashDir(context), LAST_FILE)
        if (file.isFile) file else null
    }

    fun lastCrashSummary(context: Context, maxChars: Int = 4096): String? = synchronized(LOCK) {
        val file = lastCrashFile(context) ?: return@synchronized null
        runCatching {
            val text = file.readText(Charsets.UTF_8)
            if (text.length <= maxChars) text else "…（已截断）\n" + text.takeLast(maxChars)
        }.getOrNull()
    }

    /**
     * 清空崩溃记录：整个 `filesDir/crash` 目录一起删。
     *
     * 与 [EventLog.clear] 保持同一种语义——清理就是"这个目录不存在了"，
     * 而不是逐个删文件（否则将来新增的导出文件、序号文件都会漏删）。
     */
    fun clear(context: Context) {
        synchronized(LOCK) {
            runCatching { crashDir(context).deleteRecursively() }
        }
    }

    private fun record(context: Context, thread: Thread, throwable: Throwable) {
        synchronized(LOCK) {
            val dir = crashDir(context).apply { mkdirs() }
            val last = File(dir, LAST_FILE)
            if (last.isFile) {
                val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
                last.renameTo(File(dir, "crash-$stamp.txt"))
            }
            trim(dir)
            val trace = StringWriter()
                .also { throwable.printStackTrace(PrintWriter(it)) }
                .toString()
            last.writeText(
                buildString {
                    appendLine("时间: " + SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date()))
                    appendLine("线程: " + thread.name)
                    appendLine("版本: " + BuildConfig.VERSION_NAME + " (" + BuildConfig.VERSION_CODE + ")")
                    appendLine("设备: " + Build.MANUFACTURER + " " + Build.MODEL + " / Android " + Build.VERSION.RELEASE)
                    appendLine()
                    append(trace)
                },
            )
        }
    }

    private fun trim(dir: File) {
        if (KEEP_ALL > 0) {
            val files = dir.listFiles { file -> file.isFile && file.name.startsWith("crash-") } ?: return
            files.sortedByDescending { it.name }
                .drop(KEEP_ALL - 1)
                .forEach { it.delete() }
        }
    }
}
