package io.xpatch.data

import android.content.Context
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class EventLog(context: Context) {

    private val appContext = context.applicationContext
    private val formatter = SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault())

    private val dir: File
        get() = File(appContext.filesDir, EVENTS_DIR)

    private val file: File
        get() = File(dir, LOG_FILE)

    fun entries(): List<String> = synchronized(LOCK) {
        readLinesLocked().asReversed()
    }

    fun add(message: String) {
        synchronized(LOCK) {
            val line = formatter.format(Date()) + "  " + message
            runCatching {
                dir.mkdirs()
                file.appendText(line + "\n", Charsets.UTF_8)
            }
        }
    }

    fun snapshot(): String = synchronized(LOCK) {
        runCatching {
            if (file.isFile) file.readText(Charsets.UTF_8) else ""
        }.getOrDefault("")
    }

    fun restore(text: String): Boolean = synchronized(LOCK) {
        runCatching {
            dir.mkdirs()
            file.writeText(text, Charsets.UTF_8)
        }.isSuccess
    }

    fun clear(): Boolean = synchronized(LOCK) {
        if (!dir.exists()) true else runCatching { dir.deleteRecursively() }.getOrDefault(false)
    }

    private fun readLinesLocked(): List<String> = runCatching {
        if (!file.isFile) emptyList() else file.readLines(Charsets.UTF_8).filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private companion object {
        const val EVENTS_DIR = "events"
        const val LOG_FILE = "operations.log"

        val LOCK = Any()
    }
}
