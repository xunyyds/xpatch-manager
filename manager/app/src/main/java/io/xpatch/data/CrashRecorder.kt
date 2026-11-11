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

object CrashRecorder {

    private const val CRASH_DIR = "crash"
    private const val LAST_FILE = "last_crash.txt"

    private const val KEEP_ALL = 0

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
